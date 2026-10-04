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
    private lateinit var rulesEngine: RulesEngine

    @Before
    fun setup() {
        rulesEngine = RulesEngine()

        liveClient = GeminiLiveClient(
            context = null,
            rulesEngine = rulesEngine,
            repository = null,
            liveModelName = "gemini-2.0-flash-exp",
            sessionStarter = { Result.success(Unit) },
            textResponder = { prompt -> Result.success("Todd reply: $prompt") }
        )
    }

    @Test
    fun `local only mode strictly blocks connection to Gemini Live`() = runBlocking {
        val result = liveClient.startSession(AIProviderMode.LOCAL_ONLY)

        assertTrue(result.isFailure)
        assertEquals(GeminiLiveState.ERROR, liveClient.state.value)
        assertTrue(
            "Error must clearly indicate local only restriction",
            result.exceptionOrNull()?.message?.contains("محلي فقط") == true
        )
    }

    @Test
    fun `cloud preferred mode connects successfully`() = runBlocking {
        val result = liveClient.startSession(AIProviderMode.CLOUD_PREFERRED)

        assertTrue(result.isSuccess)
        assertEquals(GeminiLiveState.LISTENING, liveClient.state.value)
    }

    @Test
    fun `barge-in immediately stops speaking state and resumes listening`() = runBlocking {
        liveClient.startSession(AIProviderMode.CLOUD_PREFERRED)
        liveClient.onUserSpeechReceived("مرحباً يا تود")
        assertEquals(GeminiLiveState.SPEAKING, liveClient.state.value)

        liveClient.handleBargeIn()
        assertEquals(GeminiLiveState.LISTENING, liveClient.state.value)
    }

    @Test
    fun `voice function calling passes through RulesEngine safely`() = runBlocking {
        liveClient.startSession(AIProviderMode.CLOUD_PREFERRED)

        liveClient.onUserSpeechReceived("تود، قم بفحص المستودع")
        val transcript = liveClient.transcripts.value.lastOrNull()?.text ?: ""
        assertTrue("Safe tool execution succeeds and reports result", transcript.contains("تم تنفيذ أداة checkRepositoryStatus"))

        liveClient.onUserSpeechReceived("تود، احذف الفرع main")
        val blockedTranscript = liveClient.transcripts.value.lastOrNull()?.text ?: ""
        assertTrue("Dangerous tool is stopped with security prompt", blockedTranscript.contains("تنبيه أمان") || blockedTranscript.contains("عالية الخطورة"))
    }
}
