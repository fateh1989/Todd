package com.todd.core.ai

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Android WebView engine based on Chromium for rendering dynamic JavaScript/SPA pages,
 * extracting deep text, headers, tables, and outlinks.
 *
 * Security & Reliability guarantees:
 * - Runs exclusively on Android Main Thread (Dispatchers.Main) with strict lifecycle control.
 * - Enforces SSRF defense: blocks non-public, local, and metadata IP targets on initial load and redirects.
 * - Restricts WebView permissions: disables file access, content access, geolocation, and multi-window popups.
 * - Optimizes memory and bandwidth: blocks remote images to keep rendering fast and lightweight.
 * - Strictly destroys WebView instances on completion, timeout, or coroutine cancellation.
 */
class AndroidChromiumPageRenderer(
    private val context: Context
) : ChromiumPageRenderer {

    // Track only active renders. Cleaning one session must not disable future searches.
    private val activeRenderJobs = ConcurrentHashMap.newKeySet<Job>()

    override suspend fun renderAndExtract(
        url: String,
        timeoutMs: Long
    ): ChromiumRenderResult? {
        // Validate scheme and check against SSRF before creating WebView
        if (!isSafePublicUrl(url)) {
            return ChromiumRenderResult(
                excerpt = "",
                title = null,
                status = "NON_PUBLIC_IP_DENIED",
                outlinks = emptyList(),
                tables = emptyList()
            )
        }

        val renderJob = currentCoroutineContext().job
        activeRenderJobs.add(renderJob)
        return try {
            withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    val deferred = CompletableDeferred<ChromiumRenderResult>()
                    val appContext = context.applicationContext

                    webView = WebView(appContext).apply {
                        configureSecureWebSettings(settings)
                    }

                    var pageFailed = false
                    var failureReason: String? = null

                    webView!!.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val nextUrl = request?.url?.toString() ?: return true
                            if (!isSafePublicUrl(nextUrl)) {
                                return true // Block navigation to unsafe / internal IP
                            }
                            return false // Allow normal navigation
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            // If request attempts to hit private IP or local metadata, block it
                            if (!isSafeSubresource(reqUrl)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked SSRF",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageFailed = true
                                failureReason = "WEBVIEW_ERROR_${error?.errorCode ?: -1}"
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true) {
                                val statusCode = errorResponse?.statusCode ?: 500
                                if (statusCode >= 400) {
                                    pageFailed = true
                                    failureReason = "HTTP_$statusCode"
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (deferred.isCompleted) return

                            if (pageFailed) {
                                deferred.complete(
                                    ChromiumRenderResult(
                                        excerpt = "",
                                        title = view?.title,
                                        status = failureReason ?: "PAGE_FAILED",
                                        outlinks = emptyList(),
                                        tables = emptyList()
                                    )
                                )
                                return
                            }

                            // Inject DOM reader script to extract content, outlinks, and tables
                            view?.evaluateJavascript(DOM_EXTRACTION_SCRIPT) { resultJson ->
                                if (deferred.isCompleted) return@evaluateJavascript
                                val result = parseExtractionResult(resultJson)
                                deferred.complete(result)
                            }
                        }
                    }

                    webView!!.loadUrl(url)
                    deferred.await()
                } ?: ChromiumRenderResult(
                    excerpt = "",
                    title = null,
                    status = "TIMEOUT_CHROMIUM",
                    outlinks = emptyList(),
                    tables = emptyList()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                ChromiumRenderResult(
                    excerpt = "",
                    title = null,
                    status = "ERROR_CHROMIUM_${e::class.java.simpleName}",
                    outlinks = emptyList(),
                    tables = emptyList()
                )
            } finally {
                // Ensure immediate and complete cleanup of the WebView instance
                cleanupWebView(webView)
            }
            }
        } finally {
            activeRenderJobs.remove(renderJob)
        }
    }

    override fun destroy() {
        // Stop current pages, but keep this renderer reusable for future searches.
        activeRenderJobs.toList().forEach { activeJob ->
            activeJob.cancel(CancellationException("Web research browsing cancelled"))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSecureWebSettings(settings: WebSettings) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // Required for React/Vue/Next/Nuxt SPAs to mount without DOMException
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            // Save memory and CPU: do not download images
            blockNetworkImage = true
            loadsImagesAutomatically = false
            // Standard mobile Chromium user agent
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile; ToddBrowser/1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun cleanupWebView(webView: WebView?) {
        webView?.runCatching {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
    }

    private fun isSafeSubresource(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https" && scheme != "data") return false
        if (scheme == "data") return true
        val host = uri.host ?: return false
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local") || host.endsWith(".internal")) return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.none { WebSourceFetcher.isNonPublicAddress(it) }
    }

    private fun isSafePublicUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local") || host.endsWith(".internal")) return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.none { WebSourceFetcher.isNonPublicAddress(it) }
    }

    private fun parseExtractionResult(rawJson: String?): ChromiumRenderResult {
        if (rawJson.isNullOrBlank() || rawJson == "null") {
            return ChromiumRenderResult(
                excerpt = "",
                title = null,
                status = "EMPTY_OR_MINIMAL_CONTENT",
                outlinks = emptyList(),
                tables = emptyList()
            )
        }

        return try {
            var jsonStr = rawJson
            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"") && jsonStr.length >= 2) {
                jsonStr = JSONObject("{\"v\": $rawJson}").optString("v")
            }

            val obj = JSONObject(jsonStr)
            val title = obj.optString("title").ifBlank { null }
            val content = obj.optString("content").trim()
            val outlinksJson = obj.optJSONArray("outlinks") ?: JSONArray()
            val tablesJson = obj.optJSONArray("tables") ?: JSONArray()

            val outlinks = mutableListOf<String>()
            for (i in 0 until outlinksJson.length()) {
                val link = outlinksJson.optString(i)
                if (link.isNotBlank()) outlinks.add(link)
            }

            val tables = mutableListOf<String>()
            for (i in 0 until tablesJson.length()) {
                val t = tablesJson.optString(i)
                if (t.isNotBlank()) tables.add(t)
            }

            val status = if (content.length >= 100) "200_OK_CHROMIUM" else "EMPTY_OR_MINIMAL_CONTENT"

            ChromiumRenderResult(
                excerpt = content,
                title = title,
                status = status,
                outlinks = outlinks,
                tables = tables
            )
        } catch (_: Exception) {
            ChromiumRenderResult(
                excerpt = "",
                title = null,
                status = "PARSE_ERROR_CHROMIUM",
                outlinks = emptyList(),
                tables = emptyList()
            )
        }
    }

    companion object {
        private const val DOM_EXTRACTION_SCRIPT = """
(function() {
    try {
        var docTitle = document.title || '';
        
        var clone = document.body ? document.body.cloneNode(true) : document.createElement('div');
        var noisySelectors = 'script, style, noscript, nav, footer, header, svg, canvas, iframe, form, [role="navigation"], [role="banner"], [role="complementary"], .ad, .ads, .advertisement';
        var noisyElements = clone.querySelectorAll(noisySelectors);
        for (var i = 0; i < noisyElements.length; i++) {
            if (noisyElements[i] && noisyElements[i].parentNode) {
                noisyElements[i].parentNode.removeChild(noisyElements[i]);
            }
        }

        var links = [];
        var seenLinks = {};
        var aTags = document.querySelectorAll('a[href]');
        for (var j = 0; j < aTags.length && links.length < 15; j++) {
            var href = aTags[j].href;
            if (href && (href.indexOf('http://') === 0 || href.indexOf('https://') === 0) && !seenLinks[href]) {
                seenLinks[href] = true;
                links.push(href);
            }
        }

        var tables = [];
        var tableTags = document.querySelectorAll('table');
        for (var k = 0; k < tableTags.length && tables.length < 3; k++) {
            var rowTexts = [];
            var trs = tableTags[k].querySelectorAll('tr');
            for (var r = 0; r < trs.length && r < 5; r++) {
                var cells = trs[r].querySelectorAll('th, td');
                var cellTexts = [];
                for (var c = 0; c < cells.length; c++) {
                    var ct = (cells[c].innerText || cells[c].textContent || '').replace(/\s+/g, ' ').trim();
                    if (ct) cellTexts.push(ct);
                }
                if (cellTexts.length > 0) {
                    rowTexts.push(cellTexts.join(' | '));
                }
            }
            if (rowTexts.length > 0) {
                tables.push(rowTexts.join('\n'));
            }
        }

        var rawText = clone.innerText || clone.textContent || '';
        var cleanText = rawText.replace(/[\t\r ]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim();

        return JSON.stringify({
            title: docTitle,
            content: cleanText.substring(0, 10000),
            outlinks: links,
            tables: tables
        });
    } catch (e) {
        return JSON.stringify({
            title: '',
            content: '',
            outlinks: [],
            tables: []
        });
    }
})();
"""
    }
}
