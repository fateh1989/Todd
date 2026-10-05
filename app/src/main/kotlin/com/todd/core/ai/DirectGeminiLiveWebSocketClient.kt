package com.todd.core.ai

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class DirectGeminiLiveCallbacks(
    val onInputTranscript: (String) -> Unit = {},
    val onOutputTranscript: (String) -> Unit = {},
    val onAudioOutput: () -> Unit = {},
    val onTurnComplete: () -> Unit = {},
    val onInterrupted: () -> Unit = {},
    val onError: (Throwable) -> Unit = {}
)

/**
 * Raw Gemini Live WebSocket transport for Todd.
 *
 * This path uses the owner's encrypted Gemini API key directly and therefore does not
 * require Firebase runtime configuration. Audio input is 16 kHz mono PCM16 and native
 * Gemini audio output is played as 24 kHz mono PCM16.
 *
 * Google recommends short-lived ephemeral tokens for production client-to-server apps.
 * Todd is a single-owner app and currently uses the owner's local API key; the key is never
 * logged or persisted by this class.
 */
class DirectGeminiLiveWebSocketClient(
    private val apiKeyProvider: () -> String?,
    private val modelName: String = "gemini-3.8-live",
    private val systemInstruction: String =
        "You are Todd, the owner's persistent Android personal assistant. " +
            "Answer naturally and concisely. Never claim an external action succeeded " +
            "unless Todd has real tool evidence."
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var connected = false

    @Volatile
    private var muted = false

    private var callbacks = DirectGeminiLiveCallbacks()
    private var tools: List<DirectToolDefinition> = emptyList()
    private var executeTool: (suspend (DirectToolCall) -> JSONObject)? = null
    private var webSocket: WebSocket? = null
    private var setupReady: CompletableDeferred<Unit>? = null

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var audioQueue: Channel<ByteArray>? = null

    fun isAvailable(): Boolean = !apiKeyProvider().isNullOrBlank()

    fun isConnected(): Boolean = connected

    suspend fun start(
        callbacks: DirectGeminiLiveCallbacks = DirectGeminiLiveCallbacks(),
        tools: List<DirectToolDefinition> = emptyList(),
        executeTool: (suspend (DirectToolCall) -> JSONObject)? = null
    ): Result<Unit> = runCatching {
        stop()
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        require(apiKey.isNotBlank()) { "Gemini API key is not configured." }

        this.callbacks = callbacks
        this.tools = tools
        this.executeTool = executeTool
        muted = false

        val ready = CompletableDeferred<Unit>()
        setupReady = ready

        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$LIVE_ENDPOINT?key=$encodedKey")
            .build()

        webSocket = httpClient.newWebSocket(request, socketListener)

        withTimeout(20_000L) {
            ready.await()
        }

        connected = true
        startPlayback()
        startMicrophone()
    }

    fun sendText(text: String): Boolean {
        if (!connected || text.isBlank()) return false
        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put("text", text)
            )
        return webSocket?.send(message.toString()) == true
    }

    fun sendVideo(file: File): Boolean {
        if (!connected || !file.exists() || !file.isFile) return false
        if (file.length() > MAX_VIDEO_FRAME_BYTES) return false

        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "video",
                    JSONObject()
                        .put("data", encoded)
                        .put("mimeType", mimeType)
                )
            )
        return webSocket?.send(message.toString()) == true
    }

    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value

        if (value) {
            stopMicrophone()
            if (connected) {
                webSocket?.send(
                    JSONObject()
                        .put(
                            "realtimeInput",
                            JSONObject().put("audioStreamEnd", true)
                        )
                        .toString()
                )
            }
        } else if (connected) {
            startMicrophone()
        }
    }

    fun stop() {
        connected = false
        setupReady?.takeIf { !it.isCompleted }?.cancel()
        setupReady = null

        stopMicrophone()
        stopPlayback()

        webSocket?.close(1000, "Todd Live session ended")
        webSocket = null
        tools = emptyList()
        executeTool = null
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setupBody = JSONObject()
                .put("model", "models/$modelName")
                .put(
                    "generationConfig",
                    JSONObject().put(
                        "responseModalities",
                        JSONArray().put("AUDIO")
                    )
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", systemInstruction)
                        )
                    )
                )

            if (tools.isNotEmpty()) {
                val declarations = JSONArray()
                tools.forEach { tool ->
                    declarations.put(
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.parameters)
                    )
                }
                setupBody.put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("functionDeclarations", declarations)
                    )
                )
            }

            val setup = JSONObject().put("setup", setupBody)

            if (!webSocket.send(setup.toString())) {
                setupReady?.completeExceptionally(
                    IllegalStateException("Unable to send Gemini Live setup.")
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = runCatching { JSONObject(text) }.getOrElse { error ->
                callbacks.onError(error)
                return
            }

            if (root.has("setupComplete")) {
                setupReady?.takeIf { !it.isCompleted }?.complete(Unit)
                return
            }

            val toolCall = root.optJSONObject("toolCall")
            val functionCalls = toolCall?.optJSONArray("functionCalls")
            if (functionCalls != null && functionCalls.length() > 0) {
                val executor = executeTool
                if (executor == null) {
                    callbacks.onError(
                        IllegalStateException(
                            "Gemini Live requested a Todd tool but no direct tool executor is connected."
                        )
                    )
                } else {
                    scope.launch {
                        val functionResponses = JSONArray()
                        for (i in 0 until functionCalls.length()) {
                            val raw = functionCalls.optJSONObject(i) ?: continue
                            val id = raw.optString("id")
                            val name = raw.optString("name")
                            if (id.isBlank() || name.isBlank()) continue

                            val call = DirectToolCall(
                                id = id,
                                name = name,
                                arguments = raw.optJSONObject("args") ?: JSONObject()
                            )
                            val result = runCatching { executor(call) }.getOrElse { error ->
                                JSONObject()
                                    .put("ok", false)
                                    .put("error", error.message ?: error::class.java.simpleName)
                            }

                            functionResponses.put(
                                JSONObject()
                                    .put("id", id)
                                    .put("name", name)
                                    .put(
                                        "response",
                                        JSONObject().put("result", result)
                                    )
                            )
                        }

                        if (functionResponses.length() > 0) {
                            webSocket.send(
                                JSONObject()
                                    .put(
                                        "toolResponse",
                                        JSONObject().put(
                                            "functionResponses",
                                            functionResponses
                                        )
                                    )
                                    .toString()
                            )
                        }
                    }
                }
            }

            val serverContent = root.optJSONObject("serverContent")
            if (serverContent != null) {
                serverContent.optJSONObject("inputTranscription")
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(callbacks.onInputTranscript)

                serverContent.optJSONObject("outputTranscription")
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(callbacks.onOutputTranscript)

                if (serverContent.optBoolean("interrupted", false)) {
                    clearPlayback()
                    callbacks.onInterrupted()
                }

                val parts = serverContent
                    .optJSONObject("modelTurn")
                    ?.optJSONArray("parts")

                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val inlineData = parts.optJSONObject(i)
                            ?.optJSONObject("inlineData")
                            ?: continue
                        val data = inlineData.optString("data")
                        if (data.isBlank()) continue
                        val audio = runCatching {
                            Base64.decode(data, Base64.DEFAULT)
                        }.getOrNull() ?: continue

                        callbacks.onAudioOutput()
                        audioQueue?.trySend(audio)
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    callbacks.onTurnComplete()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            stopMicrophone()
            stopPlayback()
            setupReady?.takeIf { !it.isCompleted }?.completeExceptionally(
                IllegalStateException(
                    "Gemini Live closed before setup completed ($code): $reason"
                )
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            stopMicrophone()
            stopPlayback()
            setupReady?.takeIf { !it.isCompleted }?.completeExceptionally(t)
            callbacks.onError(t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophone() {
        if (!connected || muted || captureJob?.isActive == true) return

        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            callbacks.onError(
                IllegalStateException("Android could not allocate a microphone buffer.")
            )
            return
        }

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(INPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer, INPUT_CHUNK_BYTES * 2))
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            callbacks.onError(
                IllegalStateException("Android microphone could not be initialized.")
            )
            return
        }

        audioRecord = record
        captureJob = scope.launch {
            try {
                record.startRecording()
                val buffer = ByteArray(INPUT_CHUNK_BYTES)

                while (isActive && connected && !muted) {
                    val read = record.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    )
                    if (read <= 0) continue

                    val encoded = Base64.encodeToString(
                        if (read == buffer.size) buffer else buffer.copyOf(read),
                        Base64.NO_WRAP
                    )

                    val message = JSONObject()
                        .put(
                            "realtimeInput",
                            JSONObject().put(
                                "audio",
                                JSONObject()
                                    .put("data", encoded)
                                    .put(
                                        "mimeType",
                                        "audio/pcm;rate=$INPUT_SAMPLE_RATE"
                                    )
                            )
                        )

                    if (webSocket?.send(message.toString()) != true) {
                        break
                    }
                }
            } catch (error: Throwable) {
                if (connected && !muted) callbacks.onError(error)
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                if (audioRecord === record) audioRecord = null
            }
        }
    }

    private fun stopMicrophone() {
        captureJob?.cancel()
        captureJob = null

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun startPlayback() {
        stopPlayback()

        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            callbacks.onError(
                IllegalStateException("Android could not allocate an audio playback buffer.")
            )
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer, OUTPUT_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            callbacks.onError(
                IllegalStateException("Android audio output could not be initialized.")
            )
            return
        }

        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        audioQueue = queue
        audioTrack = track
        track.play()

        playbackJob = scope.launch {
            try {
                for (chunk in queue) {
                    if (!isActive) break
                    track.write(
                        chunk,
                        0,
                        chunk.size,
                        AudioTrack.WRITE_BLOCKING
                    )
                }
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    private fun clearPlayback() {
        val queue = audioQueue
        if (queue != null) {
            while (queue.tryReceive().isSuccess) {
                // Drain audio generated before the interruption.
            }
        }

        val track = audioTrack ?: return
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
    }

    private fun stopPlayback() {
        audioQueue?.close()
        audioQueue = null

        playbackJob?.cancel()
        playbackJob = null

        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    companion object {
        private const val LIVE_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000

        // 100 ms of PCM16 mono at 16 kHz.
        private const val INPUT_CHUNK_BYTES = 3_200
        private const val OUTPUT_BUFFER_BYTES = 24_000
        private const val MAX_VIDEO_FRAME_BYTES = 5L * 1024L * 1024L
    }
}
