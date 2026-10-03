package com.todd.service.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import com.todd.ToddApplication
import com.todd.core.ai.AIRequest
import kotlinx.coroutines.*

class ToddInputMethodService : InputMethodService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isArabic = true

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(8, 8, 8, 8)
        }

        // 1. Todd AI Action Toolbar
        val aiBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val aiBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val actions = listOf(
            "✨ Todd" to "ask",
            "صوّب (Correct)" to "correct",
            "أعد صياغة (Rewrite)" to "rewrite",
            "ترجم (Translate)" to "translate",
            "لخّص (Summarize)" to "summarize",
            "ردّ (Reply)" to "reply"
        )

        for ((label, actionKey) in actions) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFF8FAFC.toInt())
                setBackgroundColor(0xFF334155.toInt())
                setOnClickListener {
                    handleAIAction(actionKey)
                }
            }
            val marginParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(4, 4, 4, 4)
            }
            aiBarLayout.addView(btn, marginParams)
        }
        aiBar.addView(aiBarLayout)
        root.addView(aiBar)

        // 2. Keyboard typing layout container
        val keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Language toggle and space row
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 10f
        }

        val langBtn = Button(this).apply {
            text = if (isArabic) "EN" else "عربي"
            setOnClickListener {
                isArabic = !isArabic
                text = if (isArabic) "EN" else "عربي"
            }
        }
        val spaceBtn = Button(this).apply {
            text = "مسافة / Space"
            setOnClickListener {
                currentInputConnection?.commitText(" ", 1)
            }
        }
        val enterBtn = Button(this).apply {
            text = "↵ إدخال"
            setOnClickListener {
                currentInputConnection?.sendKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
                )
            }
        }
        val backspaceBtn = Button(this).apply {
            text = "⌫"
            setOnClickListener {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
        }

        bottomRow.addView(langBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        bottomRow.addView(spaceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 5f))
        bottomRow.addView(backspaceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        bottomRow.addView(enterBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))

        keysContainer.addView(bottomRow)
        root.addView(keysContainer)

        return root
    }

    private fun handleAIAction(action: String) {
        val ic = currentInputConnection ?: return

        // Extract selected text or text before cursor
        val selectedText = ic.getSelectedText(0)?.toString()
        val textBefore = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val targetText = if (!selectedText.isNullOrBlank()) selectedText else textBefore

        if (targetText.isBlank()) {
            Toast.makeText(this, "يرجى تحديد نص أو كتابة جملة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            try {
                val app = ToddApplication.instance
                val request = AIRequest(
                    prompt = "$action: $targetText",
                    selectedText = targetText,
                    projectContext = "Todd Personal Keyboard Session"
                )
                val result = app.aiRouter.route(request, app.stateMachine.state.value.aiMode)
                result.onSuccess { response ->
                    // Replace or insert text
                    ic.commitText(response.text, 1)
                }.onFailure { err ->
                    Toast.makeText(this@ToddInputMethodService, "تعذر معالجة الطلب: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
