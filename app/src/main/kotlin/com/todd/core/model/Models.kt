package com.todd.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ToddGlobalStatus {
    IDLE,
    LISTENING,
    THINKING,
    WORKING,
    WAITING,
    LOCAL_ONLY,
    CLOUD_ACTIVE,
    PAUSED,
    OFF,
    ERROR
}

enum class AIProviderMode {
    LOCAL_ONLY,
    AUTO,
    CLOUD_PREFERRED
}

enum class TaskStatus {
    PLANNED,
    IN_PROGRESS,
    WAITING,
    EXECUTED,
    VERIFYING,
    VERIFIED,
    FAILED,
    BLOCKED,
    PAUSED,
    CANCELLED,
    COMPLETED
}

enum class RuleBehavior {
    ALLOW_WITHOUT_ASKING,
    ALLOW_IF_PREAPPROVED,
    ASK_BEFORE_ACTION,
    HAND_OFF_TO_OWNER
}

enum class MemoryLayer {
    PREFERENCES,
    PROJECT,
    TASK,
    EPISODIC,
    CONNECTED_SOURCE
}

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val repository: String? = null,
    val branch: String = "main",
    val lastVerifiedCommit: String? = null,
    val currentGoal: String = "",
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val goal: String,
    val userInstructions: String = "",
    val status: TaskStatus = TaskStatus.PLANNED,
    val currentStep: String = "",
    val completionCriteria: String = "",
    val lastEvidence: String? = null,
    val failureCause: String? = null,
    val isRecurring: Boolean = false,
    val scheduledTime: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memories")
data class MemoryEntry(
    @PrimaryKey val id: String,
    val projectId: String? = null,
    val layer: MemoryLayer = MemoryLayer.PROJECT,
    val key: String,
    val value: String,
    val provenance: String = "USER", // USER, TOOL, MODEL, INFERENCE
    val isVerified: Boolean = true,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "failures")
data class FailureRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val projectId: String,
    val operation: String,
    val errorMessage: String,
    val suspectedCause: String,
    val confirmedCause: String? = null,
    val attemptedFix: String,
    val retryCondition: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey val id: String,
    val projectId: String? = null,
    val scope: String = "GLOBAL",
    val category: String, // COMMIT, PUSH, EMAIL, SCREEN, FILE
    val behavior: RuleBehavior = RuleBehavior.ASK_BEFORE_ACTION,
    val explanation: String = "",
    val isEnabled: Boolean = true
)

data class ToddState(
    val globalStatus: ToddGlobalStatus = ToddGlobalStatus.IDLE,
    val isPaused: Boolean = false,
    val isPowerOff: Boolean = false,
    val isOverlayVisible: Boolean = true,
    val activeProjectId: String? = "todd-main",
    val activeTaskId: String? = null,
    val aiMode: AIProviderMode = AIProviderMode.AUTO,
    val lastVerifiedAction: String = "Initialized system architecture",
    val nextPlannedAction: String = "Ready for owner instructions"
)
