package com.todd.core.ai

import android.graphics.BitmapFactory
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GeminiAIProvider(
    private val modelName: String = "gemini-3.5-flash-lite"
) : AIProvider {

    override val type: ProviderType = ProviderType.CLOUD_GEMINI

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsText = true,
        supportsStreaming = false,
        supportsTools = true,
        supportsVision = true,
        supportsAudio = false,
        maxContextTokens = 1000000,
        isLocal = false
    )

    private val generativeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = modelName,
                tools = listOf(Tool.googleSearch())
            )
    }

    override suspend fun isAvailable(): Boolean {
        return runCatching { generativeModel }.isSuccess
    }

    override suspend fun generateText(request: AIRequest): Result<AIResponse> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        val contextPrompt = buildString {
            request.systemPrompt?.let { appendLine("System: $it\n") }
            request.projectContext?.let { appendLine("Project Context: $it\n") }
            request.screenContext?.let { appendLine("Semantic Screen Context: $it\n") }
            request.selectedText?.let { appendLine("Selected Text: $it\n") }
            if (!request.screenImagePath.isNullOrBlank()) {
                appendLine(
                    "A current screenshot is attached. Read the visible text, controls, icons, images, " +
                        "layout and state, and combine that visual evidence with the semantic screen context."
                )
            }
            appendLine("User: ${request.prompt}")
        }

        try {
            val imagePath = request.screenImagePath
            val response = if (!imagePath.isNullOrBlank()) {
                val imageFile = File(imagePath)
                if (!imageFile.exists()) {
                    generativeModel.generateContent(contextPrompt)
                } else {
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        ?: return@withContext Result.failure(
                            IllegalStateException("Could not decode the latest screen image.")
                        )

                    try {
                        generativeModel.generateContent(
                            content {
                                image(bitmap)
                                text(contextPrompt)
                            }
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            } else {
                generativeModel.generateContent(contextPrompt)
            }

            val responseText = response.text
                ?: return@withContext Result.failure(
                    IllegalStateException("Gemini returned empty content")
                )

            Result.success(
                AIResponse(
                    text = responseText,
                    providerUsed = ProviderType.CLOUD_GEMINI,
                    isVerified = false,
                    tokensUsed = (contextPrompt.length + responseText.length) / 4,
                    latencyMs = System.currentTimeMillis() - start
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
