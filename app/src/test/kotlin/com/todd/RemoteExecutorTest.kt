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

        executor.stepIteration("remote-test-1", IterationRecord(1, "5583d33", "H1", listOf("A.kt"), "./gradlew test", false, err))
        assertFalse(executor.reconnect("remote-test-1").getOrThrow().isStuckLoopDetected)

        executor.stepIteration("remote-test-1", IterationRecord(2, "5583d33", "H2", listOf("A.kt"), "./gradlew test", false, err))
        assertFalse(executor.reconnect("remote-test-1").getOrThrow().isStuckLoopDetected)

        executor.stepIteration("remote-test-1", IterationRecord(3, "5583d33", "H3", listOf("A.kt"), "./gradlew test", false, err))
        val state3 = executor.reconnect("remote-test-1").getOrThrow()

        assertTrue(state3.isStuckLoopDetected)
        assertEquals(RemoteJobStatus.BLOCKED, state3.status)
    }

    @Test
    fun `real executor dispatches reconnects and verifies provider completion`() = runBlocking {
        val gateway = FakeGateway()
        val store = InMemoryRemoteJobStore()
        val executor = GitHubActionsRemoteExecutor(
            gateway = gateway,
            store = store,
            pollIntervalMs = 60_000L
        )

        val request = RemoteJobRequest(
            jobId = "remote-real-1",
            projectId = "todd-main",
            repository = "fateh1989/Todd",
            branch = "main",
            startCommit = "abc123",
            objective = "Run Android verification",
            completionCriteria = "Unit tests and APK build pass",
            mode = RemoteExecutionMode.VERIFY_ANDROID
        )

        assertEquals("remote-real-1", executor.startJob(request).getOrThrow())

        gateway.snapshot = RemoteRunSnapshot(
            runId = 991L,
            runUrl = "https://github.com/fateh1989/Todd/actions/runs/991",
            status = "completed",
            conclusion = "success",
            currentStep = null,
            artifactNames = listOf("todd-remote-remote-real-1")
        )

        val state = executor.reconnect("remote-real-1").getOrThrow()
        assertEquals(RemoteJobStatus.COMPLETED, state.status)
        assertEquals(991L, state.providerRunId)
        assertTrue(state.artifactNames.contains("todd-remote-remote-real-1"))
    }

    @Test
    fun `real executor does not pretend GitHub Actions can pause`() = runBlocking {
        val executor = GitHubActionsRemoteExecutor(
            gateway = FakeGateway(),
            store = InMemoryRemoteJobStore(),
            pollIntervalMs = 60_000L
        )

        executor.startJob(
            RemoteJobRequest(
                jobId = "remote-pause-1",
                projectId = "p",
                repository = "fateh1989/Todd",
                branch = "main",
                startCommit = "abc",
                objective = "verify",
                completionCriteria = "green"
            )
        )

        val pause = executor.requestPause("remote-pause-1")
        assertTrue(pause.isFailure)
    }

    private class FakeGateway : RemoteExecutionGateway {
        var snapshot = RemoteRunSnapshot(
            runId = 991L,
            runUrl = "https://github.com/fateh1989/Todd/actions/runs/991",
            status = "queued",
            conclusion = null,
            currentStep = null,
            artifactNames = emptyList()
        )

        override suspend fun dispatch(request: RemoteJobRequest): Result<RemoteDispatchReceipt> =
            Result.success(
                RemoteDispatchReceipt(
                    runId = 991L,
                    runUrl = "https://github.com/fateh1989/Todd/actions/runs/991"
                )
            )

        override suspend fun getRun(runId: Long): Result<RemoteRunSnapshot> =
            Result.success(snapshot)

        override suspend fun cancel(runId: Long): Result<Boolean> = Result.success(true)
    }
}
