package com.todd.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    CANCELLING,
    PAUSED,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class RemoteExecutionMode {
    INSPECT,
    TEST,
    BUILD,
    VERIFY_ANDROID
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
    val timeoutMinutes: Int = 120,
    val mode: RemoteExecutionMode = RemoteExecutionMode.VERIFY_ANDROID
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
    val providerRunId: Long? = null,
    val providerRunUrl: String? = null,
    val repository: String = "",
    val branch: String = "",
    val artifactNames: List<String> = emptyList(),
    val failureMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val lastHeartbeat: Long = System.currentTimeMillis()
)

data class RemoteDispatchReceipt(
    val runId: Long,
    val runUrl: String
)

data class RemoteRunSnapshot(
    val runId: Long,
    val runUrl: String,
    val status: String,
    val conclusion: String?,
    val currentStep: String?,
    val artifactNames: List<String>,
    val failureMessage: String? = null
)

data class StoredRemoteJob(
    val request: RemoteJobRequest,
    val state: RemoteJobState
)

interface RemoteExecutionGateway {
    suspend fun findExisting(jobId: String): Result<RemoteDispatchReceipt?>
    suspend fun dispatch(request: RemoteJobRequest): Result<RemoteDispatchReceipt>
    suspend fun getRun(runId: Long): Result<RemoteRunSnapshot>
    suspend fun cancel(runId: Long): Result<Boolean>
}

interface RemoteJobStore {
    fun save(job: StoredRemoteJob)
    fun load(jobId: String): StoredRemoteJob?
}

class InMemoryRemoteJobStore : RemoteJobStore {
    private val jobs = mutableMapOf<String, StoredRemoteJob>()

    override fun save(job: StoredRemoteJob) {
        jobs[job.request.jobId] = job
    }

    override fun load(jobId: String): StoredRemoteJob? = jobs[jobId]
}

interface RemoteExecutor {
    suspend fun startJob(request: RemoteJobRequest): Result<String>
    suspend fun reconnect(jobId: String): Result<RemoteJobState>
    suspend fun requestPause(jobId: String): Result<Boolean>
    suspend fun requestCancel(jobId: String): Result<Boolean>
    fun observeJob(jobId: String): Flow<RemoteJobState>
}

class GitHubActionsRemoteExecutor(
    private val gateway: RemoteExecutionGateway,
    private val store: RemoteJobStore,
    private val pollIntervalMs: Long = 7_500L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val onStateChanged: (RemoteJobState) -> Unit = {}
) : RemoteExecutor {

    private val flows = mutableMapOf<String, MutableStateFlow<RemoteJobState>>()

    override suspend fun startJob(request: RemoteJobRequest): Result<String> {
        if (request.repository.isBlank()) {
            return Result.failure(IllegalArgumentException("Remote repository is required."))
        }
        if (request.branch.isBlank()) {
            return Result.failure(IllegalArgumentException("Remote branch is required."))
        }

        val stored = store.load(request.jobId)
        if (stored?.state?.providerRunId != null) {
            startPolling(request.jobId)
            return Result.success(request.jobId)
        }

        val initial = stored?.state ?: RemoteJobState(
            jobId = request.jobId,
            status = RemoteJobStatus.SUBMITTED,
            currentStepDescription = "Submitting verified remote job for ${request.repository}@${request.branch}",
            headCommit = request.startCommit,
            repository = request.repository,
            branch = request.branch
        )
        publish(request, initial)

        val recovered = gateway.findExisting(request.jobId).getOrElse { error ->
            val degraded = initial.copy(
                currentStepDescription = "Could not check for an existing remote run before dispatch",
                failureMessage = error.message,
                lastHeartbeat = System.currentTimeMillis()
            )
            publish(request, degraded)
            return Result.failure(error)
        }

        val receipt = recovered ?: gateway.dispatch(request).getOrElse { error ->
            val failed = initial.copy(
                status = RemoteJobStatus.FAILED,
                currentStepDescription = "Remote dispatch failed",
                failureMessage = error.message,
                completedAt = System.currentTimeMillis(),
                lastHeartbeat = System.currentTimeMillis()
            )
            publish(request, failed)
            return Result.failure(error)
        }

        val submitted = initial.copy(
            status = RemoteJobStatus.CONNECTING,
            currentStepDescription = if (recovered != null) {
                "Recovered existing remote worker run; reconnecting"
            } else {
                "Remote worker accepted the job; waiting for runner"
            },
            providerRunId = receipt.runId,
            providerRunUrl = receipt.runUrl,
            failureMessage = null,
            lastHeartbeat = System.currentTimeMillis()
        )
        publish(request, submitted)
        startPolling(request.jobId)
        return Result.success(request.jobId)
    }

    override suspend fun reconnect(jobId: String): Result<RemoteJobState> {
        val stored = store.load(jobId)
            ?: return Result.failure(IllegalArgumentException("Remote job $jobId not found"))

        val runId = stored.state.providerRunId
            ?: return Result.success(stored.state)

        val snapshot = gateway.getRun(runId).getOrElse { error ->
            val degraded = stored.state.copy(
                currentStepDescription = "Remote job exists, but status refresh failed",
                failureMessage = error.message,
                lastHeartbeat = System.currentTimeMillis()
            )
            publish(stored.request, degraded)
            return Result.failure(error)
        }

        val refreshed = mapSnapshot(stored.state, snapshot)
        publish(stored.request, refreshed)
        return Result.success(refreshed)
    }

    override suspend fun requestPause(jobId: String): Result<Boolean> {
        val stored = store.load(jobId)
            ?: return Result.failure(IllegalArgumentException("Remote job $jobId not found"))

        return when (stored.state.status) {
            RemoteJobStatus.COMPLETED,
            RemoteJobStatus.FAILED,
            RemoteJobStatus.CANCELLED -> Result.success(false)
            else -> Result.failure(
                UnsupportedOperationException(
                    "The GitHub Actions remote provider cannot suspend a running process. " +
                        "Cancel is supported, and completed/queued jobs can be reconnected."
                )
            )
        }
    }

    override suspend fun requestCancel(jobId: String): Result<Boolean> {
        val stored = store.load(jobId)
            ?: return Result.failure(IllegalArgumentException("Remote job $jobId not found"))
        val runId = stored.state.providerRunId
            ?: return Result.failure(IllegalStateException("Remote run has not been assigned yet."))

        val accepted = gateway.cancel(runId).getOrElse { return Result.failure(it) }
        if (accepted) {
            publish(
                stored.request,
                stored.state.copy(
                    status = RemoteJobStatus.CANCELLING,
                    currentStepDescription = "Cancellation requested; waiting for provider confirmation",
                    lastHeartbeat = System.currentTimeMillis()
                )
            )
            startPolling(jobId)
        }
        return Result.success(accepted)
    }

    override fun observeJob(jobId: String): Flow<RemoteJobState> {
        val existing = flows[jobId]
        if (existing != null) return existing.asStateFlow()

        val initial = store.load(jobId)?.state ?: RemoteJobState(jobId = jobId)
        return flows.getOrPut(jobId) { MutableStateFlow(initial) }.asStateFlow()
    }

    private fun startPolling(jobId: String) {
        scope.launch {
            while (true) {
                delay(pollIntervalMs)
                val state = reconnect(jobId).getOrNull() ?: continue
                if (state.status in TERMINAL_STATES) break
            }
        }
    }

    private fun publish(request: RemoteJobRequest, state: RemoteJobState) {
        store.save(StoredRemoteJob(request, state))
        flows.getOrPut(request.jobId) { MutableStateFlow(state) }.value = state
        onStateChanged(state)
    }

    private fun mapSnapshot(
        previous: RemoteJobState,
        snapshot: RemoteRunSnapshot
    ): RemoteJobState {
        val status = when {
            snapshot.status == "queued" || snapshot.status == "waiting" ->
                RemoteJobStatus.SUBMITTED
            snapshot.status == "in_progress" -> statusFromStep(snapshot.currentStep)
            snapshot.conclusion == "success" -> RemoteJobStatus.COMPLETED
            snapshot.conclusion == "cancelled" -> RemoteJobStatus.CANCELLED
            snapshot.conclusion == "skipped" -> RemoteJobStatus.CANCELLED
            snapshot.status == "completed" -> RemoteJobStatus.FAILED
            else -> RemoteJobStatus.CONNECTING
        }

        val finished = status in TERMINAL_STATES

        return previous.copy(
            status = status,
            currentStepDescription = snapshot.currentStep
                ?: when (status) {
                    RemoteJobStatus.COMPLETED -> "Remote verification completed successfully"
                    RemoteJobStatus.CANCELLED -> "Remote job cancelled"
                    RemoteJobStatus.FAILED -> "Remote job failed"
                    else -> previous.currentStepDescription
                },
            providerRunId = snapshot.runId,
            providerRunUrl = snapshot.runUrl,
            artifactNames = snapshot.artifactNames,
            artifactPath = snapshot.artifactNames.firstOrNull(),
            failureMessage = snapshot.failureMessage,
            completedAt = if (finished) previous.completedAt ?: System.currentTimeMillis() else null,
            lastHeartbeat = System.currentTimeMillis()
        )
    }

    private fun statusFromStep(step: String?): RemoteJobStatus {
        val normalized = step.orEmpty().lowercase()
        return when {
            "checkout" in normalized || "clone" in normalized -> RemoteJobStatus.CLONING_REPO
            "start commit" in normalized || "inspect" in normalized -> RemoteJobStatus.INSPECTING_STATE
            "test" in normalized || "gradle" in normalized || "remote task" in normalized ->
                RemoteJobStatus.RUNNING_TESTS
            "artifact" in normalized || "collect" in normalized -> RemoteJobStatus.VERIFYING_ARTIFACTS
            else -> RemoteJobStatus.CONNECTING
        }
    }

    companion object {
        private val TERMINAL_STATES = setOf(
            RemoteJobStatus.COMPLETED,
            RemoteJobStatus.FAILED,
            RemoteJobStatus.CANCELLED
        )
    }
}

class SimulatedRemoteExecutor : RemoteExecutor {
    private val jobStates = mutableMapOf<String, MutableStateFlow<RemoteJobState>>()

    override suspend fun startJob(request: RemoteJobRequest): Result<String> {
        val flow = MutableStateFlow(
            RemoteJobState(
                jobId = request.jobId,
                status = RemoteJobStatus.SUBMITTED,
                currentStepDescription = "Job queued on simulated worker for ${request.repository}",
                headCommit = request.startCommit,
                repository = request.repository,
                branch = request.branch
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
            it.copy(status = RemoteJobStatus.PAUSED, currentStepDescription = "Simulated remote job paused")
        }
        return Result.success(true)
    }

    override suspend fun requestCancel(jobId: String): Result<Boolean> {
        jobStates[jobId]?.update {
            it.copy(status = RemoteJobStatus.CANCELLED, currentStepDescription = "Simulated remote job cancelled")
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
            val recentErrors = updatedList.takeLast(3).mapNotNull { it.errorObserved }
            val isStuck = recentErrors.size == 3 && recentErrors.distinct().size == 1

            current.copy(
                status = if (isStuck) {
                    RemoteJobStatus.BLOCKED
                } else if (iteration.testPassed) {
                    RemoteJobStatus.VERIFYING_ARTIFACTS
                } else {
                    RemoteJobStatus.RUNNING_TESTS
                },
                currentStepDescription = if (isStuck) {
                    "Stuck loop detected: 3 identical failures without changed technical factor."
                } else {
                    "Executed ${iteration.commandExecuted}"
                },
                iterationCount = updatedList.size,
                iterations = updatedList,
                isStuckLoopDetected = isStuck,
                lastHeartbeat = System.currentTimeMillis()
            )
        }
    }
}
