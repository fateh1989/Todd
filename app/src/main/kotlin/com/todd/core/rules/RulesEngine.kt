package com.todd.core.rules

import com.todd.core.model.Rule
import com.todd.core.model.RuleBehavior

enum class ActionCategory {
    GIT_READ,
    SCREEN_CONTEXT_READ,
    GIT_COMMIT_FEATURE_BRANCH,
    GIT_PUSH_MAIN,
    GIT_DELETE_BRANCH,
    GIT_FORCE_PUSH,
    SEND_COMMUNICATION,
    UPLOAD_SCREENSHOT_CLOUD,
    RUN_SHELL_COMMAND
}

data class ActionRequest(
    val category: ActionCategory,
    val projectId: String,
    val target: String,
    val dataSummary: String,
    val isPreApprovedInInstruction: Boolean = false
)

data class EvaluationResult(
    val behavior: RuleBehavior,
    val requiresPrompt: Boolean,
    val promptMessage: String? = null,
    val isAllowed: Boolean
)

class RulesEngine(private val customRules: List<Rule> = emptyList()) {

    fun evaluate(request: ActionRequest): EvaluationResult {
        // Built-in hard safety rules cannot be bypassed by custom rules
        if (request.category == ActionCategory.GIT_DELETE_BRANCH ||
            request.category == ActionCategory.GIT_FORCE_PUSH
        ) {
            return EvaluationResult(
                behavior = RuleBehavior.HAND_OFF_TO_OWNER,
                requiresPrompt = true,
                promptMessage = "عملية عالية الخطورة: طلب Todd تنفيذ (${request.category}) على الهدف (${request.target}). تتطلب هذه العملية موافقة يدوية صريحة ولا يمكن تفويضها.",
                isAllowed = false
            )
        }

        // Check if there is an active matching rule
        val matchingRule = customRules.firstOrNull {
            it.isEnabled && it.category == request.category.name && (it.projectId == null || it.projectId == request.projectId)
        }

        val behavior = matchingRule?.behavior ?: defaultBehaviorFor(request.category)

        return when (behavior) {
            RuleBehavior.ALLOW_WITHOUT_ASKING -> EvaluationResult(
                behavior = behavior,
                requiresPrompt = false,
                isAllowed = true
            )
            RuleBehavior.ALLOW_IF_PREAPPROVED -> {
                if (request.isPreApprovedInInstruction) {
                    EvaluationResult(
                        behavior = behavior,
                        requiresPrompt = false,
                        isAllowed = true
                    )
                } else {
                    EvaluationResult(
                        behavior = behavior,
                        requiresPrompt = true,
                        promptMessage = "الموافقة المسبقة مطلوبة: يرغب Todd في تنفيذ ${request.category} على ${request.target}. هل توافق على التنفيذ؟",
                        isAllowed = false
                    )
                }
            }
            RuleBehavior.ASK_BEFORE_ACTION -> EvaluationResult(
                behavior = behavior,
                requiresPrompt = true,
                promptMessage = "يرغب Todd في تنفيذ ${request.category} على ${request.target} (${request.dataSummary}). هذه العملية ستؤثر على الحالة الخارجية. هل تؤكد المتابعة؟",
                isAllowed = false
            )
            RuleBehavior.HAND_OFF_TO_OWNER -> EvaluationResult(
                behavior = behavior,
                requiresPrompt = true,
                promptMessage = "العملية تتطلب تدخل المالك المباشر: ${request.category} على ${request.target}.",
                isAllowed = false
            )
        }
    }

    private fun defaultBehaviorFor(category: ActionCategory): RuleBehavior {
        return when (category) {
            ActionCategory.GIT_READ -> RuleBehavior.ALLOW_WITHOUT_ASKING
            ActionCategory.SCREEN_CONTEXT_READ -> RuleBehavior.ALLOW_WITHOUT_ASKING
            ActionCategory.GIT_COMMIT_FEATURE_BRANCH -> RuleBehavior.ALLOW_IF_PREAPPROVED
            ActionCategory.GIT_PUSH_MAIN -> RuleBehavior.ASK_BEFORE_ACTION
            ActionCategory.SEND_COMMUNICATION -> RuleBehavior.ASK_BEFORE_ACTION
            ActionCategory.UPLOAD_SCREENSHOT_CLOUD -> RuleBehavior.ASK_BEFORE_ACTION
            ActionCategory.RUN_SHELL_COMMAND -> RuleBehavior.ALLOW_IF_PREAPPROVED
            else -> RuleBehavior.ASK_BEFORE_ACTION
        }
    }
}
