package com.todd.core.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

data class GeminiUsageSnapshot(
    val quotaDay: String,
    val flashLiteRequests: Int,
    val flashRequests: Int
)

class GeminiUsageTracker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    private val _usage = MutableStateFlow(loadCurrent())
    val usage: StateFlow<GeminiUsageSnapshot> = _usage.asStateFlow()

    fun recordRequest(modelName: String) {
        synchronized(lock) {
            val day = currentQuotaDay()
            if (prefs.getString(KEY_DAY, null) != day) {
                prefs.edit()
                    .putString(KEY_DAY, day)
                    .putInt(KEY_LITE, 0)
                    .putInt(KEY_STRONG, 0)
                    .apply()
            }

            val key = when (modelName) {
                GeminiCloudModel.FLASH_LITE_3_5.apiName -> KEY_LITE
                GeminiCloudModel.FLASH_3_8.apiName -> KEY_STRONG
                else -> return
            }
            val next = prefs.getInt(key, 0) + 1
            prefs.edit().putInt(key, next).apply()
            _usage.value = loadCurrent()
        }
    }

    fun refresh() {
        synchronized(lock) {
            _usage.value = loadCurrent()
        }
    }

    private fun loadCurrent(): GeminiUsageSnapshot {
        val day = currentQuotaDay()
        val storedDay = prefs.getString(KEY_DAY, null)
        if (storedDay != day) {
            prefs.edit()
                .putString(KEY_DAY, day)
                .putInt(KEY_LITE, 0)
                .putInt(KEY_STRONG, 0)
                .apply()
            return GeminiUsageSnapshot(day, 0, 0)
        }
        return GeminiUsageSnapshot(
            quotaDay = day,
            flashLiteRequests = prefs.getInt(KEY_LITE, 0),
            flashRequests = prefs.getInt(KEY_STRONG, 0)
        )
    }

    private fun currentQuotaDay(): String =
        LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    companion object {
        private const val PREFS_NAME = "todd_gemini_usage"
        private const val KEY_DAY = "quota_day"
        private const val KEY_LITE = "flash_lite_requests"
        private const val KEY_STRONG = "flash_requests"
    }
}
