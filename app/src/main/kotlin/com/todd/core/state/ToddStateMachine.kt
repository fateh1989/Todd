package com.todd.core.state

import com.todd.core.model.*
import com.todd.data.repository.ToddRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ToddStateMachine(
    private val repository: ToddRepository
) {
    private val _state = MutableStateFlow(ToddState())
    val state: StateFlow<ToddState> = _state.asStateFlow()

    fun planTask(taskId: String, projectId: String, title: String, goal: String, criteria: String): Task {
        val task = Task(
            id = taskId,
            projectId = projectId,
            title = title,
            goal = goal,
            status = TaskStatus.PLANNED,
            completionCriteria = criteria,
            currentStep = "Plan established: $goal"
        )
        _state.update {
            it.copy(
                globalStatus = ToddGlobalStatus.IDLE,
                activeProjectId = projectId,
                activeTaskId = taskId,
                nextPlannedAction = "Execute step 1 for: $title"
            )
        }
        return task
    }

    fun startExecution(task: Task): Task {
        check(!_state.value.isPaused) { "Cannot start execution while Todd is paused" }
        check(!_state.value.isPowerOff) { "Cannot execute while Todd is powered off" }

        val inProgress = task.copy(
            status = TaskStatus.IN_PROGRESS,
            currentStep = "Executing action for: ${task.title}",
            updatedAt = System.currentTimeMillis()
        )
        _state.update {
            it.copy(globalStatus = ToddGlobalStatus.WORKING)
        }
        return inProgress
    }

    fun markExecuted(task: Task, executionSummary: String): Task {
        // EXECUTED is explicitly NOT VERIFIED!
        val executed = task.copy(
            status = TaskStatus.EXECUTED,
            currentStep = "Executed: $executionSummary. Awaiting verification evidence.",
            updatedAt = System.currentTimeMillis()
        )
        _state.update {
            it.copy(globalStatus = ToddGlobalStatus.WAITING)
        }
        return executed
    }

    fun verifyTask(task: Task, evidence: String, isSuccess: Boolean, failureReason: String? = null): Task {
        return if (isSuccess) {
            val verified = task.copy(
                status = TaskStatus.VERIFIED,
                lastEvidence = evidence,
                currentStep = "Verified with proof: $evidence",
                updatedAt = System.currentTimeMillis()
            )
            _state.update {
                it.copy(
                    globalStatus = ToddGlobalStatus.IDLE,
                    lastVerifiedAction = "Verified: ${task.title} ($evidence)"
                )
            }
            verified
        } else {
            val failed = task.copy(
                status = TaskStatus.FAILED,
                failureCause = failureReason ?: "Verification check failed",
                currentStep = "Failed verification: $failureReason",
                updatedAt = System.currentTimeMillis()
            )
            _state.update {
                it.copy(
                    globalStatus = ToddGlobalStatus.ERROR,
                    nextPlannedAction = "Diagnose failure cause: $failureReason"
                )
            }
            failed
        }
    }

    fun completeTask(task: Task): Task {
        // Verification must precede completion
        require(task.status == TaskStatus.VERIFIED) {
            "Task cannot be marked COMPLETED without prior verified status and evidence."
        }
        val completed = task.copy(
            status = TaskStatus.COMPLETED,
            currentStep = "Task completed successfully and verified.",
            updatedAt = System.currentTimeMillis()
        )
        _state.update {
            it.copy(
                globalStatus = ToddGlobalStatus.IDLE,
                activeTaskId = null,
                nextPlannedAction = "Ready for next objective"
            )
        }
        return completed
    }

    fun pause() {
        _state.update {
            it.copy(
                isPaused = true,
                globalStatus = ToddGlobalStatus.PAUSED
            )
        }
    }

    fun resume() {
        _state.update {
            it.copy(
                isPaused = false,
                globalStatus = ToddGlobalStatus.IDLE
            )
        }
    }

    fun powerOff() {
        _state.update {
            it.copy(
                isPowerOff = true,
                isPaused = true,
                isOverlayVisible = false,
                globalStatus = ToddGlobalStatus.OFF,
                activeTaskId = null,
                nextPlannedAction = "Todd is OFF. Power on from app to resume."
            )
        }
    }

    fun powerOn() {
        _state.update {
            it.copy(
                isPowerOff = false,
                isPaused = false,
                isOverlayVisible = true,
                globalStatus = ToddGlobalStatus.IDLE,
                nextPlannedAction = "Todd powered on. Standing by."
            )
        }
    }

    fun hideOverlay() {
        // Hiding overlay does NOT pause or power off Todd!
        _state.update { it.copy(isOverlayVisible = false) }
    }

    fun showOverlay() {
        _state.update { it.copy(isOverlayVisible = true) }
    }

    fun setAIMode(mode: AIProviderMode) {
        _state.update { it.copy(aiMode = mode) }
    }
}
