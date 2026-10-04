package com.todd.service.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.todd.ToddApplication

class FloatingToddService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var dwellStartTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlayButton()
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

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            dp(64),
            dp(64),
            layoutParamsType,
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
                        openCompactPanel()
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
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (_: Exception) {
                            }
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

    private fun hideOverlayButton() {
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

    private fun openCompactPanel() {
        val app = ToddApplication.instance
        if (app.stateMachine.state.value.isPowerOff) {
            app.stateMachine.powerOn()
        }

        val intent = Intent(this, com.todd.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.todd.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.todd.action.HIDE_OVERLAY"
        private const val POWER_DWELL_MS = 1200L
    }
}
