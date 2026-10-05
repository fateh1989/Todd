package com.todd.core.agent

import com.todd.core.ai.AIProvider
import com.todd.core.ai.AIRequest
import com.todd.core.remote.RemoteExecutionMode
import com.todd.core.remote.RemoteExecutor
import com.todd.core.remote.RemoteJobRequest
import com.todd.core.remote.RemoteJobState
import com.todd.core.remote.RemoteJobStatus
import com.todd.core.tools.GitHubFileContent
import com.todd.core.tools.GitHubTool
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AutonomousCodingRequest(
    val taskId: String,
    val projectId: String,
    val repository: String,
    val branch: String,
    val objective: String,
    val completionCriteria: String,
    val maxIterations: Int = 4
)

data class CodingFileChange(
    val path: String,
    val content: String
)

data class CodingPatch(
    val commitMessage: String,
    val changes: List<CodingFileChange>
)

data class AutonomousCodingResult(
    val success: Boolean,
    val iterations: Int,
    val commitShas: List<String>,
    val finalCommitSha: String?,
    val evidenceUrl: String? = null,
    val artifactNames: List<String> = emptyList(),
    val failureMessage: String? = null
)

/**
 * Model-driven coding loop with durable checkpoints.
 *
 * A commit is never treated as verified until the RemoteExecutor reports COMPLETED.
 * The checkpoint is persisted before commit and again immediately after commit, so a
 * process restart can reconnect to the same remote verification job instead of starting over.
 */
class AutonomousCodingLoop(
    private val aiProvider: AIProvider,
    private val githubTool: GitHubTool,
    private val remoteExecutor: RemoteExecutor,
    private val checkpointStore: AutonomousCheckpointStore = InMemoryAutonomousCheckpointStore(),
    private val pollIntervalMs: Long = 7_500L,
    private val maxPollsPerIteration: Int = 960,
    private val onProgress: suspend (taskId: String, message: String) -> Unit = { _, _ -> }
) {

    suspend fun run(request: AutonomousCodingRequest): Result<AutonomousCodingResult> = runCatching {
        validateRequest(request)
        onProgress(request.taskId, "بدء المهمة البرمجية والتحقق من مزود الذكاء.")

        if (!aiProvider.isAvailable()) {
            throw IllegalStateException("The coding AI provider is not available.")
        }

        onProgress(request.taskId, "قراءة حالة المستودع ${request.repository} على الفرع ${request.branch}.")
        var repositoryInfo = githubTool
            .getRepositoryInfo(request.repository, request.branch)
            .getOrThrow()
        onProgress(request.taskId, "رأس الفرع الحالي: ${repositoryInfo.latestCommitSha.take(12)}")

        var checkpoint = checkpointStore.load(request.taskId)
        if (checkpoint != null && !checkpointMatches(checkpoint, request)) {
            checkpointStore.delete(request.taskId)
            checkpoint = null
        }

        val commits = checkpoint?.commitShas?.toMutableList() ?: mutableListOf()
        var failureContext = checkpoint?.failureContext.orEmpty()
        var headSha = repositoryInfo.latestCommitSha
        var firstIteration = checkpoint?.iteration ?: 1

        if (checkpoint != null) {
            when (checkpoint.phase) {
                AutonomousCheckpointPhase.VERIFYING -> {
                    val committedSha = checkpoint.committedSha
                        ?: throw IllegalStateException(
                            "Checkpoint is VERIFYING but has no committed SHA."
                        )
                    val verifyJobId = checkpoint.verifyJobId
                        ?: throw IllegalStateException(
                            "Checkpoint is VERIFYING but has no remote verification job ID."
                        )

                    onProgress(request.taskId, "استئناف التحقق البعيد للالتزام ${committedSha.take(12)}.")
                    remoteExecutor.startJob(
                        verificationRequest(
                            request = request,
                            iteration = checkpoint.iteration,
                            commitSha = committedSha,
                            verifyJobId = verifyJobId
                        )
                    ).getOrThrow()

                    onProgress(request.taskId, "انتظار نتيجة GitHub Actions للمهمة $verifyJobId.")
                    val remoteState = waitForTerminalState(verifyJobId)
                    onProgress(request.taskId, "انتهى التحقق البعيد بالحالة: ${remoteState.status}.")
                    when (remoteState.status) {
                        RemoteJobStatus.COMPLETED -> {
                            checkpointStore.delete(request.taskId)
                            return@runCatching AutonomousCodingResult(
                                success = true,
                                iterations = checkpoint.iteration,
                                commitShas = commits.toList(),
                                finalCommitSha = committedSha,
                                evidenceUrl = remoteState.providerRunUrl,
                                artifactNames = remoteState.artifactNames
                            )
                        }

                        RemoteJobStatus.CANCELLED -> {
                            checkpointStore.delete(request.taskId)
                            return@runCatching AutonomousCodingResult(
                                success = false,
                                iterations = checkpoint.iteration,
                                commitShas = commits.toList(),
                                finalCommitSha = committedSha,
                                evidenceUrl = remoteState.providerRunUrl,
                                artifactNames = remoteState.artifactNames,
                                failureMessage = "Remote verification was cancelled."
                            )
                        }

                        else -> {
                            failureContext = buildFailureContext(
                                iteration = checkpoint.iteration,
                                commitSha = committedSha,
                                changedPaths = checkpoint.changedPaths,
                                remoteState = remoteState
                            )
                            repositoryInfo = githubTool
                                .getRepositoryInfo(request.repository, request.branch)
                                .getOrThrow()
                            headSha = repositoryInfo.latestCommitSha
                            firstIteration = checkpoint.iteration + 1
                        }
                    }
                }

                AutonomousCheckpointPhase.READY_TO_COMMIT -> {
                    repositoryInfo = githubTool
                        .getRepositoryInfo(request.repository, request.branch)
                        .getOrThrow()
                    headSha = repositoryInfo.latestCommitSha
                    firstIteration = checkpoint.iteration

                    if (
                        checkpoint.headSha.isNotBlank() &&
                        checkpoint.headSha != repositoryInfo.latestCommitSha
                    ) {
                        failureContext = buildString {
                            if (checkpoint.failureContext.isNotBlank()) {
                                appendLine(checkpoint.failureContext)
                            }
                            appendLine(
                                "Repository advanced from ${checkpoint.headSha} to " +
                                    "${repositoryInfo.latestCommitSha} after a prepared patch. " +
                                    "Re-inspect the current repository state before creating another commit."
                            )
                        }.trim()
                    }
                }

                AutonomousCheckpointPhase.INSPECTING -> {
                    repositoryInfo = githubTool
                        .getRepositoryInfo(request.repository, request.branch)
                        .getOrThrow()
                    headSha = repositoryInfo.latestCommitSha
                    firstIteration = checkpoint.iteration
                }
            }
        }

        if (firstIteration > request.maxIterations) {
            checkpointStore.delete(request.taskId)
            return@runCatching AutonomousCodingResult(
                success = false,
                iterations = request.maxIterations,
                commitShas = commits.toList(),
                finalCommitSha = commits.lastOrNull(),
                failureMessage = failureContext.ifBlank {
                    "Autonomous coding reached the iteration limit without verified completion."
                }
            )
        }

        for (iteration in firstIteration..request.maxIterations) {
            onProgress(request.taskId, "الدورة $iteration من ${request.maxIterations}: فحص المستودع.")
            checkpointStore.save(
                AutonomousCheckpoint(
                    request = request,
                    iteration = iteration,
                    headSha = headSha,
                    commitShas = commits.toList(),
                    failureContext = failureContext,
                    phase = AutonomousCheckpointPhase.INSPECTING
                )
            )

            onProgress(request.taskId, "قراءة شجرة ملفات المستودع.")
            val repositoryFiles = githubTool
                .listRepositoryFiles(request.repository, request.branch)
                .getOrThrow()
                .filter(::isTextCandidate)
                .distinct()
                .sorted()

            if (repositoryFiles.isEmpty()) {
                throw IllegalStateException("Repository tree contains no readable text files.")
            }

            onProgress(request.taskId, "اختيار الملفات المرتبطة بالمهمة بواسطة نموذج البرمجة.")
            val selectedPaths = selectFiles(
                request = request,
                repositoryFiles = repositoryFiles,
                failureContext = failureContext
            )

            onProgress(
                request.taskId,
                "سيقرأ Todd: " + selectedPaths.joinToString(limit = 8, truncated = "...")
            )
            val selectedFiles = selectedPaths.map { path ->
                githubTool.readFile(
                    repoFullName = request.repository,
                    path = path,
                    ref = headSha
                ).getOrThrow()
            }

            onProgress(request.taskId, "تحليل الملفات وصياغة أقل تعديل مطلوب.")
            val patch = proposePatch(
                request = request,
                headSha = headSha,
                iteration = iteration,
                selectedFiles = selectedFiles,
                failureContext = failureContext
            )

            if (patch.changes.isEmpty()) {
                throw IllegalStateException("The coding model returned no file changes.")
            }

            patch.changes.forEach { change ->
                validateChangePath(change.path)
            }

            val changedPaths = patch.changes.map { it.path }
            onProgress(
                request.taskId,
                "التعديل المقترح على: " + changedPaths.joinToString()
            )

            checkpointStore.save(
                AutonomousCheckpoint(
                    request = request,
                    iteration = iteration,
                    headSha = headSha,
                    commitShas = commits.toList(),
                    failureContext = failureContext,
                    phase = AutonomousCheckpointPhase.READY_TO_COMMIT,
                    changedPaths = changedPaths
                )
            )

            onProgress(
                request.taskId,
                "إنشاء Commit في GitHub: " + patch.commitMessage.ifBlank { "Todd autonomous iteration $iteration" }
            )
            val newCommit = githubTool.createCommit(
                repoFullName = request.repository,
                branch = request.branch,
                commitMessage = patch.commitMessage.ifBlank {
                    "Todd autonomous iteration $iteration"
                },
                files = patch.changes.associate { it.path to it.content }
            ).getOrThrow()

            commits += newCommit
            headSha = newCommit
            onProgress(request.taskId, "تم إنشاء الالتزام ${newCommit.take(12)}.")

            val verifyJobId = "${request.taskId}-verify-$iteration"

            checkpointStore.save(
                AutonomousCheckpoint(
                    request = request,
                    iteration = iteration,
                    headSha = headSha,
                    commitShas = commits.toList(),
                    failureContext = failureContext,
                    phase = AutonomousCheckpointPhase.VERIFYING,
                    verifyJobId = verifyJobId,
                    committedSha = newCommit,
                    changedPaths = changedPaths
                )
            )

            onProgress(request.taskId, "تشغيل التحقق البعيد GitHub Actions للالتزام ${newCommit.take(12)}.")
            remoteExecutor.startJob(
                verificationRequest(
                    request = request,
                    iteration = iteration,
                    commitSha = newCommit,
                    verifyJobId = verifyJobId
                )
            ).getOrThrow()

            onProgress(request.taskId, "التحقق البعيد يعمل الآن؛ انتظار النتيجة.")
            val remoteState = waitForTerminalState(verifyJobId)
            onProgress(request.taskId, "نتيجة التحقق البعيد: ${remoteState.status}.")

            when (remoteState.status) {
                RemoteJobStatus.COMPLETED -> {
                    checkpointStore.delete(request.taskId)
                    return@runCatching AutonomousCodingResult(
                        success = true,
                        iterations = iteration,
                        commitShas = commits.toList(),
                        finalCommitSha = newCommit,
                        evidenceUrl = remoteState.providerRunUrl,
                        artifactNames = remoteState.artifactNames
                    )
                }

                RemoteJobStatus.CANCELLED -> {
                    checkpointStore.delete(request.taskId)
                    return@runCatching AutonomousCodingResult(
                        success = false,
                        iterations = iteration,
                        commitShas = commits.toList(),
                        finalCommitSha = newCommit,
                        evidenceUrl = remoteState.providerRunUrl,
                        artifactNames = remoteState.artifactNames,
                        failureMessage = "Remote verification was cancelled."
                    )
                }

                else -> {
                    onProgress(request.taskId, "فشل التحقق؛ سيقرأ Todd الخطأ ويبدأ دورة إصلاح جديدة إن بقيت محاولات.")
                    failureContext = buildFailureContext(
                        iteration = iteration,
                        commitSha = newCommit,
                        changedPaths = changedPaths,
                        remoteState = remoteState
                    )

                    repositoryInfo = githubTool
                        .getRepositoryInfo(request.repository, request.branch)
                        .getOrThrow()
                    headSha = repositoryInfo.latestCommitSha

                    if (iteration < request.maxIterations) {
                        checkpointStore.save(
                            AutonomousCheckpoint(
                                request = request,
                                iteration = iteration + 1,
                                headSha = headSha,
                                commitShas = commits.toList(),
                                failureContext = failureContext,
                                phase = AutonomousCheckpointPhase.INSPECTING
                            )
                        )
                    }
                }
            }
        }

        checkpointStore.delete(request.taskId)
        AutonomousCodingResult(
            success = false,
            iterations = request.maxIterations,
            commitShas = commits.toList(),
            finalCommitSha = commits.lastOrNull(),
            failureMessage = failureContext.ifBlank {
                "Autonomous coding reached the iteration limit without verified completion."
            }
        )
    }

    private fun validateRequest(request: AutonomousCodingRequest) {
        require(request.taskId.isNotBlank()) { "taskId is required." }
        require(request.repository.contains("/")) { "Repository must use owner/name format." }
        require(request.branch.isNotBlank()) { "branch is required." }
        require(request.objective.isNotBlank()) { "objective is required." }
        require(request.maxIterations in 1..12) { "maxIterations must be between 1 and 12." }
    }

    private fun checkpointMatches(
        checkpoint: AutonomousCheckpoint,
        request: AutonomousCodingRequest
    ): Boolean =
        checkpoint.request.taskId == request.taskId &&
            checkpoint.request.projectId == request.projectId &&
            checkpoint.request.repository == request.repository &&
            checkpoint.request.branch == request.branch &&
            checkpoint.request.objective == request.objective

    private fun verificationRequest(
        request: AutonomousCodingRequest,
        iteration: Int,
        commitSha: String,
        verifyJobId: String
    ): RemoteJobRequest =
        RemoteJobRequest(
            jobId = verifyJobId,
            projectId = request.projectId,
            repository = request.repository,
            branch = request.branch,
            startCommit = commitSha,
            objective = "Verify autonomous coding iteration $iteration: ${request.objective}",
            completionCriteria = request.completionCriteria,
            mode = RemoteExecutionMode.VERIFY_ANDROID
        )

    private suspend fun selectFiles(
        request: AutonomousCodingRequest,
        repositoryFiles: List<String>,
        failureContext: String
    ): List<String> {
        val manifest = repositoryFiles.take(MAX_MANIFEST_FILES).joinToString("\n")
        val response = aiProvider.generateText(
            AIRequest(
                systemPrompt = "You are Todd's repository inspection planner. Return strict JSON only.",
                prompt = """
                    Goal:
                    ${request.objective}

                    Completion criteria:
                    ${request.completionCriteria}

                    Previous verification failure, if any:
                    ${failureContext.ifBlank { "None. This is the first iteration." }}

                    Repository file paths:
                    $manifest

                    Choose 1 to $MAX_SELECTED_FILES existing text files that must be read before making the next minimal fix.
                    Do not invent paths.
                    Return exactly:
                    {"files":["path/one","path/two"]}
                """.trimIndent(),
                temperature = 0.1f,
                maxTokens = 1024
            )
        ).getOrThrow()

        val root = parseJsonObject(response.text)
        val files = root["files"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf(String::isNotBlank) }
            ?.distinct()
            ?.take(MAX_SELECTED_FILES)
            .orEmpty()

        val allowed = repositoryFiles.toSet()
        val valid = files.filter { it in allowed && isTextCandidate(it) }

        if (valid.isEmpty()) {
            throw IllegalStateException("The coding model did not select any valid repository files.")
        }
        return valid
    }

    private suspend fun proposePatch(
        request: AutonomousCodingRequest,
        headSha: String,
        iteration: Int,
        selectedFiles: List<GitHubFileContent>,
        failureContext: String
    ): CodingPatch {
        val sourceBundle = selectedFiles.joinToString("\n\n") { file ->
            """
                ===== FILE: ${file.path} =====
                ${file.content.take(MAX_FILE_CHARS)}
            """.trimIndent()
        }

        val response = aiProvider.generateText(
            AIRequest(
                systemPrompt = """
                    You are Todd's coding worker.
                    Make the smallest technically justified change that advances the requested goal.
                    Return strict JSON only. Every changed file must contain its complete replacement content.
                    Do not claim tests passed; verification happens separately.
                """.trimIndent(),
                prompt = """
                    Iteration: $iteration
                    Repository: ${request.repository}
                    Branch: ${request.branch}
                    Current commit: $headSha

                    Goal:
                    ${request.objective}

                    Completion criteria:
                    ${request.completionCriteria}

                    Previous verification failure, if any:
                    ${failureContext.ifBlank { "None." }}

                    Read files:
                    $sourceBundle

                    Return exactly this JSON shape:
                    {
                      "commitMessage":"short git commit message",
                      "changes":[
                        {"path":"repository/relative/path","content":"complete new file content"}
                      ]
                    }

                    You may replace a file shown above or create a new text/source/config file when the goal technically requires it.
                    New paths must be repository-relative. Never use generated build output or binary file paths.
                    Do not use markdown fences.
                """.trimIndent(),
                temperature = 0.15f,
                maxTokens = 16384
            )
        ).getOrThrow()

        val root = parseJsonObject(response.text)
        val message = root["commitMessage"]?.jsonPrimitive?.content.orEmpty().trim()
        val changes = root["changes"]?.jsonArray
            ?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val path = item["path"]?.jsonPrimitive?.content?.trim().orEmpty()
                val content = item["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (path.isBlank()) null else CodingFileChange(path, content)
            }
            ?.distinctBy { it.path }
            ?.take(MAX_CHANGED_FILES)
            .orEmpty()

        return CodingPatch(
            commitMessage = message,
            changes = changes
        )
    }

    private suspend fun waitForTerminalState(jobId: String): RemoteJobState {
        repeat(maxPollsPerIteration) {
            val state = remoteExecutor.reconnect(jobId).getOrThrow()
            if (state.status in TERMINAL_REMOTE_STATES) return state
            delay(pollIntervalMs)
        }
        throw IllegalStateException(
            "Remote verification timed out before reaching a terminal state for $jobId."
        )
    }

    private fun buildFailureContext(
        iteration: Int,
        commitSha: String,
        changedPaths: List<String>,
        remoteState: RemoteJobState
    ): String = buildString {
        appendLine("Iteration $iteration failed verification.")
        appendLine("Commit: $commitSha")
        appendLine("Changed files: ${changedPaths.joinToString()}")
        appendLine("Remote status: ${remoteState.status}")
        if (remoteState.currentStepDescription.isNotBlank()) {
            appendLine("Failed/current step: ${remoteState.currentStepDescription}")
        }
        remoteState.failureMessage?.let { appendLine("Observed failure: $it") }
        remoteState.providerRunUrl?.let { appendLine("Evidence URL: $it") }
    }.trim()

    private fun parseJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException("The coding model did not return a JSON object.")
        }

        return Json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
    }

    private fun validateChangePath(path: String) {
        require(path.isNotBlank()) { "Change path must not be blank." }
        require(!path.startsWith("/")) { "Absolute paths are not allowed." }
        require(path.split('/').none { it == ".." }) { "Parent path traversal is not allowed." }
        require(isTextCandidate(path)) { "Binary or generated output paths are not editable." }
        require(!path.contains('\u0000')) { "Invalid path." }
    }

    private fun isTextCandidate(path: String): Boolean {
        val lower = path.lowercase()
        if (
            lower.startsWith(".git/") ||
            "/build/" in lower ||
            lower.startsWith("build/") ||
            "/.gradle/" in lower ||
            lower.endsWith(".apk") ||
            lower.endsWith(".aab") ||
            lower.endsWith(".jar") ||
            lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".pdf") ||
            lower.endsWith(".zip") ||
            lower.endsWith(".keystore") ||
            lower.endsWith(".jks")
        ) {
            return false
        }
        return true
    }

    companion object {
        private const val MAX_MANIFEST_FILES = 2_000
        private const val MAX_SELECTED_FILES = 8
        private const val MAX_CHANGED_FILES = 6
        private const val MAX_FILE_CHARS = 30_000

        private val TERMINAL_REMOTE_STATES = setOf(
            RemoteJobStatus.COMPLETED,
            RemoteJobStatus.FAILED,
            RemoteJobStatus.CANCELLED,
            RemoteJobStatus.BLOCKED
        )
    }
}
