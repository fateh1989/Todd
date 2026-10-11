package com.todd

import com.todd.core.ai.AIRequest
import com.todd.core.ai.AISource
import com.todd.core.ai.WebResearchPolicy
import com.todd.core.ai.WebSourceFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ToddWebResearchAgentTest {

    // 1. سؤال بسيط يتطلب البحث عن معلومة (Simple factual query)
    @Test
    fun `test1_simpleFactualQueryAndGrounding`() {
        val query = "ابحث في الإنترنت عن موعد إطلاق أندرويد 15 الرسمي"
        val request = AIRequest(prompt = query, webResearch = false)

        // WebResearchPolicy recognizes explicit research signals
        assertTrue(WebResearchPolicy.isRequested(request))

        // Simplify query extracts core subject without conversational search wrapper
        val simplified = WebResearchPolicy.simplifyQueryForSearch(query)
        assertEquals("موعد إطلاق أندرويد 15 الرسمي", simplified)

        val sources = listOf(
            AISource(
                title = "Android 15 Release Notes",
                url = "https://developer.android.com/about/versions/15",
                domain = "developer.android.com",
                excerpt = "Android 15 source code released to AOSP in September 2024.",
                citationIndex = 1
            )
        )

        val prompt = WebResearchPolicy.evidenceReviewPrompt(query, "تم إطلاق أندرويد 15 في سبتمبر 2024 [1].", sources)
        assertTrue(prompt.contains("[1] Android 15 Release Notes"))
        assertTrue(prompt.contains("https://developer.android.com/about/versions/15"))
        assertTrue(prompt.contains("September 2024"))
    }

    // 2. مقارنة معلومات من خمسة مواقع مستقلة (Compare info from 5 independent sites)
    @Test
    fun `test2_compareInformationFromFiveIndependentSites`() = runBlocking {
        val domains = listOf(
            "site1.example.org",
            "site2.example.org",
            "site3.example.org",
            "site4.example.org",
            "site5.example.org"
        )

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val host = chain.request().url.host
                val index = domains.indexOf(host) + 1
                val html = """
                    <html>
                    <head><title>Comparison Site $index</title></head>
                    <body>
                        <article>
                            <h1>Report from Source $index</h1>
                            <p>Benchmark score from site $index is ${100 + index * 10} points.</p>
                        </article>
                    </body>
                    </html>
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        val fetcher = WebSourceFetcher(client = mockClient)
        val initialSources = domains.mapIndexed { idx, domain ->
            AISource(
                url = "https://$domain/benchmark",
                domain = domain,
                citationIndex = idx + 1
            )
        }

        // Must support at least 5 independent sources simultaneously
        assertEquals(5, initialSources.size)
        val fetched = fetcher.fetch(initialSources, maxSources = 5)
        assertEquals(5, fetched.size)

        fetched.forEachIndexed { i, s ->
            val expectedNum = i + 1
            assertEquals(expectedNum, s.citationIndex)
            assertNotNull(s.excerpt)
            assertTrue(s.excerpt!!.contains("Benchmark score from site $expectedNum"))
            assertEquals("Comparison Site $expectedNum", s.title)
        }

        val reviewPrompt = WebResearchPolicy.evidenceReviewPrompt(
            question = "قارن نتائج الأداء بين المواقع الخمسة",
            draft = "المواقع تتراوح بين 110 و 150 نقطة.",
            sources = fetched
        )

        for (i in 1..5) {
            assertTrue("Must contain citation index [$i]", reviewPrompt.contains("[$i] Comparison Site $i"))
        }
        assertTrue(reviewPrompt.contains("Synthesize and compare findings across independent sources"))
    }

    // 3. بحث متعدد اللغات (Multilingual search: Arabic, English, Cross-lingual)
    @Test
    fun `test3_multilingualSearchAndEntityDecoding`() {
        // Arabic signals
        assertTrue(WebResearchPolicy.isRequested("قارن بين مواصفات الهاتفين على الويب"))
        assertTrue(WebResearchPolicy.isRequested("ابحث عن أسعار التذاكر اليوم"))
        assertTrue(WebResearchPolicy.isRequested("تحقق من الأخبار الحديثة عبر الانترنت"))

        // English signals
        assertTrue(WebResearchPolicy.isRequested("compare Pixel 8 vs Galaxy S24 online"))
        assertTrue(WebResearchPolicy.isRequested("fact check the latest battery benchmarks"))

        // HTML entity decoding in Arabic and multilingual text
        val rawHtml = """
            <html><body>
                <p>سعر الهاتف &quot;بكسل 8&quot; هو &pound;699 &amp; هاتف سامسونج هو &euro;799.</p>
            </body></html>
        """.trimIndent()

        val extracted = WebSourceFetcher.extractReadableText(rawHtml)
        assertTrue(extracted.contains("سعر الهاتف \"بكسل 8\" هو £699 & هاتف سامسونج هو €799."))
    }

    // 4. قراءة مقال طويل واستخراج التفاصيل (Long article extraction & detail retention)
    @Test
    fun `test4_longArticleExtractionAndDetailRetention`() {
        val longContent = buildString {
            append("<html><head><title>تقرير تقني شامل</title></head><body><article>")
            append("<h1>مقدمة التقرير</h1>")
            for (i in 1..40) {
                append("<p>هذه الفقرة رقم $i تشرح خلفية معمارية النظام والأداء وتفاصيل التحديثات البرمجية السابقة.</p>")
            }
            append("<div class='deep-spec'><h3>معلومة حاسمة في عمق المقال:</h3><p>المعالج الجديد يستهلك 4.2 واط في أقصى حمل مع ذاكرة 16GB LPDDR5X.</p></div>")
            for (i in 41..60) {
                append("<p>استمرار المناقشة والتحليلات الثانوية في الفقرة رقم $i حتى نهاية المقال.</p>")
            }
            append("</article></body></html>")
        }

        val extracted = WebSourceFetcher.extractReadableText(longContent)
        // Ensure deep details beyond 2,400 chars are preserved
        assertTrue(extracted.length > 2500)
        assertTrue(extracted.contains("المعالج الجديد يستهلك 4.2 واط في أقصى حمل مع ذاكرة 16GB LPDDR5X"))
    }

    // 5. بحث يحتاج إلى متابعة روابط متعددة (Follow multiple links / outlinks)
    @Test
    fun `test5_followMultipleLinksAndOutlinkDiscovery`() {
        val baseUri = URI("https://techhub.example.org/review/pixel8")
        val htmlWithLinks = """
            <html><body>
                <p>لقراءة التفاصيل الكاملة، راجع الروابط التالية:</p>
                <a href="/specs/detailed">المواصفات الكاملة</a>
                <a href="/benchmarks/battery?utm_source=twitter&ref=share">اختبارات البطارية</a>
                <a href="https://external-lab.example.org/teardown">تقرير التفكيك الخارجي</a>
                <a href="#comments">التعليقات</a>
                <a href="javascript:void(0)">مشاركة</a>
            </body></html>
        """.trimIndent()

        val links = WebSourceFetcher.extractOutlinks(htmlWithLinks, baseUri)
        assertEquals(3, links.size)
        assertTrue(links.contains("https://techhub.example.org/specs/detailed"))
        // Tracking params utm_source and ref stripped by normalization
        assertTrue(links.contains("https://techhub.example.org/benchmarks/battery"))
        assertTrue(links.contains("https://external-lab.example.org/teardown"))
        assertFalse(links.any { it.contains("#comments") || it.contains("javascript:") })
    }

    // 6. بحث معقد يتطلب عدة جولات من البحث والتحقق (Multi-round research & deduplication)
    @Test
    fun `test6_multiRoundResearchWithStatePreservationAndDeduplication`() = runBlocking {
        var fetchCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                fetchCount++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html><body><p>Fact about topic</p></body></html>".toResponseBody("text/html".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        val fetcher = WebSourceFetcher(client = client)

        // Fetch same URL with different tracking parameters across two rounds
        val sourceRound1 = AISource(url = "https://example.org/article?utm_source=google", domain = "example.org")
        val sourceRound2 = AISource(url = "https://example.org/article?ref=homepage&fbclid=xyz", domain = "example.org")

        val res1 = fetcher.fetchUrl(sourceRound1.url)
        val res2 = fetcher.fetchUrl(sourceRound2.url)

        assertNotNull(res1.excerpt)
        assertNotNull(res2.excerpt)
        // In-memory cache deduplicates after URL normalization so network is called only once
        assertEquals(1, fetchCount)
    }

    // 7. التعامل مع موقع لا يستجيب أو يحجب الوصول الآلي (Blocked site, SPA, timeout handling)
    @Test
    fun `test7_blockedSiteAndSpaHandlingWithDiagnostics`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                when (chain.request().url.host) {
                    "blocked.example.org" -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden - Cloudflare WAF")
                        .body("".toResponseBody("text/html".toMediaTypeOrNull()))
                        .build()

                    "spa.example.org" -> {
                        val spaHtml = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <title>SPA News Portal</title>
                                <meta name="description" content="ملخص المقال من وصف الميتا للموقع التفاعلي">
                                <script type="application/ld+json">
                                {
                                  "@context": "https://schema.org",
                                  "@type": "NewsArticle",
                                  "headline": "إطلاق ميزة الذكاء الاصطناعي الجديدة",
                                  "articleBody": "أعلنت الشركة اليوم عن إطلاق محرك البحث الذكي المدمج في الأجهزة."
                                }
                                </script>
                            </head>
                            <body>
                                <div id="root"></div>
                                <script src="/bundle.js"></script>
                            </body>
                            </html>
                        """.trimIndent()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(spaHtml.toResponseBody("text/html".toMediaTypeOrNull()))
                            .build()
                    }

                    "normal.example.org" -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html><body><p>Normal article text from verified source.</p></body></html>".toResponseBody("text/html".toMediaTypeOrNull()))
                        .build()

                    else -> throw java.net.SocketTimeoutException("Simulated connection timeout")
                }
            })
            .build()

        val fetcher = WebSourceFetcher(client = client)

        val sources = listOf(
            AISource(url = "https://blocked.example.org/article", domain = "blocked.example.org"),
            AISource(url = "https://timeout.example.org/slow", domain = "timeout.example.org"),
            AISource(url = "https://spa.example.org/app", domain = "spa.example.org"),
            AISource(url = "https://normal.example.org/doc", domain = "normal.example.org")
        )

        val results = fetcher.fetch(sources, maxSources = 4)

        // Blocked source is captured with status and does not crash the agent
        val blocked = results.first { it.domain == "blocked.example.org" }
        assertEquals("403_BLOCKED_WAF", blocked.fetchStatus)

        // Timeout source is captured gracefully
        val timeout = results.first { it.domain == "timeout.example.org" }
        assertEquals("TIMEOUT", timeout.fetchStatus)

        // SPA source extracts JSON-LD and meta description even with empty body root div!
        val spa = results.first { it.domain == "spa.example.org" }
        assertNotNull(spa.excerpt)
        assertTrue(spa.excerpt!!.contains("إطلاق ميزة الذكاء الاصطناعي الجديدة") || spa.excerpt!!.contains("أعلنت الشركة اليوم"))

        // Normal source is fetched cleanly
        val normal = results.first { it.domain == "normal.example.org" }
        assertNotNull(normal.excerpt)
        assertTrue(normal.excerpt!!.contains("Normal article text"))

        // Evidence review prompt includes successful sources and reports status of inaccessible ones
        val reviewPrompt = WebResearchPolicy.evidenceReviewPrompt("ما هي المستجدات؟", "ملخص الأخبار", results)
        assertTrue(reviewPrompt.contains("Fetch Status: 403_BLOCKED_WAF"))
        assertTrue(reviewPrompt.contains("Fetch Status: TIMEOUT"))
    }
}
