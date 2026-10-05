package com.todd.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.todd.ToddApplication
import com.todd.core.model.*
import com.todd.core.ai.GeminiLiveState
import com.todd.core.ai.LiveTranscriptItem
import com.todd.core.ai.FirebaseRuntimeConfig
import com.todd.core.ai.LocalOnDeviceAIProvider
import com.todd.core.remote.RemoteExecutionMode
import com.todd.core.remote.RemoteJobRequest
import com.todd.core.agent.AutonomousCodingRequest
import com.todd.service.overlay.FloatingToddService
import com.todd.service.accessibility.ToddAccessibilityService
import com.todd.service.screen.ScreenCaptureService
import com.todd.service.screen.ScreenCaptureStore
import com.todd.service.context.DeviceContextProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
            ToddMainScreen(
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
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    val tasks by app.repository.getAllTasks().collectAsState(initial = emptyList())
    val projects by app.repository.getAllProjects().collectAsState(initial = emptyList())
    val projectId = state.activeProjectId ?: "todd-main"
    val memories by app.repository.getMemoriesForProject(projectId).collectAsState(initial = emptyList())
    val chatMessages = remember(memories) {
        memories
            .filter { it.layer == MemoryLayer.EPISODIC && it.key.startsWith("chat:") }
            .sortedBy { it.timestamp }
    }
    var isChatBusy by remember { mutableStateOf(false) }
    var localAIStatus by remember { mutableStateOf("لم يتم فحص النموذج المحلي بعد") }
    var localAIBusy by remember { mutableStateOf(false) }
    val firebaseRuntimeStatus = remember { FirebaseRuntimeConfig.current() }

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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A))
                .padding(16.dp)
        ) {
            // Status Card
            StatusCard(state, onStartOverlay)

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> HomeDashboard(
                    state = state,
                    messages = chatMessages,
                    isBusy = isChatBusy,
                    voiceState = liveState,
                    voiceTranscripts = liveTranscripts,
                    voiceMuted = liveMuted,
                    onToggleVoice = {
                        if (liveState == GeminiLiveState.DISCONNECTED || liveState == GeminiLiveState.ERROR) {
                            onStartVoice()
                        } else {
                            onStopVoice()
                        }
                    },
                    onToggleVoiceMute = { app.liveClient.toggleMute() },
                    onSendMessage = { message ->
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

                                val result = app.aiRouter.route(
                                    com.todd.core.ai.AIRequest(
                                        prompt = message,
                                        projectContext = contextBeforeMessage,
                                        screenContext = DeviceContextProvider.currentTextContext().ifBlank { null },
                                        screenImagePath = ScreenCaptureStore.latestFile()?.absolutePath
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
                                            isVerified = true
                                        )
                                    )
                                }
                                isChatBusy = false
                            }
                        }
                    },
                    onTaskAction = { title, goal ->
                        scope.launch {
                            val task = stateMachine.planTask(
                                taskId = "task-${System.currentTimeMillis()}",
                                projectId = projectId,
                                title = title,
                                goal = goal,
                                criteria = "GitHub Actions verification must complete with evidence"
                            )
                            app.repository.saveTask(task)

                            if (!app.githubCredentialStore.hasToken()) {
                                app.repository.updateTask(
                                    task.copy(
                                        status = TaskStatus.BLOCKED,
                                        currentStep = "أضف تفويض GitHub من الإعدادات لتشغيل المهمة البعيدة.",
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            } else {
                                val project = app.repository.getProjectById(projectId)
                                val repoName = project?.repository
                                    ?: if (projectId == "todd-main") "fateh1989/Todd" else null
                                val branch = project?.branch?.ifBlank { "main" } ?: "main"

                                if (repoName == null) {
                                    app.repository.updateTask(
                                        task.copy(
                                            status = TaskStatus.BLOCKED,
                                            currentStep = "لا يوجد مستودع مرتبط بهذا المشروع.",
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                } else {
                                    val info = app.githubTool.getRepositoryInfo(repoName, branch).getOrNull()
                                    if (info == null) {
                                        app.repository.updateTask(
                                            task.copy(
                                                status = TaskStatus.FAILED,
                                                currentStep = "تعذر قراءة حالة المستودع.",
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        )
                                    } else {
                                        val started = app.remoteExecutor.startJob(
                                            RemoteJobRequest(
                                                jobId = task.id,
                                                projectId = projectId,
                                                repository = repoName,
                                                branch = branch,
                                                startCommit = info.latestCommitSha,
                                                objective = goal,
                                                completionCriteria = task.completionCriteria,
                                                mode = RemoteExecutionMode.VERIFY_ANDROID
                                            )
                                        )
                                        started.exceptionOrNull()?.let { error ->
                                            app.repository.updateTask(
                                                task.copy(
                                                    status = TaskStatus.FAILED,
                                                    currentStep = error.message ?: "تعذر بدء المهمة البعيدة.",
                                                    failureCause = error.message,
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onAutonomousCodingTask = { title, goal ->
                        app.autonomousTaskCoordinator.start(
                            projectId = projectId,
                            title = title,
                            goal = goal,
                            maxIterations = 4
                        )
                    }
                )
                1 -> ProjectsView(projects)
                2 -> ActivityView(
                    tasks = tasks,
                    onRefreshRemote = { task ->
                        scope.launch {
                            val result = app.remoteExecutor.reconnect(task.id)
                            result.exceptionOrNull()?.let { error ->
                                app.repository.updateTask(
                                    task.copy(
                                        currentStep = "تعذر تحديث المهمة البعيدة: ${error.message ?: "خطأ غير معروف"}",
                                        failureCause = error.message,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    },
                    onCancelRemote = { task ->
                        scope.launch {
                            val result = app.remoteExecutor.requestCancel(task.id)
                            result.exceptionOrNull()?.let { error ->
                                app.repository.updateTask(
                                    task.copy(
                                        currentStep = "تعذر إلغاء المهمة البعيدة: ${error.message ?: "خطأ غير معروف"}",
                                        failureCause = error.message,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                )
                3 -> SettingsView(
                    state = state,
                    visualScreenRunning = visualScreen.isRunning,
                    lastVisualCaptureAt = visualScreen.capturedAt,
                    githubTokenConfigured = app.githubCredentialStore.hasToken(),
                    firebaseConfigured = firebaseRuntimeStatus.configured,
                    firebaseProjectId = firebaseRuntimeStatus.projectId,
                    firebaseReason = firebaseRuntimeStatus.reason,
                    localAIStatus = localAIStatus,
                    localAIBusy = localAIBusy,
                    onCheckLocalAI = {
                        scope.launch {
                            localAIBusy = true
                            val available = app.aiRouter.localProvider.isAvailable()
                            localAIStatus = if (available) {
                                "النموذج المحلي متاح الآن على هذا الجهاز"
                            } else {
                                "النموذج المحلي غير جاهز بعد أو غير مدعوم على هذا الجهاز"
                            }
                            localAIBusy = false
                        }
                    },
                    onPrepareLocalAI = {
                        scope.launch {
                            localAIBusy = true
                            val provider = app.aiRouter.localProvider as? LocalOnDeviceAIProvider
                            localAIStatus = if (provider == null) {
                                "مزود الذكاء المحلي الحالي لا يدعم التهيئة"
                            } else {
                                provider.prepareModel().fold(
                                    onSuccess = { "تم تجهيز النموذج المحلي وأصبح متاحاً" },
                                    onFailure = { "تعذر تجهيز النموذج المحلي: ${it.message ?: "خطأ غير معروف"}" }
                                )
                            }
                            localAIBusy = false
                        }
                    },
                    onSaveGitHubToken = { token ->
                        app.githubCredentialStore.saveToken(token)
                        app.autonomousTaskCoordinator.resumePending()
                    },
                    onClearGitHubToken = { app.githubCredentialStore.clearToken() },
                    onAIModeChange = { mode -> stateMachine.setAIMode(mode) },
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onStartVisualScreen = onStartVisualScreen,
                    onStopVisualScreen = onStopVisualScreen
                )
            }
        }
    }
}

@Composable
fun StatusCard(state: ToddState, onStartOverlay: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when {
                        state.isPowerOff -> Color(0xFFEF4444)
                        state.isPaused -> Color(0xFFF59E0B)
                        state.globalStatus == ToddGlobalStatus.WORKING -> Color(0xFF6366F1)
                        else -> Color(0xFF10B981)
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, RoundedCornerShape(5.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isPowerOff) "Todd متوقف (Power OFF)"
                               else if (state.isPaused) "Todd في وضع الإيقاف المؤقت (PAUSED)"
                               else "Todd نشط وجاهز (${state.globalStatus})",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                AssistChip(
                    onClick = onStartOverlay,
                    label = { Text("الزر العائم", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "آخر إجراء تم التحقق منه: ${state.lastVerifiedAction}",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                "الخطوة المخططة التالية: ${state.nextPlannedAction}",
                fontSize = 12.sp,
                color = Color(0xFFCBD5E1)
            )
        }
    }
}

@Composable
fun HomeDashboard(
    state: ToddState,
    messages: List<MemoryEntry>,
    isBusy: Boolean,
    voiceState: GeminiLiveState,
    voiceTranscripts: List<LiveTranscriptItem>,
    voiceMuted: Boolean,
    onToggleVoice: () -> Unit,
    onToggleVoiceMute: () -> Unit,
    onSendMessage: (String) -> Unit,
    onTaskAction: (String, String) -> Unit,
    onAutonomousCodingTask: (String, String) -> Unit
) {
    var quickInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        VoiceConversationCard(
            state = voiceState,
            transcripts = voiceTranscripts,
            muted = voiceMuted,
            onToggle = onToggleVoice,
            onToggleMute = onToggleVoiceMute
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("محادثة Todd", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                val isUser = message.key.startsWith("chat:user:")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (isUser) Color(0xFF4338CA) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        Text(
                            text = message.value,
                            color = Color.White,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            if (isBusy) {
                item {
                    Text("Todd يعمل...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = quickInput,
            onValueChange = { quickInput = it },
            placeholder = { Text("اكتب لتود...") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6366F1),
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = {
                    if (quickInput.isNotBlank()) {
                        onTaskAction(quickInput, quickInput)
                        quickInput = ""
                    }
                },
                enabled = !isBusy
            ) {
                Text("تحقق بعيد")
            }

            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    if (quickInput.isNotBlank()) {
                        onAutonomousCodingTask(quickInput, quickInput)
                        quickInput = ""
                    }
                },
                enabled = !isBusy
            ) {
                Text("برمجة ذاتية")
            }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = quickInput.trim()
                    if (text.isNotBlank()) {
                        onSendMessage(text)
                        quickInput = ""
                    }
                },
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إرسال")
            }
        }
    }
}

@Composable
fun VoiceConversationCard(
    state: GeminiLiveState,
    transcripts: List<LiveTranscriptItem>,
    muted: Boolean,
    onToggle: () -> Unit,
    onToggleMute: () -> Unit
) {
    val active = state != GeminiLiveState.DISCONNECTED && state != GeminiLiveState.ERROR
    val stateLabel = when (state) {
        GeminiLiveState.DISCONNECTED -> "غير متصل"
        GeminiLiveState.CONNECTING -> "جارِ الاتصال"
        GeminiLiveState.LISTENING -> "يستمع"
        GeminiLiveState.THINKING -> "يفكر"
        GeminiLiveState.SPEAKING -> "يتحدث"
        GeminiLiveState.RECONNECTING -> "يعيد الاتصال"
        GeminiLiveState.ERROR -> "خطأ في الاتصال"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("المحادثة الصوتية المباشرة", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(stateLabel, color = Color(0xFF94A3B8), fontSize = 12.sp)
                }

                Row {
                    if (active) {
                        IconButton(onClick = onToggleMute) {
                            Icon(
                                if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute",
                                tint = if (muted) Color(0xFFF59E0B) else Color.White
                            )
                        }
                    }
                    Button(
                        onClick = onToggle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Color(0xFFB91C1C) else Color(0xFF4F46E5)
                        )
                    ) {
                        Icon(
                            if (active) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (active) "إيقاف" else "تحدث مع Todd")
                    }
                }
            }

            transcripts.takeLast(3).forEach { item ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${if (item.sender == "USER") "أنت" else "Todd"}: ${item.text}",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ProjectsView(projects: List<Project>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(projects) { project ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(project.name, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("المستودع: ${project.repository ?: "محلي"}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Text("الفرع: ${project.branch}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Text("آخر التزام موثق: ${project.lastVerifiedCommit ?: "لا يوجد بعد"}", fontSize = 11.sp, color = Color(0xFF6366F1))
                }
            }
        }
    }
}

@Composable
fun ActivityView(
    tasks: List<Task>,
    onRefreshRemote: (Task) -> Unit,
    onCancelRemote: (Task) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tasks) { task ->
            val remoteActive = task.status in setOf(
                TaskStatus.PLANNED,
                TaskStatus.IN_PROGRESS,
                TaskStatus.WAITING,
                TaskStatus.EXECUTED,
                TaskStatus.VERIFYING,
                TaskStatus.BLOCKED,
                TaskStatus.PAUSED
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(task.title, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Badge(
                            containerColor = when (task.status) {
                                TaskStatus.COMPLETED, TaskStatus.VERIFIED -> Color(0xFF10B981)
                                TaskStatus.FAILED, TaskStatus.CANCELLED -> Color(0xFFEF4444)
                                TaskStatus.IN_PROGRESS,
                                TaskStatus.WAITING,
                                TaskStatus.VERIFYING -> Color(0xFFF59E0B)
                                TaskStatus.BLOCKED, TaskStatus.PAUSED -> Color(0xFF8B5CF6)
                                else -> Color(0xFF64748B)
                            }
                        ) {
                            Text(task.status.name, fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "الخطوة: ${task.currentStep}",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )

                    task.lastEvidence?.let {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            "الدليل: $it",
                            fontSize = 11.sp,
                            color = Color(0xFF10B981)
                        )
                    }

                    task.failureCause?.let {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            "الخطأ: $it",
                            fontSize = 11.sp,
                            color = Color(0xFFFCA5A5)
                        )
                    }

                    if (remoteActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onRefreshRemote(task) }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("تحديث")
                            }

                            if (task.status != TaskStatus.BLOCKED && task.status != TaskStatus.PAUSED) {
                                OutlinedButton(onClick = { onCancelRemote(task) }) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("إلغاء")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsView(
    state: ToddState,
    visualScreenRunning: Boolean,
    lastVisualCaptureAt: Long,
    githubTokenConfigured: Boolean,
    firebaseConfigured: Boolean,
    firebaseProjectId: String?,
    firebaseReason: String?,
    localAIStatus: String,
    localAIBusy: Boolean,
    onCheckLocalAI: () -> Unit,
    onPrepareLocalAI: () -> Unit,
    onSaveGitHubToken: (String) -> Unit,
    onClearGitHubToken: () -> Unit,
    onAIModeChange: (AIProviderMode) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStartVisualScreen: () -> Unit,
    onStopVisualScreen: () -> Unit
) {
    var githubToken by remember { mutableStateOf("") }

    Column {
        Text("وضع توجيه الذكاء الاصطناعي (AI Routing)", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.aiMode == AIProviderMode.LOCAL_ONLY,
                onClick = { onAIModeChange(AIProviderMode.LOCAL_ONLY) },
                label = { Text("محلي فقط") }
            )
            FilterChip(
                selected = state.aiMode == AIProviderMode.AUTO,
                onClick = { onAIModeChange(AIProviderMode.AUTO) },
                label = { Text("تلقائي (Auto)") }
            )
            FilterChip(
                selected = state.aiMode == AIProviderMode.CLOUD_PREFERRED,
                onClick = { onAIModeChange(AIProviderMode.CLOUD_PREFERRED) },
                label = { Text("سحابي مسبق") }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("حالة الذكاء", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (firebaseConfigured) {
                "الذكاء السحابي: Firebase جاهز" +
                    (firebaseProjectId?.let { " • المشروع: $it" } ?: "")
            } else {
                "الذكاء السحابي: غير مهيأ للتشغيل الحقيقي" +
                    (firebaseReason?.let { " • $it" } ?: "")
            },
            fontSize = 12.sp,
            color = if (firebaseConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)
        )
        Spacer(modifier = Modifier.height(6.dp))
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
        Text("فهم الشاشة", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "فعّل خدمة Todd لإمكانية الوصول ليقرأ عناصر الشاشة ويستخدمها كسياق أثناء العمل.",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenAccessibility) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("فتح إعدادات فهم الشاشة")
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenNotificationAccess) {
            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("فتح وصول الإشعارات")
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
    }
}
