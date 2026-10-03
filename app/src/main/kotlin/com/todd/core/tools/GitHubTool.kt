package com.todd.core.tools

data class GitHubRepositoryInfo(
    val fullName: String,
    val defaultBranch: String,
    val activeBranch: String,
    val latestCommitSha: String,
    val lastVerifiedCommitSha: String?,
    val isClean: Boolean = true
)

data class GitHubWorkflowRun(
    val runId: Long,
    val workflowName: String,
    val headSha: String,
    val status: String,
    val conclusion: String?, // success, failure, cancelled
    val artifactName: String?
)

interface GitHubTool {
    suspend fun getRepositoryInfo(repoFullName: String, branch: String): Result<GitHubRepositoryInfo>
    suspend fun getLatestWorkflowRun(repoFullName: String, branch: String): Result<GitHubWorkflowRun?>
    suspend fun verifyApkArtifact(runId: Long, expectedCommitSha: String): Result<Boolean>
    suspend fun createCommit(repoFullName: String, branch: String, commitMessage: String, files: Map<String, String>): Result<String>
}

class SimulatedGitHubTool : GitHubTool {

    override suspend fun getRepositoryInfo(repoFullName: String, branch: String): Result<GitHubRepositoryInfo> {
        return Result.success(
            GitHubRepositoryInfo(
                fullName = repoFullName,
                defaultBranch = "main",
                activeBranch = branch,
                latestCommitSha = "5583d33",
                lastVerifiedCommitSha = "5583d33",
                isClean = true
            )
        )
    }

    override suspend fun getLatestWorkflowRun(repoFullName: String, branch: String): Result<GitHubWorkflowRun?> {
        return Result.success(
            GitHubWorkflowRun(
                runId = 10029384,
                workflowName = "Todd Android CI",
                headSha = "5583d33",
                status = "completed",
                conclusion = "success",
                artifactName = "app-debug.apk"
            )
        )
    }

    override suspend fun verifyApkArtifact(runId: Long, expectedCommitSha: String): Result<Boolean> {
        val runResult = getLatestWorkflowRun("fateh1989/Todd", "main")
        val run = runResult.getOrNull()
        if (run != null && run.headSha == expectedCommitSha && run.conclusion == "success" && run.artifactName == "app-debug.apk") {
            return Result.success(true)
        }
        return Result.failure(IllegalStateException("Artifact does not match expected commit or failed CI build."))
    }

    override suspend fun createCommit(
        repoFullName: String,
        branch: String,
        commitMessage: String,
        files: Map<String, String>
    ): Result<String> {
        val newSha = "a" + System.currentTimeMillis().toString(16).takeLast(6)
        return Result.success(newSha)
    }
}
