package com.todd.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenContextSnapshot(
    val packageName: String? = null,
    val className: String? = null,
    val text: String = "",
    val capturedAt: Long = 0L
)

class ToddAccessibilityService : AccessibilityService() {

    private var lastCaptureAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < MIN_CAPTURE_INTERVAL_MS) return
        lastCaptureAt = now
        captureCurrentScreen(event)
    }

    override fun onInterrupt() {}

    fun extractSemanticScreenText(): String {
        val builder = StringBuilder()
        val activeWindows = windows

        if (activeWindows.isNotEmpty()) {
            activeWindows.forEachIndexed { index, window ->
                val root = window.root ?: return@forEachIndexed
                builder.appendLine("[Window ${index + 1}]")
                traverseNode(root, builder, 0)
                root.recycle()
            }
        } else {
            val root = rootInActiveWindow
                ?: return latestSnapshot.value.text.ifBlank { "لا توجد عناصر شاشة متاحة حالياً" }
            traverseNode(root, builder, 0)
            root.recycle()
        }

        return builder.toString().trim()
    }

    private fun captureCurrentScreen(event: AccessibilityEvent?) {
        val text = extractSemanticScreenText()
        latestSnapshot.value = ScreenContextSnapshot(
            packageName = event?.packageName?.toString(),
            className = event?.className?.toString(),
            text = text,
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (
            node == null ||
            depth > MAX_DEPTH ||
            sb.length >= MAX_SCREEN_CONTEXT_CHARS
        ) return

        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val bounds = Rect().also { node.getBoundsInScreen(it) }

        if (
            text.isNotBlank() ||
            description.isNotBlank() ||
            hint.isNotBlank() ||
            viewId.isNotBlank() ||
            node.isClickable ||
            node.isEditable ||
            node.isFocused ||
            node.isSelected
        ) {
            sb.append("  ".repeat(depth))
            sb.append(role)

            if (text.isNotBlank()) sb.append(" text=\"").append(text).append('\"')
            if (description.isNotBlank()) sb.append(" description=\"").append(description).append('\"')
            if (hint.isNotBlank()) sb.append(" hint=\"").append(hint).append('\"')
            if (viewId.isNotBlank()) sb.append(" id=").append(viewId)

            sb.append(" bounds=[")
                .append(bounds.left).append(',')
                .append(bounds.top).append(',')
                .append(bounds.right).append(',')
                .append(bounds.bottom).append(']')

            if (node.isClickable) sb.append(" clickable")
            if (node.isEditable) sb.append(" editable")
            if (node.isFocused) sb.append(" focused")
            if (node.isSelected) sb.append(" selected")
            if (node.isChecked) sb.append(" checked")
            if (!node.isEnabled) sb.append(" disabled")
            sb.appendLine()
        }

        for (i in 0 until node.childCount) {
            if (sb.length >= MAX_SCREEN_CONTEXT_CHARS) break
            val child = node.getChild(i)
            try {
                traverseNode(child, sb, depth + 1)
            } finally {
                child?.recycle()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        captureCurrentScreen(null)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val MIN_CAPTURE_INTERVAL_MS = 3000L
        private const val MAX_DEPTH = 10
        private const val MAX_SCREEN_CONTEXT_CHARS = 6000

        @Volatile
        var instance: ToddAccessibilityService? = null
            private set

        private val latestSnapshot = MutableStateFlow(ScreenContextSnapshot())
        val screenContext: StateFlow<ScreenContextSnapshot> = latestSnapshot.asStateFlow()

        fun latestScreenText(): String = latestSnapshot.value.text

        fun latestScreenContext(): String {
            val snapshot = latestSnapshot.value
            return buildString {
                snapshot.packageName?.let { appendLine("Package: $it") }
                snapshot.className?.let { appendLine("Screen: $it") }
                if (snapshot.text.isNotBlank()) append(snapshot.text)
            }.trim()
        }
    }
}
