package com.todd.data.repository

import com.todd.core.model.*
import com.todd.data.local.ToddDatabase
import kotlinx.coroutines.flow.Flow

class ToddRepository(private val database: ToddDatabase) {

    fun getAllProjects(): Flow<List<Project>> = database.projectDao().getAllProjects()

    suspend fun getProjectById(id: String): Project? = database.projectDao().getProjectById(id)

    suspend fun saveProject(project: Project) = database.projectDao().insertProject(project)

    fun getAllTasks(): Flow<List<Task>> = database.taskDao().getAllTasks()

    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>> = database.taskDao().getTasksByStatus(status)

    suspend fun saveTask(task: Task) = database.taskDao().insertTask(task)

    suspend fun updateTask(task: Task) = database.taskDao().updateTask(task)

    fun getMemoriesForProject(projectId: String): Flow<List<MemoryEntry>> =
        database.memoryDao().getMemoriesForProject(projectId)

    suspend fun saveMemory(memory: MemoryEntry) = database.memoryDao().insertMemory(memory)

    fun getFailures(projectId: String): Flow<List<FailureRecord>> =
        database.failureDao().getFailuresForProject(projectId)

    suspend fun recordFailure(failure: FailureRecord) = database.failureDao().insertFailure(failure)

    fun getActiveRules(): Flow<List<Rule>> = database.ruleDao().getActiveRules()

    suspend fun saveRule(rule: Rule) = database.ruleDao().insertRule(rule)
}
