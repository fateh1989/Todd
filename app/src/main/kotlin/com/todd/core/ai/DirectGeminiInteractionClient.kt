package com.todd.core.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class DirectToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject
)

data class DirectToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

class GeminiInteractionApiException(
    val statusCode: Int,
    val apiMessage: String,
    val modelName: String
) : IllegalStateException(
    GeminiApiErrorMessages.describe(
        statusCode = statusCode,
        apiMessage = apiMessage,
        modelName = modelName
    )
)

/**
 * Direct Gemini Interactions API client for Todd's real text agent.
 *
 * It supports Google Search grounding, custom Todd tools, tool-result continuation with
 * previous_interaction_id, and optional inline screen images. This keeps text-agent tooling
 * independent from Firebase while still using the owner's encrypted Gemini API key.
 */
class DirectGeminiInteractionClient(
    private val apiKeyProvider: () -> String?,
    private val modelNameProvider: () -> String
) {

    suspend fun isAvailable(): Boolean = !apiKeyProvider().isNullOrBlank()

    suspend fun respond(
        request: AIRequest,
        prompt: String,
        tools: List<DirectToolDefinition>,
        executeTool: suspend (DirectToolCall) -> JSONObject,
        maxToolRounds: Int = 6
    ): Result<AIResponse> = withContext(Dispatchers.IO) {
        try {
            val apiKey = apiKeyProvider()?.trim().orEmpty()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Gemini API key is not configured.")
                )
            }

            val modelName = modelNameProvider()
            val startedAt = System.currentTimeMillis()
            val toolPayload = buildToolPayload(tools)
            var totalTokens = 0
            val allSources = linkedMapOf<String, AISource>()

            var response = postInteraction(
                apiKey = apiKey,
                body = JSONObject()
                    .put("model", modelName)
                    .put("input", buildInitialInput(request, prompt))
                    .put("tools", toolPayload)
            )

            repeat(maxToolRounds + 1) { round ->
                totalTokens += response
                    .optJSONObject("usage")
                    ?.optInt("total_tokens", 0)
                    ?: 0

                parseSources(response).forEach { allSources[it.url] = it }

                val calls = parseFunctionCalls(response)
                if (calls.isEmpty()) {
                    val text = parseModelText(response).trim()
                    if (text.isBlank()) {
                        val status = response.optString("status", "unknown")
                        val error = response.optJSONObject("error")
                            ?.optString("message")
                            .orEmpty()
                        throw IllegalStateException(
                            buildString {
                                append("Gemini interaction returned no final text")
                                append(" (status=").append(status).append(")")
                                if (error.isNotBlank()) append(": ").append(error)
                            }
                        )
                    }

                    return@withContext Result.success(
                        AIResponse(
                            text = text,
                            providerUsed = ProviderType.CLOUD_GEMINI,
                            isVerified = false,
                            tokensUsed = totalTokens,
                            latencyMs = System.currentTimeMillis() - startedAt,
                            sources = allSources.values.toList()
                        )
                    )
                }

                if (round >= maxToolRounds) {
                    throw IllegalStateException(
                        "Todd exceeded the maximum number of direct Gemini tool rounds."
                    )
                }

                val interactionId = response.optString("id")
                if (interactionId.isBlank()) {
                    throw IllegalStateException(
                        "Gemini returned function calls without an interaction ID."
                    )
                }

                val functionResults = JSONArray()
                for (call in calls) {
                    val result = executeTool(call)
                    functionResults.put(
                        JSONObject()
                            .put("type", "function_result")
                            .put("name", call.name)
                            .put("call_id", call.id)
                            .put(
                                "result",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", result.toString())
                                )
                            )
                    )
                }

                response = postInteraction(
                    apiKey = apiKey,
                    body = JSONObject()
                        .put("model", modelName)
                        .put("previous_interaction_id", interactionId)
                        .put("input", functionResults)
                        .put("tools", toolPayload)
                )
            }

            Result.failure(
                IllegalStateException("Todd direct Gemini interaction ended unexpectedly.")
            )
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun buildToolPayload(definitions: List<DirectToolDefinition>): JSONArray =
        JSONArray().apply {
            put(JSONObject().put("type", "google_search"))
            definitions.forEach { definition ->
                put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", definition.name)
                        .put("description", definition.description)
                        .put("parameters", definition.parameters)
                )
            }
        }

    private fun buildInitialInput(request: AIRequest, prompt: String): Any {
        val imagePath = request.screenImagePath
        if (imagePath.isNullOrBlank()) return prompt

        val file = File(imagePath)
        if (!file.exists() || !file.isFile || file.length() > MAX_INLINE_IMAGE_BYTES) {
            return prompt
        }

        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image")
                    .put("mime_type", mimeType)
                    .put(
                        "data",
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    )
            )
    }

    private fun parseFunctionCalls(root: JSONObject): List<DirectToolCall> {
        val result = mutableListOf<DirectToolCall>()
        val steps = root.optJSONArray("steps") ?: return emptyList()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "function_call") continue
            val id = step.optString("id")
            val name = step.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            result += DirectToolCall(
                id = id,
                name = name,
                arguments = step.optJSONObject("arguments") ?: JSONObject()
            )
        }
        return result
    }

    private fun parseModelText(root: JSONObject): String {
        val textParts = mutableListOf<String>()
        val steps = root.optJSONArray("steps") ?: return ""
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") != "text") continue
                block.optString("text")
                    .takeIf { it.isNotBlank() }
                    ?.let(textParts::add)
            }
        }
        return textParts.joinToString("\n")
    }

    private fun parseSources(root: JSONObject): List<AISource> {
        val result = linkedMapOf<String, AISource>()
        val steps = root.optJSONArray("steps") ?: return emptyList()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                val annotations = block.optJSONArray("annotations") ?: continue
                for (k in 0 until annotations.length()) {
                    val annotation = annotations.optJSONObject(k) ?: continue
                    if (annotation.optString("type") != "url_citation") continue
                    val url = annotation.optString("url")
                    if (url.isBlank()) continue
                    result[url] = AISource(
                        title = annotation.optString("title").takeIf { it.isNotBlank() },
                        url = url,
                        domain = runCatching { URI(url).host }.getOrNull()
                    )
                }
            }
        }
        return result.values.toList()
    }

    private fun postInteraction(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL(INTERACTIONS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("User-Agent", "Todd-Android")
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(body.toString())
            }

            val code = connection.responseCode
            val responseText = (if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.optString("message")
                }.getOrNull().orEmpty()
                throw GeminiInteractionApiException(
                    statusCode = code,
                    apiMessage = message,
                    modelName = body.optString("model", "Gemini")
                )
            }

            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val MAX_INLINE_IMAGE_BYTES = 15L * 1024L * 1024L
    }
}
