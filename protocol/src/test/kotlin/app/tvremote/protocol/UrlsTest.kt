package app.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    @Test fun urlsAndHostsAreLoaded() {
        assertEquals("https://example.com", normalizeInput("https://example.com"))
        assertEquals("https://example.com", normalizeInput("  example.com "))
        assertEquals("https://example.com/a?b=1", normalizeInput("example.com/a?b=1"))
        assertEquals("http://192.168.1.5:8096", normalizeInput("192.168.1.5:8096"))
        assertEquals("http://localhost:3000/x", normalizeInput("localhost:3000/x"))
        assertEquals("https://example.com:8443", normalizeInput("example.com:8443"))
        assertEquals("http://nas.local", normalizeInput("nas.local"))
    }

    @Test fun phrasesBecomeSearches() {
        assertEquals(SEARCH_URL + "cats+in+space", normalizeInput("cats in space"))
        assertEquals(SEARCH_URL + "hello", normalizeInput("hello"))
        assertEquals(SEARCH_URL + "javascript%3Aalert%281%29+now", normalizeInput("javascript:alert(1) now"))
    }

    @Test fun dangerousSchemesAreRefused() {
        listOf(
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>1</script>", "file:///etc/passwd",
            "intent://x#Intent;end", "market://details?id=a", "content://a/b", "about:blank", "ftp://example.com",
            "mailto:a@b.co", "http://", "https://:80",
        ).forEach { assertNull("'$it'", normalizeInput(it)) }
        assertNull(normalizeInput("   "))
    }

    @Test fun isWebUrl() {
        assertTrue(isWebUrl("https://a.test/x"))
        assertTrue(isWebUrl("http://192.168.0.1:8080"))
        listOf("javascript:1", "file:///x", "intent://a", "https://", "//a.test", "a.test", "").forEach {
            assertFalse("'$it'", isWebUrl(it))
        }
    }

    @Test fun blocklistMatchesSubdomainsButNotLookalikes() {
        val b = HostBlocklist.parse("# comment\ndoubleclick.net\nads.example.com  # trailing\n")
        assertTrue(b.isBlocked("doubleclick.net"))
        assertTrue(b.isBlocked("stats.g.DoubleClick.net"))
        assertTrue(b.isBlocked("ads.example.com"))
        assertTrue(b.isBlocked("x.ads.example.com"))
        assertFalse(b.isBlocked("example.com"))
        assertFalse(b.isBlocked("notdoubleclick.net"))
        assertFalse(b.isBlocked("net"))
        assertFalse(b.isBlocked(null))
    }

    @Test fun siteOf() {
        assertEquals("example.com", siteOf("www.example.com"))
        assertEquals("example.com", siteOf("a.b.example.com"))
        assertEquals("example.com", siteOf("example.com"))
        assertEquals("", siteOf(null))
        assertEquals("10.0.2.2", siteOf("10.0.2.2"))
        assertTrue(siteOf("10.0.2.2") != siteOf("10.0.2.15"))
    }

    @Test fun blocklistReadsCommonListFormats() {
        val b = HostBlocklist.parse(
            """
            # Title: test
            0.0.0.0 tracker.example
            127.0.0.1   ads.hosts.example
            ||adblock.example^
            ||path.example/ads^
            @@||allowed.example^
            ! comment
            *.wild.example
            0.0.0.0 0.0.0.0
            localhost
            not a domain at all
            plain.example
            """.trimIndent(),
        )
        assertTrue(b.isBlocked("tracker.example"))
        assertTrue(b.isBlocked("cdn.ads.hosts.example"))
        assertTrue(b.isBlocked("x.adblock.example"))
        assertTrue(b.isBlocked("wild.example"))
        assertTrue(b.isBlocked("plain.example"))
        assertFalse(b.isBlocked("path.example"))
        assertFalse(b.isBlocked("allowed.example"))
        assertFalse(b.isBlocked("localhost"))
        assertFalse(b.isBlocked("0.0.0.0"))
        assertEquals(5, b.size)
    }

    @Test fun blocklistsCombineWithoutDuplicates() {
        val a = HostBlocklist(listOf("a.example", "b.example"))
        val c = HostBlocklist(listOf("b.example", "c.example"))
        val both = a + c
        assertEquals(3, both.size)
        listOf("a.example", "x.b.example", "c.example").forEach { assertTrue(it, both.isBlocked(it)) }
        assertFalse(both.isBlocked("d.example"))
        assertFalse(HostBlocklist(emptyList()).isBlocked("a.example"))
    }
}
