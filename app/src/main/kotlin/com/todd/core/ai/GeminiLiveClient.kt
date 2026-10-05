package com.todd.core.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Transcription
import com.google.firebase.ai.type.liveGenerationConfig
import com.todd.core.model.AIProviderMode
import com.todd.core.rules.RulesEngine
import com.todd.core.rules.ActionCategory
import com.todd.core.rules.ActionRequest
import com.todd.core.tools.GitHubTool
import com.todd.core.remote.RemoteExecutor
import com.todd.core.remote.RemoteExecutionMode
import com.todd.core.remote.RemoteJobRequest
import com.todd.core.model.Task
import com.todd.core.model.TaskStatus
import com.todd.core.agent.AutonomousTaskCoordinator
import com.todd.core.state.ToddStateMachine
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.todd.data.repository.ToddRepository
import com.todd.service.context.DeviceContextProvider
import com.todd.service.screen.ScreenCaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class GeminiLiveState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    RECONNECTING,
    ERROR
}

data class LiveTranscriptItem(
    val id: String = System.nanoTime().toString(),
    val sender: String,
    val text: String,
    val isFinal: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(PublicPreviewAPI::class)
class GeminiLiveClient(
    private val context: Context?,
    private val rulesEngine: RulesEngine,
    private val repository: ToddRepository?,
    private val githubTool: GitHubTool? = null,
    private val remoteExecutor: RemoteExecutor? = null,
    private val autonomousTaskCoordinator: AutonomousTaskCoordinator? = null,
    private val stateMachine: ToddStateMachine? = null,
    val liveModelName: String = "gemini-3.1-flash-live-preview",
    private val sessionStarter: (suspend () -> Result<Unit>)? = null,
    private val textResponder: (suspend (String) -> Result<String>)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(GeminiLiveState.DISCONNECTED)
    val state: StateFlow<GeminiLiveState> = _state.asStateFlow()

    private val _transcripts = MutableStateFlow<List<LiveTranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<LiveTranscriptItem>> = _transcripts.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var liveSession: LiveSession? = null
    private var screenSyncJob: Job? = null

    suspend fun startSession(mode: AIProviderMode): Result<Boolean> = withContext(Dispatchers.IO) {
        if (mode == AIProviderMode.LOCAL_ONLY) {
            _state.value = GeminiLiveState.ERROR
            return@withContext Result.failure(
                IllegalStateException(
                    "وضع الذكاء مضبوط على 'محلي فقط' (LOCAL_ONLY). لا يمكن تشغيل Gemini Live في هذا الوضع."
                )
            )
        }

        if (sessionStarter == null && !FirebaseRuntimeConfig.current().configured) {
            _state.value = GeminiLiveState.ERROR
            return@withContext Result.failure(
                IllegalStateException(
                    "Firebase cloud AI is not configured for runtime use. Install a build made with the real Firebase configuration."
                )
            )
        }

        _state.value = GeminiLiveState.CONNECTING
        requestAudioFocus()

        try {
            val injectedStarter = sessionStarter
            if (injectedStarter != null) {
                injectedStarter().getOrThrow()
            } else {
                val generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                }

                val liveModel = Firebase.ai(app = FirebaseRuntimeConfig.requireConfiguredApp(), backend = GenerativeBackend.googleAI())
                    .liveModel(
                        modelName = liveModelName,
                        generationConfig = generationConfig,
                        tools = buildLiveTools()
                    )

                val session = liveModel.connect()
                liveSession = session
                session.startAudioConversation(
                    functionCallHandler = ::handleFunctionCall,
                    transcriptHandler = ::handleTranscription,
                    enableInterruptions = true
                )
            }

            _isMuted.value = false
            _state.value = GeminiLiveState.LISTENING
            startScreenAwarenessStreaming()
            Result.success(true)
        } catch (e: Exception) {
            _state.value = GeminiLiveState.ERROR
            abandonAudioFocus()
            Result.failure(e)
        }
    }

    private fun buildLiveTools(): List<Tool> {
        val declarations = mutableListOf(
            FunctionDeclaration(
                "getCurrentDeviceContext",
                "Read Todd's current fused Android context: screen elements, active screen metadata, visual capture state, and recent notifications.",
                mapOf<String, Schema>()
            )
        )


        if (autonomousTaskCoordinator != null && repository != null) {
            declarations += listOf(
                FunctionDeclaration(
                    "startAutonomousCoding",
                    "Start Todd's persistent autonomous coding loop from the live voice conversation.",
                    mapOf(
                        "objective" to Schema.string("Exact coding objective spoken by the owner."),
                        "projectId" to Schema.string("Todd project ID. Leave blank to use the active project.")
                    )
                ),
                FunctionDeclaration(
                    "checkTaskStatus",
                    "Read one durable Todd task state, step, failure, and evidence.",
                    mapOf(
                        "taskId" to Schema.string("Todd task ID.")
                    )
                ),
                FunctionDeclaration(
                    "checkLatestTask",
                    "Read the newest durable task for the active or specified Todd project.",
                    mapOf(
                        "projectId" to Schema.string("Todd project ID. Leave blank to use the active project.")
                    )
                )
            )
        }

        if (remoteExecutor != null && githubTool != null) {
            declarations += listOf(
                FunctionDeclaration(
                    "startRemoteVerification",
                    "Start a persistent GitHub Actions remote Android verification job for a repository. Todd will save the task and can reconnect later.",
                    mapOf(
                        "repository" to Schema.string("Repository in owner/name format."),
                        "branch" to Schema.string("Branch name, for example main."),
                        "objective" to Schema.string("What the remote job should verify."),
                        "completionCriteria" to Schema.string("Concrete completion criteria.")
                    )
                ),
                FunctionDeclaration(
                    "checkRemoteJob",
                    "Reconnect to a previously started Todd remote job and return its live state and evidence.",
                    mapOf(
                        "jobId" to Schema.string("Todd remote job ID.")
                    )
                ),
                FunctionDeclaration(
                    "cancelRemoteJob",
                    "Cancel a previously started Todd remote GitHub Actions job.",
                    mapOf(
                        "jobId" to Schema.string("Todd remote job ID.")
                    )
                )
            )
        }

        if (githubTool != null) {
            declarations += listOf(
                FunctionDeclaration(
                    "checkRepositoryStatus",
                    "Read the current branch and latest commit for a GitHub repository.",
                    mapOf(
                        "repository" to Schema.string("Repository in owner/name format."),
                        "branch" to Schema.string("Branch name, for example main.")
                    )
                ),
                FunctionDeclaration(
                    "checkLatestWorkflow",
                    "Read the latest GitHub Actions workflow status and artifact for a repository branch.",
                    mapOf(
                        "repository" to Schema.string("Repository in owner/name format."),
                        "branch" to Schema.string("Branch name, for example main.")
                    )
                ),
                FunctionDeclaration(
                    "listRepositoryFiles",
                    "List all file paths in a repository branch before deciding which files to inspect.",
                    mapOf(
                        "repository" to Schema.string("Repository in owner/name format."),
                        "branch" to Schema.string("Branch name.")
                    )
                ),
                FunctionDeclaration(
                    "readRepositoryFile",
                    "Read the current text content of one repository file.",
                    mapOf(
                        "repository" to Schema.string("Repository in owner/name format."),
                        "path" to Schema.string("Repository-relative file path."),
                        "ref" to Schema.string("Branch or commit SHA.")
                    )
                ),
                FunctionDeclaration(
                    "commitRepositoryFile",
                    "Create one real Git commit that replaces or creates one text file.",
                    mapOf(
                        "repository" to Schema.string("Repository in owner/name format."),
                        "branch" to Schema.string("Target branch."),
                        "path" to Schema.string("Repository-relative file path."),
                        "content" to Schema.string("Complete new file content."),
                        "message" to Schema.string("Git commit message.")
                    )
                )
            )
        }

        return listOf(Tool.functionDeclarations(declarations))
    }

    private fun handleFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        val response: JsonObject = runBlocking {
            val repo = call.args["repository"]?.jsonPrimitive?.content?.ifBlank { null }
                ?: "fateh1989/Todd"
            val branch = call.args["branch"]?.jsonPrimitive?.content?.ifBlank { null }
                ?: "main"
            val path = call.args["path"]?.jsonPrimitive?.content.orEmpty()
            val ref = call.args["ref"]?.jsonPrimitive?.content?.ifBlank { null } ?: branch
            val content = call.args["content"]?.jsonPrimitive?.content.orEmpty()
            val commitMessage = call.args["message"]?.jsonPrimitive?.content?.ifBlank { null }
                ?: "Todd update"
            val jobId = call.args["jobId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val taskId = call.args["taskId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val requestedProjectId = call.args["projectId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val activeProjectId = requestedProjectId.ifBlank {
                stateMachine?.state?.value?.activeProjectId ?: "todd-main"
            }
            val objective = call.args["objective"]?.jsonPrimitive?.content?.trim().orEmpty()
            val completionCriteria =
                call.args["completionCriteria"]?.jsonPrimitive?.content?.trim().orEmpty()

            val category = when (call.name) {
                "getCurrentDeviceContext" -> ActionCategory.SCREEN_CONTEXT_READ
                "startRemoteVerification", "startAutonomousCoding", "cancelRemoteJob" ->
                    ActionCategory.RUN_SHELL_COMMAND
                "checkRemoteJob", "checkTaskStatus", "checkLatestTask" -> ActionCategory.GIT_READ
                "commitRepositoryFile" ->
                    if (branch == "main" || branch == "master") ActionCategory.GIT_PUSH_MAIN
                    else ActionCategory.GIT_COMMIT_FEATURE_BRANCH
                else -> ActionCategory.GIT_READ
            }

            val evaluation = rulesEngine.evaluate(
                ActionRequest(
                    category = category,
                    projectId = activeProjectId,
                    target = repo,
                    dataSummary = "Gemini Live tool call: ${call.name}",
                    isPreApprovedInInstruction = true
                )
            )

            if (!evaluation.isAllowed) {
                buildJsonObject {
                    put("ok", false)
                    put("error", evaluation.promptMessage ?: "Tool call was not approved.")
                }
            } else if (call.name == "getCurrentDeviceContext") {
                buildJsonObject {
                    put("ok", true)
                    put("context", DeviceContextProvider.currentTextContext())
                    val visual = ScreenCaptureStore.state.value
                    put("visualCaptureRunning", visual.isRunning)
                    put("visualWidth", visual.width)
                    put("visualHeight", visual.height)
                    put("visualCapturedAt", visual.capturedAt)
                }
            } else if (
                call.name == "startAutonomousCoding" ||
                call.name == "checkTaskStatus" ||
                call.name == "checkLatestTask"
            ) {
                val coordinator = autonomousTaskCoordinator
                val repoStore = repository
                if (coordinator == null || repoStore == null) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "Todd autonomous task tools are not connected.")
                    }
                } else {
                    when (call.name) {
                        "startAutonomousCoding" -> {
                            if (objective.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "Coding objective is required.")
                                }
                            } else {
                                val newTaskId = coordinator.start(
                                    projectId = activeProjectId,
                                    title = objective.take(120),
                                    goal = objective,
                                    maxIterations = 4
                                )
                                buildJsonObject {
                                    put("ok", true)
                                    put("taskId", newTaskId)
                                    put("projectId", activeProjectId)
                                    put("status", "STARTED")
                                }
                            }
                        }

                        "checkTaskStatus" -> {
                            if (taskId.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "taskId is required.")
                                }
                            } else {
                                val task = repoStore.getTaskById(taskId)
                                if (task == null) {
                                    buildJsonObject {
                                        put("ok", false)
                                        put("taskId", taskId)
                                        put("error", "Todd task was not found.")
                                    }
                                } else {
                                    taskJson(task)
                                }
                            }
                        }

                        "checkLatestTask" -> {
                            val task = repoStore.getLatestTaskForProject(activeProjectId)
                            if (task == null) {
                                buildJsonObject {
                                    put("ok", true)
                                    put("found", false)
                                    put("projectId", activeProjectId)
                                }
                            } else {
                                taskJson(task)
                            }
                        }

                        else -> buildJsonObject {
                            put("ok", false)
                            put("error", "Unknown Todd task tool.")
                        }
                    }
                }
            } else if (
                call.name == "startRemoteVerification" ||
                call.name == "checkRemoteJob" ||
                call.name == "cancelRemoteJob"
            ) {
                val executor = remoteExecutor
                if (executor == null) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "Remote executor is not connected.")
                    }
                } else {
                    when (call.name) {
                        "startRemoteVerification" -> {
                            val tool = githubTool
                            if (tool == null) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "GitHub tool is not connected.")
                                }
                            } else if (objective.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "Remote objective is required.")
                                }
                            } else {
                                val infoResult = tool.getRepositoryInfo(repo, branch)
                                val info = infoResult.getOrNull()
                                if (info == null) {
                                    buildJsonObject {
                                        put("ok", false)
                                        put(
                                            "error",
                                            infoResult.exceptionOrNull()?.message
                                                ?: "Could not resolve repository state."
                                        )
                                    }
                                } else {
                                    val newJobId = "voice-remote-${System.currentTimeMillis()}"
                                    val criteria = completionCriteria.ifBlank {
                                        "GitHub Actions verification completes successfully with evidence."
                                    }

                                    repository?.saveTask(
                                        Task(
                                            id = newJobId,
                                            projectId = activeProjectId,
                                            title = objective.take(120),
                                            goal = objective,
                                            userInstructions = objective,
                                            status = TaskStatus.PLANNED,
                                            currentStep = "Preparing remote verification",
                                            completionCriteria = criteria
                                        )
                                    )

                                    val started = executor.startJob(
                                        RemoteJobRequest(
                                            jobId = newJobId,
                                            projectId = activeProjectId,
                                            repository = repo,
                                            branch = branch,
                                            startCommit = info.latestCommitSha,
                                            objective = objective,
                                            completionCriteria = criteria,
                                            mode = RemoteExecutionMode.VERIFY_ANDROID
                                        )
                                    )

                                    started.fold(
                                        onSuccess = {
                                            buildJsonObject {
                                                put("ok", true)
                                                put("jobId", newJobId)
                                                put("repository", repo)
                                                put("branch", branch)
                                                put("startCommit", info.latestCommitSha)
                                            }
                                        },
                                        onFailure = { error ->
                                            buildJsonObject {
                                                put("ok", false)
                                                put("jobId", newJobId)
                                                put(
                                                    "error",
                                                    error.message ?: "Remote verification failed to start."
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        "checkRemoteJob" -> {
                            if (jobId.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "jobId is required.")
                                }
                            } else {
                                executor.reconnect(jobId).fold(
                                    onSuccess = { state ->
                                        buildJsonObject {
                                            put("ok", true)
                                            put("jobId", state.jobId)
                                            put("status", state.status.name)
                                            put("step", state.currentStepDescription)
                                            put("runId", state.providerRunId ?: 0L)
                                            put("runUrl", state.providerRunUrl ?: "")
                                            put("headCommit", state.headCommit)
                                            put("artifacts", state.artifactNames.joinToString("\n"))
                                            put("failure", state.failureMessage ?: "")
                                        }
                                    },
                                    onFailure = { error ->
                                        buildJsonObject {
                                            put("ok", false)
                                            put("jobId", jobId)
                                            put("error", error.message ?: "Remote job refresh failed.")
                                        }
                                    }
                                )
                            }
                        }

                        "cancelRemoteJob" -> {
                            if (jobId.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "jobId is required.")
                                }
                            } else {
                                executor.requestCancel(jobId).fold(
                                    onSuccess = { accepted ->
                                        buildJsonObject {
                                            put("ok", accepted)
                                            put("jobId", jobId)
                                            put("cancelRequested", accepted)
                                        }
                                    },
                                    onFailure = { error ->
                                        buildJsonObject {
                                            put("ok", false)
                                            put("jobId", jobId)
                                            put("error", error.message ?: "Remote cancellation failed.")
                                        }
                                    }
                                )
                            }
                        }

                        else -> buildJsonObject {
                            put("ok", false)
                            put("error", "Unknown remote tool.")
                        }
                    }
                }
            } else {
                val tool = githubTool
                if (tool == null) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "GitHub tool is not connected.")
                    }
                } else {
                    when (call.name) {
                        "checkRepositoryStatus" -> {
                            tool.getRepositoryInfo(repo, branch).fold(
                                onSuccess = { info ->
                                    buildJsonObject {
                                        put("ok", true)
                                        put("repository", info.fullName)
                                        put("branch", info.activeBranch)
                                        put("defaultBranch", info.defaultBranch)
                                        put("latestCommit", info.latestCommitSha)
                                    }
                                },
                                onFailure = { error ->
                                    buildJsonObject {
                                        put("ok", false)
                                        put("error", error.message ?: "GitHub read failed.")
                                    }
                                }
                            )
                        }

                        "checkLatestWorkflow" -> {
                            tool.getLatestWorkflowRun(repo, branch).fold(
                                onSuccess = { run ->
                                    if (run == null) {
                                        buildJsonObject {
                                            put("ok", true)
                                            put("found", false)
                                        }
                                    } else {
                                        buildJsonObject {
                                            put("ok", true)
                                            put("found", true)
                                            put("runId", run.runId)
                                            put("workflow", run.workflowName)
                                            put("headSha", run.headSha)
                                            put("status", run.status)
                                            put("conclusion", run.conclusion ?: "")
                                            put("artifact", run.artifactName ?: "")
                                        }
                                    }
                                },
                                onFailure = { error ->
                                    buildJsonObject {
                                        put("ok", false)
                                        put("error", error.message ?: "Workflow read failed.")
                                    }
                                }
                            )
                        }

                        "listRepositoryFiles" -> {
                            tool.listRepositoryFiles(repo, branch).fold(
                                onSuccess = { files ->
                                    buildJsonObject {
                                        put("ok", true)
                                        put("count", files.size)
                                        put("files", files.joinToString("\n"))
                                    }
                                },
                                onFailure = { error ->
                                    buildJsonObject {
                                        put("ok", false)
                                        put("error", error.message ?: "Repository tree read failed.")
                                    }
                                }
                            )
                        }

                        "readRepositoryFile" -> {
                            if (path.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "File path is required.")
                                }
                            } else {
                                tool.readFile(repo, path, ref).fold(
                                    onSuccess = { file ->
                                        buildJsonObject {
                                            put("ok", true)
                                            put("path", file.path)
                                            put("sha", file.sha)
                                            put("content", file.content)
                                        }
                                    },
                                    onFailure = { error ->
                                        buildJsonObject {
                                            put("ok", false)
                                            put("error", error.message ?: "File read failed.")
                                        }
                                    }
                                )
                            }
                        }

                        "commitRepositoryFile" -> {
                            if (path.isBlank() || content.isBlank()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "Path and complete file content are required.")
                                }
                            } else {
                                tool.createCommit(
                                    repoFullName = repo,
                                    branch = branch,
                                    commitMessage = commitMessage,
                                    files = mapOf(path to content)
                                ).fold(
                                    onSuccess = { sha ->
                                        buildJsonObject {
                                            put("ok", true)
                                            put("commitSha", sha)
                                            put("path", path)
                                        }
                                    },
                                    onFailure = { error ->
                                        buildJsonObject {
                                            put("ok", false)
                                            put("error", error.message ?: "Commit failed.")
                                        }
                                    }
                                )
                            }
                        }

                        else -> buildJsonObject {
                            put("ok", false)
                            put("error", "Unknown tool: ${call.name}")
                        }
                    }
                }
            }
        }

        return FunctionResponsePart(call.name, response, call.id)
    }

    private fun taskJson(task: Task): JsonObject = buildJsonObject {
        put("ok", true)
        put("found", true)
        put("taskId", task.id)
        put("projectId", task.projectId)
        put("title", task.title)
        put("goal", task.goal)
        put("status", task.status.name)
        put("step", task.currentStep)
        put("evidence", task.lastEvidence ?: "")
        put("failure", task.failureCause ?: "")
        put("updatedAt", task.updatedAt)
    }

    private fun handleTranscription(input: Transcription?, output: Transcription?) {
        input?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            addTranscript("USER", it)
            _state.value = GeminiLiveState.THINKING
        }

        output?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            addTranscript("TODD", it)
            _state.value = GeminiLiveState.SPEAKING
        }
    }

    private fun startScreenAwarenessStreaming() {
        screenSyncJob?.cancel()
        val session = liveSession ?: return

        screenSyncJob = scope.launch {
            var lastVisualCaptureAt = 0L

            while (isActive && liveSession === session) {
                val visual = ScreenCaptureStore.state.value
                if (
                    visual.isRunning &&
                    visual.capturedAt > lastVisualCaptureAt &&
                    !visual.filePath.isNullOrBlank()
                ) {
                    val file = File(visual.filePath)
                    if (file.exists()) {
                        runCatching {
                            session.sendVideoRealtime(
                                InlineData(
                                    data = file.readBytes(),
                                    mimeType = "image/png",
                                    displayName = "Todd live Android screen"
                                )
                            )
                        }
                        lastVisualCaptureAt = visual.capturedAt
                    }
                }

                delay(1_200L)
            }
        }
    }

    fun handleBargeIn() {
        if (_state.value == GeminiLiveState.SPEAKING) {
            _state.value = GeminiLiveState.LISTENING
        }
    }

    fun toggleMute() {
        val newValue = !_isMuted.value
        _isMuted.value = newValue

        val session = liveSession ?: return
        if (newValue) {
            session.stopAudioConversation()
            _state.value = GeminiLiveState.LISTENING
        } else {
            scope.launch {
                try {
                    session.startAudioConversation(
                        functionCallHandler = ::handleFunctionCall,
                        transcriptHandler = ::handleTranscription,
                        enableInterruptions = true
                    )
                    _state.value = GeminiLiveState.LISTENING
                } catch (_: Exception) {
                    _state.value = GeminiLiveState.ERROR
                }
            }
        }
    }

    fun endSession() {
        val session = liveSession
        liveSession = null
        screenSyncJob?.cancel()
        screenSyncJob = null

        session?.stopAudioConversation()
        _state.value = GeminiLiveState.DISCONNECTED
        _isMuted.value = false
        abandonAudioFocus()

        if (session != null) {
            scope.launch {
                runCatching { session.close() }
            }
        }
    }

    suspend fun onUserSpeechReceived(speechText: String): Result<String> = withContext(Dispatchers.IO) {
        if (speechText.isBlank()) return@withContext Result.success("")

        addTranscript("USER", speechText)
        _state.value = GeminiLiveState.THINKING

        val injectedResponder = textResponder
        if (injectedResponder != null) {
            return@withContext try {
                val reply = injectedResponder(speechText).getOrThrow()
                addTranscript("TODD", reply)
                _state.value = GeminiLiveState.SPEAKING
                Result.success(reply)
            } catch (e: Exception) {
                _state.value = GeminiLiveState.ERROR
                Result.failure(e)
            }
        }

        val session = liveSession
            ?: return@withContext Result.failure(
                IllegalStateException("Gemini Live session is not connected.")
            )

        return@withContext try {
            session.sendTextRealtime(speechText)
            Result.success("")
        } catch (e: Exception) {
            _state.value = GeminiLiveState.ERROR
            Result.failure(e)
        }
    }

    private fun addTranscript(sender: String, text: String) {
        _transcripts.update { current ->
            val last = current.lastOrNull()
            if (last != null && last.sender == sender && last.text == text) {
                current
            } else {
                current + LiveTranscriptItem(sender = sender, text = text)
            }
        }
    }

    private fun requestAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            _state.value = GeminiLiveState.LISTENING
                        }
                    }
                    .build()
                am.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            _state.value = GeminiLiveState.LISTENING
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            }
        }
        audioFocusRequest = null
    }
}
