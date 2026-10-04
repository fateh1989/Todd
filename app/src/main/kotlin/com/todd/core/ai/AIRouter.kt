package com.todd.core.ai

import com.todd.core.model.AIProviderMode

class AIRouter(
    val localProvider: AIProvider,
    val cloudProvider: AIProvider
) {
    suspend fun route(request: AIRequest, mode: AIProviderMode): Result<AIResponse> {
        return when (mode) {
            AIProviderMode.LOCAL_ONLY -> localProvider.generateText(request)
            AIProviderMode.CLOUD_PREFERRED -> routeCloudThenLocal(request)
            AIProviderMode.AUTO -> {
                val localIsPlaceholder = localProvider.type == ProviderType.LOCAL_MOCK
                val isComplex =
                    (request.projectContext?.length ?: 0) > 1000 ||
                    request.prompt.length > 300 ||
                    !request.screenContext.isNullOrBlank() ||
                    !request.screenImagePath.isNullOrBlank()

                if (localIsPlaceholder || isComplex) routeCloudThenLocal(request)
                else localProvider.generateText(request)
            }
        }
    }

    private suspend fun routeCloudThenLocal(request: AIRequest): Result<AIResponse> {
        if (cloudProvider.isAvailable()) {
            val cloudResult = cloudProvider.generateText(request)
            if (cloudResult.isSuccess) return cloudResult
        }
        return localProvider.generateText(request)
    }
}
