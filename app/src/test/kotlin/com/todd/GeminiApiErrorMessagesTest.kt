package com.todd

import com.todd.core.ai.GeminiApiErrorMessages
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiErrorMessagesTest {

    @Test
    fun `quota error tells owner to switch model manually`() {
        val message = GeminiApiErrorMessages.describe(
            statusCode = 429,
            apiMessage = "Resource exhausted",
            modelName = "gemini-3.1-flash-lite"
        )

        assertTrue(message.contains("Gemini 3.1 Flash-Lite"))
        assertTrue(message.contains("اختر النموذج الآخر"))
    }

    @Test
    fun `invalid api key gets a specific message`() {
        val message = GeminiApiErrorMessages.describe(
            statusCode = 400,
            apiMessage = "API key not valid",
            modelName = "gemini-3.8-flash"
        )

        assertTrue(message.contains("مفتاح Gemini API"))
        assertTrue(message.contains("مرفوض"))
    }
}
