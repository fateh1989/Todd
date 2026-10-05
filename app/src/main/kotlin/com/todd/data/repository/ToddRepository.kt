package com.todd.data.repository

import com.todd.core.model.*
import com.todd.data.local.ToddDatabase
import kotlinx.coroutines.flow.Flow

class ToddRepository(private val database: ToddDatabase) {

    fun getAllProjects(): Flow<List<Project>> = database.projectDao().getAllProjects()
    suspend fun getProjectById(id: String): Project? = database.projectDao().getProjectById(id)
    suspend fun saveProject(project: Project) = database.projectDao().insertProject(project)

    fun getAllTasks(): Flow<List<Task>> = database.taskDao().getAllTasks()
    suspend fun getTaskById(id: String): Task? = database.taskDao().getTaskById(id)
    suspend fun getLatestTaskForProject(projectId: String): Task? =
        database.taskDao().getLatestTaskForProject(projectId)
    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>> =
        database.taskDao().getTasksByStatus(status)
    suspend fun saveTask(task: Task) = database.taskDao().insertTask(task)
    suspend fun updateTask(task: Task) = database.taskDao().updateTask(task)

    fun getAllSchedules(): Flow<List<ScheduledTask>> =
        database.scheduledTaskDao().getAllSchedules()

    suspend fun getEnabledSchedules(): List<ScheduledTask> =
        database.scheduledTaskDao().getEnabledSchedules()

    suspend fun getScheduledTask(id: String): ScheduledTask? =
        database.scheduledTaskDao().getById(id)

    suspend fun saveScheduledTask(schedule: ScheduledTask) =
        database.scheduledTaskDao().insert(schedule)

    suspend fun updateScheduledTask(schedule: ScheduledTask) =
        database.scheduledTaskDao().update(schedule)

    suspend fun deleteScheduledTask(schedule: ScheduledTask) =
        database.scheduledTaskDao().delete(schedule)

    fun getMemoriesForProject(projectId: String): Flow<List<MemoryEntry>> =
        database.memoryDao().getMemoriesForProject(projectId)
    suspend fun saveMemory(memory: MemoryEntry) = database.memoryDao().insertMemory(memory)
    suspend fun deleteMemory(memory: MemoryEntry) = database.memoryDao().deleteMemory(memory)

    fun getFailures(projectId: String): Flow<List<FailureRecord>> =
        database.failureDao().getFailuresForProject(projectId)
    suspend fun recordFailure(failure: FailureRecord) = database.failureDao().insertFailure(failure)

    fun getActiveRules(): Flow<List<Rule>> = database.ruleDao().getActiveRules()
    suspend fun saveRule(rule: Rule) = database.ruleDao().insertRule(rule)

    suspend fun buildProjectContext(
        projectId: String,
        memoryLimit: Int = 24,
        failureLimit: Int = 8
    ): String {
        val project = database.projectDao().getProjectById(projectId)
        val latestTask = database.taskDao().getLatestTaskForProject(projectId)
        val memories = database.memoryDao().getRecentMemories(projectId, memoryLimit)
        val failures = database.failureDao().getRecentFailures(projectId, failureLimit)

        return buildString {
            appendLine("Todd persistent project context")

            if (project != null) {
                appendLine("Project: ${project.name}")
                project.repository?.let { appendLine("Repository: $it") }
                appendLine("Branch: ${project.branch}")
                project.lastVerifiedCommit?.let { appendLine("Last verified commit: $it") }
                if (project.currentGoal.isNotBlank()) appendLine("Current goal: ${project.currentGoal}")
            } else {
                appendLine("Project ID: $projectId")
            }

            if (latestTask != null) {
                appendLine()
                appendLine("Latest task:")
                appendLine("- Title: ${latestTask.title}")
                appendLine("- Goal: ${latestTask.goal}")
                appendLine("- Status: ${latestTask.status}")
                if (latestTask.currentStep.isNotBlank()) appendLine("- Current step: ${latestTask.currentStep}")
                latestTask.lastEvidence?.let { appendLine("- Evidence: $it") }
                latestTask.failureCause?.let { appendLine("- Failure: $it") }
            }

            if (memories.isNotEmpty()) {
                appendLine()
                appendLine("Recent memory:")
                memories.asReversed().forEach { memory ->
                    appendLine("- [${memory.layer}] ${memory.key}: ${memory.value}")
                }
            }

            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("Recent failures not to repeat blindly:")
                failures.forEach { failure ->
                    append("- ${failure.operation}: ${failure.errorMessage}")
                    if (failure.confirmedCause != null) append(" | cause=${failure.confirmedCause}")
                    append(" | retry when=${failure.retryCondition}")
                    appendLine()
                }
            }
        }.trim()
    }
}
