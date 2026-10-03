package com.todd

import com.todd.core.model.RuleBehavior
import com.todd.core.rules.*
import org.junit.Assert.*
import org.junit.Test

class RulesEngineTest {

    @Test
    fun `destructive operations always require handoff to owner`() {
        val engine = RulesEngine()
        val result = engine.evaluate(
            ActionRequest(
                category = ActionCategory.GIT_FORCE_PUSH,
                projectId = "todd-main",
                target = "origin/main",
                dataSummary = "Force push commit 5583d33",
                isPreApprovedInInstruction = true // even if user said pre-approved, safety rule catches it
            )
        )

        assertEquals(RuleBehavior.HAND_OFF_TO_OWNER, result.behavior)
        assertFalse(result.isAllowed)
        assertTrue(result.requiresPrompt)
    }

    @Test
    fun `pre-approved action proceeds without prompt if flag is true`() {
        val engine = RulesEngine()
        val result = engine.evaluate(
            ActionRequest(
                category = ActionCategory.GIT_COMMIT_FEATURE_BRANCH,
                projectId = "todd-main",
                target = "feature/test",
                dataSummary = "Commit verified test fix",
                isPreApprovedInInstruction = true
            )
        )

        assertEquals(RuleBehavior.ALLOW_IF_PREAPPROVED, result.behavior)
        assertTrue(result.isAllowed)
        assertFalse(result.requiresPrompt)
    }

    @Test
    fun `read actions are allowed without asking`() {
        val engine = RulesEngine()
        val result = engine.evaluate(
            ActionRequest(
                category = ActionCategory.GIT_READ,
                projectId = "todd-main",
                target = "fateh1989/Todd",
                dataSummary = "Fetch branch heads",
                isPreApprovedInInstruction = false
            )
        )

        assertEquals(RuleBehavior.ALLOW_WITHOUT_ASKING, result.behavior)
        assertTrue(result.isAllowed)
        assertFalse(result.requiresPrompt)
    }
}
