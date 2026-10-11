package com.todd

import com.todd.core.ai.WebAgentLogStore
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAgentLogStoreTest {
    @Test
    fun stopCancelsCurrentJobAndAllowsANewOne() {
        val log = WebAgentLogStore()
        val first = Job()
        log.bindCurrentJob(first)
        assertTrue(log.isResearchActive.value)

        log.cancelCurrentResearch()
        assertTrue(first.isCancelled)
        assertFalse(log.isResearchActive.value)

        val second = Job()
        log.bindCurrentJob(second)
        log.markCompleted(first)
        assertTrue(log.isResearchActive.value)
        assertTrue(second.isActive)

        log.markCompleted(second)
        assertFalse(log.isResearchActive.value)
    }
}
