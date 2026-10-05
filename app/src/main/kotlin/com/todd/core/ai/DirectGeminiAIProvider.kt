package com.todd.core.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal fun thinkingLevelForGeminiModel(modelName: String): String =
    when {
        modelName == "gemini-3.1-flash-lite" -> "minimal"
        modelName == "gemini-3.8-flash" -> "medium"
        else -> "low"
    }

class DirectGeminiAIProvider(
    private val apiKeyProvider: () -> String?,
    private val modelName: String = "gemini-3.8-flash",
    private val requestObserver: ((String) -> Unit)? = null
) : AIProvider {

    override val type: ProviderType = ProviderType.CLOUD_GEMINI

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsText = true,
        supportsStreaming = false,
        supportsTools = false,
        supportsVision = true,
        supportsAudio = false,
        maxContextTokens = 1000000,
        isLocal = false
    )

    override suspend fun isAvailable(): Boolean =
        !apiKeyProvider().isNullOrBlank()

    override suspend fun generateText(request: AIRequest): Result<AIResponse> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val apiKey = apiKeyProvider()?.trim().orEmpty()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Gemini API key is not configured. Add it in Todd Settings."
                    )
                )
            }

            val prompt = buildString {
                request.systemPrompt?.let { appendLine("System: $it") }
                request.projectContext?.let { appendLine("Project Context: $it") }
                request.screenContext?.let { appendLine("Screen Context: $it") }
                request.selectedText?.let { appendLine("Selected Text: $it") }
                appendLine("User: ${request.prompt}")
            }

            runCatching {
                val parts = JSONArray()
                val imagePath = request.screenImagePath
                if (!imagePath.isNullOrBlank()) {
                    val file = File(imagePath)
                    if (file.exists() && file.isFile) {
                        val mime = when (file.extension.lowercase()) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "webp" -> "image/webp"
                            else -> "image/png"
                        }
                        parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject()
                                    .put("mime_type", mime)
                                    .put(
                                        "data",
                                        Base64.encodeToString(
                                            file.readBytes(),
                                            Base64.NO_WRAP
                                        )
                                    )
                            )
                        )
                    }
                }
                parts.put(JSONObject().put("text", prompt))

                val body = JSONObject()
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("parts", parts)
                        )
                    )
                    .put(
                        "generationConfig",
                        JSONObject()
                            // Respect the owner's manual model choice: Lite stays very
                            // fast for frequent work; 3.8 gets a deeper budget for coding.
                            .put("maxOutputTokens", maxOf(request.maxTokens, 512))
                            .put(
                                "thinkingConfig",
                                JSONObject().put(
                                    "thinkingLevel",
                                    thinkingLevelForGeminiModel(modelName)
                                )
                            )
                    )

                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        modelName + ":generateContent"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                    setRequestProperty("User-Agent", "Todd-Android")
                }

                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                        it.write(body.toString())
                    }
                    requestObserver?.invoke(modelName)

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
                        throw IllegalStateException(
                            GeminiApiErrorMessages.describe(
                                statusCode = code,
                                apiMessage = message,
                                modelName = modelName
                            )
                        )
                    }

                    val root = JSONObject(responseText)
                    val candidates = root.optJSONArray("candidates")
                        ?: throw IllegalStateException("Gemini returned no candidates.")
                    if (candidates.length() == 0) {
                        throw IllegalStateException("Gemini returned an empty candidate list.")
                    }

                    val firstCandidate = candidates.getJSONObject(0)
                    val finishReason = firstCandidate.optString("finishReason", "UNKNOWN")
                    val usage = root.optJSONObject("usageMetadata")
                    val thoughts = usage?.optInt("thoughtsTokenCount", 0) ?: 0
                    val candidateTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0
                    val content = firstCandidate.optJSONObject("content")
                        ?: throw IllegalStateException(
                            "Gemini returned no content (finishReason=$finishReason, " +
                                "thoughts=$thoughts, output=$candidateTokens)."
                        )
                    val responseParts = content.optJSONArray("parts")
                        ?: throw IllegalStateException(
                            "Gemini returned no response parts (finishReason=$finishReason, " +
                                "thoughts=$thoughts, output=$candidateTokens)."
                        )

                    val text = buildString {
                        for (i in 0 until responseParts.length()) {
                            val value = responseParts.getJSONObject(i).optString("text").trim()
                            if (value.isNotBlank()) {
                                if (isNotEmpty()) appendLine()
                                append(value)
                            }
                        }
                    }.trim()

                    if (text.isBlank()) {
                        throw IllegalStateException("Gemini returned empty text.")
                    }

                    val tokens = usage?.optInt("totalTokenCount", 0) ?: 0

                    val sources = mutableListOf<AISource>()
                    val grounding = candidates.getJSONObject(0).optJSONObject("groundingMetadata")
                    val chunks = grounding?.optJSONArray("groundingChunks")
                    if (chunks != null) {
                        for (i in 0 until chunks.length()) {
                            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                            val uri = web.optString("uri").takeIf { it.isNotBlank() } ?: continue
                            sources += AISource(
                                title = web.optString("title").takeIf { it.isNotBlank() },
                                url = uri,
                                domain = web.optString("domain").takeIf { it.isNotBlank() }
                            )
                        }
                    }

                    AIResponse(
                        text = text,
                        providerUsed = ProviderType.CLOUD_GEMINI,
                        isVerified = false,
                        tokensUsed = tokens,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        sources = sources.distinctBy { it.url }
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }
}
