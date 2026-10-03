package com.todd.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ToddAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Debounced inspection of active window when user invokes Todd
    }

    override fun onInterrupt() {}

    fun extractSemanticScreenText(): String {
        val rootNode = rootInActiveWindow ?: return "لا يمكن الوصول إلى شجرة واجهة المستخدم الحالية"
        val sb = StringBuilder()

        traverseNode(rootNode, sb, 0)
        return sb.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 8) return

        // Respect privacy: never read password fields!
        if (node.isPassword) {
            sb.appendLine("  ".repeat(depth) + "[حقل محمي / Password Field]")
            return
        }

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName

        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
            val label = text ?: desc
            val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
            sb.appendLine("  ".repeat(depth) + "$role: $label ${if (viewId != null) "($viewId)" else ""}")
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), sb, depth + 1)
        }
    }

    companion object {
        var instance: ToddAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
