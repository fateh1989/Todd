package com.todd.core.ai

class MockAIProvider : AIProvider {
    override val type: ProviderType = ProviderType.LOCAL_MOCK
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsText = true,
        supportsStreaming = true,
        supportsTools = false,
        supportsVision = false,
        supportsAudio = false,
        maxContextTokens = 4096,
        isLocal = true
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun generateText(request: AIRequest): Result<AIResponse> {
        val start = System.currentTimeMillis()
        val prompt = request.prompt.lowercase()

        val reply = when {
            prompt.contains("correct") || prompt.contains("صحح") -> {
                "التصحيح المقترح: ${request.selectedText ?: request.prompt}"
            }
            prompt.contains("translate") || prompt.contains("ترجم") -> {
                "الترجمة المعتمدة: ${request.selectedText ?: request.prompt}"
            }
            prompt.contains("summarize") || prompt.contains("لخص") -> {
                "الملخص المركز: ${request.selectedText?.take(100) ?: request.prompt.take(100)}..."
            }
            prompt.contains("rewrite") || prompt.contains("أعد صياغة") -> {
                "الصياغة البديلة المحسنة: ${request.selectedText ?: request.prompt}"
            }
            prompt.contains("screen") || prompt.contains("شاشة") -> {
                "سياق الشاشة الملتقط: ${request.screenContext ?: "لا توجد عناصر واضحة حالياً"}"
            }
            else -> {
                "Todd [Local AI Mock]: تم استلام الطلب ومعالجته محلياً بنجاح: ${request.prompt}"
            }
        }

        return Result.success(
            AIResponse(
                text = reply,
                providerUsed = ProviderType.LOCAL_MOCK,
                isVerified = true,
                tokensUsed = reply.length / 4,
                latencyMs = System.currentTimeMillis() - start
            )
        )
    }
}
