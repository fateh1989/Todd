package com.todd

import com.todd.core.agent.AutonomousCodingLoop
import com.todd.core.agent.AutonomousCodingRequest
import com.todd.core.agent.AutonomousCheckpoint
import com.todd.core.agent.AutonomousCheckpointPhase
import com.todd.core.agent.InMemoryAutonomousCheckpointStore
import com.todd.core.ai.AIProvider
import com.todd.core.ai.AIRequest
import com.todd.core.ai.AIResponse
import com.todd.core.ai.ProviderCapabilities
import com.todd.core.ai.ProviderType
import com.todd.core.remote.RemoteExecutor
import com.todd.core.remote.RemoteJobRequest
import com.todd.core.remote.RemoteJobState
import com.todd.core.remote.RemoteJobStatus
import com.todd.core.tools.GitHubFileContent
import com.todd.core.tools.GitHubRepositoryInfo
import com.todd.core.tools.GitHubTool
import com.todd.core.tools.GitHubWorkflowRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousCodingLoopTest {

    @Test
    fun `autonomous loop commits model edit and requires remote verification`() = runBlocking {
        val ai = ScriptedProvider(
            responses = ArrayDeque(
                listOf(
                    """{"files":["app/src/main/kotlin/com/todd/Example.kt"]}""",
                    """{"commitMessage":"fix: example","changes":[{"path":"app/src/main/kotlin/com/todd/Example.kt","content":"package com.todd\nclass Example { fun ok() = true }"}]}"""
                )
            )
        )
        val git = FakeGitHubTool()
        val remote = CompletedRemoteExecutor()

        val loop = AutonomousCodingLoop(
            aiProvider = ai,
            githubTool = git,
            remoteExecutor = remote,
            pollIntervalMs = 0L,
            maxPollsPerIteration = 2
        )

        val result = loop.run(
            AutonomousCodingRequest(
                taskId = "task-1",
                projectId = "todd-main",
                repository = "fateh1989/Todd",
                branch = "main",
                objective = "Make Example.ok return true",
                completionCriteria = "Remote Android verification succeeds",
                maxIterations = 2
            )
        ).getOrThrow()

        assertTrue(result.success)
        assertEquals(listOf("commit-1"), result.commitShas)
        assertEquals("commit-1", result.finalCommitSha)
        assertEquals("package com.todd\nclass Example { fun ok() = true }", git.lastFiles.values.single())
        assertEquals("commit-1", remote.lastRequest?.startCommit)
    }

    @Test
    fun `resumes verification checkpoint without generating another commit`() = runBlocking {
        val request = AutonomousCodingRequest(
            taskId = "task-resume",
            projectId = "todd-main",
            repository = "fateh1989/Todd",
            branch = "main",
            objective = "Resume the previous coding job",
            completionCriteria = "Remote verification succeeds",
            maxIterations = 4
        )
        val checkpoints = InMemoryAutonomousCheckpointStore()
        checkpoints.save(
            AutonomousCheckpoint(
                request = request,
                iteration = 2,
                headSha = "commit-2",
                commitShas = listOf("commit-1", "commit-2"),
                phase = AutonomousCheckpointPhase.VERIFYING,
                verifyJobId = "task-resume-verify-2",
                committedSha = "commit-2",
                changedPaths = listOf("app/src/main/kotlin/com/todd/Example.kt")
            )
        )

        val git = FakeGitHubTool()
        val remote = CompletedRemoteExecutor()
        val loop = AutonomousCodingLoop(
            aiProvider = ScriptedProvider(ArrayDeque()),
            githubTool = git,
            remoteExecutor = remote,
            checkpointStore = checkpoints,
            pollIntervalMs = 0L,
            maxPollsPerIteration = 2
        )

        val result = loop.run(request).getOrThrow()

        assertTrue(result.success)
        assertEquals(2, result.iterations)
        assertEquals("commit-2", result.finalCommitSha)
        assertEquals("task-resume-verify-2", remote.lastRequest?.jobId)
        assertTrue(git.lastFiles.isEmpty())
        assertEquals(null, checkpoints.load("task-resume"))
    }

    @Test
    fun `autonomous loop can create a new source file when the model requires it`() = runBlocking {
        val ai = ScriptedProvider(
            responses = ArrayDeque(
                listOf(
                    """{"files":["app/src/main/kotlin/com/todd/Example.kt"]}""",
                    """{"commitMessage":"feat: add helper","changes":[{"path":"app/src/main/kotlin/com/todd/NewHelper.kt","content":"package com.todd\nclass NewHelper"}]}"""
                )
            )
        )
        val git = FakeGitHubTool()

        val result = AutonomousCodingLoop(
            aiProvider = ai,
            githubTool = git,
            remoteExecutor = CompletedRemoteExecutor(),
            pollIntervalMs = 0L,
            maxPollsPerIteration = 1
        ).run(
            AutonomousCodingRequest(
                taskId = "task-new-file",
                projectId = "todd-main",
                repository = "fateh1989/Todd",
                branch = "main",
                objective = "Add a helper class",
                completionCriteria = "green"
            )
        ).getOrThrow()

        assertTrue(result.success)
        assertTrue(git.lastFiles.containsKey("app/src/main/kotlin/com/todd/NewHelper.kt"))
    }

    @Test
    fun `autonomous loop rejects parent path traversal`() = runBlocking {
        val ai = ScriptedProvider(
            responses = ArrayDeque(
                listOf(
                    """{"files":["app/src/main/kotlin/com/todd/Example.kt"]}""",
                    """{"commitMessage":"bad","changes":[{"path":"../Secret.kt","content":"bad"}]}"""
                )
            )
        )

        val result = AutonomousCodingLoop(
            aiProvider = ai,
            githubTool = FakeGitHubTool(),
            remoteExecutor = CompletedRemoteExecutor(),
            pollIntervalMs = 0L,
            maxPollsPerIteration = 1
        ).run(
            AutonomousCodingRequest(
                taskId = "task-2",
                projectId = "todd-main",
                repository = "fateh1989/Todd",
                branch = "main",
                objective = "test",
                completionCriteria = "green"
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Parent path traversal") == true)
    }

    private class ScriptedProvider(
        private val responses: ArrayDeque<String>
    ) : AIProvider {
        override val type = ProviderType.CLOUD_GEMINI
        override val capabilities = ProviderCapabilities(isLocal = false)

        override suspend fun isAvailable(): Boolean = true

        override suspend fun generateText(request: AIRequest): Result<AIResponse> =
            Result.success(
                AIResponse(
                    text = responses.removeFirst(),
                    providerUsed = type
                )
            )
    }

    private class FakeGitHubTool : GitHubTool {
        var lastFiles: Map<String, String> = emptyMap()

        override suspend fun getRepositoryInfo(
            repoFullName: String,
            branch: String
        ): Result<GitHubRepositoryInfo> = Result.success(
            GitHubRepositoryInfo(
                fullName = repoFullName,
                defaultBranch = "main",
                activeBranch = branch,
                latestCommitSha = if (lastFiles.isEmpty()) "base-sha" else "commit-1",
                lastVerifiedCommitSha = null
            )
        )

        override suspend fun getLatestWorkflowRun(
            repoFullName: String,
            branch: String
        ): Result<GitHubWorkflowRun?> = Result.success(null)

        override suspend fun listRepositoryFiles(
            repoFullName: String,
            branch: String
        ): Result<List<String>> = Result.success(
            listOf("app/src/main/kotlin/com/todd/Example.kt")
        )

        override suspend fun readFile(
            repoFullName: String,
            path: String,
            ref: String
        ): Result<GitHubFileContent> = Result.success(
            GitHubFileContent(
                path = path,
                sha = "file-sha",
                content = "package com.todd\nclass Example { fun ok() = false }"
            )
        )

        override suspend fun verifyApkArtifact(
            runId: Long,
            expectedCommitSha: String
        ): Result<Boolean> = Result.success(true)

        override suspend fun createCommit(
            repoFullName: String,
            branch: String,
            commitMessage: String,
            files: Map<String, String>
        ): Result<String> {
            lastFiles = files
            return Result.success("commit-1")
        }
    }

    private class CompletedRemoteExecutor : RemoteExecutor {
        var lastRequest: RemoteJobRequest? = null
        private val state = MutableStateFlow(RemoteJobState(jobId = "none"))

        override suspend fun startJob(request: RemoteJobRequest): Result<String> {
            lastRequest = request
            state.value = RemoteJobState(
                jobId = request.jobId,
                status = RemoteJobStatus.COMPLETED,
                currentStepDescription = "Verified",
                headCommit = request.startCommit,
                providerRunId = 123L,
                providerRunUrl = "https://github.com/fateh1989/Todd/actions/runs/123",
                repository = request.repository,
                branch = request.branch,
                artifactNames = listOf("app-debug")
            )
            return Result.success(request.jobId)
        }

        override suspend fun reconnect(jobId: String): Result<RemoteJobState> =
            Result.success(state.value)

        override suspend fun requestPause(jobId: String): Result<Boolean> = Result.success(false)

        override suspend fun requestCancel(jobId: String): Result<Boolean> = Result.success(true)

        override fun observeJob(jobId: String): Flow<RemoteJobState> = state
    }
}
