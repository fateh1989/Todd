package com.todd

import com.todd.core.ai.*
import com.todd.core.model.AIProviderMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIRouterTest {

    private class FailingCloudProvider : AIProvider {
        override val type = ProviderType.CLOUD_GEMINI
        override val capabilities = ProviderCapabilities(isLocal = false)

        override suspend fun isAvailable(): Boolean = true

        override suspend fun generateText(request: AIRequest): Result<AIResponse> =
            Result.failure(IllegalStateException("cloud unavailable"))
    }

    private class FailingLocalProvider : AIProvider {
        override val type = ProviderType.LOCAL_ON_DEVICE
        override val capabilities = ProviderCapabilities(isLocal = true)

        override suspend fun isAvailable(): Boolean = false

        override suspend fun generateText(request: AIRequest): Result<AIResponse> =
            Result.failure(IllegalStateException("local unavailable"))
    }

    private class SuccessfulCloudProvider : AIProvider {
        override val type = ProviderType.CLOUD_GEMINI
        override val capabilities = ProviderCapabilities(isLocal = false)

        override suspend fun isAvailable(): Boolean = true

        override suspend fun generateText(request: AIRequest): Result<AIResponse> =
            Result.success(
                AIResponse(
                    text = "cloud fallback",
                    providerUsed = ProviderType.CLOUD_GEMINI,
                    isVerified = false
                )
            )
    }

    @Test
    fun `local only mode routes to local provider`() = runBlocking {
        val mockLocal = MockAIProvider()
        val mockCloud = MockAIProvider()
        val router = AIRouter(localProvider = mockLocal, cloudProvider = mockCloud)

        val request = AIRequest(prompt = "ترجم هذه الرسالة")
        val response = router.route(request, AIProviderMode.LOCAL_ONLY).getOrThrow()

        assertEquals(ProviderType.LOCAL_MOCK, response.providerUsed)
    }

    @Test
    fun `auto mode falls back to local when cloud request fails`() = runBlocking {
        val router = AIRouter(
            localProvider = MockAIProvider(),
            cloudProvider = FailingCloudProvider()
        )

        val response = router.route(
            AIRequest(prompt = "hello"),
            AIProviderMode.AUTO
        ).getOrThrow()

        assertEquals(ProviderType.LOCAL_MOCK, response.providerUsed)
    }

    @Test
    fun `auto mode falls back to cloud when real local provider is unavailable`() = runBlocking {
        val router = AIRouter(
            localProvider = FailingLocalProvider(),
            cloudProvider = SuccessfulCloudProvider()
        )

        val response = router.route(
            AIRequest(prompt = "hello"),
            AIProviderMode.AUTO
        ).getOrThrow()

        assertEquals(ProviderType.CLOUD_GEMINI, response.providerUsed)
        assertEquals("cloud fallback", response.text)
    }

    @Test
    fun `cloud preferred preserves cloud failure when local is unavailable`() = runBlocking {
        val router = AIRouter(
            localProvider = FailingLocalProvider(),
            cloudProvider = FailingCloudProvider()
        )

        val result = router.route(
            AIRequest(prompt = "hello"),
            AIProviderMode.CLOUD_PREFERRED
        )

        assertTrue(result.isFailure)
        assertEquals("cloud unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `local only mode never falls back to cloud when local provider is unavailable`() = runBlocking {
        val router = AIRouter(
            localProvider = FailingLocalProvider(),
            cloudProvider = SuccessfulCloudProvider()
        )

        val result = router.route(
            AIRequest(prompt = "hello"),
            AIProviderMode.LOCAL_ONLY
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `deterministic mock provider handles correction`() = runBlocking {
        val mockLocal = MockAIProvider()
        val request = AIRequest(prompt = "correct this", selectedText = "هدا نص بوه خطأ")
        val response = mockLocal.generateText(request).getOrThrow()

        assertTrue(response.text.contains("التصحيح المقترح"))
    }
}
