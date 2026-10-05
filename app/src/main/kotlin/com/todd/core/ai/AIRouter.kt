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

                if (localIsPlaceholder || isComplex) {
                    routeCloudThenLocal(request)
                } else {
                    routeLocalThenCloud(request)
                }
            }
        }
    }

    private suspend fun routeLocalThenCloud(request: AIRequest): Result<AIResponse> {
        var localFailure: Throwable? = null
        if (localProvider.isAvailable()) {
            val localResult = localProvider.generateText(request)
            if (localResult.isSuccess) return localResult
            localFailure = localResult.exceptionOrNull()
        }

        if (cloudProvider.isAvailable()) {
            val cloudResult = cloudProvider.generateText(request)
            if (cloudResult.isSuccess) return cloudResult
            return cloudResult
        }

        return Result.failure(
            localFailure ?: IllegalStateException("No configured AI provider is available.")
        )
    }

    private suspend fun routeCloudThenLocal(request: AIRequest): Result<AIResponse> {
        var cloudFailure: Throwable? = null
        if (cloudProvider.isAvailable()) {
            val cloudResult = cloudProvider.generateText(request)
            if (cloudResult.isSuccess) return cloudResult
            cloudFailure = cloudResult.exceptionOrNull()
        }

        if (localProvider.isAvailable()) {
            val localResult = localProvider.generateText(request)
            if (localResult.isSuccess) return localResult
            if (cloudFailure == null) return localResult
        }

        return Result.failure(
            cloudFailure ?: IllegalStateException("No configured AI provider is available.")
        )
    }
}
