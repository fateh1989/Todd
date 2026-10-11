package com.todd.core.agent

import com.todd.core.ai.AIRequest
import java.util.Locale

/** Recognizes self-contained greetings; never erases or alters persistent memory. */
object ChatGreetingPolicy {
    private val punctuation = Regex("[!؟?،,.؛;:…]+")
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670]")
    private val whitespace = Regex("\\s+")

    private val greetings = setOf(
        "الو", "الوو", "الووو", "الوووو", "هلو",
        "اهلا", "اهلين", "هلا", "مرحبا", "مرحبا تود",
        "السلام عليكم", "صباح الخير", "مساء الخير",
        "hi", "hello", "hey", "hello todd"
    )

    fun isStandaloneGreeting(request: AIRequest): Boolean {
        // Respect explicit translation and research modes.
        if (!request.systemPrompt.isNullOrBlank() || request.webResearch) return false

        val normalized = request.prompt.lowercase(Locale.ROOT)
            .replace(diacritics, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace(punctuation, " ")
            .trim()
            .replace(whitespace, " ")
        return normalized in greetings
    }
}
