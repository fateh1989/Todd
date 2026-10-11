package com.todd.core.ai

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Real-time event log and lifecycle management for Todd's Web Research Agent.
 *
 * Connects directly to the Android UI:
 * - Real live events: query searched, site opened, engine used (HTTP vs Chromium), success/failure details.
 * - Outlinks and tables discovered.
 * - Cancellation control: "Stop" cancels the actual running Kotlin coroutine Job and OkHttp/WebView connections.
 * - Memory release: destroys browser sessions, clears OkHttp connection pool, and triggers garbage collection.
 */
class WebAgentLogStore : WebAgentLogListener {

    private val _events = MutableStateFlow<List<WebAgentLogEvent>>(emptyList())
    val events: StateFlow<List<WebAgentLogEvent>> = _events.asStateFlow()

    private val _isResearchActive = MutableStateFlow(false)
    val isResearchActive: StateFlow<Boolean> = _isResearchActive.asStateFlow()

    private val _lastSearchQuery = MutableStateFlow<String?>(null)
    val lastSearchQuery: StateFlow<String?> = _lastSearchQuery.asStateFlow()

    @Volatile
    private var currentActiveJob: Job? = null

    @Volatile
    private var webSourceFetcherRef: WebSourceFetcher? = null

    fun attachFetcher(fetcher: WebSourceFetcher) {
        webSourceFetcherRef = fetcher
    }

    fun bindCurrentJob(job: Job) {
        currentActiveJob = job
        _isResearchActive.value = true
    }

    fun markCompleted(job: Job) {
        // A cancelled previous request must not clear a newer search's active state.
        if (currentActiveJob === job) {
            currentActiveJob = null
            _isResearchActive.value = false
        }
    }

    override fun onLogEvent(event: WebAgentLogEvent) {
        if (event.query != null) {
            _lastSearchQuery.value = event.query
        }
        if (event.phase == "SEARCH" || event.phase == "OPEN_PAGE") {
            _isResearchActive.value = true
        }
        if (event.phase == "CLEANUP" && event.details.contains("إلغاء")) {
            _isResearchActive.value = false
        }
        _events.update { current ->
            (current + event).takeLast(100)
        }
    }

    /**
     * Cancel the active research job, terminate in-flight connections, and release memory.
     */
    fun cancelCurrentResearch(reason: String = "تم إيقاف البحث بطلب من المستخدم") {
        currentActiveJob?.cancel()
        currentActiveJob = null
        _isResearchActive.value = false
        webSourceFetcherRef?.releaseResources()
        onLogEvent(
            WebAgentLogEvent(
                phase = "CLEANUP",
                engine = "SYSTEM",
                details = "تم إلغاء مهمة البحث الحالية وإيقاف كافة العمليات وتحرير موارد التصفح ($reason)",
                failureReason = "USER_CANCELLED"
            )
        )
    }

    /**
     * Clear all recorded log events.
     */
    fun clearLogs() {
        _events.value = emptyList()
    }

    /**
     * Force complete memory cleanup: release session cache, evict connection pools, destroy WebViews, run GC.
     */
    fun releaseAllMemory() {
        webSourceFetcherRef?.releaseResources()
        System.gc()
        onLogEvent(
            WebAgentLogEvent(
                phase = "CLEANUP",
                engine = "SYSTEM",
                details = "تم تحرير ذاكرة التخزين المؤقت، وإغلاق جلسات التصفح، وتشغيل جامع المهملات (GC) فعلياً."
            )
        )
    }
}
