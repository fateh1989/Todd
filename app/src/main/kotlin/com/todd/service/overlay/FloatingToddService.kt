package com.todd.service.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.todd.ToddApplication
import com.todd.core.ai.GeminiLiveState
import com.todd.core.ai.AIRequest
import com.todd.service.context.DeviceContextProvider
import com.todd.service.screen.ScreenCaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingToddService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var dwellStartTime = 0L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                closeCompactPanel()
                hideOverlayButton()
            }
            ACTION_SHOW, null -> showOverlayButton()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "todd_floating_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Todd Always Available",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Todd active and provides quick controls"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Todd")
            .setContentText("Todd جاهز")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            dp(64),
            dp(64),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        overlayParams = params

        val container = FrameLayout(this)
        val button = TextView(this).apply {
            text = "T"
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(79, 70, 229))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
        }
        container.addView(
            button,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        overlayView = container

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    dwellStartTime = 0L
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                        closeCompactPanel()
                    }

                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)

                        val screenHeight = resources.displayMetrics.heightPixels
                        val screenWidth = resources.displayMetrics.widthPixels
                        val inBottomTargetArea = event.rawY > screenHeight - dp(150)

                        if (inBottomTargetArea && event.rawX >= screenWidth / 2f) {
                            if (dwellStartTime == 0L) {
                                dwellStartTime = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - dwellStartTime >= POWER_DWELL_MS) {
                                ToddApplication.instance.stateMachine.powerOff()
                                stopSelf()
                            }
                        } else {
                            dwellStartTime = 0L
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleCompactPanel()
                    } else if (isDragging) {
                        val screenHeight = resources.displayMetrics.heightPixels
                        val screenWidth = resources.displayMetrics.widthPixels
                        val droppedInBottomArea = event.rawY > screenHeight - dp(150)

                        if (droppedInBottomArea && event.rawX < screenWidth / 2f) {
                            hideOverlayButton()
                        } else {
                            params.x = if (event.rawX < screenWidth / 2f) {
                                dp(12)
                            } else {
                                screenWidth - dp(76)
                            }
                            params.y = params.y.coerceIn(dp(24), screenHeight - dp(100))
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                    }

                    dwellStartTime = 0L
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            ToddApplication.instance.stateMachine.showOverlay()
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun toggleCompactPanel() {
        if (panelView != null) {
            closeCompactPanel()
        } else {
            openCompactPanel()
        }
    }

    private fun openCompactPanel() {
        val app = ToddApplication.instance
        if (app.stateMachine.state.value.isPowerOff) {
            app.stateMachine.powerOn()
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val buttonParams = overlayParams

        val panelWidth = minOf(dp(340), screenWidth - dp(24))
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = ((buttonParams?.x ?: dp(12)) - panelWidth + dp(64))
                .coerceIn(dp(12), maxOf(dp(12), screenWidth - panelWidth - dp(12)))
            y = ((buttonParams?.y ?: dp(120)) + dp(72))
                .coerceIn(dp(24), maxOf(dp(24), screenHeight - dp(420)))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(15, 23, 42))
                setStroke(dp(1), Color.rgb(71, 85, 105))
            }
            elevation = dp(12).toFloat()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Todd"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(
            title,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        )

        val close = Button(this).apply {
            text = "×"
            textSize = 18f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { closeCompactPanel() }
        }
        headerRow.addView(close)
        panel.addView(headerRow)

        val state = app.stateMachine.state.value
        val liveState = app.liveClient.state.value
        val status = TextView(this).apply {
            text = buildString {
                append(
                    when {
                        state.isPowerOff -> "متوقف"
                        state.isPaused -> "متوقف مؤقتاً"
                        else -> "نشط"
                    }
                )
                append(" • الصوت: ")
                append(
                    when (liveState) {
                        GeminiLiveState.DISCONNECTED -> "غير متصل"
                        GeminiLiveState.CONNECTING -> "يتصل"
                        GeminiLiveState.LISTENING -> "يستمع"
                        GeminiLiveState.THINKING -> "يفكر"
                        GeminiLiveState.SPEAKING -> "يتحدث"
                        GeminiLiveState.RECONNECTING -> "يعيد الاتصال"
                        GeminiLiveState.ERROR -> "خطأ"
                    }
                )
            }
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184))
        }
        panel.addView(status)

        val contextText = DeviceContextProvider.currentTextContext()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(420)

        val contextView = TextView(this).apply {
            text = if (contextText.isBlank()) {
                "لا يوجد سياق شاشة متاح الآن."
            } else {
                "السياق الحالي: $contextText"
            }
            textSize = 12f
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(0, dp(10), 0, dp(10))
            maxLines = 5
        }
        panel.addView(contextView)

        val responseView = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(6))
            maxLines = 7
            visibility = View.GONE
        }
        panel.addView(responseView)

        val quickInput = EditText(this).apply {
            hint = "اسأل Todd عن هذه الشاشة..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 163, 184))
            setSingleLine(false)
            maxLines = 3
            minHeight = dp(48)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(30, 41, 59))
                setStroke(dp(1), Color.rgb(71, 85, 105))
            }
        }
        panel.addView(
            quickInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(4), 0, dp(6))
            }
        )

        val sendQuick = Button(this).apply {
            text = "إرسال إلى Todd"
            setOnClickListener {
                val prompt = quickInput.text?.toString()?.trim().orEmpty()
                if (prompt.isBlank()) return@setOnClickListener

                isEnabled = false
                responseView.visibility = View.VISIBLE
                responseView.text = "Todd يعمل..."
                serviceScope.launch {
                    val projectId =
                        app.stateMachine.state.value.activeProjectId ?: "todd-main"
                    val projectContext = app.repository.buildProjectContext(projectId)
                    val currentContext = DeviceContextProvider.currentTextContext()

                    val result = app.textAgent.respond(
                        AIRequest(
                            prompt = prompt,
                            projectContext = projectContext,
                            screenContext = currentContext.ifBlank { null },
                            screenImagePath = ScreenCaptureStore.latestFile()?.absolutePath
                        ),
                        app.stateMachine.state.value.aiMode
                    )

                    responseView.text = result.fold(
                        onSuccess = { it.text },
                        onFailure = { "تعذر تنفيذ الطلب: ${it.message ?: "خطأ غير معروف"}" }
                    )
                    if (result.isSuccess) {
                        quickInput.setText("")
                    }
                    isEnabled = true
                }
            }
        }
        panel.addView(sendQuick)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val openApp = Button(this).apply {
            text = "محادثة"
            setOnClickListener {
                closeCompactPanel()
                openMainActivity()
            }
        }
        actions.addView(
            openApp,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        )

        val voice = Button(this).apply {
            text = if (
                liveState == GeminiLiveState.DISCONNECTED ||
                liveState == GeminiLiveState.ERROR
            ) {
                "صوت"
            } else {
                "إيقاف الصوت"
            }
            setOnClickListener {
                toggleVoiceFromOverlay()
                closeCompactPanel()
            }
        }
        actions.addView(
            voice,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        )

        panel.addView(actions)

        val hide = Button(this).apply {
            text = "إخفاء الزر العائم"
            setOnClickListener {
                closeCompactPanel()
                hideOverlayButton()
            }
        }
        panel.addView(hide)

        try {
            windowManager.addView(panel, params)
            panelView = panel
        } catch (_: Exception) {
            panelView = null
            openMainActivity()
        }
    }

    private fun toggleVoiceFromOverlay() {
        val app = ToddApplication.instance
        val liveState = app.liveClient.state.value

        if (
            liveState != GeminiLiveState.DISCONNECTED &&
            liveState != GeminiLiveState.ERROR
        ) {
            app.liveClient.endSession()
            return
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            openMainActivity()
            return
        }

        serviceScope.launch {
            app.liveClient.startSession(app.stateMachine.state.value.aiMode)
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, com.todd.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun closeCompactPanel() {
        panelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelView = null
    }

    private fun hideOverlayButton() {
        closeCompactPanel()
        overlayView?.visibility = View.GONE
        ToddApplication.instance.stateMachine.hideOverlay()
    }

    private fun showOverlayButton() {
        val view = overlayView
        if (view == null) {
            setupOverlay()
        } else {
            view.visibility = View.VISIBLE
            ToddApplication.instance.stateMachine.showOverlay()
        }
    }

    override fun onDestroy() {
        closeCompactPanel()
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.todd.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.todd.action.HIDE_OVERLAY"
        private const val POWER_DWELL_MS = 1200L
    }
}
