package com.todd

import com.todd.core.ai.AIRequest
import com.todd.core.ai.googleSearchToolsForRequest
import com.todd.core.ai.shouldRetryGenerateRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGeminiSearchTest {
    @Test
    fun `explicit research enables REST grounding without changing the selected model`() {
        val tools = googleSearchToolsForRequest(AIRequest("ابحث في الإنترنت", webResearch = true))
        assertEquals(1, tools!!.length())
        assertTrue(tools.getJSONObject(0).has("google_search"))
    }

    @Test
    fun `normal chat never enables internet grounding`() {
        assertNull(googleSearchToolsForRequest(AIRequest("كيف حالك")))
    }

    @Test
    fun `only temporary server errors get one automatic retry`() {
        for (code in listOf(500, 502, 503, 504)) {
            assertTrue(shouldRetryGenerateRequest(code, 0))
            assertFalse(shouldRetryGenerateRequest(code, 1))
        }
        for (code in listOf(400, 401, 403, 408, 429)) {
            assertFalse(shouldRetryGenerateRequest(code, 0))
        }
    }
}
