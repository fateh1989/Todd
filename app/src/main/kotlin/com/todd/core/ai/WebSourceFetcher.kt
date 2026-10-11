package com.todd.core.ai

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resilient multi-source web content fetcher and reader for Todd's Internet Agent.
 *
 * Supports:
 * - Up to 5+ independent sources comparison
 * - JavaScript/SPA detection with Schema.org JSON-LD, Next.js hydration data, and meta fallback
 * - Deep text reading (up to 10,000 characters per page)
 * - Outlink discovery for following citations across pages
 * - Anti-bot resistance with modern browser headers
 * - SSRF protection blocking private, local, and metadata IP addresses
 * - In-memory session deduplication and caching
 * - Diagnostic status tracking (OK, 403 WAF, 404, Timeout, SPA)
 */
/**
 * Listener for transparent user-facing activity logs:
 * - What is being searched
 * - Which site is being opened (Lightweight HTTP vs Chromium WebView)
 * - What was read (characters, tables, outlinks)
 * - What failed and why
 */
interface WebAgentLogListener {
    fun onLogEvent(event: WebAgentLogEvent)
}

data class WebAgentLogEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val phase: String, // "SEARCH", "OPEN_PAGE", "READ_PAGE", "FAILURE", "CLEANUP"
    val query: String? = null,
    val url: String? = null,
    val engine: String = "LIGHTWEIGHT_HTTP", // "LIGHTWEIGHT_HTTP" or "CHROMIUM_WEBVIEW"
    val details: String,
    val failureReason: String? = null,
    val charsRead: Int = 0,
    val outlinksCount: Int = 0,
    val tablesCount: Int = 0
)

/**
 * Chromium WebView rendering contract for dynamic JavaScript/SPA pages.
 */
interface ChromiumPageRenderer {
    suspend fun renderAndExtract(url: String, timeoutMs: Long = 10_000L): Pair<String, String>?
    fun destroy()
}

class WebSourceFetcher(
    private val client: OkHttpClient = safeClient(),
    private val browserRenderer: ChromiumPageRenderer? = null,
    private val logListener: WebAgentLogListener? = null
) {
    private val sessionCache = ConcurrentHashMap<String, AISource>()

    fun releaseResources() {
        sessionCache.clear()
        browserRenderer?.destroy()
        runCatching {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
        logListener?.onLogEvent(
            WebAgentLogEvent(
                phase = "CLEANUP",
                details = "تم إغلاق كافة جلسات التصفح وتحرير كائنات WebView وتفريغ الذاكرة التخزينية بنجاح."
            )
        )
    }

    suspend fun fetch(
        sources: List<AISource>,
        maxSources: Int = MAX_SOURCES
    ): List<AISource> = withContext(Dispatchers.IO) {
        coroutineScope {
            sources.distinctBy { normalizeUrl(it.url) }
                .take(maxSources)
                .mapIndexed { index, source ->
                    async {
                        val citationIndex = source.citationIndex ?: (index + 1)
                        val normalized = normalizeUrl(source.url)
                        sessionCache[normalized]?.copy(citationIndex = citationIndex)
                            ?: fetchSingleSource(source, citationIndex).also {
                                sessionCache[normalized] = it
                            }
                    }
                }.awaitAll()
        }
    }

    suspend fun fetchUrl(rawUrl: String): AISource = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawUrl)
        sessionCache[normalized] ?: run {
            val baseSource = AISource(
                url = normalized,
                domain = runCatching { URI(normalized).host }.getOrNull()
            )
            val result = fetchSingleSource(baseSource, 1)
            sessionCache[normalized] = result
            result
        }
    }

    private suspend fun fetchSingleSource(source: AISource, citationIndex: Int): AISource {
        logListener?.onLogEvent(
            WebAgentLogEvent(
                phase = "OPEN_PAGE",
                url = source.url,
                engine = "LIGHTWEIGHT_HTTP",
                details = "فتح الموقع عبر اتصال خفيف مباشر: ${source.url}"
            )
        )

        return try {
            val (excerpt, title, status, outlinks, tables) = fetchExcerptWithDiagnostics(source.url)

            var finalExcerpt = excerpt
            var finalStatus = status

            // If page requires JavaScript execution, fallback to Chromium WebView safely
            if ((status == "JS_CLIENT_RENDER_ONLY" || status == "EMPTY_OR_MINIMAL_CONTENT") && browserRenderer != null) {
                logListener?.onLogEvent(
                    WebAgentLogEvent(
                        phase = "OPEN_PAGE",
                        url = source.url,
                        engine = "CHROMIUM_WEBVIEW",
                        details = "الموقع يتطلب جافاسكريبت. جاري تشغيل Chromium WebView المعزول لقراءة DOM..."
                    )
                )
                val rendered = browserRenderer.renderAndExtract(source.url)
                if (rendered != null) {
                    finalExcerpt = rendered.first.take(MAX_EXCERPT_CHARS)
                    finalStatus = rendered.second
                }
            }

            if (finalExcerpt != null) {
                logListener?.onLogEvent(
                    WebAgentLogEvent(
                        phase = "READ_PAGE",
                        url = source.url,
                        details = "تمت قراءة الصفحة واستخراج المحتوى (${finalExcerpt.length} حرف، العنوان: \"${title ?: ""}\")",
                        charsRead = finalExcerpt.length,
                        outlinksCount = outlinks.size,
                        tablesCount = tables.size
                    )
                )
            } else {
                logListener?.onLogEvent(
                    WebAgentLogEvent(
                        phase = "FAILURE",
                        url = source.url,
                        details = "تعذر استخراج نص كافٍ من الصفحة ($finalStatus)",
                        failureReason = finalStatus
                    )
                )
            }

            source.copy(
                title = source.title ?: title,
                domain = source.domain ?: runCatching { URI(source.url).host }.getOrNull(),
                excerpt = finalExcerpt,
                citationIndex = citationIndex,
                fetchStatus = finalStatus,
                outlinks = outlinks
            )
        } catch (cancelled: CancellationException) {
            logListener?.onLogEvent(
                WebAgentLogEvent(
                    phase = "CLEANUP",
                    url = source.url,
                    details = "تم إلغاء عملية البحث بطلب من المستخدم، وتحرير الاتصال.",
                    failureReason = "USER_CANCELLED"
                )
            )
            throw cancelled
        } catch (e: Exception) {
            val errStatus = when {
                e is java.net.SocketTimeoutException -> "TIMEOUT (انتهت مهلة استجابة الخادم)"
                e is UnknownHostException -> "DNS_OR_SSRF_BLOCKED (عنوان غير متاح أو حظر أمني)"
                e.message?.contains("not publicly routable") == true -> "NON_PUBLIC_IP_DENIED (حظر عنوان شبكة خاصة SSRF)"
                else -> "ERROR_${e::class.java.simpleName}"
            }
            logListener?.onLogEvent(
                WebAgentLogEvent(
                    phase = "FAILURE",
                    url = source.url,
                    details = "فشل فتح أو قراءة الموقع: $errStatus",
                    failureReason = errStatus
                )
            )
            source.copy(
                citationIndex = citationIndex,
                fetchStatus = errStatus
            )
        }
    }

    private fun fetchExcerptWithDiagnostics(rawUrl: String): FetchResult {
        var url = checkedUri(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(url.toString())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build()

            var response: Response? = null
            try {
                response = client.newCall(request).execute()
                val code = response.code

                if (code in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        return FetchResult(null, null, "TOO_MANY_REDIRECTS", emptyList())
                    }
                    val location = response.header("Location")
                        ?: return FetchResult(null, null, "REDIRECT_NO_LOCATION", emptyList())
                    url = checkedUri(url.resolve(location).toString())
                    response.close()
                    return@repeat
                }

                if (code == 403 || code == 401) {
                    return FetchResult(null, null, "403_BLOCKED_WAF", emptyList())
                }
                if (code == 404) {
                    return FetchResult(null, null, "404_NOT_FOUND", emptyList())
                }
                if (code == 429) {
                    return FetchResult(null, null, "429_RATE_LIMITED", emptyList())
                }
                if (code in 500..599) {
                    return FetchResult(null, null, "SERVER_ERROR_$code", emptyList())
                }
                if (!response.isSuccessful) {
                    return FetchResult(null, null, "HTTP_ERROR_$code", emptyList())
                }

                val mime = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                if (mime != null && mime != "text/html" && mime != "application/xhtml+xml" &&
                    mime != "text/plain" && !mime.contains("json") && !mime.contains("xml")
                ) {
                    return FetchResult(null, null, "UNSUPPORTED_MIME_$mime", emptyList())
                }

                val body = response.body ?: return FetchResult(null, null, "EMPTY_BODY", emptyList())
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        output.write(buffer, 0, read)
                        if (total >= MAX_BYTES) break // Keep what was read up to 1.5MB instead of dropping
                    }
                    output.toByteArray()
                }

                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val rawHtml = bytes.toString(charset)
                val title = extractTitle(rawHtml)
                val outlinks = extractOutlinks(rawHtml, url)

                val tables = extractTables(rawHtml)

                if (mime == "text/plain") {
                    val readable = rawHtml.trim().take(MAX_EXCERPT_CHARS)
                    return FetchResult(readable, title, "200_OK", outlinks, tables)
                }

                val (readable, status) = processHtmlContent(rawHtml)
                val finalExcerpt = readable.take(MAX_EXCERPT_CHARS).takeIf { it.length >= MIN_EXCERPT_CHARS }
                return FetchResult(finalExcerpt, title, status, outlinks, tables)
            } finally {
                response?.close()
            }
        }
        return FetchResult(null, null, "EXHAUSTED_ATTEMPTS", emptyList(), emptyList())
    }

    private data class FetchResult(
        val excerpt: String?,
        val title: String?,
        val status: String,
        val outlinks: List<String>,
        val tables: List<String> = emptyList()
    )

    private fun checkedUri(raw: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid source URL")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: throw IllegalArgumentException("Source URL has no host")
        require(scheme == "https" || scheme == "http") { "Scheme must be http or https" }
        require(uri.userInfo == null && host.isNotBlank())
        require(host != "localhost" && !host.endsWith(".localhost") &&
            !host.endsWith(".local") && !host.endsWith(".internal"))
        // Resolve with the same guarded OkHttp DNS path used by the actual connection.
        safeDns.lookup(host)
        return uri
    }

    companion object {
        const val MAX_SOURCES = 5
        const val MAX_EXCERPT_CHARS = 10_000
        private const val MIN_EXCERPT_CHARS = 30
        private const val MAX_BYTES = 1_500_000
        private const val MAX_REDIRECTS = 4
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val safeDns: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) {
                    throw UnknownHostException("Source host is not publicly routable")
                }
                return addresses
            }
        }

        private fun safeClient() = OkHttpClient.Builder()
            .dns(safeDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        fun normalizeUrl(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return raw.trim()
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return raw.trim()
            val path = uri.path?.ifBlank { "/" } ?: "/"
            val cleanQuery = uri.query?.split("&")
                ?.filterNot { param ->
                    val key = param.substringBefore("=").lowercase()
                    key.startsWith("utm_") || key == "ref" || key == "fbclid" ||
                        key == "gclid" || key == "source" || key == "campaign"
                }
                ?.joinToString("&")
                ?.takeIf { it.isNotBlank() }
            val portPart = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            val queryPart = if (cleanQuery != null) "?$cleanQuery" else ""
            return "$scheme://$host$portPart$path$queryPart"
        }

        fun extractTitle(html: String): String? =
            Regex("(?is)<title\\b[^>]*>(.*?)</title>")
                .find(html)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }?.takeIf { it.isNotBlank() }

        fun extractMetaDescription(html: String): String? {
            val ogDesc = Regex("(?is)<meta\\s+[^>]*property=[\"']og:description[\"'][^>]*content=[\"']([^\"']+)[\"']")
                .find(html)?.groupValues?.get(1)
            val metaDesc = Regex("(?is)<meta\\s+[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)[\"']")
                .find(html)?.groupValues?.get(1)
            return (ogDesc ?: metaDesc)?.let { decodeEntities(it).trim() }?.takeIf { it.isNotBlank() }
        }

        fun extractJsonLdText(html: String): String {
            val regex = Regex("(?is)<script\\s+[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>")
            val results = mutableListOf<String>()
            regex.findAll(html).forEach { match ->
                val json = match.groupValues[1].trim()
                if (json.isNotBlank()) {
                    val textFields = listOf("articleBody", "description", "headline", "name", "text")
                    for (field in textFields) {
                        val fieldRegex = Regex("\"$field\"\\s*:\\s*\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)
                        fieldRegex.findAll(json).forEach { fm ->
                            val textVal = fm.groupValues[1]
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")
                                .replace("\\t", " ")
                                .trim()
                            if (textVal.length >= 25 && !results.contains(textVal)) {
                                results.add(textVal)
                            }
                        }
                    }
                }
            }
            return results.joinToString("\n\n")
        }

        fun extractNextDataText(html: String): String? {
            val match = Regex("(?is)<script\\s+id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>")
                .find(html)?.groupValues?.get(1) ?: return null
            val textMatches = Regex("\"([^\"]{60,})\"").findAll(match)
                .map { it.groupValues[1] }
                .filter { !it.startsWith("http") && !it.contains("/") && !it.contains("{") }
                .take(6)
                .toList()
            return if (textMatches.isNotEmpty()) textMatches.joinToString("\n") else null
        }

        fun extractOutlinks(html: String, baseUrl: URI): List<String> {
            val regex = Regex("(?is)<a\\s+[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>")
            val outlinks = mutableSetOf<String>()
            regex.findAll(html).forEach { match ->
                val href = match.groupValues[1].trim()
                if (href.isNotBlank() && !href.startsWith("javascript:") &&
                    !href.startsWith("mailto:") && !href.startsWith("tel:")
                ) {
                    val resolved = runCatching { baseUrl.resolve(href) }.getOrNull()
                    if (resolved != null && (resolved.scheme == "https" || resolved.scheme == "http")) {
                        val cleanUrl = normalizeUrl(resolved.toString())
                        if (cleanUrl != normalizeUrl(baseUrl.toString())) {
                            outlinks.add(cleanUrl)
                        }
                    }
                }
            }
            return outlinks.take(15).toList()
        }

        fun extractTables(html: String): List<String> {
            val tableRegex = Regex("(?is)<table\\b[^>]*>(.*?)</table>")
            val rowRegex = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>")
            val cellRegex = Regex("(?is)<t[hd]\\b[^>]*>(.*?)</t[hd]>")
            val tables = mutableListOf<String>()

            tableRegex.findAll(html).take(3).forEach { tm ->
                val rows = rowRegex.findAll(tm.groupValues[1]).map { rm ->
                    cellRegex.findAll(rm.groupValues[1])
                        .map { decodeEntities(it.groupValues[1].replace(Regex("<[^>]+>"), "")).trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(" | ")
                }.filter { it.isNotBlank() }.take(5).toList()

                if (rows.isNotEmpty()) {
                    tables.add(rows.joinToString("\n"))
                }
            }
            return tables
        }

        internal fun processHtmlContent(html: String): Pair<String, String> {
            val metaDesc = extractMetaDescription(html)
            val jsonLd = extractJsonLdText(html)
            val nextData = extractNextDataText(html)

            val withNoscriptPreserved = html.replace(Regex("(?is)<noscript\\b[^>]*>(.*?)</noscript>"), "\n$1\n")
            val withoutNonContent = withNoscriptPreserved
                .replace(Regex("(?is)<!--.*?-->"), " ")
                .replace(Regex("(?is)<(script|style|svg|canvas|template|head|nav|footer|header|form|aside)\\b[^>]*>.*?</\\1\\s*>"), " ")
                .replace(Regex("(?is)<br\\s*/?>|</(p|div|li|tr|h[1-6]|article|section)\\s*>"), "\n")
                .replace(Regex("(?is)<[^>]+>"), " ")
            val bodyText = decodeEntities(withoutNonContent)
                .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
                .replace(Regex(" *\\n+ *"), "\n")
                .trim()

            val combined = buildString {
                if (!metaDesc.isNullOrBlank() && !bodyText.contains(metaDesc)) {
                    appendLine("الملخص من المصدر: $metaDesc")
                }
                if (jsonLd.isNotBlank() && !bodyText.contains(jsonLd.take(40))) {
                    appendLine("بيانات المصدر المهيكلة: $jsonLd")
                }
                if (!nextData.isNullOrBlank() && !bodyText.contains(nextData.take(40))) {
                    appendLine("بيانات الصفحة: $nextData")
                }
                if (bodyText.isNotBlank()) {
                    append(bodyText)
                }
            }.trim()

            val isSpaDetected = bodyText.length < 150 && html.contains("<script") &&
                (html.contains("id=\"root\"") || html.contains("id=\"app\"") || html.contains("id=\"__next\""))

            val status = when {
                isSpaDetected && combined.length >= MIN_EXCERPT_CHARS -> "OK_STRUCTURED_SPA"
                isSpaDetected && combined.length < MIN_EXCERPT_CHARS -> "JS_CLIENT_RENDER_ONLY"
                jsonLd.isNotBlank() -> "OK_WITH_SCHEMA"
                combined.length >= MIN_EXCERPT_CHARS -> "200_OK"
                else -> "EMPTY_OR_MINIMAL_CONTENT"
            }

            return combined to status
        }

        internal fun extractReadableText(html: String): String =
            processHtmlContent(html).first

        private fun decodeEntities(value: String): String = value.replace(
            Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);"),
        ) { match ->
            val entity = match.groupValues[1].lowercase()
            when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity == "nbsp" -> " "
                entity == "pound" -> "£"
                entity == "euro" -> "€"
                entity == "yen" -> "¥"
                entity == "cent" -> "¢"
                entity == "copy" -> "©"
                entity == "reg" -> "®"
                entity == "trade" -> "™"
                entity == "ndash" -> "–"
                entity == "mdash" -> "—"
                entity == "hellip" -> "…"
                entity == "laquo" -> "«"
                entity == "raquo" -> "»"
                entity == "ldquo" -> "“"
                entity == "rdquo" -> "”"
                entity == "lsquo" -> "‘"
                entity == "rsquo" -> "’"
                entity.startsWith("#x") ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePoint) ?: " "
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint) ?: " "
                else -> match.value
            }
        }

        private fun codePoint(value: Int): String =
            runCatching { String(Character.toChars(value)) }.getOrDefault(" ")

        internal fun isNonPublicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) return true

            if (address is Inet4Address) {
                val a = bytes[0].toInt() and 0xff
                val b = bytes[1].toInt() and 0xff
                val c = bytes[2].toInt() and 0xff
                return a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && ((b == 0 && c in 0..2) || b == 168 || (b == 88 && c == 99))) ||
                    (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
                    (a == 203 && b == 0 && c == 113)
            }

            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            val fourth = bytes[3].toInt() and 0xff
            return first == 0xfc || first == 0xfd ||
                (first == 0xfe && second and 0xc0 == 0x80) ||
                (first == 0x20 && second == 0x02) ||
                (first == 0x20 && second == 0x01 && third == 0 && fourth == 0) ||
                (first == 0x00 && second == 0x64 && third == 0xff && fourth == 0x9b) ||
                (first == 0x20 && second == 0x01 &&
                    (bytes[2].toInt() and 0xff) == 0x0d &&
                    (bytes[3].toInt() and 0xff) == 0xb8) ||
                bytes.take(12).all { it.toInt() == 0 } ||
                (bytes.take(10).all { it.toInt() == 0 } &&
                    bytes[10].toInt() == -1 && bytes[11].toInt() == -1)
        }
    }
}
