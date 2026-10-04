package com.todd.service.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
            setPadding(6, 6, 6, 6)
        }

        val aiBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val aiBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val actions = listOf(
            "✨ Todd" to "ask",
            "صوّب" to "correct",
            "أعد صياغة" to "rewrite",
            "ترجم" to "translate",
            "لخّص" to "summarize",
            "ردّ" to "reply",
            "اشرح" to "explain"
        )

        for ((label, actionKey) in actions) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { handleAIAction(actionKey) }
            }
            aiBarLayout.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(2, 2, 2, 2) }
            )
        }
        aiBar.addView(aiBarLayout)
        root.addView(aiBar)

        val keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(keysContainer)

        fun renderKeyboard() {
            keysContainer.removeAllViews()

            val rows = if (isArabic) {
                listOf(
                    listOf("ض","ص","ث","ق","ف","غ","ع","ه","خ","ح","ج","د"),
                    listOf("ش","س","ي","ب","ل","ا","ت","ن","م","ك","ط"),
                    listOf("ئ","ء","ؤ","ر","لا","ى","ة","و","ز","ظ","ذ")
                )
            } else {
                listOf(
                    listOf("q","w","e","r","t","y","u","i","o","p"),
                    listOf("a","s","d","f","g","h","j","k","l"),
                    listOf("z","x","c","v","b","n","m")
                )
            }

            rows.forEach { rowKeys ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                rowKeys.forEach { key ->
                    val keyButton = Button(this).apply {
                        text = key
                        textSize = if (isArabic) 18f else 16f
                        minWidth = 0
                        minimumWidth = 0
                        setPadding(0, 0, 0, 0)
                        setOnClickListener {
                            currentInputConnection?.commitText(key, 1)
                        }
                    }
                    row.addView(
                        keyButton,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply { setMargins(1, 1, 1, 1) }
                    )
                }

                keysContainer.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val langBtn = Button(this).apply {
                text = if (isArabic) "EN" else "عربي"
                setOnClickListener {
                    isArabic = !isArabic
                    renderKeyboard()
                }
            }

            val spaceBtn = Button(this).apply {
                text = if (isArabic) "مسافة" else "Space"
                setOnClickListener { currentInputConnection?.commitText(" ", 1) }
            }

            val backspaceBtn = Button(this).apply {
                text = "⌫"
                setOnClickListener {
                    val ic = currentInputConnection ?: return@setOnClickListener
                    val selected = ic.getSelectedText(0)
                    if (!selected.isNullOrEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                }
            }

            val enterBtn = Button(this).apply {
                text = "↵"
                setOnClickListener {
                    val ic = currentInputConnection ?: return@setOnClickListener
                    val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                        ?: EditorInfo.IME_ACTION_NONE
                    if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        ic.performEditorAction(action)
                    } else {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                }
            }

            bottomRow.addView(langBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
            bottomRow.addView(spaceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 5f))
            bottomRow.addView(backspaceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
            bottomRow.addView(enterBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))

            keysContainer.addView(bottomRow)
        }

        renderKeyboard()
        return root
    }

    private fun handleAIAction(action: String) {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)?.toString()
        val hasSelection = !selectedText.isNullOrBlank()
        val textBefore = ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        val targetText = if (hasSelection) selectedText.orEmpty() else textBefore

        if (targetText.isBlank()) {
            Toast.makeText(this, "حدد نصاً أو اكتب جملة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val instruction = when (action) {
            "correct" -> "صحح النص التالي مع الحفاظ على معناه"
            "rewrite" -> "أعد صياغة النص التالي بشكل طبيعي"
            "translate" -> "ترجم النص التالي إلى اللغة المناسبة للسياق"
            "summarize" -> "لخص النص التالي"
            "reply" -> "اكتب رداً مناسباً على النص التالي"
            "explain" -> "اشرح النص التالي ببساطة"
            else -> "أجب عن النص أو الطلب التالي"
        }

        serviceScope.launch {
            try {
                val app = ToddApplication.instance
                val request = AIRequest(
                    prompt = "$instruction:\n$targetText",
                    selectedText = targetText,
                    projectContext = "Todd keyboard"
                )
                val result = app.aiRouter.route(request, app.stateMachine.state.value.aiMode)
                result.onSuccess { response ->
                    if (!hasSelection) {
                        ic.deleteSurroundingText(targetText.length, 0)
                    }
                    ic.commitText(response.text, 1)
                }.onFailure { err ->
                    Toast.makeText(
                        this@ToddInputMethodService,
                        "تعذر معالجة الطلب: ${err.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ToddInputMethodService,
                    "تعذر تشغيل Todd: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
