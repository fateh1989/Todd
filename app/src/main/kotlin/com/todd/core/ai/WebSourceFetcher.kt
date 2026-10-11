package com.todd.core.ai

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.TimeUnit

/** Fetches a small amount of readable text from a few search citations for evidence review. */
class WebSourceFetcher(
    private val client: OkHttpClient = safeClient()
) {
    suspend fun fetch(sources: List<AISource>): List<AISource> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                sources.distinctBy { it.url }.take(MAX_SOURCES).mapIndexed { index, source ->
                    async {
                        val excerpt = try {
                            fetchExcerpt(source.url)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        source.copy(
                            excerpt = excerpt,
                            citationIndex = source.citationIndex ?: (index + 1)
                        )
                    }
                }.awaitAll()
            }
        }

    private fun fetchExcerpt(rawUrl: String): String? {
        var url = checkedUri(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(url.toString())
                .header("Accept", "text/html, text/plain;q=0.9")
                .header("User-Agent", "ToddResearch/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) return null
                    val location = response.header("Location") ?: return null
                    url = checkedUri(url.resolve(location).toString())
                    return@use
                }
                if (!response.isSuccessful) return null
                val mime = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                if (mime != "text/html" && mime != "application/xhtml+xml" && mime != "text/plain") {
                    return null
                }
                val body = response.body ?: return null
                if ((body.contentLength() > MAX_BYTES) || body.contentLength() == 0L) return null
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val rawText = bytes.toString(charset)
                val readable = if (mime == "text/plain") rawText else extractReadableText(rawText)
                return readable.take(MAX_EXCERPT_CHARS).takeIf { it.length >= MIN_EXCERPT_CHARS }
            }
            // A redirect continues here after the previous response has been closed.
        }
        return null
    }

    private fun checkedUri(raw: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid source URL")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: throw IllegalArgumentException("Source URL has no host")
        require(scheme == "https")
        require(uri.userInfo == null && host.isNotBlank())
        require(host != "localhost" && !host.endsWith(".localhost") &&
            !host.endsWith(".local") && !host.endsWith(".internal"))
        // Resolve with the same guarded OkHttp DNS path used by the actual connection.
        safeDns.lookup(host)
        return uri
    }

    companion object {
        const val MAX_SOURCES = 3
        const val MAX_EXCERPT_CHARS = 2_400
        private const val MIN_EXCERPT_CHARS = 80
        private const val MAX_BYTES = 1_000_000
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val safeDns = Dns { hostname ->
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) {
                throw UnknownHostException("Source host is not publicly routable")
            }
            addresses
        }

        private fun safeClient() = OkHttpClient.Builder()
            .dns(safeDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        internal fun extractReadableText(html: String): String {
            val withoutNonContent = html
                .replace(Regex("(?is)<!--.*?-->"), " ")
                .replace(Regex("(?is)<(script|style|noscript|svg|canvas|template|head|nav|footer|header|form|aside)\\b[^>]*>.*?</\\1\\s*>"), " ")
                .replace(Regex("(?is)<br\\s*/?>|</(p|div|li|tr|h[1-6]|article|section)\\s*>"), "\n")
                .replace(Regex("(?is)<[^>]+>"), " ")
            return decodeEntities(withoutNonContent)
                .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
                .replace(Regex(" *\\n+ *"), "\n")
                .trim()
        }

        private fun decodeEntities(value: String): String = value.replace(
            Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);"),
        ) { match ->
            val entity = match.groupValues[1]
            when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity == "nbsp" -> " "
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePoint) ?: " "
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint) ?: " "
                else -> " "
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
                // Block private, shared, protocol assignment, documentation, benchmark,
                // multicast, and reserved IPv4 ranges.
                return a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && ((b == 0 && c in 0..2) || b == 168 || (b == 88 && c == 99))) ||
                    (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
                    (a == 203 && b == 0 && c == 113)
            }

            // RFC 4193 unique local (fc00::/7), link-local, and IPv4-mapped IPv6.
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return first == 0xfc || first == 0xfd ||
                (first == 0xfe && second and 0xc0 == 0x80) ||
                (first == 0x20 && second == 0x01 &&
                    (bytes[2].toInt() and 0xff) == 0x0d &&
                    (bytes[3].toInt() and 0xff) == 0xb8) ||
                (bytes.take(10).all { it.toInt() == 0 } &&
                    bytes[10].toInt() == -1 && bytes[11].toInt() == -1)
        }
    }
}
