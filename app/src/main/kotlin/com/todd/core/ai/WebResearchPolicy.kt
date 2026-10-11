package com.todd.core.ai

import java.util.Locale

/** Shared policy for deciding when Todd may use a live web-search tool. */
object WebResearchPolicy {
    fun isRequested(request: AIRequest): Boolean =
        request.webResearch || isRequested(request.prompt)

    fun isRequested(prompt: String): Boolean {
        val normalized = prompt.lowercase(Locale.ROOT)
        return ARABIC_SIGNALS.any { normalized.contains(it) } ||
            ENGLISH_SIGNALS.any { normalized.contains(it) }
    }

    fun instructions(): String = """
        This is an explicit web-research request. Use web search before answering; do not answer
        from model memory alone. Prefer primary and official sources, compare independent sources
        when available, and state the relevant dates. Put numbered source markers such as [1] after
        factual claims that the sources support. Separate confirmed facts from uncertainty or
        conflicting reports. If search returns no usable sources, say that the research could not
        be verified instead of inventing results. Respect the country, city, language, and time
        period explicitly requested by the user; never infer or transmit device location.
    """.trimIndent()

    fun noSourcesMessage(): String =
        "تعذر إجراء بحث موثق على الإنترنت الآن: لم تصل نتائج بمصادر قابلة للفحص. لم أقدّم إجابة من الذاكرة على أنها بحث حديث."

    fun localOnlyMessage(): String =
        "البحث على الإنترنت غير متاح في وضع «محلي فقط». غيّر وضع الذكاء إلى «تلقائي» أو «سحابي» لتنفيذ البحث؛ لم يُرسل طلب البحث إلى الإنترنت."

    private val ARABIC_SIGNALS = listOf(
        "ابحث", "البحث على الإنترنت", "بحث على الإنترنت", "بحث في الإنترنت",
        "على الانترنت", "عبر الانترنت", "على الويب", "في الويب", "مصادر حديثة",
        "آخر الأخبار", "اخر الاخبار", "تحقق من الإنترنت", "تحقق على الإنترنت"
    )

    private val ENGLISH_SIGNALS = listOf(
        "search online", "web search", "search the web", "research this", "do research",
        "latest news", "current information", "find sources", "look up online"
    )
}

class WebResearchUnavailableException(message: String) : IllegalStateException(message)
