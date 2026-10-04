package com.todd.core.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AndroidRemoteJobStore(context: Context) : RemoteJobStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(job: StoredRemoteJob) {
        prefs.edit()
            .putString(job.request.jobId, encode(job).toString())
            .apply()
    }

    override fun load(jobId: String): StoredRemoteJob? {
        val raw = prefs.getString(jobId, null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    private fun encode(job: StoredRemoteJob): JSONObject {
        val request = job.request
        val state = job.state
        return JSONObject()
            .put("request", JSONObject()
                .put("jobId", request.jobId)
                .put("projectId", request.projectId)
                .put("repository", request.repository)
                .put("branch", request.branch)
                .put("startCommit", request.startCommit)
                .put("objective", request.objective)
                .put("completionCriteria", request.completionCriteria)
                .put("timeoutMinutes", request.timeoutMinutes)
                .put("mode", request.mode.name)
                .put("allowedTools", JSONArray(request.allowedTools))
            )
            .put("state", JSONObject()
                .put("jobId", state.jobId)
                .put("status", state.status.name)
                .put("currentStepDescription", state.currentStepDescription)
                .put("headCommit", state.headCommit)
                .put("providerRunId", state.providerRunId)
                .put("providerRunUrl", state.providerRunUrl)
                .put("repository", state.repository)
                .put("branch", state.branch)
                .put("artifactNames", JSONArray(state.artifactNames))
                .put("artifactPath", state.artifactPath)
                .put("failureMessage", state.failureMessage)
                .put("startedAt", state.startedAt)
                .put("completedAt", state.completedAt)
                .put("lastHeartbeat", state.lastHeartbeat)
            )
    }

    private fun decode(root: JSONObject): StoredRemoteJob {
        val r = root.getJSONObject("request")
        val s = root.getJSONObject("state")

        val allowed = buildList {
            val array = r.optJSONArray("allowedTools") ?: JSONArray()
            for (i in 0 until array.length()) add(array.getString(i))
        }

        val artifacts = buildList {
            val array = s.optJSONArray("artifactNames") ?: JSONArray()
            for (i in 0 until array.length()) add(array.getString(i))
        }

        val request = RemoteJobRequest(
            jobId = r.getString("jobId"),
            projectId = r.getString("projectId"),
            repository = r.getString("repository"),
            branch = r.getString("branch"),
            startCommit = r.optString("startCommit"),
            objective = r.optString("objective"),
            completionCriteria = r.optString("completionCriteria"),
            allowedTools = allowed,
            timeoutMinutes = r.optInt("timeoutMinutes", 120),
            mode = runCatching {
                RemoteExecutionMode.valueOf(r.optString("mode", RemoteExecutionMode.VERIFY_ANDROID.name))
            }.getOrDefault(RemoteExecutionMode.VERIFY_ANDROID)
        )

        val state = RemoteJobState(
            jobId = s.getString("jobId"),
            status = runCatching {
                RemoteJobStatus.valueOf(s.optString("status", RemoteJobStatus.IDLE.name))
            }.getOrDefault(RemoteJobStatus.IDLE),
            currentStepDescription = s.optString("currentStepDescription"),
            headCommit = s.optString("headCommit"),
            providerRunId = if (s.isNull("providerRunId")) null else s.optLong("providerRunId"),
            providerRunUrl = s.optString("providerRunUrl").takeIf { it.isNotBlank() && it != "null" },
            repository = s.optString("repository"),
            branch = s.optString("branch"),
            artifactNames = artifacts,
            artifactPath = s.optString("artifactPath").takeIf { it.isNotBlank() && it != "null" },
            failureMessage = s.optString("failureMessage").takeIf { it.isNotBlank() && it != "null" },
            startedAt = s.optLong("startedAt", System.currentTimeMillis()),
            completedAt = if (s.isNull("completedAt")) null else s.optLong("completedAt"),
            lastHeartbeat = s.optLong("lastHeartbeat", System.currentTimeMillis())
        )

        return StoredRemoteJob(request, state)
    }

    companion object {
        private const val PREFS_NAME = "todd_remote_jobs"
    }
}
