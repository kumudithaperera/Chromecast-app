package app.tvremote.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import app.tvremote.protocol.BrowserState
import app.tvremote.protocol.Command
import app.tvremote.protocol.ControlHandler
import app.tvremote.protocol.ControlServer
import app.tvremote.protocol.HostBlocklist
import app.tvremote.protocol.KeyName
import app.tvremote.protocol.NavAction
import app.tvremote.protocol.PORT
import app.tvremote.protocol.TabAction
import app.tvremote.protocol.VideoAction
import app.tvremote.protocol.VideoState
import app.tvremote.protocol.ZoomAction
import app.tvremote.protocol.isWebUrl
import app.tvremote.protocol.normalizeInput
import app.tvremote.protocol.siteOf
import java.net.BindException

class BrowserActivity : Activity(), Tab.Owner, ControlHandler, BrowserChrome.Actions {
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<Tab>()
    private var current = 0
    private var desktop = true

    private lateinit var content: FrameLayout

    // Split view: two panes side by side. With split off only paneA is used.
    private lateinit var panes: LinearLayout
    private lateinit var paneA: FrameLayout
    private lateinit var paneB: FrameLayout
    private var split = false
    private var leftTab: Tab? = null
    private var rightTab: Tab? = null

    private lateinit var adLists: AdLists
    private lateinit var allowStore: android.content.SharedPreferences

    /** Sites where the person turned ad blocking off. Replaced whole, read from WebView threads. */
    @Volatile private var allowedSites: Set<String> = emptySet()
    private lateinit var fullscreen: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var chrome: BrowserChrome
    private lateinit var bookmarkStore: Bookmarks
    private lateinit var historyStore: History
    private lateinit var root: FrameLayout
    private lateinit var injector: PointerInjector
    private lateinit var phones: PhoneStore

    private var server: ControlServer? = null
    private var phoneConnected = false
    private var showInfo = false

    /** Code shown while a phone is pairing, else null. */
    private var pairingCode: String? = null
    private var drmSummary = "checking Widevine…"
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null

    private var cx = 0f
    private var cy = 0f
    private var lastHoverAt = 0L
    private var hoverPending = false
    private var dpadHeld = 0

    // What the phone last heard about the video, so it only hears changes.
    private var lastVideoSent: VideoState? = null
    private var videoSentOnce = false
    private val videoTick = object : Runnable {
        override fun run() {
            publishVideo()
            ui.postDelayed(this, VIDEO_TICK_MS)
        }
    }

    override var lastUserActionAt: Long = 0L
        private set

    /** The bundled seed list at first, then the seed plus the downloaded lists. Read from WebView threads. */
    @Volatile override var blocklist: HostBlocklist = HostBlocklist(emptyList())
        private set
    override lateinit var siteScripts: SiteScripts
        private set

    override fun adsAllowed(site: String): Boolean = site in allowedSites

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phones = PhoneStore(this)
        bookmarkStore = Bookmarks(this)
        historyStore = History(this)
        injector = PointerInjector(this)
        siteScripts = SiteScripts(this)
        allowStore = getSharedPreferences("ads_allowed", MODE_PRIVATE)
        allowedSites = allowStore.getStringSet("sites", emptySet())!!.toSet()
        adLists = AdLists(this)
        blocklist = adLists.seed
        loadAdLists()
        buildUi()
        Thread {
            val summary = DrmInfo.widevineSummary()
            ui.post { drmSummary = summary; refreshInfo() }
        }.start()

        if (!handleIntent(intent)) newTab(home = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Opens the link a VIEW intent carries. Returns true if it opened something. */
    private fun handleIntent(intent: Intent?): Boolean {
        val data = intent?.data?.toString() ?: return false
        if (intent.action != Intent.ACTION_VIEW) return false
        if (data.startsWith("tvbrowser:")) {
            if (tabs.isEmpty()) newTab(home = true)
            return true
        }
        if (!isWebUrl(data)) return false
        // At the tab limit the link takes over the tab on screen instead of being dropped.
        val tab = if (tabs.size < MAX_TABS) newTab(home = false) else tabs.getOrNull(current)
        tab?.load(data)
        return tab != null
    }

    override fun onStart() {
        super.onStart()
        startServer()
        ui.post(videoTick)
    }

    override fun onResume() {
        super.onResume()
        if (tabs.isNotEmpty()) layoutTabs()
        refreshInfo()
    }

    override fun onPause() {
        tabs.forEach { it.web.onPause() }
        super.onPause()
    }

    override fun onStop() {
        ui.removeCallbacks(videoTick)
        server?.close()
        server = null
        phoneConnected = false
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        tabs.toList().forEach { it.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    private fun startServer() {
        server?.close()
        try {
            val id = TvIdentity.load()
            val s = ControlServer(PORT, phones, android.os.Build.MODEL ?: "TV", id.ssl, id.fingerprint, this)
            s.start()
            server = s
        } catch (e: BindException) {
            toast("Phone control is off: port $PORT is in use")
        } catch (e: Exception) {
            toast("Phone control is off")
        }
        refreshInfo()
    }

    // ---- UI ----

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.ground)) }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chrome = BrowserChrome(this, this)
        column.addView(chrome.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this)
        paneA = FrameLayout(this)
        paneB = FrameLayout(this).apply { visibility = View.GONE }
        panes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(paneA, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(paneB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = (4 * resources.displayMetrics.density).toInt()
            })
        }
        content.addView(panes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            cursor.moveTo(cx, cy)
        }
    }

    private fun refreshInfo() {
        val ip = Net.localAddress()
        val t = tabs.getOrNull(current)
        val phone = when {
            pairingCode != null -> "Pairing code $pairingCode"
            showInfo -> "$drmSummary · ${phones.count()} paired"
            server == null -> "Phone control off"
            phoneConnected -> "Phone cursor on"
            else -> "Phone control · ${ip ?: "no network"}"
        }
        val url = t?.displayUrl.orEmpty()
        chrome.bind(
            BrowserChrome.Model(
                tabTitles = tabs.map { tabTitle(it) },
                current = current,
                canAddTab = tabs.size < MAX_TABS,
                url = if (t == null || t.isHome) "" else url.removePrefix("https://").removePrefix("http://").removeSuffix("/"),
                secure = t == null || t.isHome || url.startsWith("https://"),
                reader = t?.readerOf != null,
                adsBlocked = t?.adsBlocked ?: 0,
                phoneText = phone,
                bookmarked = t != null && !t.isHome && bookmarkStore.contains(url),
                adsAllowed = t != null && adsAllowed(t.pageSite),
                zoom = t?.let { siteScripts.zoomFor(it.pageSite) } ?: 100,
                webPage = t != null && !t.isHome && t.pageSite.isNotEmpty(),
            ),
        )
    }

    private fun tabTitle(t: Tab): String = when {
        t.isHome -> "Home"
        t.title.isNotBlank() && !t.title.startsWith("http") -> t.title
        else -> runCatching { android.net.Uri.parse(t.displayUrl).host }.getOrNull()?.removePrefix("www.") ?: "New tab"
    }

    // ---- BrowserChrome.Actions (pointer clicks on the toolbar) ----

    override fun onSelectTab(index: Int) = select(index)

    override fun onNewTab() {
        newTab(home = true)
    }

    override fun onCloseTab(index: Int) {
        tabs.getOrNull(index)?.let(::closeTab)
    }

    override fun onBack() = goBack()

    override fun onReload() {
        tabs.getOrNull(current)?.reload()
    }

    override fun onAddressBar() = toast("Type the address on your phone")

    override fun onReader() {
        tabs.getOrNull(current)?.toggleReader()
    }

    override fun onPhonePill() {
        showInfo = !showInfo
        refreshInfo()
    }

    override fun onAdsToggle() = toggleAds()


    override fun onZoomReset() = zoom(ZoomAction.RESET)

    override fun onBookmark() {
        val t = tabs.getOrNull(current) ?: return
        if (t.isHome || !isWebUrl(t.displayUrl)) return
        val saved = bookmarkStore.toggle(tabTitle(t), t.displayUrl)
        toast(if (saved) "Saved to the start page" else "Removed from the start page")
        refreshInfo()
    }

    override fun bookmarks(): List<Pair<String, String>> = bookmarkStore.all()

    // ---- history ----

    override fun history(): List<HistoryEntry> = historyStore.all()

    override fun onPageVisited(tab: Tab, url: String, title: String) = historyStore.record(url, title)

    override fun onPageTitle(tab: Tab, url: String, title: String) = historyStore.setTitle(url, title)

    /** Links on the start page: remove one history entry or clear it. The page is updated in place afterwards. */
    override fun onHomeAction(tab: Tab, action: String, params: Uri) {
        when (action) {
            "hdel" -> params.getQueryParameter("u")?.let { historyStore.remove(it) }
            "hclear" -> {
                historyStore.clear()
                toast("History cleared")
            }
            else -> return
        }
        tab.refreshHome()
    }

    // ---- tabs ----

    private fun newTab(home: Boolean, popup: Boolean = false): Tab? {
        if (tabs.size >= MAX_TABS) {
            toast("Too many tabs; close one first")
            return null
        }
        val tab = Tab(this, this, desktop, popup)
        tabs.add(tab)
        select(tabs.lastIndex)
        if (home) tab.loadHome()
        return tab
    }

    private fun select(index: Int) {
        if (index !in tabs.indices) return
        val t = tabs[index]
        if (split && t !== leftTab && t !== rightTab) {
            // The chosen tab takes the place of the one in the active pane.
            if (tabs.getOrNull(current) === rightTab) rightTab = t else leftTab = t
        }
        current = index
        layoutTabs()
        t.refreshHome()
        publish()
        publishVideo()
    }

    /**
     * Puts each tab's WebView where it belongs: the current tab alone, or the two split tabs side by
     * side. Hidden tabs are paused. The active pane gets a yellow edge so typing never goes somewhere
     * unexpected.
     */
    private fun layoutTabs() {
        val cur = tabs.getOrNull(current)
        if (split && (leftTab !in tabs || rightTab !in tabs || leftTab === rightTab)) split = false
        val placement: Map<Tab, FrameLayout> =
            if (split) mapOf(leftTab!! to paneA, rightTab!! to paneB) else listOfNotNull(cur).associateWith { paneA }
        for (t in tabs) {
            val want = placement[t]
            val parent = t.web.parent as? ViewGroup
            if (parent !== want) {
                parent?.removeView(t.web)
                want?.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            if (want == null) t.web.onPause() else t.web.onResume()
        }
        paneB.visibility = if (split) View.VISIBLE else View.GONE
        val ring = { on: Boolean ->
            if (!on) null else android.graphics.drawable.GradientDrawable().apply {
                setStroke((3 * resources.displayMetrics.density).toInt(), getColor(R.color.signal))
            }
        }
        paneA.foreground = ring(split && cur === leftTab)
        paneB.foreground = ring(split && cur === rightTab)
    }

    private fun closeTab(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        if (tab === leftTab || tab === rightTab) {
            split = false
            leftTab = null
            rightTab = null
        }
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        tabs.removeAt(i)
        tab.destroy()
        if (tabs.isEmpty()) {
            current = 0
            newTab(home = true)
            return
        }
        if (i < current || (i == current && current > tabs.lastIndex)) current--
        current = current.coerceIn(0, tabs.lastIndex)
        layoutTabs()
        tabs[current].refreshHome()
        publish()
    }

    /** Two tabs side by side: the current one on the left, the next one (or a new start page) on the right. */
    private fun toggleSplit() {
        if (split) {
            split = false
            leftTab = null
            rightTab = null
            layoutTabs()
            publish()
            return
        }
        val left = tabs.getOrNull(current) ?: return
        val right = tabs.getOrNull((current + 1) % tabs.size)?.takeIf { it !== left }
            ?: Tab(this, this, desktop).also {
                if (tabs.size >= MAX_TABS) {
                    toast("Too many tabs; close one first")
                    it.destroy()
                    return
                }
                tabs.add(it)
                it.loadHome()
            }
        split = true
        leftTab = left
        rightTab = right
        current = tabs.indexOf(left)
        layoutTabs()
        toast("Split view: click a side to type there")
        publish()
    }

    // ---- Tab.Owner ----

    override fun onTabUpdated(tab: Tab) {
        if (tabs.getOrNull(current) === tab) publish()
    }

    override fun onBlocked(tab: Tab, what: String) {
        toast("Blocked: $what")
    }

    override fun onNewWindow(opener: Tab, userGesture: Boolean, transport: Message): Boolean {
        if (!userGesture) {
            toast("Blocked a popup")
            opener.markBlocked("popup")
            return false
        }
        val popup = newTab(home = false, popup = true) ?: return false
        val t = transport.obj as? WebView.WebViewTransport ?: return false
        t.webView = popup.web
        transport.sendToTarget()
        return true
    }

    override fun onCloseRequested(tab: Tab) {
        ui.post { closeTab(tab) }
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        customView?.let { hideCustomView() }
        customView = view
        customViewCallback = callback
        fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreen.visibility = View.VISIBLE
        publishVideo()
    }

    override fun onHideCustomView() = hideCustomView()

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        publishVideo()
    }

    override fun toast(message: String) {
        ui.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---- state for the phone ----

    private var publishPending = false

    private fun publish() {
        refreshInfo()
        if (publishPending) return
        publishPending = true
        ui.postDelayed({
            publishPending = false
            val t = tabs.getOrNull(current) ?: return@postDelayed
            server?.sendState(
                BrowserState(
                    url = if (t.isHome) "" else t.displayUrl,
                    title = if (t.isHome) "Home" else t.title,
                    tabCount = tabs.size,
                    tabIndex = current,
                    desktop = desktop,
                    loading = t.loading,
                    blocked = t.lastBlockedHost,
                    adsAllowed = adsAllowed(t.pageSite),
                    zoom = siteScripts.zoomFor(t.pageSite),
                    split = split,
                    helpers = siteScripts.helpersOn,
                ),
            )
        }, 100)
    }

    // ---- ControlHandler (server threads) ----

    override fun onClientChanged(connected: Boolean) {
        ui.post {
            phoneConnected = connected
            showInfo = false
            if (connected) {
                publish()
                publishVideo(force = true)
            } else {
                refreshInfo()
            }
        }
    }

    override fun onCommand(command: Command) {
        ui.post { run(command) }
    }

    override fun onPairingCode(code: String?) {
        ui.post {
            pairingCode = code
            if (code != null) toast("Pairing code $code. Type it on your phone.")
            refreshInfo()
        }
    }

    private fun run(c: Command) {
        val tab = tabs.getOrNull(current) ?: return
        when (c) {
            is Command.Move -> moveCursor(c.dx, c.dy)
            Command.Click -> click()
            is Command.Scroll -> scroll(c.dy)
            is Command.Text -> tab.type(c.text)
            is Command.Key -> when (c.key) {
                KeyName.BACKSPACE -> tab.backspace()
                KeyName.ENTER -> tab.key(KeyEvent.KEYCODE_ENTER)
            }
            is Command.Nav -> when (c.action) {
                NavAction.BACK -> goBack()
                NavAction.FORWARD -> if (tab.web.canGoForward()) tab.web.goForward()
                NavAction.RELOAD -> tab.reload()
                NavAction.STOP -> tab.web.stopLoading()
                NavAction.HOME -> tab.loadHome()
                NavAction.ALLOW_BLOCKED -> tab.allowBlocked()
                NavAction.READER -> tab.toggleReader()
                NavAction.ADS -> toggleAds()
                NavAction.AD_LISTS -> {
                    adLists.enabled = !adLists.enabled
                    if (adLists.enabled) {
                        loadAdLists()
                        toast("Stronger ad blocking on. Some video players may stop loading.")
                    } else {
                        blocklist = adLists.seed
                        toast("Stronger ad blocking off. Only the basic list blocks.")
                    }
                    tab.reload()
                }
                NavAction.HELPERS -> {
                    siteScripts.helpersOn = !siteScripts.helpersOn
                    tabs.forEach { it.reinstallScripts() }
                    toast(if (siteScripts.helpersOn) "Site helpers on (from the next page)" else "Site helpers off (from the next page)")
                    publish()
                }
            }
            is Command.Go -> {
                val target = normalizeInput(c.input)
                if (target == null) toast("Can't open that") else tab.load(target)
            }
            is Command.Tab -> when (c.action) {
                TabAction.NEW -> newTab(home = true)
                TabAction.NEXT -> select((current + 1) % tabs.size)
                TabAction.PREV -> select((current - 1 + tabs.size) % tabs.size)
                TabAction.CLOSE -> closeTab(tab)
                TabAction.SPLIT -> toggleSplit()
            }
            is Command.Video -> videoCommand(tab, c)
            is Command.Zoom -> zoom(c.action)
            is Command.Desktop -> {
                desktop = c.on
                tabs.forEach { it.setDesktop(c.on) }
                publish()
            }
            Command.Ping -> Unit
        }
    }

    // ---- video ----

    /**
     * The video the phone should show controls for. "Full screen" is the TV's real state (the WebView is
     * showing a full-screen view), whatever the page believes. If the player can't be reached (an old
     * WebView, or a player that hides its <video>), the phone still learns the TV is full screen.
     */
    private fun currentVideo(): VideoState? {
        val fullscreen = customView != null
        val reported = tabs.getOrNull(current)?.video?.video
        return when {
            reported != null -> reported.copy(fullscreen = fullscreen)
            fullscreen -> VideoState(fullscreen = true)
            else -> null
        }
    }

    private fun publishVideo(force: Boolean = false) {
        val now = currentVideo()
        if (!force && videoSentOnce && now == lastVideoSent) return
        videoSentOnce = true
        lastVideoSent = now
        server?.sendVideo(now)
    }

    override fun onVideoChanged(tab: Tab) {
        if (tabs.getOrNull(current) === tab) publishVideo()
    }

    private fun videoCommand(tab: Tab, c: Command.Video) {
        when (c.action) {
            VideoAction.EXIT_FULLSCREEN -> {
                tab.video.send(VideoAction.EXIT_FULLSCREEN)
                hideCustomView()
                publishVideo()
            }
            // Without a reachable player, the media key still pauses and resumes what is playing.
            VideoAction.TOGGLE -> if (!tab.video.send(VideoAction.TOGGLE)) tab.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            VideoAction.FULLSCREEN -> enterFullscreen(tab)
            else -> tab.video.send(c.action, c.value)
        }
    }

    /**
     * Shows the page's video full screen: the video's frame says where the video is and is armed, then a
     * real tap there (the only thing a page accepts as a reason to go full screen) is turned into the request.
     */
    private fun enterFullscreen(tab: Tab) {
        if (customView != null) return
        val asked = tab.video.requestFullscreen { x, y, viewportWidth ->
            val web = tab.web
            if (tabs.getOrNull(current) !== tab || customView != null || web.width <= 0) return@requestFullscreen
            val scale = web.width / viewportWidth
            lastUserActionAt = SystemClock.uptimeMillis()
            injector.tap(web, (x * scale).coerceIn(1f, web.width - 1f), (y * scale).coerceIn(1f, web.height - 1f))
        }
        if (!asked) {
            toast("No video on this page yet")
            return
        }
        ui.postDelayed({
            if (customView == null && tabs.getOrNull(current) === tab) {
                toast("Couldn't open full screen. Use the player's own full screen button.")
            }
        }, VideoBridge.FULLSCREEN_WINDOW_MS + 1_000L)
    }

    // ---- ads, zoom ----

    private fun toggleAds() {
        val tab = tabs.getOrNull(current) ?: return
        val site = tab.pageSite
        if (site.isEmpty()) return
        val allowed = site !in allowedSites
        allowedSites = if (allowed) allowedSites + site else allowedSites - site
        allowStore.edit().putStringSet("sites", allowedSites).apply()
        toast(if (allowed) "Ads allowed on $site" else "Blocking ads on $site again")
        tab.reload()
        publish()
    }

    private fun zoom(action: ZoomAction) {
        val tab = tabs.getOrNull(current) ?: return
        val site = tab.pageSite
        if (site.isEmpty()) {
            toast("Zoom works on web pages")
            return
        }
        val now = siteScripts.zoomFor(site)
        val next = when (action) {
            ZoomAction.IN -> ZOOM_STEPS.firstOrNull { it > now } ?: ZOOM_STEPS.last()
            ZoomAction.OUT -> ZOOM_STEPS.lastOrNull { it < now } ?: ZOOM_STEPS.first()
            ZoomAction.RESET -> 100
        }
        val stored = siteScripts.setZoom(site, next)
        // The page on screen changes now; the stored value applies to this site's next pages in every tab.
        tabs.forEach { t ->
            t.reinstallScripts()
            if (t.pageSite == site) siteScripts.applyNow(t.web, stored)
        }
        publish()
    }

    /** Reads the saved list, then downloads a fresh one if it's a week old. Never on the main thread. */
    private fun loadAdLists() {
        if (!adLists.enabled) return
        Thread {
            adLists.loadSaved()?.let { saved -> ui.post { if (adLists.enabled) blocklist = adLists.seed + saved } }
            if (adLists.isStale()) {
                adLists.download()?.let { fresh -> ui.post { if (adLists.enabled) blocklist = adLists.seed + fresh } }
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    // ---- pointer ----

    private fun moveCursor(dxDp: Float, dyDp: Float) {
        val d = resources.displayMetrics.density
        setCursor(cx + dxDp * d, cy + dyDp * d)
    }

    private fun setCursor(x: Float, y: Float) {
        cx = x.coerceIn(0f, (root.width - 1).coerceAtLeast(0).toFloat())
        cy = y.coerceIn(0f, (root.height - 1).coerceAtLeast(0).toFloat())
        cursor.moveTo(cx, cy)
        chrome.focus(chrome.itemAt(root, cx, cy))
        val now = SystemClock.uptimeMillis()
        if (now - lastHoverAt >= HOVER_MS) {
            sendHover()
        } else if (!hoverPending) {
            // Throttled: make sure the page still gets the final resting position.
            hoverPending = true
            ui.postDelayed({ hoverPending = false; sendHover() }, HOVER_MS)
        }
    }

    private fun sendHover() {
        lastHoverAt = SystemClock.uptimeMillis()
        target()?.let { (view, x, y) -> injector.hover(view, x, y) }
    }

    private fun click() {
        lastUserActionAt = SystemClock.uptimeMillis()
        cursor.wake()
        if (customView == null) {
            chrome.itemAt(root, cx, cy)?.let {
                it.performClick()
                return
            }
        }
        // In split view, clicking the other pane makes it the active one first.
        target()?.let { (view, _, _) ->
            val tapped = tabs.indexOfFirst { it.web === view }
            if (tapped >= 0 && tapped != current) select(tapped)
        }
        // Over the full-screen video, a tap goes to the video's own controls.
        target()?.let { (view, x, y) -> injector.tap(view, x, y) }
    }

    private fun scroll(dyDp: Float) {
        val d = resources.displayMetrics.density
        lastUserActionAt = SystemClock.uptimeMillis()
        target()?.let { (view, x, y) -> injector.scroll(view, x, y, dyDp * d) }
    }

    /** Scrolls the page from the TV remote, wherever the pointer is (it may be resting on the toolbar). */
    private fun scrollPage(dyDp: Float) {
        val web = tabs.getOrNull(current)?.web ?: return
        cursor.wake()
        val t = target()?.takeIf { it.view === web }
        injector.scroll(web, t?.x ?: (web.width / 2f), t?.y ?: (web.height / 2f), dyDp * resources.displayMetrics.density)
    }

    private data class Target(val view: View, val x: Float, val y: Float)

    /** The view under the cursor and the cursor's position in that view, or null over the top bar. */
    private fun target(): Target? {
        if (customView == null && split) {
            for (t in listOfNotNull(leftTab, rightTab)) hit(t.web)?.let { return it }
            return null
        }
        val view: View = if (customView != null) fullscreen else tabs.getOrNull(current)?.web ?: return null
        return hit(view)
    }

    private fun hit(view: View): Target? {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val x = cx + rootLoc[0] - loc[0]
        val y = cy + rootLoc[1] - loc[1]
        if (x < 0 || y < 0 || x >= view.width || y >= view.height) return null
        return Target(view, x, y)
    }

    private fun goBack() {
        val tab = tabs.getOrNull(current) ?: return
        when {
            customView != null -> hideCustomView()
            tab.web.canGoBack() -> tab.web.goBack()
            tabs.size > 1 -> closeTab(tab)
        }
    }

    // ---- TV remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    dpadHeld = if (event.repeatCount == 0) 0 else dpadHeld + 1
                    val step = 8f + minOf(40f, dpadHeld * 2f)
                    // Pushing the pointer against the bottom or the top of the screen scrolls the page,
                    // so the TV's own remote can read a long page without the phone.
                    // The top edge is the toolbar, so "against the top" means the very top of the screen.
                    val atTop = cy <= 1f
                    val atBottom = cy >= root.height * (1f - EDGE)
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> if (atTop && customView == null) scrollPage(step * 4f) else moveCursor(0f, -step)
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (atBottom && customView == null) scrollPage(-step * 4f) else moveCursor(0f, step)
                        KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-step, 0f)
                        else -> moveCursor(step, 0f)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) click()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    val tab = tabs.getOrNull(current)
                    if (customView != null || tab?.web?.canGoBack() == true || tabs.size > 1) goBack() else finish()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (down && event.repeatCount == 0) {
                    showInfo = !showInfo
                    refreshInfo()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val MAX_TABS = 5

        /** Zoom steps in percent, for zoom in and out from the phone. */
        val ZOOM_STEPS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200)
        const val HOVER_MS = 40L
        const val VIDEO_TICK_MS = 1000L

        /** The share of the screen height at the top and bottom where the D-pad scrolls instead of moving the pointer. */
        const val EDGE = 0.06f
    }
}
