package com.todd.core.agent

import android.graphics.BitmapFactory
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.todd.core.ai.AIRequest
import com.todd.core.ai.AIResponse
import com.todd.core.ai.AIRouter
import com.todd.core.ai.AISource
import com.todd.core.ai.DirectGeminiInteractionClient
import com.todd.core.ai.DirectToolCall
import com.todd.core.ai.DirectToolDefinition
import com.todd.core.ai.FirebaseRuntimeConfig
import com.todd.core.ai.ProviderType
import com.todd.core.model.AIProviderMode
import com.todd.core.remote.RemoteExecutor
import com.todd.core.rules.ActionCategory
import com.todd.core.rules.ActionRequest
import com.todd.core.rules.RulesEngine
import com.todd.core.state.ToddStateMachine
import com.todd.core.scheduler.ToddTaskScheduler
import com.todd.core.model.ScheduledTask
import com.todd.core.model.Task
import com.todd.core.model.TaskStatus
import com.todd.core.tools.GitHubTool
import com.todd.data.repository.ToddRepository
import com.todd.service.context.DeviceContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool-capable text agent for the main Todd chat.
 *
 * Unlike a plain text completion, this agent can decide to read GitHub state, inspect the
 * current phone context, start autonomous coding, and reconnect/cancel remote verification
 * jobs. Tool results are sent back to Gemini before Todd produces the user-visible answer.
 */
class ToddTextAgent(
    private val aiRouter: AIRouter,
    private val githubTool: GitHubTool,
    private val remoteExecutor: RemoteExecutor,
    private val autonomousTaskCoordinator: AutonomousTaskCoordinator,
    private val repository: ToddRepository,
    private val taskScheduler: ToddTaskScheduler,
    private val stateMachine: ToddStateMachine,
    private val rulesEngine: RulesEngine,
    private val directInteractionClient: DirectGeminiInteractionClient? = null,
    private val modelName: String = "gemini-3.8-flash"
) {

    suspend fun respond(
        request: AIRequest,
        mode: AIProviderMode
    ): Result<AIResponse> = withContext(Dispatchers.IO) {
        if (mode == AIProviderMode.LOCAL_ONLY) {
            return@withContext aiRouter.route(request, mode)
        }

        val prompt = buildPrompt(request)
        val directClient = directInteractionClient
        if (directClient != null && directClient.isAvailable()) {
            return@withContext directClient.respond(
                request = request,
                prompt = prompt,
                tools = directToolDefinitions(),
                executeTool = { call -> executeDirectTool(call) },
                maxToolRounds = MAX_TOOL_ROUNDS
            )
        }

        if (!FirebaseRuntimeConfig.current().configured) {
            return@withContext aiRouter.route(request, mode)
        }

        val startedAt = System.currentTimeMillis()

        try {
            val model = Firebase.ai(
                app = FirebaseRuntimeConfig.requireConfiguredApp(),
                backend = GenerativeBackend.googleAI()
            ).generativeModel(
                modelName = modelName,
                tools = listOf(
                    Tool.googleSearch(),
                    Tool.functionDeclarations(toolDeclarations())
                )
            )
            val chat = model.startChat()

            var response = sendInitialMessage(chat, prompt, request.screenImagePath)

            repeat(MAX_TOOL_ROUNDS) {
                val calls = response.functionCalls
                if (calls.isEmpty()) {
                    val text = response.text
                        ?: return@withContext Result.failure(
                            IllegalStateException("Todd received an empty model response.")
                        )

                    val sources = response.candidates
                        .firstOrNull()
                        ?.groundingMetadata
                        ?.groundingChunks
                        ?.mapNotNull { chunk ->
                            val web = chunk.web ?: return@mapNotNull null
                            val uri = web.uri?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            AISource(
                                title = web.title?.takeIf { it.isNotBlank() },
                                url = uri,
                                domain = web.domain?.takeIf { it.isNotBlank() }
                            )
                        }
                        ?.distinctBy { it.url }
                        .orEmpty()

                    return@withContext Result.success(
                        AIResponse(
                            text = text,
                            providerUsed = ProviderType.CLOUD_GEMINI,
                            isVerified = false,
                            tokensUsed = (prompt.length + text.length) / 4,
                            latencyMs = System.currentTimeMillis() - startedAt,
                            sources = sources
                        )
                    )
                }

                val functionResponses = calls.map { call ->
                    executeTool(call)
                }

                response = chat.sendMessage(
                    content("function") {
                        functionResponses.forEach { part(it) }
                    }
                )
            }

            Result.failure(
                IllegalStateException(
                    "Todd exceeded the maximum number of tool-call rounds without a final answer."
                )
            )
        } catch (e: Exception) {
            val fallback = aiRouter.route(request, mode)
            if (fallback.isSuccess) fallback else Result.failure(e)
        }
    }

    private suspend fun sendInitialMessage(
        chat: com.google.firebase.ai.Chat,
        prompt: String,
        imagePath: String?
    ): com.google.firebase.ai.type.GenerateContentResponse {
        if (imagePath.isNullOrBlank()) return chat.sendMessage(prompt)

        val file = File(imagePath)
        if (!file.exists()) return chat.sendMessage(prompt)

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return chat.sendMessage(prompt)

        return try {
            chat.sendMessage(
                content("user") {
                    image(bitmap)
                    text(prompt)
                }
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildPrompt(request: AIRequest): String = buildString {
        appendLine(
            "You are Todd, a persistent Android personal agent. " +
                "Use tools when they provide real evidence or when the user's request asks for an action. " +
                "Never say an external action succeeded unless a tool result proves it."
        )
        request.systemPrompt?.let { appendLine("System: $it") }
        request.projectContext?.let {
            appendLine()
            appendLine("Project context:")
            appendLine(it)
        }
        request.screenContext?.let {
            appendLine()
            appendLine("Current device context:")
            appendLine(it)
        }
        request.selectedText?.let {
            appendLine()
            appendLine("Selected text:")
            appendLine(it)
        }
        appendLine()
        appendLine("User request:")
        append(request.prompt)
    }

    private fun toolDeclarations(): List<FunctionDeclaration> = listOf(
        FunctionDeclaration(
            "getCurrentDeviceContext",
            "Read Todd's current merged phone context: accessibility screen data, local OCR, visual capture metadata, and recent notifications.",
            emptyMap()
        ),
        FunctionDeclaration(
            "checkRepositoryStatus",
            "Read the live branch and latest commit for a GitHub repository.",
            mapOf(
                "repository" to Schema.string("Repository in owner/name form."),
                "branch" to Schema.string("Branch name, normally main.")
            )
        ),
        FunctionDeclaration(
            "checkLatestWorkflow",
            "Read the latest GitHub Actions workflow run and artifact for a repository branch.",
            mapOf(
                "repository" to Schema.string("Repository in owner/name form."),
                "branch" to Schema.string("Branch name, normally main.")
            )
        ),
        FunctionDeclaration(
            "startAutonomousCoding",
            "Start Todd's persistent autonomous coding loop for the current project. Use this when the user asks Todd to implement, fix, change, or build code.",
            mapOf(
                "objective" to Schema.string("Exact coding objective from the user.")
            )
        ),
        FunctionDeclaration(
            "scheduleTask",
            "Schedule a Todd task to run later. repeatMinutes is optional; recurring schedules must be at least 15 minutes apart.",
            mapOf(
                "objective" to Schema.string("What Todd should do at the scheduled time."),
                "delayMinutes" to Schema.string("Minutes from now before the first run."),
                "repeatMinutes" to Schema.string("Optional recurrence in minutes; blank for one-time.")
            )
        ),
        FunctionDeclaration(
            "checkTaskStatus",
            "Read the durable Todd task state, current step, failure, and verification evidence for a task ID.",
            mapOf(
                "taskId" to Schema.string("Todd task ID returned when a task was started.")
            )
        ),
        FunctionDeclaration(
            "checkLatestTask",
            "Read the newest durable task for the current or specified Todd project. Use this when the user asks where Todd reached or what it is doing.",
            mapOf(
                "projectId" to Schema.string("Todd project ID. Leave blank to use the active project.")
            )
        ),
        FunctionDeclaration(
            "checkRemoteJob",
            "Reconnect to a Todd remote verification job and return its live status and evidence.",
            mapOf(
                "jobId" to Schema.string("Todd remote job ID.")
            )
        ),
        FunctionDeclaration(
            "cancelRemoteJob",
            "Cancel a Todd remote verification job when the user asked to cancel it.",
            mapOf(
                "jobId" to Schema.string("Todd remote job ID.")
            )
        )
    )

    private fun directToolDefinitions(): List<DirectToolDefinition> = listOf(
        directTool(
            name = "getCurrentDeviceContext",
            description = "Read Todd's current merged phone context: accessibility screen data, local OCR, visual capture metadata, and recent notifications."
        ),
        directTool(
            name = "checkRepositoryStatus",
            description = "Read the live branch and latest commit for a GitHub repository.",
            params = mapOf(
                "repository" to "Repository in owner/name form.",
                "branch" to "Branch name, normally main."
            )
        ),
        directTool(
            name = "checkLatestWorkflow",
            description = "Read the latest GitHub Actions workflow run and artifact for a repository branch.",
            params = mapOf(
                "repository" to "Repository in owner/name form.",
                "branch" to "Branch name, normally main."
            )
        ),
        directTool(
            name = "startAutonomousCoding",
            description = "Start Todd's persistent autonomous coding loop for the current project. Use this when the owner asks Todd to implement, fix, change, or build code.",
            params = mapOf(
                "objective" to "Exact coding objective from the owner."
            ),
            required = listOf("objective")
        ),
        directTool(
            name = "scheduleTask",
            description = "Schedule a Todd task to run later. repeatMinutes is optional; recurring schedules must be at least 15 minutes apart.",
            params = mapOf(
                "objective" to "What Todd should do at the scheduled time.",
                "delayMinutes" to "Minutes from now before the first run.",
                "repeatMinutes" to "Optional recurrence in minutes; omit for one-time."
            ),
            required = listOf("objective", "delayMinutes")
        ),
        directTool(
            name = "checkTaskStatus",
            description = "Read the durable Todd task state, current step, failure, and verification evidence for a task ID.",
            params = mapOf(
                "taskId" to "Todd task ID returned when a task was started."
            ),
            required = listOf("taskId")
        ),
        directTool(
            name = "checkLatestTask",
            description = "Read the newest durable task for the current or specified Todd project. Use this when the owner asks where Todd reached or what it is doing.",
            params = mapOf(
                "projectId" to "Todd project ID. Omit to use the active project."
            )
        ),
        directTool(
            name = "checkRemoteJob",
            description = "Reconnect to a Todd remote verification job and return its live status and evidence.",
            params = mapOf(
                "jobId" to "Todd remote job ID."
            ),
            required = listOf("jobId")
        ),
        directTool(
            name = "cancelRemoteJob",
            description = "Cancel a Todd remote verification job when the owner explicitly asked to cancel it.",
            params = mapOf(
                "jobId" to "Todd remote job ID."
            ),
            required = listOf("jobId")
        )
    )

    private fun directTool(
        name: String,
        description: String,
        params: Map<String, String> = emptyMap(),
        required: List<String> = emptyList()
    ): DirectToolDefinition {
        val properties = JSONObject()
        params.forEach { (paramName, paramDescription) ->
            properties.put(
                paramName,
                JSONObject()
                    .put("type", "string")
                    .put("description", paramDescription)
            )
        }

        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            parameters.put("required", JSONArray(required))
        }

        return DirectToolDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    }

    private suspend fun executeTool(call: FunctionCallPart): FunctionResponsePart {
        val response = executeToolJson(call.name) { name ->
            call.args[name]?.jsonPrimitive?.content?.trim().orEmpty()
        }
        return FunctionResponsePart(call.name, response, call.id)
    }

    private suspend fun executeDirectTool(call: DirectToolCall): JSONObject {
        val response = executeToolJson(call.name) { name ->
            call.arguments.optString(name, "").trim()
        }
        return JSONObject(response.toString())
    }

    private suspend fun executeToolJson(
        name: String,
        argValue: (String) -> String
    ): JsonObject {
        return when (name) {
            "getCurrentDeviceContext" -> buildJsonObject {
                put("ok", true)
                put("context", DeviceContextProvider.currentTextContext())
            }

            "checkRepositoryStatus" -> {
                val repo = argValue("repository").ifBlank { "fateh1989/Todd" }
                val branch = argValue("branch").ifBlank { "main" }

                githubTool.getRepositoryInfo(repo, branch).fold(
                    onSuccess = { info ->
                        buildJsonObject {
                            put("ok", true)
                            put("repository", info.fullName)
                            put("branch", info.activeBranch)
                            put("defaultBranch", info.defaultBranch)
                            put("latestCommit", info.latestCommitSha)
                        }
                    },
                    onFailure = { error -> errorJson(error) }
                )
            }

            "checkLatestWorkflow" -> {
                val repo = argValue("repository").ifBlank { "fateh1989/Todd" }
                val branch = argValue("branch").ifBlank { "main" }

                githubTool.getLatestWorkflowRun(repo, branch).fold(
                    onSuccess = { run ->
                        buildJsonObject {
                            put("ok", true)
                            put("found", run != null)
                            if (run != null) {
                                put("runId", run.runId)
                                put("workflow", run.workflowName)
                                put("headSha", run.headSha)
                                put("status", run.status)
                                put("conclusion", run.conclusion ?: "")
                                put("artifact", run.artifactName ?: "")
                            }
                        }
                    },
                    onFailure = { error -> errorJson(error) }
                )
            }

            "startAutonomousCoding" -> {
                val objective = argValue("objective")
                if (objective.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "Coding objective is required.")
                    }
                } else {
                    val evaluation = rulesEngine.evaluate(
                        ActionRequest(
                            category = ActionCategory.RUN_SHELL_COMMAND,
                            projectId = stateMachine.state.value.activeProjectId ?: "todd-main",
                            target = "autonomous-coding",
                            dataSummary = objective,
                            isPreApprovedInInstruction = true
                        )
                    )

                    if (!evaluation.isAllowed) {
                        buildJsonObject {
                            put("ok", false)
                            put("error", evaluation.promptMessage ?: "Action was not approved.")
                        }
                    } else {
                        val projectId =
                            stateMachine.state.value.activeProjectId ?: "todd-main"
                        val taskId = autonomousTaskCoordinator.start(
                            projectId = projectId,
                            title = objective.take(120),
                            goal = objective,
                            maxIterations = 4
                        )
                        buildJsonObject {
                            put("ok", true)
                            put("taskId", taskId)
                            put("projectId", projectId)
                            put("status", "STARTED")
                        }
                    }
                }
            }

            "scheduleTask" -> {
                val objective = argValue("objective")
                val delay = argValue("delayMinutes").toLongOrNull()
                val repeat = argValue("repeatMinutes").toLongOrNull()
                val projectId =
                    stateMachine.state.value.activeProjectId ?: "todd-main"

                when {
                    objective.isBlank() -> buildJsonObject {
                        put("ok", false)
                        put("error", "Scheduled objective is required.")
                    }

                    delay == null || delay < 0L -> buildJsonObject {
                        put("ok", false)
                        put("error", "delayMinutes must be zero or greater.")
                    }

                    repeat != null && repeat < ToddTaskScheduler.MIN_PERIODIC_MINUTES ->
                        buildJsonObject {
                            put("ok", false)
                            put(
                                "error",
                                "Recurring Todd schedules require at least ${ToddTaskScheduler.MIN_PERIODIC_MINUTES} minutes."
                            )
                        }

                    else -> {
                        val now = System.currentTimeMillis()
                        val taskId = "scheduled-task-${System.nanoTime()}"
                        val scheduleId = "schedule-${System.nanoTime()}"
                        val firstRun = now + java.util.concurrent.TimeUnit.MINUTES.toMillis(delay)

                        val task = Task(
                            id = taskId,
                            projectId = projectId,
                            title = objective.take(120),
                            goal = objective,
                            userInstructions = objective,
                            status = TaskStatus.WAITING,
                            currentStep = if (repeat == null) {
                                "مجدولة للتنفيذ لاحقاً."
                            } else {
                                "مجدولة للتكرار كل $repeat دقيقة."
                            },
                            completionCriteria = "Scheduled Todd execution produces a saved result.",
                            isRecurring = repeat != null,
                            scheduledTime = firstRun
                        )
                        repository.saveTask(task)

                        taskScheduler.schedule(
                            ScheduledTask(
                                id = scheduleId,
                                taskId = taskId,
                                projectId = projectId,
                                prompt = objective,
                                firstRunAt = firstRun,
                                intervalMinutes = repeat,
                                nextRunAt = firstRun
                            )
                        )

                        buildJsonObject {
                            put("ok", true)
                            put("taskId", taskId)
                            put("scheduleId", scheduleId)
                            put("firstRunAt", firstRun)
                            put("repeatMinutes", repeat ?: 0L)
                        }
                    }
                }
            }

            "checkTaskStatus" -> {
                val taskId = argValue("taskId")
                if (taskId.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "taskId is required.")
                    }
                } else {
                    val task = repository.getTaskById(taskId)
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
                val requestedProjectId = argValue("projectId")
                val projectId = requestedProjectId.ifBlank {
                    stateMachine.state.value.activeProjectId ?: "todd-main"
                }
                val task = repository.getLatestTaskForProject(projectId)
                if (task == null) {
                    buildJsonObject {
                        put("ok", true)
                        put("found", false)
                        put("projectId", projectId)
                    }
                } else {
                    taskJson(task)
                }
            }

            "checkRemoteJob" -> {
                val jobId = argValue("jobId")
                if (jobId.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "jobId is required.")
                    }
                } else {
                    remoteExecutor.reconnect(jobId).fold(
                        onSuccess = { state ->
                            buildJsonObject {
                                put("ok", true)
                                put("jobId", state.jobId)
                                put("status", state.status.name)
                                put("step", state.currentStepDescription)
                                put("runId", state.providerRunId ?: 0L)
                                put("runUrl", state.providerRunUrl ?: "")
                                put("artifacts", state.artifactNames.joinToString("\n"))
                                put("failure", state.failureMessage ?: "")
                            }
                        },
                        onFailure = { error -> errorJson(error) }
                    )
                }
            }

            "cancelRemoteJob" -> {
                val jobId = argValue("jobId")
                if (jobId.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "jobId is required.")
                    }
                } else {
                    remoteExecutor.requestCancel(jobId).fold(
                        onSuccess = { accepted ->
                            buildJsonObject {
                                put("ok", accepted)
                                put("jobId", jobId)
                                put("cancelRequested", accepted)
                            }
                        },
                        onFailure = { error -> errorJson(error) }
                    )
                }
            }

            else -> buildJsonObject {
                put("ok", false)
                put("error", "Unknown Todd tool: $name")
            }
        }
    }

    private fun taskJson(task: com.todd.core.model.Task): JsonObject = buildJsonObject {
        put("ok", true)
        put("found", true)
        put("taskId", task.id)
        put("projectId", task.projectId)
        put("title", task.title)
        put("goal", task.goal)
        put("status", task.status.name)
        put("currentStep", task.currentStep)
        put("completionCriteria", task.completionCriteria)
        put("evidence", task.lastEvidence ?: "")
        put("failure", task.failureCause ?: "")
        put("updatedAt", task.updatedAt)
    }

    private fun errorJson(error: Throwable): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", error.message ?: error::class.java.simpleName)
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6
    }
}
