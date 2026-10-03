package com.todd.data.local

import androidx.room.*
import com.todd.core.model.*
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun fromMemoryLayer(value: MemoryLayer): String = value.name

    @TypeConverter
    fun toMemoryLayer(value: String): MemoryLayer = MemoryLayer.valueOf(value)

    @TypeConverter
    fun fromRuleBehavior(value: RuleBehavior): String = value.name

    @TypeConverter
    fun toRuleBehavior(value: String): RuleBehavior = RuleBehavior.valueOf(value)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): Project?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: Project)

    @Delete
    suspend fun deleteProject(project: Project)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getTasksByProject(projectId: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY createdAt DESC")
    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task)

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE projectId = :projectId OR projectId IS NULL ORDER BY timestamp DESC")
    fun getMemoriesForProject(projectId: String): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM memories WHERE `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): MemoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntry)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntry)
}

@Dao
interface FailureDao {
    @Query("SELECT * FROM failures WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getFailuresForProject(projectId: String): Flow<List<FailureRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailure(failure: FailureRecord)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules WHERE isEnabled = 1")
    fun getActiveRules(): Flow<List<Rule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: Rule)
}
