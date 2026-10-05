package com.todd.service.ime

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.todd.ToddApplication
import com.todd.core.ai.AIRequest
import com.todd.core.model.MemoryEntry
import com.todd.core.model.MemoryLayer
import com.todd.service.context.DeviceContextProvider
import com.todd.service.screen.ScreenCaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ToddInputMethodService : InputMethodService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isArabic = true
    private var drawerVisible = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
        }

        fun addTopButton(label: String, action: () -> Unit, weight: Float = 1f) {
            val button = Button(this).apply {
                text = label
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minimumHeight = dp(42)
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { action() }
            }
            topBar.addView(
                button,
                LinearLayout.LayoutParams(0, dp(44), weight).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
            )
        }

        addTopButton("✨ Todd", { handleAIAction("ask") }, 1.3f)
        addTopButton("صحح", { handleAIAction("correct") })
        addTopButton("ترجم", { handleAIAction("translate") })
        addTopButton("⋮", {
            drawerVisible = !drawerVisible
            drawer.visibility = if (drawerVisible) View.VISIBLE else View.GONE
        }, 0.65f)

        root.addView(topBar)

        fun drawerRow(vararg items: Pair<String, String>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            items.forEach { (label, action) ->
                val button = Button(this).apply {
                    text = label
                    textSize = 11f
                    minWidth = 0
                    minimumWidth = 0
                    minimumHeight = dp(42)
                    setPadding(dp(2), 0, dp(2), 0)
                    setOnClickListener {
                        drawerVisible = false
                        drawer.visibility = View.GONE
                        when (action) {
                            "paste" -> pasteClipboard()
                            "send_project" -> sendCurrentTextToProject()
                            else -> handleAIAction(action)
                        }
                    }
                }
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    }
                )
            }

            drawer.addView(row)
        }

        drawerRow(
            "إعادة صياغة" to "rewrite",
            "لخّص" to "summarize",
            "ردّ" to "reply",
            "اشرح" to "explain"
        )
        drawerRow(
            "أكمل" to "continue",
            "قصّر" to "shorten",
            "وسّع" to "expand",
            "غيّر النبرة" to "tone"
        )
        drawerRow(
            "بحث" to "research",
            "إلى المشروع" to "send_project",
            "لصق" to "paste",
            "Todd" to "ask"
        )

        root.addView(drawer)

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
                        textSize = if (isArabic) 19f else 17f
                        minWidth = 0
                        minimumWidth = 0
                        minimumHeight = dp(48)
                        setPadding(0, 0, 0, 0)
                        setOnClickListener {
                            currentInputConnection?.commitText(key, 1)
                        }
                    }
                    row.addView(
                        keyButton,
                        LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                            setMargins(dp(1), dp(1), dp(1), dp(1))
                        }
                    )
                }

                keysContainer.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                    )
                )
            }

            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val langBtn = Button(this).apply {
                text = if (isArabic) "EN" else "عربي"
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    isArabic = !isArabic
                    renderKeyboard()
                }
            }

            val spaceBtn = Button(this).apply {
                text = if (isArabic) "مسافة" else "Space"
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { currentInputConnection?.commitText(" ", 1) }
            }

            val backspaceBtn = Button(this).apply {
                text = "⌫"
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    val ic = currentInputConnection ?: return@setOnClickListener
                    val selected = ic.getSelectedText(0)
                    if (!selected.isNullOrEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                }
                setOnLongClickListener {
                    currentInputConnection?.deleteSurroundingText(8, 0)
                    true
                }
            }

            val enterBtn = Button(this).apply {
                text = "↵"
                minWidth = 0
                minimumWidth = 0
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

            bottomRow.addView(langBtn, LinearLayout.LayoutParams(0, dp(52), 1.4f))
            bottomRow.addView(spaceBtn, LinearLayout.LayoutParams(0, dp(52), 5.2f))
            bottomRow.addView(backspaceBtn, LinearLayout.LayoutParams(0, dp(52), 1.5f))
            bottomRow.addView(enterBtn, LinearLayout.LayoutParams(0, dp(52), 1.5f))

            keysContainer.addView(bottomRow)
        }

        renderKeyboard()
        return root
    }

    private fun currentTargetText(): Pair<String, Boolean> {
        val ic = currentInputConnection ?: return "" to false
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) return selected to true

        val before = ic.getTextBeforeCursor(700, 0)?.toString().orEmpty()
        return before to false
    }

    private fun handleAIAction(action: String) {
        val ic = currentInputConnection ?: return
        val (targetText, hasSelection) = currentTargetText()
        val screenContext = DeviceContextProvider.currentTextContext()
        val effectiveText = if (targetText.isNotBlank()) targetText else screenContext

        if (effectiveText.isBlank()) {
            Toast.makeText(this, "لا يوجد نص أو سياق شاشة متاح حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val instruction = when (action) {
            "correct" -> "صحح النص التالي مع الحفاظ على المعنى وبأقل تغيير ضروري"
            "rewrite" -> "أعد صياغة النص التالي بشكل طبيعي وواضح"
            "translate" -> "ترجم النص التالي إلى اللغة المناسبة للسياق"
            "summarize" -> "لخص النص التالي باختصار"
            "reply" -> "اكتب رداً مناسباً وطبيعياً على النص التالي"
            "explain" -> "اشرح النص التالي ببساطة"
            "continue" -> "أكمل النص التالي بنفس الأسلوب والسياق"
            "shorten" -> "اختصر النص التالي مع الحفاظ على المعنى"
            "expand" -> "وسّع النص التالي بإضافة تفاصيل مفيدة دون حشو"
            "tone" -> "أعد كتابة النص التالي بنبرة أنسب للسياق"
            "research" -> "ابحث بحثاً حياً عن الموضوع التالي وأعطني خلاصة دقيقة مع المصادر المتاحة"
            else -> "أجب عن النص أو الطلب التالي"
        }

        serviceScope.launch {
            try {
                val app = ToddApplication.instance
                val projectId = app.stateMachine.state.value.activeProjectId ?: "todd-main"
                val memoryContext = app.repository.buildProjectContext(projectId)

                val request = AIRequest(
                    prompt = "$instruction:\n$effectiveText",
                    selectedText = if (hasSelection) targetText else null,
                    screenContext = screenContext.ifBlank { null },
                    screenImagePath = ScreenCaptureStore.latestFile()?.absolutePath,
                    projectContext = memoryContext
                )

                val result = app.textAgent.respond(
                    request,
                    app.stateMachine.state.value.aiMode
                )
                result.onSuccess { response ->
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

    private fun pasteClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

        if (text.isBlank()) {
            Toast.makeText(this, "الحافظة فارغة", Toast.LENGTH_SHORT).show()
            return
        }

        currentInputConnection?.commitText(text, 1)
    }

    private fun sendCurrentTextToProject() {
        val (targetText, _) = currentTargetText()
        val value = targetText.ifBlank { DeviceContextProvider.currentTextContext() }

        if (value.isBlank()) {
            Toast.makeText(this, "لا يوجد نص لإرساله إلى المشروع", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            val app = ToddApplication.instance
            val projectId = app.stateMachine.state.value.activeProjectId ?: "todd-main"
            app.repository.saveMemory(
                MemoryEntry(
                    id = "keyboard-project-${System.nanoTime()}",
                    projectId = projectId,
                    layer = MemoryLayer.PROJECT,
                    key = "keyboard-note:${System.currentTimeMillis()}",
                    value = value,
                    provenance = "USER",
                    isVerified = true
                )
            )
            Toast.makeText(
                this@ToddInputMethodService,
                "تمت إضافة النص إلى ذاكرة المشروع",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
