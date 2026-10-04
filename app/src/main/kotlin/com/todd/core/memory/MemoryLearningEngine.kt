package com.todd.core.memory

import com.todd.core.model.MemoryEntry
import com.todd.core.model.MemoryLayer
import com.todd.data.repository.ToddRepository

class MemoryLearningEngine(
    private val repository: ToddRepository
) {
    suspend fun learnExplicitInstruction(
        projectId: String,
        userMessage: String
    ): MemoryEntry? {
        val text = userMessage.trim()
        if (text.isBlank()) return null

        val normalized = text.lowercase()
        val isExplicitDurableInstruction = DURABLE_SIGNALS.any { normalized.contains(it) }
        if (!isExplicitDurableInstruction) return null

        val layer = if (
            normalized.contains("هذا المشروع") ||
            normalized.contains("في المشروع") ||
            normalized.contains("project")
        ) {
            MemoryLayer.PROJECT
        } else {
            MemoryLayer.PREFERENCES
        }

        val stableKey = "learned:" + text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .take(96)
            .hashCode()
            .toUInt()
            .toString(16)

        val memory = MemoryEntry(
            id = "learned-${System.nanoTime()}",
            projectId = if (layer == MemoryLayer.PROJECT) projectId else null,
            layer = layer,
            key = stableKey,
            value = text,
            provenance = "USER",
            isVerified = true,
            confidence = 1.0f
        )

        repository.saveMemory(memory)
        return memory
    }

    companion object {
        private val DURABLE_SIGNALS = listOf(
            "من الآن",
            "دائماً",
            "دائما",
            "تذكر",
            "تذكّر",
            "لا تفعل",
            "لا تستخدم",
            "لا تقل",
            "أبداً",
            "ابداً",
            "افضل ان",
            "أفضل أن",
            "اريدك ان",
            "أريدك أن",
            "always",
            "from now on",
            "remember",
            "never ",
            "do not ",
            "don't "
        )
    }
}
