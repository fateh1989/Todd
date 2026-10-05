package com.todd.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class FirebaseRuntimeCredentials(
    val projectId: String,
    val applicationId: String,
    val apiKey: String
)

class FirebaseRuntimeCredentialStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasCredentials(): Boolean =
        prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    fun saveGoogleServicesJson(rawJson: String): FirebaseRuntimeCredentials {
        val credentials = parseGoogleServicesJson(
            rawJson = rawJson,
            expectedPackage = context.packageName
        )
        save(credentials)
        return credentials
    }

    fun save(credentials: FirebaseRuntimeCredentials) {
        require(credentials.projectId.isNotBlank()) { "Firebase project ID is required." }
        require(credentials.applicationId.isNotBlank()) { "Firebase application ID is required." }
        require(credentials.apiKey.isNotBlank()) { "Firebase API key is required." }

        val plain = listOf(
            credentials.projectId.trim(),
            credentials.applicationId.trim(),
            credentials.apiKey.trim()
        ).joinToString("\n")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun getCredentials(): FirebaseRuntimeCredentials? {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )

            val plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            val parts = plain.split("\n", limit = 3)
            require(parts.size == 3) { "Stored Firebase configuration is incomplete." }

            FirebaseRuntimeCredentials(
                projectId = parts[0],
                applicationId = parts[1],
                apiKey = parts[2]
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "todd_firebase_runtime_credentials"
        private const val KEY_CIPHERTEXT = "firebase_config_ciphertext"
        private const val KEY_IV = "firebase_config_iv"
        private const val KEY_ALIAS = "todd_firebase_runtime_aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun parseGoogleServicesJson(
            rawJson: String,
            expectedPackage: String = "com.todd"
        ): FirebaseRuntimeCredentials {
            require(rawJson.isNotBlank()) { "google-services.json is empty." }

            val root = Json.parseToJsonElement(rawJson).jsonObject
            val projectId = root["project_info"]
                ?.jsonObject
                ?.get("project_id")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()

            val clients = root["client"]?.jsonArray.orEmpty()
            require(clients.isNotEmpty()) { "google-services.json has no Android clients." }

            val selected = clients
                .map { it.jsonObject }
                .firstOrNull { client ->
                    client["client_info"]
                        ?.jsonObject
                        ?.get("android_client_info")
                        ?.jsonObject
                        ?.get("package_name")
                        ?.jsonPrimitive
                        ?.content == expectedPackage
                }
                ?: clients.first().jsonObject

            val applicationId = selected["client_info"]
                ?.jsonObject
                ?.get("mobilesdk_app_id")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()

            val apiKey = selected["api_key"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("current_key")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()

            require(projectId.isNotBlank()) { "Firebase project_id is missing." }
            require(applicationId.isNotBlank()) { "Firebase mobilesdk_app_id is missing." }
            require(apiKey.isNotBlank()) { "Firebase current_key is missing." }

            return FirebaseRuntimeCredentials(
                projectId = projectId,
                applicationId = applicationId,
                apiKey = apiKey
            )
        }
    }
}
