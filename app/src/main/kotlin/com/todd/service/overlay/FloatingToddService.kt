package com.todd.service.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.todd.R
import com.todd.ToddApplication
import com.todd.core.model.ToddGlobalStatus

class FloatingToddService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging = false
    private var dwellStartTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlay()
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Todd is standing by")
            .setContentText("Tap overlay button or keyboard to summon Todd")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val container = FrameLayout(this)
        overlayView = container

        // Handle drag, edge snap, Hide and Power Off targets
        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)

                        // Check if hovering over bottom targets
                        val screenHeight = resources.displayMetrics.heightPixels
                        val screenWidth = resources.displayMetrics.widthPixels

                        if (params.y > screenHeight - 250) {
                            // Target area!
                            if (params.x < screenWidth / 2) {
                                // Hide target zone
                            } else {
                                // Power Off target zone (requires dwell)
                                if (dwellStartTime == 0L) {
                                    dwellStartTime = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - dwellStartTime > 1200) {
                                    // Dwell reached! Trigger Power Off
                                    ToddApplication.instance.stateMachine.powerOff()
                                    stopSelf()
                                }
                            }
                        } else {
                            dwellStartTime = 0L
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Single tap: open compact panel
                        openCompactPanel()
                    } else {
                        // Edge snap logic
                        val screenWidth = resources.displayMetrics.widthPixels
                        params.x = if (params.x < screenWidth / 2) 20 else screenWidth - 140
                        windowManager.updateViewLayout(view, params)
                    }
                    dwellStartTime = 0L
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openCompactPanel() {
        // Open compact Todd panel or launch MainActivity
        val app = ToddApplication.instance
        if (app.stateMachine.state.value.isPowerOff) {
            app.stateMachine.powerOn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
