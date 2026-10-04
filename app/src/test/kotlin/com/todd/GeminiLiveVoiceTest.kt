package com.todd

import com.todd.core.ai.GeminiLiveClient
import com.todd.core.ai.GeminiLiveState
import com.todd.core.model.AIProviderMode
import com.todd.core.rules.RulesEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiLiveVoiceTest {

    private lateinit var liveClient: GeminiLiveClient

    @Before
    fun setup() {
        liveClient = GeminiLiveClient(
            context = null,
            rulesEngine = RulesEngine(),
            repository = null,
            liveModelName = "gemini-2.5-flash-native-audio-preview-12-2025",
            sessionStarter = { Result.success(Unit) },
            textResponder = { prompt -> Result.success("Todd reply: $prompt") }
        )
    }

    @Test
    fun `local only mode strictly blocks connection to Gemini Live`() = runBlocking {
        val result = liveClient.startSession(AIProviderMode.LOCAL_ONLY)

        assertTrue(result.isFailure)
        assertEquals(GeminiLiveState.ERROR, liveClient.state.value)
        assertTrue(result.exceptionOrNull()?.message?.contains("محلي فقط") == true)
    }

    @Test
    fun `cloud preferred mode connects successfully with injected session`() = runBlocking {
        val result = liveClient.startSession(AIProviderMode.CLOUD_PREFERRED)

        assertTrue(result.isSuccess)
        assertEquals(GeminiLiveState.LISTENING, liveClient.state.value)
    }

    @Test
    fun `barge-in returns state to listening`() = runBlocking {
        liveClient.startSession(AIProviderMode.CLOUD_PREFERRED)
        liveClient.onUserSpeechReceived("مرحباً يا تود")
        assertEquals(GeminiLiveState.SPEAKING, liveClient.state.value)

        liveClient.handleBargeIn()
        assertEquals(GeminiLiveState.LISTENING, liveClient.state.value)
    }

    @Test
    fun `manual live text uses injected responder in JVM test`() = runBlocking {
        liveClient.startSession(AIProviderMode.CLOUD_PREFERRED)
        val result = liveClient.onUserSpeechReceived("اختبار")

        assertTrue(result.isSuccess)
        assertEquals("Todd reply: اختبار", result.getOrNull())
        assertEquals("TODD", liveClient.transcripts.value.last().sender)
    }
}
