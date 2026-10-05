package com.todd.core.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.todd.core.ai.AIRouter
import com.todd.core.ai.AIRequest
import com.todd.core.ai.FirebaseRuntimeConfig
import com.todd.core.tools.GitHubCredentialStore
import com.todd.core.tools.GitHubTool
import com.todd.data.repository.ToddRepository
import com.todd.service.accessibility.ToddAccessibilityService
import com.todd.service.screen.ScreenCaptureStore

enum class DiagnosticStatus {
    PASS,
    WARN,
    FAIL
}

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val detail: String
)

data class ToddDiagnosticReport(
    val checks: List<DiagnosticCheck>,
    val generatedAt: Long = System.currentTimeMillis()
) {
    val passCount: Int get() = checks.count { it.status == DiagnosticStatus.PASS }
    val warnCount: Int get() = checks.count { it.status == DiagnosticStatus.WARN }
    val failCount: Int get() = checks.count { it.status == DiagnosticStatus.FAIL }
}

class ToddDiagnostics(
    private val context: Context,
    private val repository: ToddRepository,
    private val aiRouter: AIRouter,
    private val githubTool: GitHubTool,
    private val githubCredentialStore: GitHubCredentialStore
) {

    suspend fun run(): ToddDiagnosticReport {
        val checks = mutableListOf<DiagnosticCheck>()

        val project = runCatching { repository.getProjectById("todd-main") }.getOrNull()
        checks += DiagnosticCheck(
            id = "memory",
            title = "الذاكرة المحلية",
            status = if (project != null) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
            detail = if (project != null) {
                "قاعدة Room متاحة ومشروع Todd محفوظ."
            } else {
                "تعذر العثور على مشروع Todd المحلي."
            }
        )

        val firebase = FirebaseRuntimeConfig.current()
        checks += DiagnosticCheck(
            id = "firebase",
            title = "إعداد الذكاء السحابي",
            status = if (firebase.configured) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (firebase.configured) {
                "Firebase مهيأ للمشروع ${firebase.projectId ?: "غير معروف"}."
            } else {
                firebase.reason ?: "Firebase غير مهيأ."
            }
        )

        if (firebase.configured) {
            val cloud = aiRouter.cloudProvider.generateText(
                AIRequest(
                    prompt = "Reply with exactly: TODD_DIAGNOSTIC_OK",
                    systemPrompt = "Todd connectivity diagnostic. Return only the requested token.",
                    temperature = 0f,
                    maxTokens = 32
                )
            )
            checks += DiagnosticCheck(
                id = "cloud",
                title = "الاتصال السحابي الحقيقي",
                status = if (cloud.isSuccess) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                detail = cloud.fold(
                    onSuccess = { "تم استلام رد فعلي من مزود الذكاء السحابي." },
                    onFailure = { it.message ?: "فشل طلب الذكاء السحابي." }
                )
            )
        } else {
            checks += DiagnosticCheck(
                id = "cloud",
                title = "الاتصال السحابي الحقيقي",
                status = DiagnosticStatus.WARN,
                detail = "لم يُختبر لأن Firebase غير مهيأ بعد."
            )
        }

        val localAvailable = runCatching { aiRouter.localProvider.isAvailable() }.getOrDefault(false)
        checks += DiagnosticCheck(
            id = "local-ai",
            title = "الذكاء المحلي",
            status = if (localAvailable) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (localAvailable) {
                "النموذج المحلي متاح على هذا الجهاز."
            } else {
                "النموذج المحلي غير جاهز أو الجهاز لا يدعمه حالياً."
            }
        )

        if (githubCredentialStore.hasToken()) {
            val git = githubTool.getRepositoryInfo("fateh1989/Todd", "main")
            checks += DiagnosticCheck(
                id = "github",
                title = "GitHub",
                status = if (git.isSuccess) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                detail = git.fold(
                    onSuccess = { "الاتصال يعمل. آخر Commit: ${it.latestCommitSha.take(12)}" },
                    onFailure = { it.message ?: "تعذر قراءة مستودع Todd." }
                )
            )
        } else {
            checks += DiagnosticCheck(
                id = "github",
                title = "GitHub",
                status = DiagnosticStatus.WARN,
                detail = "لم يُضف تفويض GitHub بعد؛ البرمجة الذاتية والمهام البعيدة لن تعمل بالكامل."
            )
        }

        val accessibilityActive = ToddAccessibilityService.instance != null
        checks += DiagnosticCheck(
            id = "accessibility",
            title = "فهم عناصر الشاشة",
            status = if (accessibilityActive) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (accessibilityActive) {
                "خدمة إمكانية الوصول متصلة."
            } else {
                "فعّل خدمة Todd من إعدادات إمكانية الوصول."
            }
        )

        val capture = ScreenCaptureStore.state.value
        checks += DiagnosticCheck(
            id = "visual-screen",
            title = "الرؤية البصرية للشاشة",
            status = if (capture.isRunning) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (capture.isRunning) {
                "MediaProjection يعمل؛ آخر لقطة ${capture.width}×${capture.height}."
            } else {
                "الرؤية البصرية غير مشغلة الآن."
            }
        )

        val overlayAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        checks += DiagnosticCheck(
            id = "overlay",
            title = "الزر العائم",
            status = if (overlayAllowed) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (overlayAllowed) {
                "صلاحية الظهور فوق التطبيقات متاحة."
            } else {
                "صلاحية الظهور فوق التطبيقات غير مفعلة."
            }
        )

        val microphoneAllowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        checks += DiagnosticCheck(
            id = "microphone",
            title = "الميكروفون",
            status = if (microphoneAllowed) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (microphoneAllowed) {
                "صلاحية الميكروفون متاحة."
            } else {
                "صلاحية الميكروفون غير ممنوحة بعد."
            }
        )

        val notificationAccess =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        checks += DiagnosticCheck(
            id = "notifications",
            title = "سياق الإشعارات",
            status = if (notificationAccess) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (notificationAccess) {
                "Todd يستطيع استقبال سياق الإشعارات."
            } else {
                "وصول الإشعارات غير مفعل."
            }
        )

        val inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboardEnabled = inputMethodManager.enabledInputMethodList.any {
            it.packageName == context.packageName
        }
        checks += DiagnosticCheck(
            id = "keyboard",
            title = "لوحة Todd",
            status = if (keyboardEnabled) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (keyboardEnabled) {
                "Todd ظاهر ضمن لوحات المفاتيح المفعلة."
            } else {
                "لوحة Todd ليست مفعلة في إعدادات النظام بعد."
            }
        )

        return ToddDiagnosticReport(checks)
    }
}
