package com.todd.core.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAIProvider(
    private val modelName: String = "gemini-3.8-flash"
) : AIProvider {

    override val type: ProviderType = ProviderType.CLOUD_GEMINI

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsText = true,
        supportsStreaming = false,
        supportsTools = false,
        supportsVision = false,
        supportsAudio = false,
        maxContextTokens = 1000000,
        isLocal = false
    )

    private val generativeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(modelName = modelName)
    }

    override suspend fun isAvailable(): Boolean {
        return runCatching { generativeModel }.isSuccess
    }

    override suspend fun generateText(request: AIRequest): Result<AIResponse> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        val contextPrompt = buildString {
            request.systemPrompt?.let { appendLine("System: $it\n") }
            request.projectContext?.let { appendLine("Project Context: $it\n") }
            request.screenContext?.let { appendLine("Screen Context: $it\n") }
            request.selectedText?.let { appendLine("Selected Text: $it\n") }
            appendLine("User: ${request.prompt}")
        }

        try {
            val response = generativeModel.generateContent(contextPrompt)
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
