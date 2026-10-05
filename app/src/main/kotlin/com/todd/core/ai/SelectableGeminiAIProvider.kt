package com.todd.core.ai

/**
 * Manual cloud-model selector.
 *
 * Todd never silently changes the selected Gemini model. The owner chooses the model
 * in Settings according to the current need, and the choice is persisted on-device.
 */
class SelectableGeminiAIProvider(
    private val selectionProvider: () -> GeminiCloudModel,
    private val liteProvider: AIProvider,
    private val strongProvider: AIProvider
) : AIProvider {

    override val type: ProviderType = ProviderType.CLOUD_GEMINI

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsText = true,
        supportsStreaming = false,
        supportsTools = false,
        supportsVision = true,
        supportsAudio = false,
        maxContextTokens = 1_000_000,
        isLocal = false
    )

    override suspend fun isAvailable(): Boolean =
        selectedProvider().isAvailable()

    override suspend fun generateText(request: AIRequest): Result<AIResponse> =
        selectedProvider().generateText(request)

    private fun selectedProvider(): AIProvider =
        when (selectionProvider()) {
            GeminiCloudModel.FLASH_LITE_3_1 -> liteProvider
            GeminiCloudModel.FLASH_3_8 -> strongProvider
        }
}
