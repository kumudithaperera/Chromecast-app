package app.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFilterTest {
    private fun filter(vararg rules: String) = AdFilter.Builder().addFilterList(rules.asSequence()).build()

    private val S = ReqType.SCRIPT
    private val I = ReqType.IMAGE

    private fun AdFilter.blocks(url: String, page: String = "news.example", types: Int = S): Boolean =
        shouldBlock(url, java.net.URI(url).host, page, types)

    @Test fun hostRuleBlocksHostAndSubdomains() {
        val f = filter("||ads.example.com^")
        assertTrue(f.blocks("https://ads.example.com/x.js"))
        assertTrue(f.blocks("https://cdn.ads.example.com/x.js"))
        assertFalse(f.blocks("https://example.com/x.js"))
        assertFalse(f.blocks("https://badads.example.com/x.js"))
        // Plain host rules block the page itself too, as uBlock Origin does.
        assertTrue(f.blocks("https://ads.example.com/", types = ReqType.DOCUMENT))
    }

    @Test fun thirdPartyOnly() {
        val f = filter("||tracker.net^\$third-party")
        assertTrue(f.blocks("https://tracker.net/p.js", page = "news.example"))
        assertFalse(f.blocks("https://tracker.net/p.js", page = "www.tracker.net"))
        // Unknown page (a service worker): rules about the party don't apply.
        assertFalse(f.blocks("https://tracker.net/p.js", page = ""))
    }

    @Test fun firstPartyCountsCountryDomains() {
        val f = filter("||cdn.co.uk^\$third-party", "/banner/*\$third-party")
        assertTrue(f.blocks("https://static.othersite.co.uk/banner/a.png", page = "www.bbc.co.uk", types = I))
        assertFalse(f.blocks("https://static.bbc.co.uk/banner/a.png", page = "www.bbc.co.uk", types = I))
    }

    @Test fun pathPatternsWildcardsAndSeparators() {
        val f = filter("/ads/banner*.gif", "||example.org/pop^", "|https://exact.example/a.js|", "-adframe-")
        assertTrue(f.blocks("https://site.example/ads/banner-300x250.gif", types = I))
        assertFalse(f.blocks("https://site.example/ads/banner.png", types = I))
        assertTrue(f.blocks("https://example.org/pop?x=1"))
        assertTrue(f.blocks("https://example.org/pop"))
        assertFalse(f.blocks("https://example.org/popular"))
        assertTrue(f.blocks("https://exact.example/a.js"))
        assertFalse(f.blocks("https://exact.example/a.js?v=2"))
        assertTrue(f.blocks("https://x.example/s-adframe-1.html", types = ReqType.SUBDOCUMENT))
    }

    @Test fun typeOptions() {
        val f = filter("||cdn.example^\$script", "||img.example^\$~image")
        assertTrue(f.blocks("https://cdn.example/a.js", types = S))
        assertFalse(f.blocks("https://cdn.example/a.png", types = I))
        assertFalse(f.blocks("https://img.example/a.png", types = I))
        assertTrue(f.blocks("https://img.example/a.js", types = S))
        // Rules without types never apply to the page itself.
        assertFalse(filter("/track/*").blocks("https://site.example/track/x", types = ReqType.DOCUMENT))
    }

    @Test fun domainOption() {
        val f = filter("/promo/*\$domain=shop.example|~help.shop.example", "/widget.js\$domain=google.*")
        assertTrue(f.blocks("https://cdn.example/promo/a.js", page = "www.shop.example"))
        assertFalse(f.blocks("https://cdn.example/promo/a.js", page = "help.shop.example"))
        assertFalse(f.blocks("https://cdn.example/promo/a.js", page = "other.example"))
        assertTrue(f.blocks("https://cdn.example/widget.js", page = "www.google.co.uk"))
        assertFalse(f.blocks("https://cdn.example/widget.js", page = "notgoogle.com"))
    }

    @Test fun exceptionsAndImportant() {
        val f = filter("||ads.example^", "@@||ads.example/ok/", "||ads2.example^\$important", "@@||ads2.example^")
        assertTrue(f.blocks("https://ads.example/x.js"))
        assertFalse(f.blocks("https://ads.example/ok/x.js"))
        assertTrue(f.blocks("https://ads2.example/x.js"))
    }

    @Test fun badfilterCancelsARule() {
        val f = filter("||ads.example^", "||ads.example^\$badfilter", "/banner/*\$image", "/banner/*\$image,badfilter")
        assertFalse(f.blocks("https://ads.example/x.js"))
        assertFalse(f.blocks("https://a.example/banner/x.png", types = I))
    }

    @Test fun unsupportedOptionsDropTheRule() {
        val f = filter("||ads.example^\$redirect=noop.js", "||x.example^\$removeparam=utm", "||y.example^\$csp=script-src 'none'")
        assertFalse(f.blocks("https://ads.example/x.js"))
        assertFalse(f.blocks("https://x.example/x.js"))
        assertFalse(f.blocks("https://y.example/x.js"))
    }

    @Test fun regexRulesNeedASite() {
        val f = filter("/^https:\\/\\/[a-z]{8}\\.com\\/[a-z]+\\.js\$/\$domain=video.example", "/^https:\\/\\/anything/")
        assertTrue(f.blocks("https://abcdefgh.com/xyz.js", page = "video.example"))
        assertFalse(f.blocks("https://abcdefgh.com/xyz.js", page = "other.example"))
        assertFalse(f.blocks("https://anything.example/a.js"))
    }

    @Test fun pageExceptions() {
        val f = filter("||ads.example^", "@@||trusted.example^\$document", "@@||plain.example^\$generichide", "@@||bare.example^\$elemhide")
        assertEquals(PageFlags.NO_BLOCKING, f.pageFlags("https://trusted.example/", "trusted.example") and PageFlags.NO_BLOCKING)
        assertEquals(PageFlags.NO_GENERICHIDE, f.pageFlags("https://plain.example/a", "plain.example"))
        assertEquals(PageFlags.NO_ELEMHIDE, f.pageFlags("https://www.bare.example/", "www.bare.example"))
        assertEquals(0, f.pageFlags("https://other.example/", "other.example"))
        // A generichide exception doesn't let blocked requests through.
        assertTrue(f.blocks("https://ads.example/x.js", page = "plain.example"))
    }

    @Test fun ifDirectives() {
        val f = filter(
            "!#if env_firefox", "||firefox-only.example^", "!#else", "||chromium.example^", "!#endif",
            "!#if !ext_ubol", "||ublock.example^", "!#endif",
        )
        assertFalse(f.blocks("https://firefox-only.example/a.js"))
        assertTrue(f.blocks("https://chromium.example/a.js"))
        assertTrue(f.blocks("https://ublock.example/a.js"))
    }

    @Test fun domainListsAndHostsFiles() {
        val f = AdFilter.Builder().addDomains(sequenceOf("# comment", "popads.example", "0.0.0.0 tracker.example")).build()
        assertTrue(f.blocks("https://popads.example/a"))
        assertTrue(f.blocks("https://x.tracker.example/a"))
        assertTrue(f.blocks("https://popads.example/", types = ReqType.POPUP))
    }

    @Test fun popupRules() {
        val f = filter("||clickunder.example^\$popup", "/go/out*\$popup,domain=stream.example")
        assertTrue(f.blocks("https://clickunder.example/x", types = ReqType.POPUP))
        assertFalse(f.blocks("https://clickunder.example/x.js", types = S))
        assertTrue(f.blocks("https://ads.example/go/out?id=1", page = "stream.example", types = ReqType.POPUP))
        assertFalse(f.blocks("https://ads.example/go/out?id=1", page = "other.example", types = ReqType.POPUP))
    }

    @Test fun elementHiding() {
        val f = filter(
            "##.ad-banner", "##div[id^=\"div-gpt-ad\"]", "news.example##.sponsored", "google.*##.g-ad",
            "news.example#@#.ad-banner", "example.org,~sub.example.org##.promo", "~quiet.example##.nag",
            "site.example##.x:has-text(Ad)", "site.example##.box:has(> .ad)", "site.example#?#.y", "site.example##^script",
        )
        val news = f.cosmeticCss("www.news.example", 0)
        assertTrue(news.contains(".sponsored{display:none!important}"))
        assertTrue(news.contains("div[id^=\"div-gpt-ad\"]{"))
        assertTrue(news.contains(".nag{"))
        assertFalse(news.contains(".ad-banner"))
        assertTrue(f.cosmeticCss("www.google.de", 0).contains(".g-ad{"))
        assertTrue(f.cosmeticCss("example.org", 0).contains(".promo{"))
        assertFalse(f.cosmeticCss("sub.example.org", 0).contains(".promo{"))
        assertFalse(f.cosmeticCss("quiet.example", 0).contains(".nag{"))
        val site = f.cosmeticCss("site.example", 0)
        assertFalse(site.contains("has-text"))
        assertTrue(site.contains(".box:has(> .ad){"))
        // Plain classes come on demand, and the site's exception still holds.
        assertEquals(".ad-banner{display:none!important}\n", f.genericCss(listOf(".ad-banner", ".other"), "a.example", 0))
        assertEquals("", f.genericCss(listOf(".ad-banner"), "news.example", 0))
        // Page exceptions.
        assertEquals("", f.cosmeticCss("news.example", PageFlags.NO_ELEMHIDE))
        assertFalse(f.cosmeticCss("news.example", PageFlags.NO_GENERICHIDE).contains("div-gpt-ad"))
        assertEquals("", f.genericCss(listOf(".ad-banner"), "a.example", PageFlags.NO_GENERICHIDE))
    }

    @Test fun scriptlets() {
        val f = filter(
            "video.example##+js(aopr, adBlockDetected)",
            "video.example##+js(set, player.ads.enabled, false)",
            "video.example##+js(nowoif)",
            "www.video.example#@#+js(nowoif)",
            "video.example##+js(trusted-set-cookie, a, b)",
            "video.example##+js(unknown-thing)",
            "other.example##+js(acs, 'document.write', 'ad, block')",
            "off.example##+js(aopr, x)", "off.example#@#+js()",
        )
        val www = f.scriptletsFor("www.video.example")
        assertEquals(
            listOf(ScriptletCall("abort-on-property-read", listOf("adBlockDetected")), ScriptletCall("set-constant", listOf("player.ads.enabled", "false"))),
            www,
        )
        assertEquals(3, f.scriptletsFor("video.example").size)
        assertEquals(listOf(ScriptletCall("abort-current-script", listOf("document.write", "ad, block"))), f.scriptletsFor("other.example"))
        assertEquals(emptyList<ScriptletCall>(), f.scriptletsFor("off.example"))
    }

    @Test fun guessesRequestTypes() {
        assertEquals(ReqType.DOCUMENT, ReqType.guess("text/html", "https://a/", isMainFrame = true))
        assertEquals(ReqType.SUBDOCUMENT, ReqType.guess("text/html,application/xhtml+xml", "https://a/frame", false))
        assertEquals(ReqType.IMAGE, ReqType.guess("image/avif,image/webp,*/*", "https://a/p", false))
        assertEquals(ReqType.SCRIPT, ReqType.guess("*/*", "https://a/app.js?v=1", false))
        assertEquals(ReqType.MEDIA, ReqType.guess("*/*", "https://a/seg", false, hasRange = true))
        assertEquals(ReqType.SCRIPT or ReqType.XHR or ReqType.OTHER, ReqType.guess("*/*", "https://a/api/x", false))
    }

    @Test fun siteOfCountryDomains() {
        assertEquals("bbc.co.uk", siteOf("www.bbc.co.uk"))
        assertEquals("abc.net.au", siteOf("news.abc.net.au"))
        assertEquals("example.com", siteOf("a.example.com"))
        assertEquals("co.uk", siteOf("co.uk"))
    }

    @Test fun lookupKeys() {
        assertEquals(listOf("www.google.co.uk", "www.google.*", "google.co.uk", "google.*", "co.uk", "uk"), AdFilter.lookupKeys("www.google.co.uk"))
    }

    @Test fun emptyFilterBlocksNothing() {
        assertFalse(AdFilter.EMPTY.blocks("https://ads.example/x.js"))
        assertEquals("", AdFilter.EMPTY.cosmeticCss("a.example", 0))
    }
}
