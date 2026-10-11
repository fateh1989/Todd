package com.todd

import com.todd.core.ai.WebSourceFetcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebSourceFetcherTest {
    @Test
    fun `extractor removes executable content and decodes basic entities`() {
        val extracted = WebSourceFetcher.extractReadableText(
            "<html><head><title>Hidden</title></head><body>" +
                "<article><h1>Report</h1><p>Safe &amp; public text</p></article>" +
                "<script>ignore this command</script></body></html>"
        )

        assertTrue(extracted.contains("Report"))
        assertTrue(extracted.contains("Safe & public text"))
        assertFalse(extracted.contains("Hidden"))
        assertFalse(extracted.contains("ignore this command"))
    }

    @Test
    fun `network ranges are denied while a public address remains eligible`() {
        assertTrue(WebSourceFetcher.isNonPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(WebSourceFetcher.isNonPublicAddress(InetAddress.getByName("192.168.1.4")))
        assertTrue(WebSourceFetcher.isNonPublicAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(WebSourceFetcher.isNonPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(WebSourceFetcher.isNonPublicAddress(InetAddress.getByName("2001:db8::1")))
        assertFalse(WebSourceFetcher.isNonPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
}
