package com.todd.service.context

import com.todd.service.accessibility.ToddAccessibilityService
import com.todd.service.notification.ToddNotificationListenerService
import com.todd.service.screen.ScreenCaptureStore

object DeviceContextProvider {

    fun currentTextContext(): String {
        val semantic = ToddAccessibilityService.latestScreenContext()
        val notifications = ToddNotificationListenerService.currentContext()
        val visual = ScreenCaptureStore.state.value

        return buildString {
            if (semantic.isNotBlank()) {
                appendLine("Current screen:")
                appendLine(semantic)
            }

            if (visual.isRunning && visual.filePath != null) {
                appendLine()
                appendLine(
                    "Visual screen capture active: ${visual.width}x${visual.height}, " +
                        "capturedAt=${visual.capturedAt}"
                )
            }

            if (notifications.isNotBlank()) {
                appendLine()
                appendLine(notifications)
            }
        }.trim()
    }
}
