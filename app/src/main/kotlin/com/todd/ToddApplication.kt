package com.todd

import android.app.Application
import com.todd.data.local.ToddDatabase
import com.todd.data.repository.ToddRepository
import com.todd.core.ai.AIRouter
import com.todd.core.ai.LocalOnDeviceAIProvider
import com.todd.core.ai.DirectGeminiAIProvider
import com.todd.core.ai.GeminiApiKeyStore
import com.todd.core.ai.GeminiLiveClient
import com.todd.core.ai.FirebaseRuntimeCredentialStore
import com.todd.core.ai.FirebaseRuntimeConfig
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
import com.todd.core.agent.AutonomousCodingLoop
import com.todd.core.agent.AutonomousTaskCoordinator
import com.todd.core.agent.AndroidAutonomousCheckpointStore
import com.todd.core.agent.AutonomousCheckpointStore
import com.todd.core.agent.ToddTextAgent
import com.todd.core.diagnostics.ToddDiagnostics
import com.todd.core.model.Project
import com.todd.core.model.TaskStatus
import com.todd.core.project.ActiveProjectStore
import com.todd.service.recovery.AutonomousRecoveryScheduler
import com.todd.core.scheduler.ToddTaskScheduler
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

    lateinit var textAgent: ToddTextAgent
        private set

    lateinit var diagnostics: ToddDiagnostics
        private set

    lateinit var taskScheduler: ToddTaskScheduler
        private set

    lateinit var stateMachine: ToddStateMachine
        private set

    lateinit var activeProjectStore: ActiveProjectStore
        private set

    lateinit var githubTool: GitHubTool
        private set

    lateinit var githubCredentialStore: GitHubCredentialStore
        private set

    lateinit var firebaseRuntimeCredentialStore: FirebaseRuntimeCredentialStore
        private set

    lateinit var geminiApiKeyStore: GeminiApiKeyStore
        private set

    lateinit var rulesEngine: RulesEngine
        private set

    lateinit var liveClient: GeminiLiveClient
        private set

    lateinit var memoryLearningEngine: MemoryLearningEngine
        private set

    lateinit var remoteExecutor: RemoteExecutor
        private set

    lateinit var autonomousCodingLoop: AutonomousCodingLoop
        private set

    lateinit var autonomousTaskCoordinator: AutonomousTaskCoordinator
        private set

    lateinit var autonomousCheckpointStore: AutonomousCheckpointStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = ToddDatabase.getDatabase(this)
        repository = ToddRepository(database)
        memoryLearningEngine = MemoryLearningEngine(repository)
        stateMachine = ToddStateMachine(repository)
        taskScheduler = ToddTaskScheduler(this)
        activeProjectStore = ActiveProjectStore(this)
        activeProjectStore.get()?.let(stateMachine::setActiveProject)
        githubCredentialStore = GitHubCredentialStore(this)
        firebaseRuntimeCredentialStore = FirebaseRuntimeCredentialStore(this)
        geminiApiKeyStore = GeminiApiKeyStore(this)
        runCatching {
            FirebaseRuntimeConfig.applyStored(this, firebaseRuntimeCredentialStore)
        }
        githubTool = GitHubRestTool(tokenProvider = { githubCredentialStore.getToken() })
        autonomousCheckpointStore = AndroidAutonomousCheckpointStore(this)
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
        // Local provider: real Gemini on-device inference; LOCAL_ONLY never falls back to cloud.
        val localProvider = LocalOnDeviceAIProvider(modelName = "gemini-3.5-flash-lite")

        // Primary cloud text provider: direct Gemini Developer API.
        // The owner enters one Gemini API key in Settings; it is encrypted with Android Keystore.
        // This deliberately does not depend on google-services.json so text chat can work even
        // when Firebase is unavailable on the physical device.
        val geminiProvider = DirectGeminiAIProvider(
            apiKeyProvider = { geminiApiKeyStore.getKey() },
            modelName = "gemini-3.8-flash"
        )

        autonomousCodingLoop = AutonomousCodingLoop(
            aiProvider = geminiProvider,
            githubTool = githubTool,
            remoteExecutor = remoteExecutor,
            checkpointStore = autonomousCheckpointStore
        )
        autonomousTaskCoordinator = AutonomousTaskCoordinator(
            repository = repository,
            stateMachine = stateMachine,
            githubCredentialStore = githubCredentialStore,
            codingLoop = autonomousCodingLoop,
            checkpointStore = autonomousCheckpointStore,
            scope = appScope,
            scheduleRecovery = {
                AutonomousRecoveryScheduler.schedule(this@ToddApplication)
            }
        )
        liveClient = GeminiLiveClient(
            context = this,
            rulesEngine = rulesEngine,
            repository = repository,
            githubTool = githubTool,
            remoteExecutor = remoteExecutor,
            autonomousTaskCoordinator = autonomousTaskCoordinator,
            stateMachine = stateMachine
        )

        aiRouter = AIRouter(
            localProvider = localProvider,
            cloudProvider = geminiProvider
        )
        textAgent = ToddTextAgent(
            aiRouter = aiRouter,
            githubTool = githubTool,
            remoteExecutor = remoteExecutor,
            autonomousTaskCoordinator = autonomousTaskCoordinator,
            repository = repository,
            taskScheduler = taskScheduler,
            stateMachine = stateMachine,
            rulesEngine = rulesEngine
        )
        diagnostics = ToddDiagnostics(
            context = this,
            repository = repository,
            aiRouter = aiRouter,
            githubTool = githubTool,
            githubCredentialStore = githubCredentialStore
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
            if (autonomousCheckpointStore.listPending().isNotEmpty()) {
                AutonomousRecoveryScheduler.schedule(this@ToddApplication)
            }
            taskScheduler.reconcile()
        }
    }

    companion object {
        lateinit var instance: ToddApplication
            private set
    }
}
