package com.todd.service.screen

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenOcrSnapshot(
    val text: String = "",
    val capturedAt: Long = 0L,
    val source: String = "ML_KIT_LATIN",
    val error: String? = null
)

object ScreenOcrStore {
    private val _state = MutableStateFlow(ScreenOcrSnapshot())
    val state: StateFlow<ScreenOcrSnapshot> = _state.asStateFlow()

    internal fun update(text: String, capturedAt: Long) {
        _state.value = ScreenOcrSnapshot(
            text = text.trim(),
            capturedAt = capturedAt,
            source = "ML_KIT_LATIN",
            error = null
        )
    }

    internal fun recordFailure(capturedAt: Long, message: String?) {
        _state.value = _state.value.copy(
            capturedAt = capturedAt,
            error = message ?: "OCR failed"
        )
    }
}

object ScreenOcrProcessor {
    private const val MAX_OCR_CHARS = 8000

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun process(bitmap: Bitmap, capturedAt: Long, onComplete: () -> Unit) {
        val input = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(input)
            .addOnSuccessListener { result ->
                ScreenOcrStore.update(
                    text = result.text.take(MAX_OCR_CHARS),
                    capturedAt = capturedAt
                )
            }
            .addOnFailureListener { error ->
                ScreenOcrStore.recordFailure(capturedAt, error.message)
            }
            .addOnCompleteListener {
                onComplete()
            }
    }
}
