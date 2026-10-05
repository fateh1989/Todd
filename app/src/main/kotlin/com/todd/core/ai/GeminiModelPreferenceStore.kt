package com.todd.core.ai

import android.content.Context

enum class GeminiCloudModel(
    val apiName: String,
    val displayName: String
) {
    FLASH_LITE_3_1(
        apiName = "gemini-3.1-flash-lite",
        displayName = "Gemini 3.1 Flash-Lite"
    ),
    FLASH_3_8(
        apiName = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash"
    )
}

class GeminiModelPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun get(): GeminiCloudModel {
        val stored = prefs.getString(KEY_MODEL, null)
        return GeminiCloudModel.entries.firstOrNull { it.name == stored }
            ?: GeminiCloudModel.FLASH_3_8
    }

    fun set(model: GeminiCloudModel) {
        prefs.edit().putString(KEY_MODEL, model.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "todd_gemini_model"
        private const val KEY_MODEL = "selected_model"
    }
}
