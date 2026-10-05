package com.todd.core.agent

import com.todd.core.ai.FirebaseRuntimeConfig
import com.todd.core.model.TaskStatus
import com.todd.core.state.ToddStateMachine
import com.todd.data.repository.ToddRepository
import com.todd.core.tools.GitHubCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns autonomous coding work outside the Activity/Compose lifecycle.
 *
 * Closing or recreating MainActivity therefore does not cancel an in-flight coding loop.
 * Durable process-death checkpoints are handled separately; this coordinator intentionally
 * does not claim process-restart recovery until a persisted iteration checkpoint exists.
 */
class AutonomousTaskCoordinator(
    private val repository: ToddRepository,
    private val stateMachine: ToddStateMachine,
    private val githubCredentialStore: GitHubCredentialStore,
    private val codingLoop: AutonomousCodingLoop,
    private val scope: CoroutineScope
) {
    private val runningTaskIds = ConcurrentHashMap.newKeySet<String>()

    fun start(
        projectId: String,
        title: String,
        goal: String,
        maxIterations: Int = 4
    ): String {
        val taskId = "code-${System.currentTimeMillis()}-${System.nanoTime()}"
        runningTaskIds += taskId

        scope.launch {
            try {
                val task = stateMachine.planTask(
                    taskId = taskId,
                    projectId = projectId,
                    title = title,
                    goal = goal,
                    criteria = "Todd must produce a commit and remote Android verification must succeed"
                )
                repository.saveTask(task)

                when {
                    !githubCredentialStore.hasToken() -> {
                        repository.updateTask(
                            task.copy(
                                status = TaskStatus.BLOCKED,
                                currentStep = "أضف تفويض GitHub من الإعدادات حتى يستطيع Todd قراءة وكتابة المستودع.",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        return@launch
                    }

                    !FirebaseRuntimeConfig.current().configured -> {
                        repository.updateTask(
                            task.copy(
                                status = TaskStatus.BLOCKED,
                                currentStep = "نسخة التطبيق الحالية لا تحتوي إعداد Firebase الحقيقي اللازم للذكاء السحابي.",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        return@launch
                    }
                }

                val project = repository.getProjectById(projectId)
                val repoName = project?.repository
                    ?: if (projectId == "todd-main") "fateh1989/Todd" else null
                val branch = project?.branch?.ifBlank { "main" } ?: "main"

                if (repoName == null) {
                    repository.updateTask(
                        task.copy(
                            status = TaskStatus.BLOCKED,
                            currentStep = "لا يوجد مستودع مرتبط بهذا المشروع.",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return@launch
                }

                repository.updateTask(
                    task.copy(
                        status = TaskStatus.IN_PROGRESS,
                        currentStep = "Todd يفحص المستودع ويحدد الملفات اللازمة قبل أي تعديل.",
                        updatedAt = System.currentTimeMillis()
                    )
                )

                val result = codingLoop.run(
                    AutonomousCodingRequest(
                        taskId = task.id,
                        projectId = projectId,
                        repository = repoName,
                        branch = branch,
                        objective = goal,
                        completionCriteria = task.completionCriteria,
                        maxIterations = maxIterations
                    )
                )

                result.fold(
                    onSuccess = { coding ->
                        repository.updateTask(
                            task.copy(
                                status = if (coding.success) TaskStatus.VERIFIED else TaskStatus.FAILED,
                                currentStep = if (coding.success) {
                                    "اكتمل التعديل البرمجي وتحقق البناء والاختبار عن بُعد."
                                } else {
                                    "انتهت دورة البرمجة بدون تحقق ناجح."
                                },
                                lastEvidence = coding.evidenceUrl
                                    ?: coding.finalCommitSha
                                    ?: task.lastEvidence,
                                failureCause = coding.failureMessage,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    },
                    onFailure = { error ->
                        repository.updateTask(
                            task.copy(
                                status = TaskStatus.FAILED,
                                currentStep = "توقفت دورة البرمجة الذاتية بسبب خطأ فعلي.",
                                failureCause = error.message,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                )
            } finally {
                runningTaskIds -= taskId
            }
        }

        return taskId
    }

    fun isRunning(taskId: String): Boolean = taskId in runningTaskIds
}
