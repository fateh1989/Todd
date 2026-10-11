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
    val screenImagePath: String? = null,
    val projectContext: String? = null,
    /** True only when the user explicitly asks for current information from the web. */
    val webResearch: Boolean = false,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024
)

data class AISource(
    val title: String? = null,
    val url: String,
    val domain: String? = null,
    /** A short, best-effort page excerpt; its presence does not itself verify a claim. */
    val excerpt: String? = null,
    /** Stable 1-based position in the original search-citation list. */
    val citationIndex: Int? = null,
    /** Diagnostic fetch status: e.g. "200_OK", "403_FORBIDDEN", "TIMEOUT", "JS_CLIENT_RENDER_ONLY", "FAILED" */
    val fetchStatus: String? = null,
    /** Discovered outlinks on the page for deep multi-step exploration */
    val outlinks: List<String> = emptyList()
)

data class AIResponse(
    val text: String,
    val providerUsed: ProviderType,
    val isVerified: Boolean = false,
    val tokensUsed: Int = 0,
    val latencyMs: Long = 0,
    val sources: List<AISource> = emptyList()
)

interface AIProvider {
    val type: ProviderType
    val capabilities: ProviderCapabilities
    suspend fun generateText(request: AIRequest): Result<AIResponse>
    suspend fun isAvailable(): Boolean
}
