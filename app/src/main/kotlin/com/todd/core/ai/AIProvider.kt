package com.todd.core.ai

enum class ProviderType {
    LOCAL_MOCK,
    LOCAL_ON_DEVICE,
    CLOUD_GEMINI,
    CLOUD_OPENAI
}

data class ProviderCapabilities(
    val supportsText: Boolean = true,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val maxContextTokens: Int = 8192,
    val isLocal: Boolean = false
)

data class AIRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val selectedText: String? = null,
    val screenContext: String? = null,
    val projectContext: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024
)

data class AIResponse(
    val text: String,
    val providerUsed: ProviderType,
    val isVerified: Boolean = true,
    val tokensUsed: Int = 0,
    val latencyMs: Long = 0
)

interface AIProvider {
    val type: ProviderType
    val capabilities: ProviderCapabilities
    suspend fun generateText(request: AIRequest): Result<AIResponse>
    suspend fun isAvailable(): Boolean
}
