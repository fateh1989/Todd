package com.todd

import com.todd.core.model.TaskStatus
import com.todd.core.model.ToddGlobalStatus
import com.todd.core.state.ToddStateMachine
import com.todd.data.repository.ToddRepository
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToddStateMachineTest {

    private lateinit var stateMachine: ToddStateMachine

    @Before
    fun setup() {
        stateMachine = ToddStateMachine()
    }

    @Test
    fun `task planning creates planned task`() {
        val task = stateMachine.planTask(
            taskId = "task-1",
            projectId = "test-project",
            title = "Test Task",
            goal = "Verify state machine",
            criteria = "Unit test passes"
        )

        assertEquals(TaskStatus.PLANNED, task.status)
        assertEquals("test-project", stateMachine.state.value.activeProjectId)
    }

    @Test
    fun `execution transitions task to IN_PROGRESS`() {
        val task = stateMachine.planTask("task-1", "p1", "Test", "Goal", "Criteria")
        val inProgress = stateMachine.startExecution(task)

        assertEquals(TaskStatus.IN_PROGRESS, inProgress.status)
        assertEquals(ToddGlobalStatus.WORKING, stateMachine.state.value.globalStatus)
    }

    @Test
    fun `executed task is NOT automatically verified`() {
        val task = stateMachine.planTask("task-1", "p1", "Test", "Goal", "Criteria")
        val inProgress = stateMachine.startExecution(task)
        val executed = stateMachine.markExecuted(inProgress, "Code applied successfully")

        assertEquals(TaskStatus.EXECUTED, executed.status)
        assertNull("Evidence must be null until explicit verification", executed.lastEvidence)
        assertNotEquals(TaskStatus.VERIFIED, executed.status)
    }

    @Test
    fun `verification requires positive proof`() {
        val task = stateMachine.planTask("task-1", "p1", "Test", "Goal", "Criteria")
        val executed = stateMachine.markExecuted(task, "Ran command")
        val verified = stateMachine.verifyTask(executed, evidence = "APK generated at app/build/outputs", isSuccess = true)

        assertEquals(TaskStatus.VERIFIED, verified.status)
        assertNotNull(verified.lastEvidence)
    }

    @Test
    fun `cannot complete task without verification`() {
        val task = stateMachine.planTask("task-1", "p1", "Test", "Goal", "Criteria")
        val executed = stateMachine.markExecuted(task, "Ran command")

        assertThrows(IllegalArgumentException::class.java) {
            stateMachine.completeTask(executed)
        }
    }

    @Test
    fun `hide overlay removes overlay without pausing or turning off Todd`() {
        stateMachine.showOverlay()
        assertTrue(stateMachine.state.value.isOverlayVisible)

        stateMachine.hideOverlay()
        assertFalse(stateMachine.state.value.isOverlayVisible)
        assertFalse(stateMachine.state.value.isPaused)
        assertFalse(stateMachine.state.value.isPowerOff)
    }

    @Test
    fun `power off stops Todd and sets status to OFF`() {
        stateMachine.powerOff()
        assertTrue(stateMachine.state.value.isPowerOff)
        assertTrue(stateMachine.state.value.isPaused)
        assertFalse(stateMachine.state.value.isOverlayVisible)
        assertEquals(ToddGlobalStatus.OFF, stateMachine.state.value.globalStatus)
    }
}
