Warning: truncated output (original token count: 26396)
Total output lines: 2283

package com.todd.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.todd.ToddApplication
import com.todd.core.model.*
import com.todd.core.ai.GeminiLiveState
import com.todd.core.ai.LiveTranscriptItem
import com.todd.core.ai.FirebaseRuntimeConfig
import com.todd.core.ai.LocalOnDeviceAIProvider
import com.todd.core.ai.GeminiCloudModel
import com.todd.core.ai.GeminiUsageSnapshot
import com.todd.core.remote.RemoteExecutionMode
import com.todd.core.rules.ActionCategory
import com.todd.core.remote.RemoteJobRequest
import com.todd.core.agent.AutonomousCodingRequest
import com.todd.core.diagnostics.ToddDiagnosticReport
import com.todd.core.diagnostics.DiagnosticStatus
import com.todd.BuildConfig
import com.todd.core.security.SigningIdentity
import com.todd.service.overlay.FloatingToddService
import com.todd.service.accessibility.ToddAccessibilityService
import com.todd.service.screen.ScreenCaptureService
import com.todd.service.screen.ScreenCaptureStore
import com.todd.service.context.DeviceContextProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var settingsRefreshVersion by mutableIntStateOf(0)

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceSession()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            settingsRefreshVersion
            ToddMainScreen(
                accessibilityEnabled = isToddAccessibilityEnabled(),
                notificationAccessEnabled = NotificationManagerCompat
                    .getEnabledListenerPackages(this)
                    .contains(packageName),
                overlayEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this),
                onStartOverlay = { checkOverlayPermissionAndStart() },
                onOpenAccessibility = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onOpenNotificationAccess = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                onStartVoice = { requestOrStartVoice() },
                onStopVoice = { stopVoiceSession() },
                onStartVisualScreen = { requestVisualScreenCapture() },
                onStopVisualScreen = { stopVisualScreenCapture() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        settingsRefreshVersion += 1
    }

    private fun isToddAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ToddAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled
            .split(':')
            .any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestVisualScreenCapture() {
        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopVisualScreenCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestOrStartVoice() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceSession()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceSession() {
        lifecycleScope.launch {
            val app = ToddApplication.instance
            app.liveClient.startSession(app.stateMachine.state.value.aiMode)
        }
    }

    private fun stopVoiceSession() {
        ToddApplication.instance.liveClient.endSession()
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }
        }
        val serviceIntent = Intent(this, FloatingToddService::class.java).apply { action = FloatingToddService.ACTION_SHOW }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToddMainScreen(
    accessibilityEnabled: Boolean,
    notificationAccessEnabled: Boolean,
    overlayEnabled: Boolean = false,
    onStartOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onStartVisualScreen: () -> Unit,
    onStopVisualScreen: () -> Unit
) {
    val app = ToddApplication.instance
    val stateMachine = app.stateMachine
    val state by stateMachine.state.collectAsState()
    val liveState by app.liveClient.state.collectAsState()
    val liveTranscripts by app.liveClient.transcripts.collectAsState()
    val liveMuted by app.liveClient.isMuted.collectAsState()
    val visualScreen by ScreenCaptureStore.state.collectAsState()
    val geminiUsage by app.geminiUsageTracker.usage.collectAsState()
    val activeRules by app.repository.getActiveRules().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    val tasks by app.repository.getAllTasks().collectAsState(initial = emptyList())
    val schedules by app.repository.getAllSchedules().collectAsState(initial = emptyList())
    val projects by app.repository.getAllProjects().collectAsState(initial = emptyList())
    val projectId = state.activeProjectId ?: "todd-main"
    val memories by app.repository.getMemoriesForProject(projectId).collectAsState(initial = emptyList())
    val chatMessages = remember(memories) {
        memories
            .filter { it.layer == MemoryLayer.EPISODIC && it.key.startsWith("chat:") }
            .sortedBy { it.timestamp }
    }
    val learnedMemories = remember(memories) {
        memories
            .filter {
                it.key.startsWith("learned:") &&
                    (it.layer == MemoryLayer.PREFERENCES || it.layer == MemoryLayer.PROJECT)
            }
            .sortedByDescending { it.timestamp }
    }
    val codingLogs = remember(memories) {
        memories
            .filter { it.layer == MemoryLayer.TASK && it.key.startsWith("coding-log:") }
            .sortedBy { it.timestamp }
    }
    var isChatBusy by remember { mutableStateOf(false) }
    var localAIStatus by remember { mutableStateOf("لم يتم فحص النموذج المحلي بعد") }
    var localAIBusy by remember { mutableStateOf(false) }
    var cloudAIStatus by remember { mutableStateOf("لم يتم اختبار الاتصال السحابي بعد") }
    var cloudAIBusy by remember { mutableStateOf(false) }
    var diagnosticsBusy by remember { mutableStateOf(false) }
    var diagnosticsReport by remember { mutableStateOf<ToddDiagnosticReport?>(null) }
    var firebaseRuntimeStatus by remember { mutableStateOf(FirebaseRuntimeConfig.current()) }
    var geminiApiKeyConfigured by remember { mutableStateOf(app.geminiApiKeyStore.hasKey()) }
    var selectedGeminiModel by remember { mutableStateOf(app.geminiModelPreferenceStore.get()) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("T", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Todd", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "المساعد الهجين المستمر",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Global Pause / Resume Control
                    IconButton(onClick = {
                        if (state.isPaused) stateMachine.resume() else stateMachine.pause()
                    }) {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Pause/Resume",
                            tint = if (state.isPaused) Color(0xFFF59E0B) else Color.White
                        )
                    }
                    // Power Control
                    IconButton(onClick = {
                        if (state.isPowerOff) stateMachine.powerOn() else stateMachine.powerOff()
                    }) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = if (state.isPowerOff) Color(0xFFEF4444) else Color(0xFF10B981)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!imeVisible) {
                NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "الرئيسية") },
                    label = { Text("الرئيسية") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "المشاريع") },
                    label = { Text("المشاريع") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = "النشاط") },
                    label = { Text("النشاط") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "الإعدادات") },
                    label = { Text("الإعدادات") }
                )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // Keep the chat screen focused on the conversation. Detailed status stays
            // available on the other tabs without consuming the home/chat viewport.
            if (selectedTab != 0) {
                StatusCard(state, onStartOverlay)
                Spacer(modifier = Modifier.height(6.dp))
            }

            when (selectedTab) {
                0 -> HomeDashboard(
                    state = state,
                    messages = chatMessages,
                    isBusy = isChatBusy,
                    voiceState = liveState,
                    voiceTranscripts = liveTranscripts,
                    voiceMuted = liveMuted,
                    selectedGeminiModel = selectedGeminiModel,
                    geminiUsage = geminiUsage,
                    onGeminiModelChange = { model ->
                        app.geminiModelPreferenceStore.set(model)
                        selectedGeminiModel = model
                        stateMachine.setAIMode(AIProviderMode.CLOUD_PREFERRED)
                        cloudAIStatus = "تم اختيار ${model.displayName} للتشغيل السحابي"
                    },
                    onToggleVoice = {
                        if (liveState == GeminiLiveState.DISCONNECTED || liveState == GeminiLiveState.ERROR) {
                            onStartVoice()
                        } else {
                            onStopVoice()
                        }
                    },
                    onToggleVoiceMute = { app.liveClient.toggleMute() },
                    onSendMessage = { message, translationMode, researchMode ->
                        if (message.isNotBlank() && !isChatBusy) {
                            scope.launch {
                                isChatBusy = true
                                val now = System.currentTimeMillis()
                                val contextBeforeMessage = app.repository.buildProjectContext(projectId)

                                app.repository.saveMemory(
                                    MemoryEntry(
                                        id = "chat-user-${System.nanoTime()}",
                                        projectId = projectId,
                                        layer = MemoryLayer.EPISODIC,
                                        key = "chat:user:$now",
                                        value = message,
                                        provenance = "USER",
                                        isVerified = true
                                    )
                                )
                                app.memoryLearningEngine.learnExplicitInstruction(
                                    projectId = projectId,
                                    userMessage = message
                                )

                                val result = app.textAgent.respond(
                                    com.todd.core.ai.AIRequest(
                                        prompt = message,
                                        systemPrompt = if (translationMode) {
                                            "أنت في وضع محادثة الترجمة. ترجم كلام المستخدم مباشرة بين العربية والإنجليزية، أو إلى اللغة التي يحددها. أعد الترجمة فقط دون شرح إضافي، وحافظ على الأسماء والأرقام والمعنى."
                                        } else {
                                            null
                                        },
                                        projectContext = contextBeforeMessage,
                                        screenContext = DeviceContextProvider.currentTextContext().ifBlank { null },
                                        screenImagePath = ScreenCaptureStore.latestFile()?.absolutePath,
                                        webResearch = researchMode
                                    ),
                                    state.aiMode
                                )

                                val aiResponse = result.getOrNull()
                                val reply = if (aiResponse != null) {
                                    buildString {
                                        append(aiResponse.text)
                                        if (aiResponse.sources.isNotEmpty()) {
                                            appendLine()
                                            appendLine()
                                            appendLine("المصادر:")
                                            aiResponse.sources.take(8).forEachIndexed { index, source ->
                                                append(index + 1)
                                                append(". ")
                                                append(source.title ?: source.domain ?: "مصدر")
                                                append(" — ")
                                                appendLine(source.url)
                                                source.excerpt?.let { excerpt ->
                                                    appendLine("   مقتطف من الصفحة (للمراجعة، وليس تحققًا كاملًا):")
                                                    appendLine("   ${excerpt.take(700)}")
                                                }
                                            }
                                        }
                                    }.trim()
                                } else {
                                    "تعذر إكمال الطلب الآن: ${result.exceptionOrNull()?.message ?: "خطأ غير معروف"}"
                                }

                                app.repository.saveMemory(
                                    MemoryEntry(
                                        id = "chat-todd-${System.nanoTime()}",
                                        projectId = projectId,
                                        layer = MemoryLayer.EPISODIC,
                                        key = "chat:todd:${System.currentTimeMillis()}",
                                        value = reply,
                                        provenance = if (aiResponse != null) "MODEL" else "SYSTEM",
                                        isVerified = false
                                    )
                                )

                                aiResponse?.sources?.forEachIndexed { index, source ->
                                    app.repository.saveMemory(
                                        MemoryEntry(
                                            id = "source-${System.nanoTime()}-$index",
                                            projectId = projectId,
                                            layer = MemoryLayer.CONNECTED_SOURCE,
                                            key = "source:${System.currentTimeMillis()}:$index",
                                            value = buildString {
                                                source.title?.let { append(it).append(" | ") }
                                                source.domain?.let { append(it).append(" | ") }
                                                append(source.url)
                                            },
                                            provenance = "TOOL",
                                            // A search citation or fetched excerpt is evidence to inspect,
                                            // not proof that every saved claim is correct.
                                            isVerified = false
                                        )
                                    )
                                }
                                isChatBusy = false
                            }
                        }
                    },
                    onTaskAction = { title, goal ->
                        scope.launch {
                            val now = System.currentTimeMillis()
                            app.repository.saveMemory(
                                MemoryEntry(
                                    id = "chat-user-${System.nanoTime()}",
                                    projectId = projectId,
                                    layer = MemoryLayer.EPISODIC,
                                    key = "chat:user:$now",
                                    value = goal,
                                    provenance = "USER",
                                    isVerified = true
                                )
                            )
                            val task = stateMachine.planTask(
                                taskId = "task-${System.currentTimeMillis()}",
                                projectId = projectId,
                                title = title,
                                goal = goal,
                                criteria = "GitHub Actions verification must complete with evidence"
                            )
                           …14396 tokens truncated…asswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (geminiApiKey.isNotBlank()) {
                        onSaveGeminiApiKey(geminiApiKey)
                        geminiApiKey = ""
                    }
                },
                enabled = geminiApiKey.isNotBlank()
            ) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("حفظ وتشغيل الذكاء النصي")
            }
        } else {
            OutlinedButton(onClick = onClearGeminiApiKey) {
                Text("مسح مفتاح Gemini")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "اختبار Gemini: $cloudAIStatus",
            fontSize = 12.sp,
            color = Color(0xFFCBD5E1)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCheckCloudAI,
            enabled = geminiApiKeyConfigured && !cloudAIBusy
        ) {
            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(if (cloudAIBusy) "جارٍ الاختبار..." else "اختبار Gemini الآن")
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("حالة الذكاء", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            when {
                geminiApiKeyConfigured ->
                    "الصوت المباشر: مهيأ عبر Gemini Live المباشر باستخدام مفتاح Gemini API."
                firebaseConfigured ->
                    "الصوت المباشر: Firebase مهيأ" +
                        (firebaseProjectId?.let { " • المشروع: $it" } ?: "")
                else ->
                    "الصوت المباشر غير مهيأ بعد."
            },
            fontSize = 12.sp,
            color = if (geminiApiKeyConfigured || firebaseConfigured) {
                Color(0xFF10B981)
            } else {
                Color(0xFFF59E0B)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        if (!firebaseConfigured && !geminiApiKeyConfigured) {
            Text(
                "يمكن تشغيل الصوت المباشر بإضافة مفتاح Gemini API أعلاه. Firebase مسار احتياطي اختياري.",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = firebaseConfigJson,
                onValueChange = { firebaseConfigJson = it },
                label = { Text("إعداد Firebase (google-services.json)") },
                minLines = 3,
                maxLines = 6,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (firebaseConfigJson.isNotBlank()) {
                            onSaveFirebaseConfig(firebaseConfigJson)
                            firebaseConfigJson = ""
                        }
                    },
                    enabled = firebaseConfigJson.isNotBlank()
                ) {
                    Text("حفظ وتشغيل الذكاء السحابي")
                }
                OutlinedButton(
                    onClick = {
                        firebaseFileLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream")
                        )
                    }
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("اختيار الملف")
                }
            }
        } else if (firebaseConfigured) {
            OutlinedButton(onClick = onClearFirebaseConfig) {
                Text("مسح إعداد Firebase المحلي")
            }
        } else {
            Text(
                "Firebase غير مطلوب للصوت عند استخدام مفتاح Gemini API المباشر.",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "الذكاء المحلي: $localAIStatus",
            fontSize = 12.sp,
            color = Color(0xFFCBD5E1)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCheckLocalAI,
                enabled = !localAIBusy
            ) {
                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("فحص المحلي")
            }
            Button(
                onClick = onPrepareLocalAI,
                enabled = !localAIBusy
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(if (localAIBusy) "جارٍ التجهيز..." else "تجهيز النموذج")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("ذاكرة Todd المتعلّمة", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            if (learnedMemories.isEmpty()) {
                "لا توجد تعليمات دائمة تعلّمها Todd منك بعد."
            } else {
                "هذه تعليمات وتفضيلات حفظها Todd من كلامك. يمكنك مراجعتها أو حذف ما لم تعد تريده."
            },
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )

        learnedMemories.take(12).forEach { memory ->
            Spacer(modifier = Modifier.height(7.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (memory.layer == MemoryLayer.PROJECT) "ذاكرة المشروع" else "تفضيل عام",
                            fontSize = 10.sp,
                            color = Color(0xFF818CF8)
                        )
                        Text(memory.value, fontSize = 12.sp, color = Color.White)
                    }
                    IconButton(onClick = { pendingMemoryDelete = memory }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "حذف الذاكرة",
                            tint = Color(0xFFF87171)
                        )
                    }
                }
            }
        }

        if (learnedMemories.size > 12) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "تظهر أحدث 12 ذاكرة من أصل ${learnedMemories.size}.",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("صلاحيات تنفيذ Todd", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "أنت تحدد ما الذي ينفذه Todd مباشرة وما الذي يجب أن يطلب موافقتك عليه.",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val shellRule = activeRules.firstOrNull {
            it.category == ActionCategory.RUN_SHELL_COMMAND.name
        }
        val shellPreapproved = shellRule?.behavior
            ?.let { it == RuleBehavior.ALLOW_IF_PREAPPROVED || it == RuleBehavior.ALLOW_WITHOUT_ASKING }
            ?: true

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("تنفيذ مهام البرمجة التي أطلبها", color = Color.White, fontSize = 13.sp)
                Text(
                    "إذا طلبت صراحةً إصلاحًا أو بناءً أو تحققًا، يبدأ Todd المهمة دون سؤال ثانٍ.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = shellPreapproved,
                onCheckedChange = { enabled ->
                    onSaveRule(
                        Rule(
                            id = "owner-permission-run-shell",
                            category = ActionCategory.RUN_SHELL_COMMAND.name,
                            behavior = if (enabled) {
                                RuleBehavior.ALLOW_IF_PREAPPROVED
                            } else {
                                RuleBehavior.ASK_BEFORE_ACTION
                            },
                            explanation = "Owner-configured autonomous coding/verification permission.",
                            isEnabled = true
                        )
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        val mainPushRule = activeRules.firstOrNull {
            it.category == ActionCategory.GIT_PUSH_MAIN.name
        }
        val mainPushPreapproved = mainPushRule?.behavior
            ?.let { it == RuleBehavior.ALLOW_IF_PREAPPROVED || it == RuleBehavior.ALLOW_WITHOUT_ASKING }
            ?: false

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("الدفع إلى الفرع الرئيسي عند طلبي الصريح", color = Color.White, fontSize = 13.sp)
                Text(
                    "مغلق افتراضيًا. عند تشغيله يسمح بالدفع إلى main فقط عندما تطلب ذلك بوضوح.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = mainPushPreapproved,
                onCheckedChange = { enabled ->
                    onSaveRule(
                        Rule(
                            id = "owner-permission-main-push",
                            category = ActionCategory.GIT_PUSH_MAIN.name,
                            behavior = if (enabled) {
                                RuleBehavior.ALLOW_IF_PREAPPROVED
                            } else {
                                RuleBehavior.ASK_BEFORE_ACTION
                            },
                            explanation = "Owner-configured permission for explicit main-branch pushes.",
                            isEnabled = true
                        )
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "حذف الفروع وForce Push يبقيان محجوبين بقواعد أمان ثابتة ولا يمكن لمفتاح هنا تجاوزهما.",
            fontSize = 11.sp,
            color = Color(0xFFF59E0B)
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text("الصلاحيات وفهم السياق", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (accessibilityEnabled) {
                "✓ فهم عناصر الشاشة عبر إمكانية الوصول: مفعّل ومتاح."
            } else {
                "فهم عناصر الشاشة عبر إمكانية الوصول: غير مفعّل."
            },
            fontSize = 12.sp,
            color = if (accessibilityEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
        )
        if (!accessibilityEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onOpenAccessibility) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تفعيل فهم عناصر الشاشة")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (notificationAccessEnabled) {
                "✓ وصول الإشعارات: مفعّل ومتاح."
            } else {
                "وصول الإشعارات: غير مفعّل."
            },
            fontSize = 12.sp,
            color = if (notificationAccessEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
        )
        if (!notificationAccessEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(onClick = onOpenNotificationAccess) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تفعيل وصول الإشعارات")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (overlayEnabled) {
                "✓ الزر العائم فوق التطبيقات: مفعّل ومتاح."
            } else {
                "الزر العائم فوق التطبيقات: غير مفعّل."
            },
            fontSize = 12.sp,
            color = if (overlayEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
        )
        if (!overlayEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(onClick = onStartOverlay) {
                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تفعيل الزر العائم")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("الرؤية البصرية", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            if (visualScreenRunning) {
                "التقاط صورة الشاشة مستمر الآن" +
                    if (lastVisualCaptureAt > 0L) " • آخر لقطة محفوظة" else ""
            } else {
                "يمكن لـTodd التقاط الشاشة بصرياً ودمجها مع عناصر إمكانية الوصول."
            },
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = if (visualScreenRunning) onStopVisualScreen else onStartVisualScreen,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (visualScreenRunning) Color(0xFFB91C1C) else Color(0xFF4F46E5)
            )
        ) {
            Icon(
                if (visualScreenRunning) Icons.Default.Stop else Icons.Default.ScreenshotMonitor,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (visualScreenRunning) "إيقاف الرؤية البصرية" else "تشغيل الرؤية البصرية")
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("اتصال GitHub", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            if (githubTokenConfigured) {
                "التفويض محفوظ ومشفّر داخل Android Keystore."
            } else {
                "ألصق رمز GitHub مرة واحدة لتمكين Todd من الكتابة وتنفيذ المهام على المستودعات."
            },
            fontSize = 12.sp,
            color = if (githubTokenConfigured) Color(0xFF10B981) else Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = githubToken,
            onValueChange = { githubToken = it },
            label = { Text("GitHub token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (githubToken.isNotBlank()) {
                        onSaveGitHubToken(githubToken)
                        githubToken = ""
                    }
                },
                enabled = githubToken.isNotBlank()
            ) {
                Text("حفظ التفويض")
            }
            if (githubTokenConfigured) {
                OutlinedButton(onClick = onClearGitHubToken) {
                    Text("مسح التفويض")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("بيانات الإصدار والبناء", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "معلومات دقيقة للقراءة فقط عن النسخة المثبتة حالياً للتحقق من هوية Build وتجنب الخلط بين الإصدارات.",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                val currentSha = SigningIdentity.currentSha256(context) ?: "غير متوفرة"
                val isStable = SigningIdentity.isStableSigned(context)
                val runNum = BuildConfig.GITHUB_RUN_NUMBER.ifBlank { "بناء محلي / غير محدد" }

                BuildInfoRow("إصدار التطبيق (Version):", "${BuildConfig.VERSION_NAME} (كود: ${BuildConfig.VERSION_CODE})")
                BuildInfoRow("Git Commit SHA:", BuildConfig.GIT_COMMIT_SHA)
                BuildInfoRow("رقم GitHub Actions Run:", runNum)
                BuildInfoRow("تاريخ ووقت البناء:", BuildConfig.BUILD_TIME)
                BuildInfoRow(
                    "نوع التوقيع الحالي:",
                    if (isStable) "توقيع Todd الدائم (مؤكد ومستقر)" else "توقيع Debug / غير رسمي",
                    valueColor = if (isStable) Color(0xFF10B981) else Color(0xFFF59E0B)
                )
                BuildInfoRow("بصمة شهادة التوقيع (SHA-256):", currentSha)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("فحص Todd الشامل", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "يفحص الذاكرة والذكاء السحابي والمحلي وGitHub والصلاحيات ولوحة Todd ورؤية الشاشة.",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRunDiagnostics,
            enabled = !diagnosticsBusy
        ) {
            Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (diagnosticsBusy) "جارٍ الفحص..." else "تشغيل الفحص الشامل")
        }

        diagnosticsReport?.let { report ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "نجح: ${report.passCount} • تحذير: ${report.warnCount} • فشل: ${report.failCount}",
                fontSize = 12.sp,
                color = if (report.failCount == 0) Color(0xFF10B981) else Color(0xFFF87171)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (report.isFullToddReady) {
                    "Todd جاهز كنسخة كاملة على هذا الجهاز."
                } else {
                    "متبقي قبل الجاهزية الكاملة: " +
                        report.blockingChecks.joinToString("، ") { it.title }
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (report.isFullToddReady) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
            Spacer(modifier = Modifier.height(8.dp))

            report.checks.forEach { check ->
                val marker = when (check.status) {
                    DiagnosticStatus.PASS -> "✓"
                    DiagnosticStatus.WARN -> "!"
                    DiagnosticStatus.FAIL -> "×"
                }
                val markerColor = when (check.status) {
                    DiagnosticStatus.PASS -> Color(0xFF10B981)
                    DiagnosticStatus.WARN -> Color(0xFFF59E0B)
                    DiagnosticStatus.FAIL -> Color(0xFFEF4444)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(marker, color = markerColor, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(check.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(check.detail, color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                }
            }
        }
    }

    pendingMemoryDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingMemoryDelete = null },
            title = { Text("حذف ذاكرة Todd؟") },
            text = { Text(memory.value) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteLearnedMemory(memory)
                        pendingMemoryDelete = null
                    }
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingMemoryDelete = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun BuildInfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 12.sp, color = valueColor)
    }
}
