package com.todd

import android.app.Application
import com.todd.data.local.ToddDatabase
import com.todd.data.repository.ToddRepository
import com.todd.core.ai.AIRouter
import com.todd.core.ai.LocalOnDeviceAIProvider
import com.todd.core.ai.GeminiAIProvider
import com.todd.core.ai.GeminiLiveClient
import com.todd.core.rules.RulesEngine
import com.todd.core.state.ToddStateMachine
import com.todd.core.tools.GitHubRestTool
import com.todd.core.tools.GitHubTool

class ToddApplication : Application() {

    lateinit var database: ToddDatabase
        private set

    lateinit var repository: ToddRepository
        private set

    lateinit var aiRouter: AIRouter
        private set

    lateinit var stateMachine: ToddStateMachine
        private set

    lateinit var githubTool: GitHubTool
        private set

    lateinit var rulesEngine: RulesEngine
        private set

    lateinit var liveClient: GeminiLiveClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = ToddDatabase.getDatabase(this)
        repository = ToddRepository(database)
        stateMachine = ToddStateMachine(repository)
        githubTool = GitHubRestTool()
        rulesEngine = RulesEngine()
        liveClient = GeminiLiveClient(
            context = this,
            rulesEngine = rulesEngine,
            repository = repository,
            githubTool = githubTool
        )

        // Local provider: real Gemini on-device inference; LOCAL_ONLY never falls back to cloud.
        val localProvider = LocalOnDeviceAIProvider(modelName = "gemini-3.5-flash-lite")

        // Cloud provider: current Firebase AI Logic using the Gemini Developer API backend
        // Credentials are secure and managed via Firebase project configuration (no hardcoded keys)
        val geminiProvider = GeminiAIProvider(modelName = "gemini-3.8-flash")

        aiRouter = AIRouter(
            localProvider = localProvider,
            cloudProvider = geminiProvider
        )
    }

    companion object {
        lateinit var instance: ToddApplication
            private set
    }
}
