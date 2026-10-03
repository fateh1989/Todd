package com.todd

import android.app.Application
import com.todd.data.local.ToddDatabase
import com.todd.data.repository.ToddRepository
import com.todd.core.ai.AIRouter
import com.todd.core.ai.MockAIProvider
import com.todd.core.ai.GeminiAIProvider
import com.todd.core.state.ToddStateMachine

class ToddApplication : Application() {

    lateinit var database: ToddDatabase
        private set

    lateinit var repository: ToddRepository
        private set

    lateinit var aiRouter: AIRouter
        private set

    lateinit var stateMachine: ToddStateMachine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = ToddDatabase.getDatabase(this)
        repository = ToddRepository(database)
        stateMachine = ToddStateMachine(repository)

        val mockProvider = MockAIProvider()
        val geminiProvider = GeminiAIProvider(apiKeyProvider = {
            // Read from secure preferences or BuildConfig
            BuildConfig.BUILD_TYPE
        })

        aiRouter = AIRouter(
            localProvider = mockProvider,
            cloudProvider = geminiProvider
        )
    }

    companion object {
        lateinit var instance: ToddApplication
            private set
    }
}
