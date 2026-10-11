package com.todd

import com.todd.core.ai.AIRequest
import com.todd.core.ai.WebResearchPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResearchPolicyTest {
    @Test
    fun `Arabic and English explicit requests are recognized`() {
        assertTrue(WebResearchPolicy.isRequested(AIRequest("ابحث عن فعاليات اليوم في مالمو")))
        assertTrue(WebResearchPolicy.isRequested(AIRequest("search online for current train times")))
        assertTrue(WebResearchPolicy.isRequested(AIRequest("question", webResearch = true)))
    }

    @Test
    fun `ordinary messages do not enable web search`() {
        assertFalse(WebResearchPolicy.isRequested(AIRequest("ترجم هذه الرسالة")))
        assertFalse(WebResearchPolicy.isRequested(AIRequest("اشرح لي معنى كلمة البحث")))
    }

    @Test
    fun `research guidance requires evidence and forbids inferred device location`() {
        val instructions = WebResearchPolicy.instructions()
        assertTrue(instructions.contains("primary and official sources"))
        assertTrue(instructions.contains("conflicting reports"))
        assertTrue(instructions.contains("never infer or transmit device location"))
    }
}
