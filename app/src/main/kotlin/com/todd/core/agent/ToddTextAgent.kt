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
import com.todd.core.ai.WebResearchPolicy
import com.todd.core.ai.WebResearchUnavailableException
import com.todd.core.ai.WebSourceFetcher
import com.todd.core.ai.DirectGeminiInteractionClient
import com.todd.core.ai.GeminiInteractionApiException
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
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
    private val promptLibrary: ToddPromptLibrary? = null,
    private val modelName: String = "gemini-3.8-flash",
    val webSourceFetcher: WebSourceFetcher = WebSourceFetcher()
) {

    suspend fun respond(
        request: AIRequest,
        mode: AIProviderMode
    ): Result<AIResponse> = withContext(Dispatchers.IO) {
        // A simple greeting is a new conversational turn, not an invitation to
        // resume a previous coding task or disclose persistent device context.
        // Keep all stored memories intact for requests that actually need them.
        if (ChatGreetingPolicy.isStandaloneGreeting(request)) {
            val greetingRequest = request.copy(
                selectedText = null,
                screenContext = null,
                screenImagePath = null,
                projectContext = null
            )
            return@withContext aiRouter.route(greetingRequest, mode)
        }

        val researchRequested = WebResearchPolicy.isRequested(request)
        // A web query should not inherit phone-screen or project data (which may reveal
        // location or identity) unless the user includes it in the query itself.
        val researchAwareRequest = request.copy(
            webResearch = researchRequested,
            screenContext = if (researchRequested) null else request.screenContext,
            screenImagePath = if (researchRequested) null else request.screenImagePath,
            projectContext = if (researchRequested) null else request.projectContext
        )
        val guidedRequest = promptLibrary?.enrich(researchAwareRequest) ?: researchAwareRequest
        if (mode == AIProviderMode.LOCAL_ONLY) {
            if (researchRequested) {
                return@withContext Result.failure(
                    WebResearchUnavailableException(WebResearchPolicy.localOnlyMessage())
                )
            }
            return@withContext aiRouter.route(guidedRequest, mode)
        }

        val prompt = buildPrompt(guidedRequest)
        val directClient = directInteractionClient
        if (directClient != null && directClient.isAvailable()) {
            val directResult = directClient.respond(
                request = guidedRequest,
                prompt = prompt,
                tools = directToolDefinitions(),
                executeTool = { call -> executeDirectTool(call) },
                maxToolRounds = MAX_TOOL_ROUNDS
            )
            if (directResult.isSuccess) {
                val result = directResult.getOrThrow()
                if (!researchRequested) return@withContext Result.success(result)
                if (result.sources.isNotEmpty()) {
                    return@withContext Result.success(
                        reviewFetchedEvidence(
                            question = guidedRequest.prompt,
                            response = result,
                            directClient = directClient
                        )
                    )
                }
                // A successful model response without citations is not a successful live search.
            }
            val apiFailure = directResult.exceptionOrNull() as? GeminiInteractionApiException
            if (apiFailure != null && apiFailure.statusCode in setOf(401, 403, 429)) {
                // Avoid repeating requests that cannot work with this key or exhausted quota.
                return@withContext directResult
            }
            // The richer Interactions/tooling path is optional. If that endpoint,
            // model, grounding, or tool schema is unavailable, normal Todd chat
            // must still work through the already verified generateContent provider.
        }

        if (researchRequested) {
            // Direct generateContent supports Google Search grounding with the same
            // saved API key even when Firebase is not configured on the phone.
            // Never return an ungrounded model answer as verified web research.
            val groundedResult = aiRouter.cloudProvider.generateText(guidedRequest)
            val grounded = groundedResult.getOrNull()
            if (grounded != null && grounded.sources.isNotEmpty()) {
                return@withContext Result.success(
                    reviewFetchedEvidence(
                        question = guidedRequest.prompt,
                        response = grounded,
                        directClient = directClient
                    )
                )
            }

            // Automatic query reformulation: if the initial complex prompt yielded 0 citations,
            // retry once with core search keywords before declaring research unavailable.
            val simplifiedQuery = WebResearchPolicy.simplifyQueryForSearch(guidedRequest.prompt)
            if (simplifiedQuery != guidedRequest.prompt.trim()) {
                val retryRequest = guidedRequest.copy(prompt = simplifiedQuery)
                val retryResult = aiRouter.cloudProvider.generateText(retryRequest)
                val retryGrounded = retryResult.getOrNull()
                if (retryGrounded != null && retryGrounded.sources.isNotEmpty()) {
                    return@withContext Result.success(
                        reviewFetchedEvidence(
                            question = guidedRequest.prompt,
                            response = retryGrounded,
                            directClient = directClient
                        )
                    )
                }
            }

            if (!FirebaseRuntimeConfig.current().configured) {
                val failureDetails = groundedResult.exceptionOrNull()?.message
                return@withContext Result.failure(
                    WebResearchUnavailableException(
                        WebResearchPolicy.noSourcesMessage() +
                            if (failureDetails.isNullOrBlank()) "" else " ($failureDetails)"
                    )
                )
            }
        }

        if (!FirebaseRuntimeConfig.current().configured) {
            return@withContext aiRouter.route(guidedRequest, mode)
        }

        val startedAt = System.currentTimeMillis()

        try {
            val model = Firebase.ai(
                app = FirebaseRuntimeConfig.requireConfiguredApp(),
                backend = GenerativeBackend.googleAI()
            ).generativeModel(
                modelName = modelName,
                tools = buildList {
                    if (researchRequested) add(Tool.googleSearch())
                    add(Tool.functionDeclarations(toolDeclarations()))
                }
            )
            val chat = model.startChat()

            var response = sendInitialMessage(chat, prompt, guidedRequest.screenImagePath)

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

                    if (researchRequested && sources.isEmpty()) {
                        return@withContext Result.failure(
                            WebResearchUnavailableException(WebResearchPolicy.noSourcesMessage())
                        )
                    }

                    val reviewed = if (researchRequested) {
                        reviewFetchedEvidence(
                            question = guidedRequest.prompt,
                            response = AIResponse(
                                text = text,
                                providerUsed = ProviderType.CLOUD_GEMINI,
                                isVerified = false,
                                tokensUsed = (prompt.length + text.length) / 4,
                                latencyMs = System.currentTimeMillis() - startedAt,
                                sources = sources
                            ),
                            firebaseReviewEnabled = true
                        )
                    } else null

                    return@withContext Result.success(
                        reviewed ?: AIResponse(
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
            if (researchRequested) {
                return@withContext Result.failure(
                    WebResearchUnavailableException(
                        "${WebResearchPolicy.noSourcesMessage()} (${e.message ?: "خطأ غير معروف"})"
                    )
                )
            }
            val fallback = aiRouter.route(guidedRequest, mode)
            if (fallback.isSuccess) fallback else Result.failure(e)
        }
    }

    private suspend fun reviewFetchedEvidence(
        question: String,
        response: AIResponse,
        directClient: DirectGeminiInteractionClient? = null,
        firebaseReviewEnabled: Boolean = false
    ): AIResponse {
        val enrichedSources = try {
            webSourceFetcher.fetch(response.sources)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            response.sources
        }
        if (enrichedSources.none { !it.excerpt.isNullOrBlank() }) {
            return response.copy(sources = enrichedSources)
        }

        val reviewedText = try {
            when {
                directClient != null -> directClient.reviewResearchEvidence(
                    question = question,
                    draft = response.text,
                    sources = enrichedSources
                )
                firebaseReviewEnabled && FirebaseRuntimeConfig.current().configured -> {
                    Firebase.ai(
                        app = FirebaseRuntimeConfig.requireConfiguredApp(),
                        backend = GenerativeBackend.googleAI()
                    ).generativeModel(modelName = modelName)
                        .generateContent(
                            WebResearchPolicy.evidenceReviewPrompt(
                                question, response.text, enrichedSources
                            )
                        ).text
                }
                else -> null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }?.trim()?.takeIf { it.isNotBlank() }

        return response.copy(
            text = reviewedText ?: response.text,
            sources = enrichedSources,
            isVerified = false
        )
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
        if (request.webResearch) {
            appendLine()
            appendLine(WebResearchPolicy.instructions())
        }
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
            "listRepositoryFiles",
            "List repository file paths before deciding what to inspect.",
            mapOf(
                "repository" to Schema.string("Repository in owner/name form."),
                "branch" to Schema.string("Branch name, normally main.")
            )
        ),
        FunctionDeclaration(
            "readRepositoryFile",
            "Read the current text content of one repository file.",
            mapOf(
                "repository" to Schema.string("Repository in owner/name form."),
                "path" to Schema.string("Repository-relative file path."),
                "ref" to Schema.string("Branch or commit SHA; normally main.")
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
        ),
        FunctionDeclaration(
            "searchWeb",
            "Search the web for up-to-date facts, official documentation, prices, or live status across multiple sources.",
            mapOf(
                "query" to Schema.string("The search query or keywords.")
            )
        ),
        FunctionDeclaration(
            "readWebPage",
            "Fetch and extract readable content, article text, and links from a specific URL.",
            mapOf(
                "url" to Schema.string("The exact web URL to read.")
            )
        ),
        FunctionDeclaration(
            "compareWebSources",
            "Fetch and compare information on a topic from up to 5 independent URLs.",
            mapOf(
                "urls" to Schema.string("Comma or newline separated list of URLs to compare."),
                "topic" to Schema.string("The topic or question being investigated.")
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
            name = "listRepositoryFiles",
            description = "List repository file paths before deciding what to inspect.",
            params = mapOf(
                "repository" to "Repository in owner/name form.",
                "branch" to "Branch name, normally main."
            )
        ),
        directTool(
            name = "readRepositoryFile",
            description = "Read the current text content of one repository file.",
            params = mapOf(
                "repository" to "Repository in owner/name form.",
                "path" to "Repository-relative file path.",
                "ref" to "Branch or commit SHA; normally main."
            ),
            required = listOf("path")
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
        ),
        directTool(
            name = "searchWeb",
            description = "Search the web for up-to-date facts, official documentation, prices, or live status across multiple sources.",
            params = mapOf("query" to "The search query or keywords."),
            required = listOf("query")
        ),
        directTool(
            name = "readWebPage",
            description = "Fetch and extract readable content, article text, and links from a specific URL.",
            params = mapOf("url" to "The exact web URL to read."),
            required = listOf("url")
        ),
        directTool(
            name = "compareWebSources",
            description = "Fetch and compare information on a topic from up to 5 independent URLs.",
            params = mapOf(
                "urls" to "Comma or newline separated list of URLs to compare.",
                "topic" to "The topic or question being investigated."
            ),
            required = listOf("urls")
        ),
        directTool(
            name = "cancelWebResearch",
            description = "Immediately abort ongoing web browsing, destroy active Chromium WebView sessions, and release allocated memory.",
            params = mapOf(
                "reason" to "Reason for stopping research or clearing resources."
            )
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

            "listRepositoryFiles" -> {
                val repo = argValue("repository").ifBlank { "fateh1989/Todd" }
                val branch = argValue("branch").ifBlank { "main" }
                githubTool.listRepositoryFiles(repo, branch).fold(
                    onSuccess = { files ->
                        buildJsonObject {
                            put("ok", true)
                            put("count", files.size)
                            put("files", files.joinToString("\n"))
                        }
                    },
                    onFailure = { error -> errorJson(error) }
                )
            }

            "readRepositoryFile" -> {
                val repo = argValue("repository").ifBlank { "fateh1989/Todd" }
                val path = argValue("path")
                val ref = argValue("ref").ifBlank { argValue("branch").ifBlank { "main" } }
                if (path.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "File path is required.")
                    }
                } else {
                    githubTool.readFile(repo, path, ref).fold(
                        onSuccess = { file ->
                            buildJsonObject {
                                put("ok", true)
                                put("path", file.path)
                                put("sha", file.sha)
                                put("content", file.content)
                            }
                        },
                        onFailure = { error -> errorJson(error) }
                    )
                }
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

            "searchWeb" -> {
                val query = argValue("query")
                if (query.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "Search query is required.")
                    }
                } else {
                    val searchRequest = AIRequest(
                        prompt = query,
                        webResearch = true,
                        maxTokens = 512
                    )
                    val searchResult = aiRouter.cloudProvider.generateText(searchRequest)
                    searchResult.fold(
                        onSuccess = { response ->
                            val enriched = webSourceFetcher.fetch(response.sources, maxSources = 5)
                            buildJsonObject {
                                put("ok", true)
                                put("query", query)
                                put("sourcesFound", enriched.size)
                                val sourcesArray = buildJsonArray {
                                    enriched.forEach { s ->
                                        add(buildJsonObject {
                                            put("title", s.title ?: "")
                                            put("url", s.url)
                                            put("domain", s.domain ?: "")
                                            put("status", s.fetchStatus ?: "200_OK")
                                            put("summary", s.excerpt?.take(1500) ?: "")
                                        })
                                    }
                                }
                                put("sources", sourcesArray)
                                put("preliminarySummary", response.text.take(1500))
                            }
                        },
                        onFailure = { err ->
                            buildJsonObject {
                                put("ok", false)
                                put("query", query)
                                put("error", err.message ?: "Search failed")
                            }
                        }
                    )
                }
            }

            "readWebPage" -> {
                val url = argValue("url")
                if (url.isBlank()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "URL is required.")
                    }
                } else {
                    val source = webSourceFetcher.fetchUrl(url)
                    buildJsonObject {
                        put("ok", source.excerpt != null)
                        put("url", source.url)
                        put("domain", source.domain ?: "")
                        put("title", source.title ?: "")
                        put("fetchStatus", source.fetchStatus ?: "UNKNOWN")
                        put("content", source.excerpt ?: "")
                        val outlinksArray = buildJsonArray {
                            source.outlinks.forEach { add(JsonPrimitive(it)) }
                        }
                        put("discoveredLinks", outlinksArray)
                    }
                }
            }

            "compareWebSources" -> {
                val rawUrls = argValue("urls")
                val topic = argValue("topic")
                val urls = rawUrls.split(",", "\n", " ")
                    .map { it.trim() }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                if (urls.isEmpty()) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "At least one valid HTTP/HTTPS URL is required.")
                    }
                } else {
                    val sources = webSourceFetcher.fetch(urls.map { AISource(url = it) }, maxSources = 5)
                    buildJsonObject {
                        put("ok", true)
                        put("topic", topic)
                        put("count", sources.size)
                        val array = buildJsonArray {
                            sources.forEachIndexed { idx, s ->
                                add(buildJsonObject {
                                    put("index", idx + 1)
                                    put("url", s.url)
                                    put("domain", s.domain ?: "")
                                    put("title", s.title ?: "")
                                    put("status", s.fetchStatus ?: "UNKNOWN")
                                    put("excerpt", s.excerpt?.take(2500) ?: "")
                                })
                            }
                        }
                        put("sources", array)
                    }
                }
            }

            "cancelWebResearch" -> {
                val reason = argValue("reason").ifBlank { "User requested cancellation" }
                webSourceFetcher.releaseResources()
                buildJsonObject {
                    put("ok", true)
                    put("status", "CANCELLED_AND_CLEANED")
                    put("reason", reason)
                    put("message", "Web browsing sessions destroyed, cache cleared, and memory released.")
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
