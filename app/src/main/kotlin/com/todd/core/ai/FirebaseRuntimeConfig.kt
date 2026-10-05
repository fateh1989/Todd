package com.todd.core.ai

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

data class FirebaseRuntimeStatus(
    val configured: Boolean,
    val projectId: String?,
    val reason: String?
)

object FirebaseRuntimeConfig {

    private const val RUNTIME_APP_NAME = "todd-runtime-ai"

    fun applyStored(
        context: Context,
        store: FirebaseRuntimeCredentialStore
    ): FirebaseRuntimeStatus {
        val credentials = store.getCredentials() ?: return current()
        return configure(context, credentials)
    }

    fun configure(
        context: Context,
        credentials: FirebaseRuntimeCredentials
    ): FirebaseRuntimeStatus {
        validateCredentials(credentials)

        val existing = runtimeAppOrNull()
        if (existing != null) {
            val options = existing.options
            val sameConfiguration =
                options.projectId == credentials.projectId &&
                    options.applicationId == credentials.applicationId &&
                    options.apiKey == credentials.apiKey

            if (sameConfiguration) return statusFor(existing)
            existing.delete()
        }

        val options = FirebaseOptions.Builder()
            .setProjectId(credentials.projectId)
            .setApplicationId(credentials.applicationId)
            .setApiKey(credentials.apiKey)
            .build()

        val app = FirebaseApp.initializeApp(
            context.applicationContext,
            options,
            RUNTIME_APP_NAME
        )

        return statusFor(app)
    }

    fun clearRuntimeApp() {
        runtimeAppOrNull()?.delete()
    }

    fun current(): FirebaseRuntimeStatus {
        val runtime = runtimeAppOrNull()
        if (runtime != null) {
            val status = statusFor(runtime)
            if (status.configured) return status
        }

        val default = defaultAppOrNull()
        if (default != null) {
            val status = statusFor(default)
            if (status.configured) return status
            if (runtime == null) return status
        }

        return runtime?.let(::statusFor)
            ?: FirebaseRuntimeStatus(
                configured = false,
                projectId = null,
                reason = "Firebase is not initialized."
            )
    }

    fun requireConfigured() {
        requireConfiguredApp()
    }

    fun requireConfiguredApp(): FirebaseApp {
        val runtime = runtimeAppOrNull()
        if (runtime != null && statusFor(runtime).configured) {
            return runtime
        }

        val default = defaultAppOrNull()
        if (default != null && statusFor(default).configured) {
            return default
        }

        val status = current()
        throw IllegalStateException(
            status.reason
                ?: "Firebase cloud AI is not configured for runtime use."
        )
    }

    fun preferredApp(): FirebaseApp =
        runtimeAppOrNull() ?: FirebaseApp.getInstance()

    private fun runtimeAppOrNull(): FirebaseApp? =
        runCatching { FirebaseApp.getInstance(RUNTIME_APP_NAME) }.getOrNull()

    private fun defaultAppOrNull(): FirebaseApp? =
        runCatching { FirebaseApp.getInstance() }.getOrNull()

    private fun statusFor(app: FirebaseApp): FirebaseRuntimeStatus {
        val options = app.options
        val projectId = options.projectId?.trim().orEmpty()
        val apiKey = options.apiKey.trim()
        val applicationId = options.applicationId.trim()

        val placeholder =
            projectId.isBlank() ||
                projectId.startsWith("todd-ci", ignoreCase = true) ||
                projectId.startsWith("todd-remote-ci", ignoreCase = true) ||
                apiKey.contains("DummyKey", ignoreCase = true) ||
                applicationId.contains("000000000000")

        return if (placeholder) {
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
    }

    private fun validateCredentials(credentials: FirebaseRuntimeCredentials) {
        require(credentials.projectId.isNotBlank()) { "Firebase project ID is required." }
        require(credentials.applicationId.isNotBlank()) { "Firebase application ID is required." }
        require(credentials.apiKey.isNotBlank()) { "Firebase API key is required." }

        require(!credentials.projectId.startsWith("todd-ci", ignoreCase = true)) {
            "Build-only Firebase placeholder cannot be used at runtime."
        }
        require(!credentials.apiKey.contains("DummyKey", ignoreCase = true)) {
            "Build-only Firebase API key cannot be used at runtime."
        }
    }
}
