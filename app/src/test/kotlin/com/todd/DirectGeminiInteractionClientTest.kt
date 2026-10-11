package com.todd

import com.todd.core.ai.DirectGeminiInteractionClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectGeminiInteractionClientTest {
    @Test
    fun `reads source URI from current Interactions API response`() {
        val client = DirectGeminiInteractionClient(
            apiKeyProvider = { null },
            modelNameProvider = { "gemini-3.5-flash-lite" }
        )
        val response = JSONObject(
            """
            {
              "steps": [{
                "type": "model_output",
                "content": [{
                  "type": "text",
                  "text": "Found a listing.",
                  "annotations": [{
                    "type": "url_citation",
                    "uri": "https://example.org/truck",
                    "title": "Truck listing"
                  }]
                }]
              }]
            }
            """.trimIndent()
        )

        val sources = client.parseSources(response)

        assertEquals(1, sources.size)
        assertEquals("https://example.org/truck", sources.single().url)
        assertEquals("Truck listing", sources.single().title)
        assertEquals("example.org", sources.single().domain)
    }

    @Test
    fun `continues to read URL source from previous schema`() {
        val client = DirectGeminiInteractionClient(
            apiKeyProvider = { null },
            modelNameProvider = { "gemini-3.5-flash-lite" }
        )
        val response = JSONObject(
            """
            {
              "steps": [{
                "type": "model_output",
                "content": [{
                  "type": "text",
                  "text": "Found a listing.",
                  "annotations": [{
                    "type": "url_citation",
                    "url": "https://example.org/truck"
                  }]
                }]
              }]
            }
            """.trimIndent()
        )

        assertEquals("https://example.org/truck", client.parseSources(response).single().url)
    }
}
