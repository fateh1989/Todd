package com.todd

import com.todd.core.remote.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteExecutorTest {

    @Test
    fun `stuck loop detection triggers when 3 consecutive identical errors occur`() = runBlocking {
        val executor = SimulatedRemoteExecutor()
        val request = RemoteJobRequest(
            jobId = "remote-test-1",
            projectId = "p1",
            repository = "fateh1989/Todd",
            branch = "main",
            startCommit = "5583d33",
            objective = "Fix Gradle dependency build",
            completionCriteria = "Test passes"
        )

        executor.startJob(request)

        val err = "Unresolved reference: Cannot find symbol R.id.button"

        // Iteration 1
        executor.stepIteration("remote-test-1", IterationRecord(1, "5583d33", "H1", listOf("A.kt"), "./gradlew test", false, err))
        val state1 = executor.reconnect("remote-test-1").getOrThrow()
        assertFalse(state1.isStuckLoopDetected)

        // Iteration 2
        executor.stepIteration("remote-test-1", IterationRecord(2, "5583d33", "H2", listOf("A.kt"), "./gradlew test", false, err))
        val state2 = executor.reconnect("remote-test-1").getOrThrow()
        assertFalse(state2.isStuckLoopDetected)

        // Iteration 3 with identical error
        executor.stepIteration("remote-test-1", IterationRecord(3, "5583d33", "H3", listOf("A.kt"), "./gradlew test", false, err))
        val state3 = executor.reconnect("remote-test-1").getOrThrow()

        assertTrue("Stuck loop MUST be detected after 3 identical errors", state3.isStuckLoopDetected)
        assertEquals(RemoteJobStatus.BLOCKED, state3.status)
    }

    @Test
    fun `reconnect returns saved job state`() = runBlocking {
        val executor = SimulatedRemoteExecutor()
        val request = RemoteJobRequest(
            jobId = "remote-job-xyz",
            projectId = "p2",
            repository = "fateh1989/Todd",
            branch = "feature-x",
            startCommit = "5583d33",
            objective = "Analyze repository",
            completionCriteria = "Summary ready"
        )
        executor.startJob(request)
        val state = executor.reconnect("remote-job-xyz").getOrThrow()

        assertEquals("remote-job-xyz", state.jobId)
        assertEquals(RemoteJobStatus.SUBMITTED, state.status)
    }
}
