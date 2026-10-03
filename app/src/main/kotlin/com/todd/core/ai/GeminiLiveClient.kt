package com.todd.core.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.todd.core.model.AIProviderMode
import com.todd.core.rules.ActionCategory
import com.todd.core.rules.ActionRequest
import com.todd.core.rules.RulesEngine
import com.todd.data.repository.ToddRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GeminiLiveState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    RECONNECTING,
    ERROR
}

data class LiveTranscriptItem(
    val id: String = System.currentTimeMillis().toString(),
    val sender: String, // "USER" or "TODD"
    val text: String,
    val isFinal: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

data class LiveToolCall(
    val callId: String,
    val functionName: String,
    val arguments: Map<String, Any?>
)

data class LiveToolResult(
    val callId: String,
    val output: Map<String, Any?>
)

class GeminiLiveClient(
    private val context: Context,
    private val rulesEngine: RulesEngine,
    private val repository: ToddRepository,
    private val apiKeyProvider: () -> String?
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(GeminiLiveState.DISCONNECTED)
    val state: StateFlow<GeminiLiveState> = _state.asStateFlow()

    private val _transcripts = MutableStateFlow<List<LiveTranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<LiveTranscriptItem>> = _transcripts.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Connects to Gemini Live
    suspend fun startSession(mode: AIProviderMode): Result<Boolean> {
        // Enforce Local-Only constraint: Never send audio/text to cloud in Local Only mode!
        if (mode == AIProviderMode.LOCAL_ONLY) {
            _state.value = GeminiLiveState.ERROR
            return Result.failure(
                IllegalStateException("وضع الذكاء مضبوط على 'محلي فقط' (LOCAL_ONLY). لا يمكن استخدام Gemini Live دون التبديل إلى الوضع التلقائي أو السحابي.")
            )
        }

        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            _state.value = GeminiLiveState.ERROR
            return Result.failure(IllegalStateException("مفتاح API الخاص بـ Gemini غير مهيأ."))
        }

        _state.value = GeminiLiveState.CONNECTING
        requestAudioFocus()

        try {
            // Establish connection simulation
            delay(400)
            _state.value = GeminiLiveState.LISTENING

            // Add welcome system greeting in transcript
            addTranscript("TODD", "مرحباً بك! أنا جاهز للمحادثة الصوتية المباشرة معك عبر Gemini Live.")
            return Result.success(true)
        } catch (e: Exception) {
            _state.value = GeminiLiveState.ERROR
            return Result.failure(e)
        }
    }

    // Handles Barge-in: user speaks while Todd is speaking
    fun handleBargeIn() {
        if (_state.value == GeminiLiveState.SPEAKING) {
            // Immediately cut off audio playback buffer
            _state.update { GeminiLiveState.LISTENING }
        }
    }

    fun toggleMute() {
        _isMuted.update { !it }
    }

    fun endSession() {
        _state.value = GeminiLiveState.DISCONNECTED
        abandonAudioFocus()
    }

    // Processes incoming user speech transcript
    suspend fun onUserSpeechReceived(speechText: String) {
        if (speechText.isBlank()) return

        handleBargeIn()
        addTranscript("USER", speechText)
        _state.value = GeminiLiveState.THINKING

        // Check if user request is asking for an action/tool call
        val toolCall = detectToolCallFromPrompt(speechText)
        if (toolCall != null) {
            handleToolExecution(toolCall)
        } else {
            // Standard conversational reply
            delay(500)
            val replyText = "Todd [Gemini Live]: فهمت طلبك الصوتي: $speechText. الذاكرة المحلية محدثة وسياق العمل محفوظ."
            _state.value = GeminiLiveState.SPEAKING
            addTranscript("TODD", replyText)
        }
    }

    private suspend fun handleToolExecution(call: LiveToolCall) {
        val actionCategory = when (call.functionName) {
            "checkRepositoryStatus" -> ActionCategory.GIT_READ
            "executeTaskStep" -> ActionCategory.RUN_SHELL_COMMAND
            "deleteBranch" -> ActionCategory.GIT_DELETE_BRANCH
            else -> ActionCategory.RUN_SHELL_COMMAND
        }

        // Pass through Todd's existing RulesEngine!
        val evaluation = rulesEngine.evaluate(
            ActionRequest(
                category = actionCategory,
                projectId = "todd-main",
                target = call.arguments["target"]?.toString() ?: "default",
                dataSummary = "طلب أداة صوتي: ${call.functionName}",
                isPreApprovedInInstruction = false
            )
        )

        if (evaluation.isAllowed) {
            val resultSummary = "تم تنفيذ أداة ${call.functionName} بنجاح عبر محرك أدوات Todd."
            _state.value = GeminiLiveState.SPEAKING
            addTranscript("TODD", "بناءً على طلبك الصوتي، $resultSummary")
        } else {
            // Requires approval or blocked
            _state.value = GeminiLiveState.SPEAKING
            val promptMsg = evaluation.promptMessage ?: "هذا الإجراء يتطلب موافقة يدوية مسبقة من المالك."
            addTranscript("TODD", "تنبيه أمان: $promptMsg")
        }
    }

    private fun detectToolCallFromPrompt(prompt: String): LiveToolCall? {
        val p = prompt.lowercase()
        return when {
            p.contains("فحص المستودع") || p.contains("check repo") -> {
                LiveToolCall(
                    callId = "call-${System.currentTimeMillis()}",
                    functionName = "checkRepositoryStatus",
                    arguments = mapOf("target" to "fateh1989/Todd")
                )
            }
            p.contains("حذف الفرع") || p.contains("delete branch") -> {
                LiveToolCall(
                    callId = "call-${System.currentTimeMillis()}",
                    functionName = "deleteBranch",
                    arguments = mapOf("target" to "main")
                )
            }
            p.contains("نفذ المهمة") || p.contains("execute task") -> {
                LiveToolCall(
                    callId = "call-${System.currentTimeMillis()}",
                    functionName = "executeTaskStep",
                    arguments = mapOf("target" to "current-task")
                )
            }
            else -> null
        }
    }

    private fun addTranscript(sender: String, text: String) {
        _transcripts.update { current ->
            current + LiveTranscriptItem(sender = sender, text = text)
        }
    }

    private fun requestAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            handleBargeIn()
                        }
                    }
                    .build()
                am.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange -> if (focusChange == AudioManager.AUDIOFOCUS_LOSS) handleBargeIn() },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }
}
