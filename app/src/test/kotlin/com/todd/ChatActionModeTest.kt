package com.todd

import com.todd.ui.nextActionModeAfterSubmission
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatActionModeTest {
    @Test
    fun `a submitted research request returns to ordinary chat`() {
        assertEquals("CHAT", nextActionModeAfterSubmission("RESEARCH"))
    }

    @Test
    fun `other interaction modes retain their existing behavior`() {
        for (mode in listOf("CHAT", "TRANSLATION", "REMOTE", "CODING")) {
            assertEquals(mode, nextActionModeAfterSubmission(mode))
        }
    }
}
