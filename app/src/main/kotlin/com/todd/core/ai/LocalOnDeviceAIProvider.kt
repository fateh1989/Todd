package com.todd.core.ai

import android.graphics.BitmapFactory
import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(PublicPreviewAPI::class)
class LocalOnDeviceAIProvider(
    private val modelName: String = "gemini-3.5-flash-lite"
) : AIProvider {

    override val type: ProviderType = ProviderType.LOCAL_ON_DEVICE

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsText = true,
        supportsStreaming = false,
        supportsTools = false,
        supportsVision = true,
        supportsAudio = false,
        maxContextTokens = 32768,
        isLocal = true
    )

    private val model by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE)
        )
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            model.onDeviceExtension?.checkStatus() == OnDeviceModelStatus.AVAILABLE
        }.getOrDefault(false)
    }

    private suspend fun ensureModelReady() {
        val extension = model.onDeviceExtension
            ?: throw IllegalStateException("On-device Gemini is not supported on this device.")

        when (extension.checkStatus()) {
            OnDeviceModelStatus.AVAILABLE -> return
            OnDeviceModelStatus.DOWNLOADABLE,
            OnDeviceModelStatus.DOWNLOADING -> {
                val downloadFlow = extension.download()
                    ?: throw IllegalStateException("On-device model download is unavailable.")

                val terminal = downloadFlow.first {
                    it is DownloadStatus.DownloadCompleted ||
                        it is DownloadStatus.DownloadFailed
                }

                if (terminal is DownloadStatus.DownloadFailed) {
                    throw IllegalStateException("On-device model download failed.")
                }
            }
            else -> throw IllegalStateException("On-device model is unavailable on this device.")
        }
    }

    override suspend fun generateText(request: AIRequest): Result<AIResponse> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        try {
            ensureModelReady()

            val promptText = buildString {
                request.systemPrompt?.let { appendLine("System: $it\n") }
                request.projectContext?.let { appendLine("Project Context: $it\n") }
                request.screenContext?.let { appendLine("Screen Context: $it\n") }
                request.selectedText?.let { appendLine("Selected Text: $it\n") }
                appendLine("User: ${request.prompt}")
            }

            val imagePath = request.screenImagePath
            val response = if (!imagePath.isNullOrBlank() && File(imagePath).exists()) {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Could not decode the latest screen image.")
                    )
                try {
                    model.generateContent(
                        content {
                            image(bitmap)
                            text(promptText)
                        }
                    )
                } finally {
                    bitmap.recycle()
                }
            } else {
                model.generateContent(promptText)
            }

            if (response.inferenceSource != InferenceSource.ON_DEVICE) {
                return@withContext Result.failure(
                    IllegalStateException("Local-only inference did not run on-device.")
                )
            }

            val reply = response.text
                ?: return@withContext Result.failure(
                    IllegalStateException("On-device model returned empty content.")
                )

            Result.success(
                AIResponse(
                    text = reply,
                    providerUsed = ProviderType.LOCAL_ON_DEVICE,
                    isVerified = false,
                    tokensUsed = (promptText.length + reply.length) / 4,
                    latencyMs = System.currentTimeMillis() - start
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
