package com.todd

import com.todd.core.model.Rule
import com.todd.core.model.RuleBehavior
import com.todd.core.rules.ActionCategory
import com.todd.core.rules.ActionRequest
import com.todd.core.rules.RulesEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineDynamicRulesTest {

    @Test
    fun `owner rule can allow a preapproved main push`() {
        val engine = RulesEngine()
        val request = ActionRequest(
            category = ActionCategory.GIT_PUSH_MAIN,
            projectId = "todd-main",
            target = "fateh1989/Todd",
            dataSummary = "owner requested push",
            isPreApprovedInInstruction = true
        )

        assertFalse(engine.evaluate(request).isAllowed)

        engine.replaceCustomRules(
            listOf(
                Rule(
                    id = "owner-main-push",
                    category = ActionCategory.GIT_PUSH_MAIN.name,
                    behavior = RuleBehavior.ALLOW_IF_PREAPPROVED,
                    explanation = "Owner configured this rule."
                )
            )
        )

        assertTrue(engine.evaluate(request).isAllowed)
    }

    @Test
    fun `hard safety rule still blocks force push`() {
        val engine = RulesEngine(
            listOf(
                Rule(
                    id = "unsafe-force",
                    category = ActionCategory.GIT_FORCE_PUSH.name,
                    behavior = RuleBehavior.ALLOW_WITHOUT_ASKING
                )
            )
        )

        val result = engine.evaluate(
            ActionRequest(
                category = ActionCategory.GIT_FORCE_PUSH,
                projectId = "todd-main",
                target = "fateh1989/Todd",
                dataSummary = "force push",
                isPreApprovedInInstruction = true
            )
        )

        assertFalse(result.isAllowed)
        assertTrue(result.requiresPrompt)
    }
}
