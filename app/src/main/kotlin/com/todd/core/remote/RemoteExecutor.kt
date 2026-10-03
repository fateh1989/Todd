package com.todd.core.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RemoteJobStatus {
    IDLE,
    SUBMITTED,
    CONNECTING,
    CLONING_REPO,
    INSPECTING_STATE,
    EDITING_FILES,
    RUNNING_TESTS,
    VERIFYING_ARTIFACTS,
    COMMITTING,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class RemoteJobRequest(
    val jobId: String,
    val projectId: String,
    val repository: String,
    val branch: String,
    val startCommit: String,
    val objective: String,
    val completionCriteria: String,
    val allowedTools: List<String> = listOf("git", "gradle", "lint", "test"),
    val timeoutMinutes: Int = 120
)

data class IterationRecord(
    val iterationIndex: Int,
    val startingCommit: String,
    val hypothesis: String,
    val filesChanged: List<String>,
    val commandExecuted: String,
    val testPassed: Boolean,
    val errorObserved: String? = null,
    val evidenceProduced: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class RemoteJobState(
    val jobId: String,
    val status: RemoteJobStatus = RemoteJobStatus.IDLE,
    val currentStepDescription: String = "",
    val headCommit: String = "",
    val iterationCount: Int = 0,
    val iterations: List<IterationRecord> = emptyList(),
    val isStuckLoopDetected: Boolean = false,
    val artifactPath: String? = null,
    val lastHeartbeat: Long = System.currentTimeMillis()
)

interface RemoteExecutor {
    suspend fun startJob(request: RemoteJobRequest): Result<String>
    suspend fun reconnect(jobId: String): Result<RemoteJobState>
    suspend fun requestPause(jobId: String): Result<Boolean>
    suspend fun requestCancel(jobId: String): Result<Boolean>
    fun observeJob(jobId: String): Flow<RemoteJobState>
}

class SimulatedRemoteExecutor : RemoteExecutor {
    private val jobStates = mutableMapOf<String, MutableStateFlow<RemoteJobState>>()

    override suspend fun startJob(request: RemoteJobRequest): Result<String> {
        val flow = MutableStateFlow(
            RemoteJobState(
                jobId = request.jobId,
                status = RemoteJobStatus.SUBMITTED,
                currentStepDescription = "Job queued on remote execution worker for ${request.repository}",
                headCommit = request.startCommit
            )
        )
        jobStates[request.jobId] = flow
        return Result.success(request.jobId)
    }

    override suspend fun reconnect(jobId: String): Result<RemoteJobState> {
        val stateFlow = jobStates[jobId]
            ?: return Result.failure(IllegalArgumentException("Remote job $jobId not found"))
        return Result.success(stateFlow.value.copy(lastHeartbeat = System.currentTimeMillis()))
    }

    override suspend fun requestPause(jobId: String): Result<Boolean> {
        jobStates[jobId]?.update {
            it.copy(status = RemoteJobStatus.PAUSED, currentStepDescription = "Remote job paused safely by owner request")
        }
        return Result.success(true)
    }

    override suspend fun requestCancel(jobId: String): Result<Boolean> {
        jobStates[jobId]?.update {
            it.copy(status = RemoteJobStatus.CANCELLED, currentStepDescription = "Remote job cancelled by owner")
        }
        return Result.success(true)
    }

    override fun observeJob(jobId: String): Flow<RemoteJobState> {
        return jobStates.getOrPut(jobId) {
            MutableStateFlow(RemoteJobState(jobId = jobId))
        }.asStateFlow()
    }

    fun stepIteration(jobId: String, iteration: IterationRecord) {
        jobStates[jobId]?.update { current ->
            val updatedList = current.iterations + iteration
            // Stuck-loop detection: check if last 3 iterations have identical error
            val recentErrors = updatedList.takeLast(3).mapNotNull { it.errorObserved }
            val isStuck = recentErrors.size == 3 && recentErrors.distinct().size == 1

            current.copy(
                status = if (isStuck) RemoteJobStatus.BLOCKED else if (iteration.testPassed) RemoteJobStatus.VERIFYING_ARTIFACTS else RemoteJobStatus.RUNNING_TESTS,
                currentStepDescription = if (isStuck) "Stuck loop detected: 3 identical failures without changed technical factor." else "Executed ${iteration.commandExecuted}",
                iterationCount = updatedList.size,
                iterations = updatedList,
                isStuckLoopDetected = isStuck,
                lastHeartbeat = System.currentTimeMillis()
            )
        }
    }
}
