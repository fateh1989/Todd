package com.todd

import com.todd.core.ai.AIProvider
import com.todd.core.ai.AIRequest
import com.todd.core.ai.AIResponse
import com.todd.core.ai.GeminiCloudModel
import com.todd.core.ai.ProviderCapabilities
import com.todd.core.ai.ProviderType
import com.todd.core.ai.SelectableGeminiAIProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectableGeminiAIProviderTest {

    @Test
    fun `uses lite model when owner selects lite`() = runBlocking {
        val lite = RecordingProvider("lite")
        val strong = RecordingProvider("strong")
        var selected = GeminiCloudModel.FLASH_LITE_3_5
        val provider = SelectableGeminiAIProvider({ selected }, lite, strong)

        assertEquals("lite", provider.generateText(AIRequest("hello")).getOrThrow().text)
        assertEquals(1, lite.calls)
        assertEquals(0, strong.calls)
    }

    @Test
    fun `uses strong model when owner selects strong`() = runBlocking {
        val lite = RecordingProvider("lite")
        val strong = RecordingProvider("strong")
        var selected = GeminiCloudModel.FLASH_3_8
        val provider = SelectableGeminiAIProvider({ selected }, lite, strong)

        assertEquals("strong", provider.generateText(AIRequest("code")).getOrThrow().text)
        assertEquals(0, lite.calls)
        assertEquals(1, strong.calls)
    }

    private class RecordingProvider(private val label: String) : AIProvider {
        var calls = 0
        override val type = ProviderType.CLOUD_GEMINI
        override val capabilities = ProviderCapabilities(isLocal = false)
        override suspend fun isAvailable(): Boolean = true
        override suspend fun generateText(request: AIRequest): Result<AIResponse> {
            calls += 1
            return Result.success(AIResponse(label, ProviderType.CLOUD_GEMINI))
        }
    }
}
