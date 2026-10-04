package com.todd.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Base64

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
    val conclusion: String?,
    val artifactName: String?
)

interface GitHubTool {
    suspend fun getRepositoryInfo(repoFullName: String, branch: String): Result<GitHubRepositoryInfo>
    suspend fun getLatestWorkflowRun(repoFullName: String, branch: String): Result<GitHubWorkflowRun?>
    suspend fun verifyApkArtifact(runId: Long, expectedCommitSha: String): Result<Boolean>
    suspend fun createCommit(
        repoFullName: String,
        branch: String,
        commitMessage: String,
        files: Map<String, String>
    ): Result<String>
}

class GitHubRestTool(
    private val tokenProvider: () -> String? = { null },
    private val apiBaseUrl: String = "https://api.github.com"
) : GitHubTool {

    override suspend fun getRepositoryInfo(
        repoFullName: String,
        branch: String
    ): Result<GitHubRepositoryInfo> = runCatching {
        val repo = requestJson("GET", "/repos/${repoPath(repoFullName)}")
        val defaultBranch = repo.getString("default_branch")
        val effectiveBranch = branch.ifBlank { defaultBranch }
        val branchJson = requestJson(
            "GET",
            "/repos/${repoPath(repoFullName)}/branches/${encodePathSegment(effectiveBranch)}"
        )
        val latestSha = branchJson.getJSONObject("commit").getString("sha")

        GitHubRepositoryInfo(
            fullName = repo.getString("full_name"),
            defaultBranch = defaultBranch,
            activeBranch = effectiveBranch,
            latestCommitSha = latestSha,
            lastVerifiedCommitSha = null,
            isClean = true
        )
    }

    override suspend fun getLatestWorkflowRun(
        repoFullName: String,
        branch: String
    ): Result<GitHubWorkflowRun?> = runCatching {
        val runsJson = requestJson(
            "GET",
            "/repos/${repoPath(repoFullName)}/actions/runs?branch=${encodeQuery(branch)}&per_page=1"
        )
        val runs = runsJson.getJSONArray("workflow_runs")
        if (runs.length() == 0) {
            null
        } else {
            val run = runs.getJSONObject(0)
            val runId = run.getLong("id")
            val artifactName = findFirstArtifactName(repoFullName, runId)

            GitHubWorkflowRun(
                runId = runId,
                workflowName = run.optString("name", "GitHub Actions"),
                headSha = run.getString("head_sha"),
                status = run.getString("status"),
                conclusion = run.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
                artifactName = artifactName
            )
        }
    }

    override suspend fun verifyApkArtifact(
        runId: Long,
        expectedCommitSha: String
    ): Result<Boolean> = runCatching {
        val repoFullName = inferConfiguredRepository()
            ?: throw IllegalStateException(
                "Repository is required before verifying an artifact. Call getRepositoryInfo first."
            )
        verifyApkArtifactForRepository(repoFullName, runId, expectedCommitSha).getOrThrow()
    }

    suspend fun verifyApkArtifactForRepository(
        repoFullName: String,
        runId: Long,
        expectedCommitSha: String
    ): Result<Boolean> = runCatching {
        val run = requestJson(
            "GET",
            "/repos/${repoPath(repoFullName)}/actions/runs/$runId"
        )

        if (run.getString("head_sha") != expectedCommitSha) {
            throw IllegalStateException("Workflow run does not match the expected commit.")
        }
        if (run.optString("conclusion") != "success") {
            throw IllegalStateException("Workflow run has not completed successfully.")
        }

        val artifacts = requestJson(
            "GET",
            "/repos/${repoPath(repoFullName)}/actions/runs/$runId/artifacts?per_page=100"
        ).getJSONArray("artifacts")

        var found = false
        for (i in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(i)
            val name = artifact.optString("name")
            val expired = artifact.optBoolean("expired", false)
            if (!expired && (name == "app-debug" || name.endsWith(".apk", ignoreCase = true))) {
                found = true
                break
            }
        }

        if (!found) {
            throw IllegalStateException("No non-expired APK artifact was found for this run.")
        }
        true
    }

    override suspend fun createCommit(
        repoFullName: String,
        branch: String,
        commitMessage: String,
        files: Map<String, String>
    ): Result<String> = runCatching {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub write authorization is not configured.")
        if (files.isEmpty()) throw IllegalArgumentException("No files were supplied for commit.")

        val encodedBranch = encodePathSegment(branch)
        val ref = requestJson(
            "GET",
            "/repos/${repoPath(repoFullName)}/git/ref/heads/$encodedBranch",
            requiredToken = token
        )
        val headSha = ref.getJSONObject("object").getString("sha")

        val headCommit = requestJson(
            "GET",
            "/repos/${repoPath(repoFullName)}/git/commits/$headSha",
            requiredToken = token
        )
        val baseTree = headCommit.getJSONObject("tree").getString("sha")

        val treeEntries = JSONArray()
        for ((path, content) in files) {
            val blobBody = JSONObject()
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
                .put("encoding", "base64")

            val blob = requestJson(
                "POST",
                "/repos/${repoPath(repoFullName)}/git/blobs",
                blobBody,
                requiredToken = token
            )

            treeEntries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.getString("sha"))
            )
        }

        val tree = requestJson(
            "POST",
            "/repos/${repoPath(repoFullName)}/git/trees",
            JSONObject()
                .put("base_tree", baseTree)
                .put("tree", treeEntries),
            requiredToken = token
        )

        val commit = requestJson(
            "POST",
            "/repos/${repoPath(repoFullName)}/git/commits",
            JSONObject()
                .put("message", commitMessage)
                .put("tree", tree.getString("sha"))
                .put("parents", JSONArray().put(headSha)),
            requiredToken = token
        )
        val newSha = commit.getString("sha")

        requestJson(
            "PATCH",
            "/repos/${repoPath(repoFullName)}/git/refs/heads/$encodedBranch",
            JSONObject()
                .put("sha", newSha)
                .put("force", false),
            requiredToken = token
        )

        newSha
    }

    @Volatile
    private var lastRepository: String? = null

    private fun inferConfiguredRepository(): String? = lastRepository

    private suspend fun findFirstArtifactName(repoFullName: String, runId: Long): String? {
        return runCatching {
            val artifacts = requestJson(
                "GET",
                "/repos/${repoPath(repoFullName)}/actions/runs/$runId/artifacts?per_page=100"
            ).getJSONArray("artifacts")
            if (artifacts.length() == 0) null
            else artifacts.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun repoPath(repoFullName: String): String {
        val parts = repoFullName.split("/", limit = 2)
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "Repository must use owner/name format."
        }
        lastRepository = repoFullName
        return "${encodePathSegment(parts[0])}/${encodePathSegment(parts[1])}"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private suspend fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
        requiredToken: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(apiBaseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Todd-Android")
            val token = requiredToken ?: tokenProvider()?.takeIf { it.isNotBlank() }
            if (token != null) setRequestProperty("Authorization", "Bearer $token")

            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }

        try {
            val code = connection.responseCode
            val responseText = (if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(responseText).optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    "GitHub API $method $path failed with HTTP $code" +
                        if (detail.isNotBlank()) ": $detail" else ""
                )
            }

            if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}

class SimulatedGitHubTool : GitHubTool {
    override suspend fun getRepositoryInfo(
        repoFullName: String,
        branch: String
    ): Result<GitHubRepositoryInfo> = Result.success(
        GitHubRepositoryInfo(
            fullName = repoFullName,
            defaultBranch = "main",
            activeBranch = branch,
            latestCommitSha = "simulated",
            lastVerifiedCommitSha = null,
            isClean = true
        )
    )

    override suspend fun getLatestWorkflowRun(
        repoFullName: String,
        branch: String
    ): Result<GitHubWorkflowRun?> = Result.success(null)

    override suspend fun verifyApkArtifact(
        runId: Long,
        expectedCommitSha: String
    ): Result<Boolean> = Result.failure(
        IllegalStateException("SimulatedGitHubTool does not verify real artifacts.")
    )

    override suspend fun createCommit(
        repoFullName: String,
        branch: String,
        commitMessage: String,
        files: Map<String, String>
    ): Result<String> = Result.failure(
        IllegalStateException("SimulatedGitHubTool does not create real commits.")
    )
}
