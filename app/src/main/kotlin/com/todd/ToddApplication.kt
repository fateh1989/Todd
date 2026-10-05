package com.todd

import android.app.Application
import com.todd.data.local.ToddDatabase
import com.todd.data.repository.ToddRepository
import com.todd.core.ai.AIRouter
import com.todd.core.ai.LocalOnDeviceAIProvider
import com.todd.core.ai.GeminiAIProvider
import com.todd.core.ai.GeminiLiveClient
import com.todd.core.rules.RulesEngine
import com.todd.core.state.ToddStateMachine
import com.todd.core.tools.GitHubRestTool
import com.todd.core.tools.GitHubTool
import com.todd.core.tools.GitHubCredentialStore
import com.todd.core.memory.MemoryLearningEngine
import com.todd.core.remote.AndroidRemoteJobStore
import com.todd.core.remote.GitHubActionsRemoteExecutor
import com.todd.core.remote.GitHubActionsRemoteGateway
import com.todd.core.remote.RemoteExecutor
import com.todd.core.remote.RemoteJobStatus
import com.todd.core.model.Project
import com.todd.core.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ToddApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var database: ToddDatabase
        private set

    lateinit var repository: ToddRepository
        private set

    lateinit var aiRouter: AIRouter
        private set

    lateinit var stateMachine: ToddStateMachine
        private set

    lateinit var githubTool: GitHubTool
        private set

    lateinit var githubCredentialStore: GitHubCredentialStore
        private set

    lateinit var rulesEngine: RulesEngine
        private set

    lateinit var liveClient: GeminiLiveClient
        private set

    lateinit var memoryLearningEngine: MemoryLearningEngine
        private set

    lateinit var remoteExecutor: RemoteExecutor
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = ToddDatabase.getDatabase(this)
        repository = ToddRepository(database)
        memoryLearningEngine = MemoryLearningEngine(repository)
        stateMachine = ToddStateMachine(repository)
        githubCredentialStore = GitHubCredentialStore(this)
        githubTool = GitHubRestTool(tokenProvider = { githubCredentialStore.getToken() })
        rulesEngine = RulesEngine()
        remoteExecutor = GitHubActionsRemoteExecutor(
            gateway = GitHubActionsRemoteGateway(
                tokenProvider = { githubCredentialStore.getToken() }
            ),
            store = AndroidRemoteJobStore(this),
            onStateChanged = { remoteState ->
                appScope.launch {
                    repository.getTaskById(remoteState.jobId)?.let { task ->
                        val taskStatus = when (remoteState.status) {
                            RemoteJobStatus.SUBMITTED,
                            RemoteJobStatus.CONNECTING,
                            RemoteJobStatus.CLONING_REPO,
                            RemoteJobStatus.INSPECTING_STATE,
                            RemoteJobStatus.EDITING_FILES,
                            RemoteJobStatus.COMMITTING,
                            RemoteJobStatus.RUNNING_TESTS ->
                                TaskStatus.IN_PROGRESS

                            RemoteJobStatus.VERIFYING_ARTIFACTS ->
                                TaskStatus.VERIFYING

                            RemoteJobStatus.COMPLETED ->
                                TaskStatus.VERIFIED

                            RemoteJobStatus.FAILED ->
                                TaskStatus.FAILED

                            RemoteJobStatus.BLOCKED ->
                                TaskStatus.BLOCKED

                            RemoteJobStatus.PAUSED ->
                                TaskStatus.PAUSED

                            RemoteJobStatus.CANCELLED,
                            RemoteJobStatus.CANCELLING ->
                                TaskStatus.CANCELLED

                            else -> task.status
                        }

                        repository.updateTask(
                            task.copy(
                                status = taskStatus,
                                currentStep = remoteState.currentStepDescription,
                                lastEvidence = remoteState.providerRunUrl
                                    ?: remoteState.artifactNames.firstOrNull()
                                    ?: task.lastEvidence,
                                failureCause = remoteState.failureMessage,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        )
        liveClient = GeminiLiveClient(
            context = this,
            rulesEngine = rulesEngine,
            repository = repository,
            githubTool = githubTool,
            remoteExecutor = remoteExecutor
        )

        // Local provider: real Gemini on-device inference; LOCAL_ONLY never falls back to cloud.
        val localProvider = LocalOnDeviceAIProvider(modelName = "gemini-3.5-flash-lite")

        // Cloud provider: current Firebase AI Logic using the Gemini Developer API backend
        // Credentials are secure and managed via Firebase project configuration (no hardcoded keys)
        val geminiProvider = GeminiAIProvider(modelName = "gemini-3.8-flash")

        aiRouter = AIRouter(
            localProvider = localProvider,
            cloudProvider = geminiProvider
        )

        appScope.launch {
            if (repository.getProjectById("todd-main") == null) {
                repository.saveProject(
                    Project(
                        id = "todd-main",
                        name = "Todd",
                        description = "Todd Android personal AI agent",
                        repository = "fateh1989/Todd",
                        branch = "main",
                        currentGoal = "Continue building and verifying Todd"
                    )
                )
            }
        }
    }

    companion object {
        lateinit var instance: ToddApplication
            private set
    }
}
