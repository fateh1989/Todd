package com.todd

import com.todd.core.agent.ChatGreetingPolicy
import com.todd.core.ai.AIRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGreetingPolicyTest {
    @Test
    fun `short Arabic greetings do not need the old project context`() {
        for (greeting in listOf("الوو", "مرحبا", "أهلًا!", "السلام عليكم", "صباح الخير")) {
            assertTrue(greeting, ChatGreetingPolicy.isStandaloneGreeting(
                AIRequest(prompt = greeting, projectContext = "Previous GitHub coding task")
            ))
        }
    }

    @Test
    fun `greetings in English are also recognized`() {
        assertTrue(ChatGreetingPolicy.isStandaloneGreeting(AIRequest("Hello Todd!")))
    }

    @Test
    fun `followups and real tasks keep their context`() {
        for (message in listOf("الوو أكمل المهمة السابقة", "تابع البرمجة", "ابحث عن الأخبار", "مرحبا، أصلح هذه المشكلة")) {
            assertFalse(message, ChatGreetingPolicy.isStandaloneGreeting(AIRequest(message)))
        }
    }

    @Test
    fun `explicit translation and research are never overridden`() {
        assertFalse(ChatGreetingPolicy.isStandaloneGreeting(
            AIRequest("مرحبا", systemPrompt = "Translate to English")
        ))
        assertFalse(ChatGreetingPolicy.isStandaloneGreeting(
            AIRequest("مرحبا", webResearch = true)
        ))
    }
}
