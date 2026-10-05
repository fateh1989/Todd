package com.todd

import com.todd.core.ai.thinkingLevelForGeminiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiThinkingPolicyTest {

    @Test
    fun `flash lite uses minimal thinking for frequent work`() {
        assertEquals(
            "minimal",
            thinkingLevelForGeminiModel("gemini-3.1-flash-lite")
        )
    }

    @Test
    fun `flash 3 8 uses medium thinking for coding work`() {
        assertEquals(
            "medium",
            thinkingLevelForGeminiModel("gemini-3.8-flash")
        )
    }

    @Test
    fun `unknown Gemini models use conservative low thinking`() {
        assertEquals(
            "low",
            thinkingLevelForGeminiModel("gemini-other")
        )
    }
}
