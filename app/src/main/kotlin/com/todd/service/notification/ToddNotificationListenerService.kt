package com.todd.service.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationContextItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long
)

class ToddNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val item = NotificationContextItem(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            postedAt = sbn.postTime
        )

        _recent.value = listOf(item) +
            _recent.value.filterNot { it.key == item.key }.take(MAX_ITEMS - 1)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        _recent.value = _recent.value.filterNot { it.key == sbn.key }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val initial = activeNotifications
            ?.map { sbn ->
                val extras = sbn.notification.extras
                NotificationContextItem(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    postedAt = sbn.postTime
                )
            }
            ?.sortedByDescending { it.postedAt }
            ?.take(MAX_ITEMS)
            .orEmpty()

        _recent.value = initial
    }

    companion object {
        private const val MAX_ITEMS = 40

        private val _recent = MutableStateFlow<List<NotificationContextItem>>(emptyList())
        val recent: StateFlow<List<NotificationContextItem>> = _recent.asStateFlow()

        fun currentContext(limit: Int = 12): String {
            val items = _recent.value.take(limit)
            if (items.isEmpty()) return ""

            return buildString {
                appendLine("Recent notifications:")
                items.forEach { item ->
                    append("- ")
                    append(item.packageName)
                    if (item.title.isNotBlank()) append(" | ").append(item.title)
                    if (item.text.isNotBlank()) append(": ").append(item.text)
                    appendLine()
                }
            }.trim()
        }
    }
}
