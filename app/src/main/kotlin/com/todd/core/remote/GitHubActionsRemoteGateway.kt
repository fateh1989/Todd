package com.todd.core.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class GitHubActionsRemoteGateway(
    private val tokenProvider: () -> String?,
    private val controlRepository: String = "fateh1989/Todd",
    private val workflowFile: String = "todd-remote-worker.yml",
    private val workflowRef: String = "main",
    private val apiBaseUrl: String = "https://api.github.com"
) : RemoteExecutionGateway {

    override suspend fun findExisting(jobId: String): Result<RemoteDispatchReceipt?> = runCatching {
        val token = requiredToken()
        findMatchingRun(jobId, token)
    }

    override suspend fun dispatch(request: RemoteJobRequest): Result<RemoteDispatchReceipt> = runCatching {
        val token = requiredToken()

        val body = JSONObject()
            .put("ref", workflowRef)
            .put(
                "inputs",
                JSONObject()
                    .put("job_id", request.jobId)
                    .put("repository", request.repository)
                    .put("branch", request.branch)
                    .put("start_commit", request.startCommit)
                    .put("objective", request.objective.take(1000))
                    .put("completion_criteria", request.completionCriteria.take(1000))
                    .put("mode", request.mode.name.lowercase())
                    .put("timeout_minutes", request.timeoutMinutes.coerceIn(5, 360).toString())
            )

        requestJsonOrEmpty(
            method = "POST",
            path = "/repos/${repoPath(controlRepository)}/actions/workflows/${encode(workflowFile)}/dispatches",
            body = body,
            token = token
        )

        repeat(30) {
            delay(700)
            findMatchingRun(request.jobId, token)?.let { return@runCatching it }
        }

        throw IllegalStateException(
            "GitHub accepted the workflow dispatch but the matching remote run was not found."
        )
    }

    override suspend fun getRun(runId: Long): Result<RemoteRunSnapshot> = runCatching {
        val token = requiredToken()
        val run = requestJson(
            "GET",
            "/repos/${repoPath(controlRepository)}/actions/runs/$runId",
            token = token
        )

        val jobs = requestJson(
            "GET",
            "/repos/${repoPath(controlRepository)}/actions/runs/$runId/jobs?per_page=100",
            token = token
        ).getJSONArray("jobs")

        var currentStep: String? = null
        var failureMessage: String? = null

        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            val steps = job.optJSONArray("steps") ?: continue
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val stepStatus = step.optString("status")
                val conclusion = step.optString("conclusion")
                if (stepStatus == "in_progress") {
                    currentStep = step.optString("name")
                }
                if (conclusion == "failure") {
                    failureMessage = "Failed step: ${step.optString("name")}"
                }
            }
        }

        val artifactsJson = requestJson(
            "GET",
            "/repos/${repoPath(controlRepository)}/actions/runs/$runId/artifacts?per_page=100",
            token = token
        ).getJSONArray("artifacts")

        val artifacts = buildList {
            for (i in 0 until artifactsJson.length()) {
                val artifact = artifactsJson.getJSONObject(i)
                if (!artifact.optBoolean("expired", false)) {
                    artifact.optString("name").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        RemoteRunSnapshot(
            runId = runId,
            runUrl = run.getString("html_url"),
            status = run.optString("status"),
            conclusion = run.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
            currentStep = currentStep,
            artifactNames = artifacts,
            failureMessage = failureMessage
        )
    }

    override suspend fun cancel(runId: Long): Result<Boolean> = runCatching {
        val token = requiredToken()
        requestJsonOrEmpty(
            "POST",
            "/repos/${repoPath(controlRepository)}/actions/runs/$runId/cancel",
            token = token
        )
        true
    }

    private suspend fun findMatchingRun(
        jobId: String,
        token: String
    ): RemoteDispatchReceipt? {
        val title = "Todd Remote $jobId"
        val runs = requestJson(
            "GET",
            "/repos/${repoPath(controlRepository)}/actions/workflows/${encode(workflowFile)}/runs?event=workflow_dispatch&per_page=100",
            token = token
        ).getJSONArray("workflow_runs")

        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (run.optString("display_title") == title) {
                return RemoteDispatchReceipt(
                    runId = run.getLong("id"),
                    runUrl = run.getString("html_url")
                )
            }
        }
        return null
    }

    private fun requiredToken(): String =
        tokenProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "GitHub authorization is required for remote execution. Configure it in Todd settings."
            )

    private fun repoPath(repo: String): String {
        val parts = repo.split("/", limit = 2)
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "Repository must use owner/name format."
        }
        return "${encode(parts[0])}/${encode(parts[1])}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private suspend fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val response = requestRaw(method, path, body, token)
        if (response.second.isBlank()) JSONObject() else JSONObject(response.second)
    }

    private suspend fun requestJsonOrEmpty(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val response = requestRaw(method, path, body, token)
        if (response.second.isBlank()) JSONObject() else JSONObject(response.second)
    }

    private fun requestRaw(
        method: String,
        path: String,
        body: JSONObject?,
        token: String
    ): Pair<Int, String> {
        val connection = (URL(apiBaseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "Todd-Android")

            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw IllegalStateException(
                    "GitHub remote API $method $path failed with HTTP $code" +
                        if (!detail.isNullOrBlank()) ": $detail" else ""
                )
            }
            return code to text
        } finally {
            connection.disconnect()
        }
    }
}
