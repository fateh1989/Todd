package com.todd

import com.todd.core.ai.FirebaseRuntimeCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseRuntimeCredentialStoreTest {

    @Test
    fun `parses matching android client from google services json`() {
        val raw = """
            {
              "project_info": {
                "project_number": "123456789",
                "project_id": "todd-real-project"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:123456789:android:other",
                    "android_client_info": {
                      "package_name": "com.other"
                    }
                  },
                  "api_key": [
                    { "current_key": "other-key" }
                  ]
                },
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:123456789:android:todd",
                    "android_client_info": {
                      "package_name": "com.todd"
                    }
                  },
                  "api_key": [
                    { "current_key": "todd-key" }
                  ]
                }
              ],
              "configuration_version": "1"
            }
        """.trimIndent()

        val parsed = FirebaseRuntimeCredentialStore.parseGoogleServicesJson(raw, "com.todd")

        assertEquals("todd-real-project", parsed.projectId)
        assertEquals("1:123456789:android:todd", parsed.applicationId)
        assertEquals("todd-key", parsed.apiKey)
    }
}
