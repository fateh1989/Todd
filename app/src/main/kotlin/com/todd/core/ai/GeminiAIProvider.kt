package com.todd.core.ai

import com.google.firebase.Firebase
import com.google.firebase.vertexai.type.GenerativeModel
import com.google.firebase.vertexai.vertexAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAIProvider(
    private val modelName: String = "gemini-2.0-flash"
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

    private var generativeModel: GenerativeModel? = null

    init {
        try {
            generativeModel = Firebase.vertexAI.generativeModel(modelName = modelName)
        } catch (_: Exception) {
            generativeModel = null
        }
    }

    override suspend fun isAvailable(): Boolean {
        // Only available if Firebase Vertex AI has been successfully initialized
        return generativeModel != null
    }

    override suspend fun generateText(request: AIRequest): Result<AIResponse> = withContext(Dispatchers.IO) {
        val model = generativeModel
            ?: return@withContext Result.failure(
                IllegalStateException("Firebase Vertex AI is not initialized. Please ensure google-services.json is configured.")
            )

        val start = System.currentTimeMillis()

        // Build comprehensive context with system, project, screen, and selected text
        val contextPrompt = buildString {
            request.systemPrompt?.let { appendLine("System: $it\n") }
            request.projectContext?.let { appendLine("Project Context: $it\n") }
            request.screenContext?.let { appendLine("Screen Context: $it\n") }
            request.selectedText?.let { appendLine("Selected Text: $it\n") }
            appendLine("User: ${request.prompt}")
        }

        try {
            val response = model.generateContent(contextPrompt)
            val responseText = response.text
                ?: return@withContext Result.failure(IllegalStateException("Gemini returned empty content"))

            // Rule: EXECUTED != VERIFIED. Do not set isVerified=true automatically just because text was returned!
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
