package com.todd.core.ai

object GeminiApiErrorMessages {

    fun describe(
        statusCode: Int,
        apiMessage: String,
        modelName: String
    ): String {
        val modelLabel = when (modelName) {
            "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash-Lite"
            "gemini-3.8-flash" -> "Gemini 3.8 Flash"
            else -> modelName
        }

        return when {
            statusCode == 429 ->
                "وصل $modelLabel إلى حد الاستخدام أو معدل الطلبات الحالي. " +
                    "اختر النموذج الآخر من أعلى محادثة Todd، أو انتظر تجدد الحصة ثم أعد المحاولة."

            statusCode == 401 || statusCode == 403 ->
                "رفضت Google تفويض Gemini. تحقق من مفتاح Gemini API المحفوظ في إعدادات Todd."

            statusCode == 400 && apiMessage.contains("api key", ignoreCase = true) ->
                "مفتاح Gemini API مرفوض من Google. أضف مفتاحاً صالحاً من إعدادات Todd."

            statusCode in 500..599 ->
                "خدمة $modelLabel غير متاحة مؤقتاً لدى Google. حاول مرة أخرى بعد قليل."

            else ->
                "Gemini API HTTP $statusCode" +
                    if (apiMessage.isNotBlank()) ": $apiMessage" else ""
        }
    }
}
