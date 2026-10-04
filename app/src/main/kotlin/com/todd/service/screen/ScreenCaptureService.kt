package com.todd.service.screen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

data class VisualScreenSnapshot(
    val filePath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val capturedAt: Long = 0L,
    val isRunning: Boolean = false
)

object ScreenCaptureStore {
    private val _state = MutableStateFlow(VisualScreenSnapshot())
    val state: StateFlow<VisualScreenSnapshot> = _state.asStateFlow()

    internal fun setRunning(running: Boolean) {
        _state.value = _state.value.copy(isRunning = running)
    }

    internal fun update(file: File, width: Int, height: Int, capturedAt: Long) {
        _state.value = VisualScreenSnapshot(
            filePath = file.absolutePath,
            width = width,
            height = height,
            capturedAt = capturedAt,
            isRunning = true
        )
    }

    fun latestFile(): File? =
        _state.value.filePath?.let(::File)?.takeIf { it.exists() }
}

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastCaptureAt: Long = 0L
    private var lastOcrAt: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseProjection()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    beginCapture(resultCode, resultData)
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Todd")
            .setContentText("الرؤية البصرية للشاشة تعمل")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        releaseProjection()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = projectionManager.getMediaProjection(resultCode, resultData)
        projection = newProjection
        newProjection.registerCallback(projectionCallback, null)

        val (width, height, density) = currentDisplayInfo()

        workerThread = HandlerThread("ToddScreenCapture").apply { start() }
        workerHandler = Handler(workerThread!!.looper)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastCaptureAt < CAPTURE_INTERVAL_MS) return@setOnImageAvailableListener
                lastCaptureAt = now

                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride

                val padded = Bitmap.createBitmap(
                    paddedWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(buffer)

                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()

                val target = File(cacheDir, "todd_latest_screen.png")
                FileOutputStream(target).use { output ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 90, output)
                }

                ScreenCaptureStore.update(target, width, height, now)

                if (now - lastOcrAt >= OCR_INTERVAL_MS) {
                    lastOcrAt = now
                    ScreenOcrProcessor.process(cropped, now) {
                        if (!cropped.isRecycled) cropped.recycle()
                    }
                } else {
                    cropped.recycle()
                }
            } finally {
                image.close()
            }
        }, workerHandler)

        virtualDisplay = newProjection.createVirtualDisplay(
            "ToddVisualScreen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )

        ScreenCaptureStore.setRunning(true)
    }

    private fun currentDisplayInfo(): Triple<Int, Int, Int> {
        val density = resources.configuration.densityDpi
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), density)
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun stopCapture() {
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        ScreenCaptureStore.setRunning(false)
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Todd Screen Vision",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.todd.action.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "com.todd.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "todd_screen_capture"
        private const val NOTIFICATION_ID = 1002
        private const val CAPTURE_INTERVAL_MS = 1200L
        private const val OCR_INTERVAL_MS = 2500L
    }
}
