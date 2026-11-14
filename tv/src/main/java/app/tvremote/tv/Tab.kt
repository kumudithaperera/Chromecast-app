package app.tvremote.tv

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import app.tvremote.protocol.HostBlocklist
import app.tvremote.protocol.isWebUrl
import app.tvremote.protocol.siteOf
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** A WebView that never asks the TV to pop up its own on-screen keyboard; typing comes from the phone. */
class TvWebView(context: Context) : WebView(context) {
    override fun onCheckIsTextEditor(): Boolean = false
}

/** One browser tab: a locked-down WebView plus the popup and redirect policy. */
class Tab(
    context: Context,
    private val owner: Owner,
    desktop: Boolean,
    /** True when another tab opened this one with window.open. */
    val isPopup: Boolean = false,
) {
    interface Owner {
        val blocklist: HostBlocklist

        /** Per-site zoom and the site helpers. */
        val siteScripts: SiteScripts

        /** True when the person turned ad blocking off for [site]. Called off the main thread. */
        fun adsAllowed(site: String): Boolean

        /** SystemClock.uptimeMillis() of the last click the person made. */
        val lastUserActionAt: Long
        fun onTabUpdated(tab: Tab)
        fun onBlocked(tab: Tab, what: String)
        fun onNewWindow(opener: Tab, userGesture: Boolean, transport: Message): Boolean
        fun onCloseRequested(tab: Tab)
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideCustomView()
        fun toast(message: String)

        /** The video in this tab changed (new position, play state, or gone). */
        fun onVideoChanged(tab: Tab)

        /** Saved pages for the start page, as (title, url). */
        fun bookmarks(): List<Pair<String, String>>

        /** Visited pages for the start page, newest first. */
        fun history(): List<HistoryEntry>

        /** A page finished loading or the address changed inside it. [title] is "" when not yet known. */
        fun onPageVisited(tab: Tab, url: String, title: String)

        /** The page at [url] reported its title. */
        fun onPageTitle(tab: Tab, url: String, title: String)

        /** A link on the start page that edits history. [action] is the part after [HOME_ACTION]. */
        fun onHomeAction(tab: Tab, action: String, params: Uri)
    }

    val web = TvWebView(context)

    /** Reports and controls the video playing in this tab, including inside iframes. */
    val video = VideoBridge(web) { owner.onVideoChanged(this) }
    var title: String = ""
        private set

    /** The address [title] was reported for. */
    private var titleUrl: String = ""
    var url: String = ""
        private set
    var loading: Boolean = false
        private set
    var desktop: Boolean = desktop
        private set

    /** The last navigation we refused, so the phone can offer to allow it. */
    var lastBlockedUrl: String? = null
        private set
    var lastBlockedHost: String = ""
        private set

    /** Requests to blocklisted hosts on the current page. Written off the main thread. */
    private val adsBlockedCount = AtomicInteger(0)
    val adsBlocked: Int get() = adsBlockedCount.get()

    /** The page Reader mode was opened from, while the tab shows the reader view. */
    var readerOf: String? = null
        private set

    /** The address that failed to load, while the tab shows the error page for it. */
    var failedUrl: String? = null
        private set

    /** The page being fetched for the main frame. Written off the main thread. */
    @Volatile private var mainRequestUrl: String = ""

    /** The site (as `siteOf` gives it) of the page in this tab, "" for our own pages. Read off the main thread. */
    @Volatile var pageSite: String = ""
        private set

    val userAgent: String get() = web.settings.userAgentString

    private var scripts: List<ScriptHandler> = emptyList()

    private var committedOnce = false
    private var allowUntil = 0L
    private val appContext = context.applicationContext

    init {
        configure()
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { _, _, _, _, _ -> owner.toast("Downloads aren't supported yet") }
        // An accidental page long-press shouldn't select text or open a context menu.
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        scripts = owner.siteScripts.install(web)
    }

    /** Puts the zoom and helper scripts back after a setting changed. They apply from the next page load. */
    fun reinstallScripts() {
        scripts.forEach { it.remove() }
        scripts = owner.siteScripts.install(web)
    }

    private fun configure() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        // Lets onCreateWindow see script popups so we can count and block the ones without a click.
        s.javaScriptCanOpenWindowsAutomatically = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = true
        s.setGeolocationEnabled(false)
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        applyUserAgent()
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
    }

    private fun applyUserAgent() {
        web.settings.userAgentString = if (desktop) desktopUserAgent() else null
    }

    private fun desktopUserAgent(): String {
        val major = try {
            WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')
        } catch (_: Exception) {
            null
        } ?: "120"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    fun setDesktop(on: Boolean) {
        if (on == desktop) return
        desktop = on
        applyUserAgent()
        if (!isHome) reload()
        owner.onTabUpdated(this)
    }

    // ---- navigation ----

    fun load(target: String) {
        if (!isWebUrl(target)) return
        web.loadUrl(target)
    }

    /** Loads the page again. Our own pages have no address to reload, so each goes back to what it stands for. */
    fun reload() {
        val failed = failedUrl
        val original = readerOf
        when {
            failed != null -> web.loadUrl(failed)
            original != null -> web.loadUrl(original)
            isHome -> loadHome()
            else -> web.reload()
        }
    }

    fun loadHome() {
        committedOnce = false
        val saved = owner.bookmarks()
        val tiles = if (saved.isEmpty()) {
            "<p class='none'>Nothing saved yet. Use the bookmark button at the top right to keep a page here.</p>"
        } else {
            "<div class='grid'>" + saved.joinToString("") { (title, link) ->
                val host = runCatching { Uri.parse(link).host }.getOrNull().orEmpty()
                "<a class='tile' href='${TextUtils.htmlEncode(link)}'><b>${TextUtils.htmlEncode(title.ifBlank { host })}</b>" +
                    "<span class='mono'>${TextUtils.htmlEncode(host)}</span></a>"
            } + "</div>"
        }
        val now = System.currentTimeMillis()
        // Everything inserted here is escaped, so none of it can contain another placeholder.
        val html = appContext.assets.open("home.html").bufferedReader().use { it.readText() }
            .replace("<!--BOOKMARKS-->", tiles)
            .replace("<!--HISTORY-->", HomeContent.history(owner.history(), now))
        // An https origin gives the page a secure context, which the DRM probe on it needs.
        web.loadDataWithBaseURL(HOME_BASE, html, "text/html", "utf-8", null)
    }

    /** Swaps the History section of the open start page for a fresh one, without reloading it, so the scroll position stays. */
    fun refreshHome() {
        if (!isHome) return
        val html = JSONObject.quote(HomeContent.history(owner.history(), System.currentTimeMillis()))
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        web.evaluateJavascript("(function(h){var b=document.getElementById('hist');if(b)b.innerHTML=h})($html)", null)
    }

    /** Lets the one navigation we refused go through. */
    fun allowBlocked() {
        val target = lastBlockedUrl ?: return
        lastBlockedUrl = null
        lastBlockedHost = ""
        // loadUrl() doesn't pass through shouldOverrideUrlLoading, but redirects after it do.
        allowUntil = SystemClock.uptimeMillis() + ALLOW_WINDOW_MS
        web.loadUrl(target)
        owner.onTabUpdated(this)
    }

    val isHome: Boolean get() = url == HOME_BASE

    /** Real web pages only: not the start page, Reader view, error page, or anything that isn't http(s). */
    private fun isHistoryWorthy(pageUrl: String) = isWebUrl(pageUrl) && !pageUrl.startsWith(HOME_BASE)

    /** What the address bar shows: the original page while in Reader mode, the address that failed on the error page. */
    val displayUrl: String get() = readerOf ?: failedUrl ?: url

    /** Shows the page as plain text in a calm layout, or goes back to the page. */
    fun toggleReader() {
        val original = readerOf
        if (original != null) {
            readerOf = null
            web.loadUrl(original)
            return
        }
        if (isHome || url.startsWith(HOME_BASE) || !isWebUrl(url)) {
            owner.toast("Reader works on web pages")
            return
        }
        val from = url
        web.evaluateJavascript(READER_SCRIPT) { result ->
            val json = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
            val items = json?.optJSONArray("items")
            if (items == null || items.length() == 0) {
                owner.toast("Couldn't find an article on this page")
                return@evaluateJavascript
            }
            val pageTitle = json.optString("title").trim()
            val body = StringBuilder()
            for (i in 0 until items.length()) {
                val pair = items.optJSONArray(i) ?: continue
                // The template already shows the title; skip the article's own copy of it.
                if (pair.optString(0) == "H1" && pair.optString(1).trim() == pageTitle) continue
                val tag = when (pair.optString(0)) {
                    "H1", "H2" -> "h2"
                    "H3" -> "h3"
                    "LI" -> "li"
                    "BLOCKQUOTE" -> "blockquote"
                    "PRE" -> "pre"
                    else -> "p"
                }
                body.append("<$tag>").append(TextUtils.htmlEncode(pair.optString(1))).append("</$tag>")
            }
            val title = TextUtils.htmlEncode(json.optString("title"))
            val host = TextUtils.htmlEncode(Uri.parse(from).host.orEmpty())
            readerOf = from
            web.loadDataWithBaseURL(
                READER_BASE,
                READER_TEMPLATE.replace("{title}", title).replace("{host}", host).replace("{body}", body.toString()),
                "text/html", "utf-8", null,
            )
        }
    }

    fun destroy() {
        scripts.forEach { it.remove() }
        scripts = emptyList()
        web.stopLoading()
        web.webViewClient = WebViewClient()
        web.webChromeClient = null
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
    }

    // ---- typing ----

    /** Types into whatever has focus on the page. Only the JSON-quoted text ever enters the script. */
    fun type(text: String) {
        val quoted = JSONObject.quote(text).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val js = "(function(t){var e=document.activeElement;if(!e)return false;" +
            "var ed=e.isContentEditable||e.tagName==='TEXTAREA'||(e.tagName==='INPUT'&&!/^(checkbox|radio|button|submit|file|range|color|image|reset)$/i.test(e.type));" +
            "if(!ed)return false;" +
            "if(document.execCommand('insertText',false,t))return true;" +
            "if('value' in e){var s=e.selectionStart,n=e.selectionEnd;e.value=e.value.slice(0,s)+t+e.value.slice(n);" +
            "e.selectionStart=e.selectionEnd=s+t.length;e.dispatchEvent(new Event('input',{bubbles:true}));return true;}" +
            "return false;})($quoted)"
        web.evaluateJavascript(js) { result ->
            // Focus can sit in a cross-origin frame the script can't reach; fall back to key events.
            if (result != "true") typeWithKeys(text)
        }
    }

    private fun typeWithKeys(text: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray()) ?: return
        web.requestFocus()
        events.forEach { web.dispatchKeyEvent(it) }
    }

    /**
     * Deletes one character (or the selection) in the focused field. A scripted delete behaves the same
     * everywhere; the raw DEL key emptied the whole field on Google TV. Falls back to the key when the
     * focus is somewhere a script can't reach.
     */
    fun backspace() {
        val js = "(function(){var e=document.activeElement;if(!e)return false;" +
            "var ed=e.isContentEditable||e.tagName==='TEXTAREA'||(e.tagName==='INPUT'&&!/^(checkbox|radio|button|submit|file|range|color|image|reset)$/i.test(e.type));" +
            "if(!ed)return false;return document.execCommand('delete',false,null);})()"
        web.evaluateJavascript(js) { result -> if (result != "true") key(KeyEvent.KEYCODE_DEL) }
    }

    fun key(code: Int) {
        web.requestFocus()
        val now = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    // ---- clients ----

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // A new page starts its count here, before any of its own requests can be blocked.
            // (Resetting in onPageStarted raced with them: that callback can arrive later.)
            if (request.isForMainFrame) {
                adsBlockedCount.set(0)
                mainRequestUrl = request.url.toString()
                pageSite = siteOf(request.url.host)
            }
            val site = pageSite
            if ((site.isNotEmpty() && owner.adsAllowed(site)) || !owner.blocklist.isBlocked(request.url.host)) {
                return null
            }
            adsBlockedCount.incrementAndGet()
            view.post { owner.onTabUpdated(this@Tab) }
            if (request.isForMainFrame) view.post { block(request.url.toString(), request.url.host.orEmpty()) }
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            // Start page links that edit history. Only honoured while our own page is showing.
            if (request.isForMainFrame && isHome && target.startsWith(HOME_ACTION)) {
                owner.onHomeAction(this@Tab, target.removePrefix(HOME_ACTION).substringBefore('?'), request.url)
                return true
            }
            if (!isWebUrl(target)) {
                // intent:, market:, file:, javascript: and friends never load.
                block(target, request.url.scheme.orEmpty() + ":")
                return true
            }
            if (!request.isForMainFrame) return false
            if (isPopup && !committedOnce && owner.blocklist.isBlocked(request.url.host)) {
                owner.onCloseRequested(this@Tab)
                return true
            }
            if (SystemClock.uptimeMillis() < allowUntil) return false
            val crossSite = url.isNotEmpty() && !isHome && siteOf(Uri.parse(url).host) != siteOf(request.url.host)
            val recentClick = SystemClock.uptimeMillis() - owner.lastUserActionAt < CLICK_WINDOW_MS
            if (crossSite && committedOnce && !request.hasGesture() && !recentClick) {
                block(target, request.url.host.orEmpty())
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
            video.reset() // a new page has new players
            if (!pageUrl.startsWith(ERROR_BASE)) failedUrl = null
            if (!pageUrl.startsWith(READER_BASE)) {
                readerOf = null
                // Our own pages (start page, error page) never pass through shouldInterceptRequest.
                if (pageUrl.startsWith(HOME_BASE)) {
                    adsBlockedCount.set(0)
                    pageSite = ""
                }
            }
            url = pageUrl
            loading = true
            owner.onTabUpdated(this@Tab)
        }

        override fun onPageFinished(view: WebView, pageUrl: String) {
            url = pageUrl
            loading = false
            committedOnce = true
            owner.onTabUpdated(this@Tab)
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
            url = pageUrl
            // Single-page sites (YouTube, streaming apps) change address here without a page load.
            // The title only counts if it was reported for this address, not left over from the last page.
            if (isHistoryWorthy(pageUrl)) owner.onPageVisited(this@Tab, pageUrl, if (titleUrl == pageUrl) title else "")
            owner.onTabUpdated(this@Tab)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            if (owner.blocklist.isBlocked(request.url.host)) return
            showError(request.url.toString(), error.description?.toString().orEmpty())
        }

        // The default cancels the load and leaves a blank page with no explanation.
        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            val failing = error.url.orEmpty()
            if (failing.isNotEmpty() && failing == mainRequestUrl) {
                showError(failing, "This site's security certificate can't be trusted, so the page wasn't loaded.")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Low memory killed the renderer. Drop this tab instead of letting the app crash.
            owner.toast("A tab ran out of memory and was closed")
            owner.onCloseRequested(this@Tab)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, t: String?) {
            title = t.orEmpty()
            titleUrl = url
            if (isHistoryWorthy(url)) owner.onPageTitle(this@Tab, url, title)
            owner.onTabUpdated(this@Tab)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
            owner.onNewWindow(this@Tab, isUserGesture, resultMsg)

        override fun onCloseWindow(window: WebView?) = owner.onCloseRequested(this@Tab)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = owner.onShowCustomView(view, callback)

        override fun onHideCustomView() = owner.onHideCustomView()

        override fun getDefaultVideoPoster(): Bitmap? = null

        // No camera, microphone or location for web pages in this prototype.
        override fun onPermissionRequest(request: android.webkit.PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    /** Replaces the page with a short explanation. Reload tries [target] again. */
    private fun showError(target: String, reason: String) {
        val host = TextUtils.htmlEncode(runCatching { Uri.parse(target).host }.getOrNull().orEmpty())
        failedUrl = target.takeIf { isWebUrl(it) }
        web.loadDataWithBaseURL(
            ERROR_BASE,
            "<body style='background:#121211;color:#EDEAE2;font:24px sans-serif;padding:48px 64px'>" +
                "<h1>Can't open $host</h1><p>${TextUtils.htmlEncode(reason)}</p>" +
                "<p style='color:#A5A29A'>Reload tries again. The menu on the phone has the start page.</p></body>",
            "text/html", "utf-8", null,
        )
    }

    /** Records a refusal that has no URL to offer back (a popup with no destination). */
    fun markBlocked(what: String) {
        lastBlockedUrl = null
        lastBlockedHost = what
        owner.onTabUpdated(this)
    }

    private fun block(target: String, what: String) {
        lastBlockedUrl = if (isWebUrl(target)) target else null
        lastBlockedHost = what
        owner.onBlocked(this, what)
        owner.onTabUpdated(this)
    }

    companion object {
        const val HOME_BASE = "https://tvbrowser.invalid/"

        /** Links on the start page that change history. Never loaded; the tab handles them. */
        const val HOME_ACTION = "https://tvbrowser.invalid/x/"
        const val READER_BASE = "https://tvbrowser.invalid/reader"
        const val ERROR_BASE = "https://tvbrowser.invalid/error"

        /** Pulls the readable text out of a page. Returns a JSON string; nothing from it is ever run. */
        private const val READER_SCRIPT = "(function(){var r=document.querySelector('article')||document.querySelector('main')||document.body;" +
            "var out=[];r.querySelectorAll('h1,h2,h3,p,li,blockquote,pre').forEach(function(e){" +
            "if(e.closest('nav,footer,aside,header,form'))return;var t=(e.innerText||'').trim();if(t.length>1)out.push([e.tagName,t.slice(0,4000)])});" +
            "return JSON.stringify({title:document.title,items:out.slice(0,600)})})()"

        private const val READER_TEMPLATE = "<!doctype html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width'><title>{title}</title><style>" +
            "*{margin:0;box-sizing:border-box}body{background:#F7F6F1;color:#1B1B19;font:20px/1.55 Georgia,'Noto Serif',serif;padding:36px 0 96px}" +
            "main{max-width:880px;margin:0 auto;padding:0 32px}.src{font:14px 'DM Mono',monospace;color:#6E6C66;margin-bottom:8px}" +
            "h1{font-size:38px;line-height:1.2;font-weight:600;border-bottom:1px solid #C9C7BF;padding-bottom:10px;margin-bottom:20px}" +
            "h2{font-size:28px;margin:28px 0 12px}h3{font-size:23px;margin:22px 0 10px}p,li,blockquote,pre{margin-bottom:16px}" +
            "li{margin-left:28px}blockquote{border-left:3px solid #C9C7BF;padding-left:16px;color:#4A4945}pre{white-space:pre-wrap;font:16px monospace}" +
            "</style></head><body><main><div class='src'>{host}</div><h1>{title}</h1>{body}</main></body></html>"
        private const val CLICK_WINDOW_MS = 3_000L
        private const val ALLOW_WINDOW_MS = 5_000L
    }
}
