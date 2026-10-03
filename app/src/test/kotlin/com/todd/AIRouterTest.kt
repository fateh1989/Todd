package com.todd

import com.todd.core.ai.*
import com.todd.core.model.AIProviderMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIRouterTest {

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
    fun `deterministic mock provider handles correction`() = runBlocking {
        val mockLocal = MockAIProvider()
        val request = AIRequest(prompt = "correct this", selectedText = "هدا نص بوه خطأ")
        val response = mockLocal.generateText(request).getOrThrow()

        assertTrue(response.text.contains("التصحيح المقترح"))
    }
}
