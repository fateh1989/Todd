package com.todd.core.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class AutonomousCheckpointPhase {
    INSPECTING,
    READY_TO_COMMIT,
    VERIFYING
}

data class AutonomousCheckpoint(
    val request: AutonomousCodingRequest,
    val iteration: Int,
    val headSha: String,
    val commitShas: List<String> = emptyList(),
    val failureContext: String = "",
    val phase: AutonomousCheckpointPhase = AutonomousCheckpointPhase.INSPECTING,
    val verifyJobId: String? = null,
    val committedSha: String? = null,
    val changedPaths: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

interface AutonomousCheckpointStore {
    fun save(checkpoint: AutonomousCheckpoint)
    fun load(taskId: String): AutonomousCheckpoint?
    fun delete(taskId: String)
    fun listPending(): List<AutonomousCheckpoint>
}

class InMemoryAutonomousCheckpointStore : AutonomousCheckpointStore {
    private val items = ConcurrentHashMap<String, AutonomousCheckpoint>()

    override fun save(checkpoint: AutonomousCheckpoint) {
        items[checkpoint.request.taskId] = checkpoint
    }

    override fun load(taskId: String): AutonomousCheckpoint? = items[taskId]

    override fun delete(taskId: String) {
        items.remove(taskId)
    }

    override fun listPending(): List<AutonomousCheckpoint> =
        items.values.sortedBy { it.updatedAt }
}

class AndroidAutonomousCheckpointStore(context: Context) : AutonomousCheckpointStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(checkpoint: AutonomousCheckpoint) {
        prefs.edit()
            .putString(key(checkpoint.request.taskId), encode(checkpoint).toString())
            .apply()
    }

    override fun load(taskId: String): AutonomousCheckpoint? {
        val raw = prefs.getString(key(taskId), null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    override fun delete(taskId: String) {
        prefs.edit().remove(key(taskId)).apply()
    }

    override fun listPending(): List<AutonomousCheckpoint> =
        prefs.all
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .values
            .mapNotNull { raw ->
                val text = raw as? String ?: return@mapNotNull null
                runCatching { decode(JSONObject(text)) }.getOrNull()
            }
            .sortedBy { it.updatedAt }

    private fun encode(checkpoint: AutonomousCheckpoint): JSONObject {
        val request = checkpoint.request

        return JSONObject()
            .put(
                "request",
                JSONObject()
                    .put("taskId", request.taskId)
                    .put("projectId", request.projectId)
                    .put("repository", request.repository)
                    .put("branch", request.branch)
                    .put("objective", request.objective)
                    .put("completionCriteria", request.completionCriteria)
                    .put("maxIterations", request.maxIterations)
            )
            .put("iteration", checkpoint.iteration)
            .put("headSha", checkpoint.headSha)
            .put("commitShas", JSONArray(checkpoint.commitShas))
            .put("failureContext", checkpoint.failureContext)
            .put("phase", checkpoint.phase.name)
            .put("verifyJobId", checkpoint.verifyJobId)
            .put("committedSha", checkpoint.committedSha)
            .put("changedPaths", JSONArray(checkpoint.changedPaths))
            .put("updatedAt", checkpoint.updatedAt)
    }

    private fun decode(root: JSONObject): AutonomousCheckpoint {
        val requestJson = root.getJSONObject("request")

        val request = AutonomousCodingRequest(
            taskId = requestJson.getString("taskId"),
            projectId = requestJson.getString("projectId"),
            repository = requestJson.getString("repository"),
            branch = requestJson.getString("branch"),
            objective = requestJson.getString("objective"),
            completionCriteria = requestJson.getString("completionCriteria"),
            maxIterations = requestJson.optInt("maxIterations", 4)
        )

        return AutonomousCheckpoint(
            request = request,
            iteration = root.optInt("iteration", 1),
            headSha = root.optString("headSha"),
            commitShas = root.optJSONArray("commitShas").toStringList(),
            failureContext = root.optString("failureContext"),
            phase = runCatching {
                AutonomousCheckpointPhase.valueOf(
                    root.optString("phase", AutonomousCheckpointPhase.INSPECTING.name)
                )
            }.getOrDefault(AutonomousCheckpointPhase.INSPECTING),
            verifyJobId = root.optString("verifyJobId")
                .takeIf { it.isNotBlank() && it != "null" },
            committedSha = root.optString("committedSha")
                .takeIf { it.isNotBlank() && it != "null" },
            changedPaths = root.optJSONArray("changedPaths").toStringList(),
            updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun JSONArray?.toStringList(): List<String> = buildList {
        val array = this@toStringList ?: return@buildList
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun key(taskId: String): String = KEY_PREFIX + taskId

    companion object {
        private const val PREFS_NAME = "todd_autonomous_checkpoints"
        private const val KEY_PREFIX = "checkpoint:"
    }
}
