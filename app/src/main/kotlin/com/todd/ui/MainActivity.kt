package com.todd.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todd.ToddApplication
import com.todd.core.model.*
import com.todd.service.overlay.FloatingToddService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToddMainScreen(
                onStartOverlay = { checkOverlayPermissionAndStart() }
            )
        }
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
fun ToddMainScreen(onStartOverlay: () -> Unit) {
    val app = ToddApplication.instance
    val stateMachine = app.stateMachine
    val state by stateMachine.state.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    val tasks by app.repository.getAllTasks().collectAsState(initial = emptyList())
    val projects by app.repository.getAllProjects().collectAsState(initial = emptyList())

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
                0 -> HomeDashboard(state, onTaskAction = { title, goal ->
                    scope.launch {
                        val task = stateMachine.planTask(
                            taskId = "task-${System.currentTimeMillis()}",
                            projectId = state.activeProjectId ?: "todd-main",
                            title = title,
                            goal = goal,
                            criteria = "Verified by tool response"
                        )
                        app.repository.saveTask(task)
                    }
                })
                1 -> ProjectsView(projects)
                2 -> ActivityView(tasks)
                3 -> SettingsView(state, onAIModeChange = { mode ->
                    stateMachine.setAIMode(mode)
                })
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
fun HomeDashboard(state: ToddState, onTaskAction: (String, String) -> Unit) {
    var quickInput by remember { mutableStateOf("") }

    Column {
        Text("تكليف Todd بمهمة جديدة", fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = quickInput,
            onValueChange = { quickInput = it },
            placeholder = { Text("اكتب هدفاً، فحص مستودع، أو طلباً برمجياً...") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6366F1),
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (quickInput.isNotBlank()) {
                    onTaskAction(quickInput, "تحقيق الهدف المحدد مع التحقق الكامل")
                    quickInput = ""
                }
            },
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("بدء الخطة")
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
fun ActivityView(tasks: List<Task>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tasks) { task ->
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
                        Badge(containerColor = when (task.status) {
                            TaskStatus.COMPLETED, TaskStatus.VERIFIED -> Color(0xFF10B981)
                            TaskStatus.FAILED -> Color(0xFFEF4444)
                            TaskStatus.IN_PROGRESS, TaskStatus.WAITING -> Color(0xFFF59E0B)
                            else -> Color(0xFF64748B)
                        }) {
                            Text(task.status.name, fontSize = 10.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("الخطوة: ${task.currentStep}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    task.lastEvidence?.let {
                        Text("الدليل: $it", fontSize = 11.sp, color = Color(0xFF10B981))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsView(state: ToddState, onAIModeChange: (AIProviderMode) -> Unit) {
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
    }
}
