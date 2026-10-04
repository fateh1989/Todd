package com.todd.core.ai

import com.google.firebase.FirebaseApp

data class FirebaseRuntimeStatus(
    val configured: Boolean,
    val projectId: String?,
    val reason: String?
)

object FirebaseRuntimeConfig {

    fun current(): FirebaseRuntimeStatus {
        return runCatching {
            val options = FirebaseApp.getInstance().options
            val projectId = options.projectId?.trim().orEmpty()
            val apiKey = options.apiKey.trim()
            val applicationId = options.applicationId.trim()

            val placeholder =
                projectId.isBlank() ||
                    projectId.startsWith("todd-ci", ignoreCase = true) ||
                    projectId.startsWith("todd-remote-ci", ignoreCase = true) ||
                    apiKey.contains("DummyKey", ignoreCase = true) ||
                    applicationId.contains("000000000000")

            if (placeholder) {
                FirebaseRuntimeStatus(
                    configured = false,
                    projectId = projectId.ifBlank { null },
                    reason = "Firebase runtime is using a build-only placeholder configuration."
                )
            } else {
                FirebaseRuntimeStatus(
                    configured = true,
                    projectId = projectId,
                    reason = null
                )
            }
        }.getOrElse { error ->
            FirebaseRuntimeStatus(
                configured = false,
                projectId = null,
                reason = error.message ?: "Firebase is not initialized."
            )
        }
    }

    fun requireConfigured() {
        val status = current()
        if (!status.configured) {
            throw IllegalStateException(
                "Firebase cloud AI is not configured for runtime use. " +
                    "Install a build produced with the real Firebase google-services.json configuration."
            )
        }
    }
}
