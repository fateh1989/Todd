package com.todd.core.ai

import com.todd.core.model.AIProviderMode

class AIRouter(
    val localProvider: AIProvider,
    val cloudProvider: AIProvider
) {
    suspend fun route(request: AIRequest, mode: AIProviderMode): Result<AIResponse> {
        return when (mode) {
            AIProviderMode.LOCAL_ONLY -> {
                localProvider.generateText(request)
            }
            AIProviderMode.CLOUD_PREFERRED -> {
                if (cloudProvider.isAvailable()) {
                    cloudProvider.generateText(request)
                } else {
                    localProvider.generateText(request)
                }
            }
            AIProviderMode.AUTO -> {
                // Auto decision based on context size and tools
                val isComplex = (request.projectContext?.length ?: 0) > 1000 ||
                                request.prompt.length > 300 ||
                                request.screenContext != null

                if (isComplex && cloudProvider.isAvailable()) {
                    cloudProvider.generateText(request)
                } else {
                    localProvider.generateText(request)
                }
            }
        }
    }
}
