package app.tvremote.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The tab strip and toolbar from the "TV: Browser" design. Sizes come from the
 * 1280×720 design and are scaled by 0.75 to the TV's 960×540 dp screen.
 * Items are clicked with the pointer: the activity hit-tests them with [itemAt].
 */
class BrowserChrome(private val context: Context, private val actions: Actions) {
    interface Actions {
        fun onSelectTab(index: Int)
        fun onNewTab()
        fun onCloseTab(index: Int)
        fun onBack()
        fun onReload()
        fun onAddressBar()
        fun onReader()
        fun onPhonePill()
        fun onBookmark()

        /** Turn ad blocking off or back on for this site. */
        fun onAdsToggle()

        /** Play the page's stream in the TV's own player. */
        fun onPlayMedia()

        /** Put this site's zoom back to 100%. */
        fun onZoomReset()
    }

    data class Model(
        val tabTitles: List<String>,
        val current: Int,
        val canAddTab: Boolean,
        val url: String,
        val secure: Boolean,
        val reader: Boolean,
        val adsBlocked: Int,
        val phoneText: String,
        val bookmarked: Boolean,
        /** The page has a stream the TV player can play. */
        val media: Boolean = false,
        /** Ad blocking is off for this site. */
        val adsAllowed: Boolean = false,
        /** Page zoom in percent for this site. */
        val zoom: Int = 100,
        /** A real web page is showing (not the start page). */
        val webPage: Boolean = false,
    )

    private val density = context.resources.displayMetrics.density
    private fun px(design: Number): Int = (design.toFloat() * 0.75f * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)

    private val sans: Typeface = context.resources.getFont(R.font.schibsted_grotesk)
    private val sansSemi: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(sans, 600, false) else Typeface.create(sans, Typeface.BOLD)
    private val mono: Typeface = context.resources.getFont(R.font.dm_mono_regular)

    private val tabStrip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(px(48), 0, px(48), 0)
        setBackgroundColor(color(R.color.ground))
    }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(48), 0, px(48), 0)
        setBackgroundColor(color(R.color.key))
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(tabStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)))
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60)))
    }

    private val back = circleButton(R.drawable.ic_arrow_left, "Back") { actions.onBack() }
    private val reload = circleButton(R.drawable.ic_refresh_cw, "Reload") { actions.onReload() }
    private val urlIcon = icon(R.drawable.ic_lock, R.color.dim, 18)
    private val urlText = text(16, mono, R.color.bone)
    private val urlPill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(16), 0, px(16), 0)
        background = rounded(R.color.ground, 22)
        addView(urlIcon, LinearLayout.LayoutParams(px(18), px(18)).apply { marginEnd = px(10) })
        addView(urlText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { actions.onAddressBar() }
        contentDescription = "Address"
    }
    private val readerIcon = icon(R.drawable.ic_book_open, R.color.bone, 18)
    private val readerText = text(14, sans, R.color.bone).apply { text = "Reader" }
    private val readerPill = pill(readerIcon, readerText).apply { setOnClickListener { actions.onReader() } }
    private val adsText = text(14, sans, R.color.bone)
    private val adsIcon = icon(R.drawable.ic_shield_check, R.color.ok, 18)
    private val adsPill = pill(adsIcon, adsText).apply {
        setOnClickListener { actions.onAdsToggle() }
        contentDescription = "Ad blocking on this site"
    }
    private val playPill = pill(icon(R.drawable.ic_play, R.color.bone, 18), text(14, sans, R.color.bone).apply { text = "TV player" }).apply {
        setOnClickListener { actions.onPlayMedia() }
        contentDescription = "Play this video in the TV player"
    }
    private val zoomText = text(14, mono, R.color.bone)
    private val zoomPill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), 0, px(14), 0)
        background = rounded(R.color.panel, 18)
        addView(zoomText)
        setOnClickListener { actions.onZoomReset() }
        contentDescription = "Zoom. Click to reset"
    }
    // Capped so a long PIN/IP line stays a pill. The toolbar is sized for a 960dp-wide TV screen.
    private val phoneText = text(14, sans, R.color.bone).apply { maxWidth = px(240) }
    private val phonePill = pill(icon(R.drawable.ic_smartphone, R.color.bone, 18), phoneText).apply {
        setOnClickListener { actions.onPhonePill() }
    }
    private val bookmarkIcon = icon(R.drawable.ic_bookmark, R.color.bone, 22)
    private val bookmark = FrameLayout(context).apply {
        addView(bookmarkIcon, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        background = rounded(android.R.color.transparent, 22)
        contentDescription = "Bookmark this page"
        setOnClickListener { actions.onBookmark() }
    }

    private val tabViews = mutableListOf<View>()
    private var focused: View? = null

    init {
        bar.addView(back, LinearLayout.LayoutParams(px(44), px(44)))
        bar.addView(reload, gap(LinearLayout.LayoutParams(px(44), px(44))))
        bar.addView(urlPill, gap(LinearLayout.LayoutParams(0, px(44), 1f)))
        bar.addView(zoomPill, gap(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36))))
        bar.addView(playPill, gap(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36))))
        bar.addView(readerPill, gap(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36))))
        bar.addView(adsPill, gap(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36))))
        bar.addView(phonePill, gap(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36))))
        bar.addView(bookmark, gap(LinearLayout.LayoutParams(px(44), px(44))))
    }

    fun bind(m: Model) {
        rebuildTabs(m)
        urlText.text = m.url.ifEmpty { "Start page" }
        urlIcon.setImageResource(if (m.secure) R.drawable.ic_lock else R.drawable.ic_globe)
        readerPill.background = rounded(if (m.reader) R.color.signal else R.color.panel, 18)
        val readerFg = color(if (m.reader) R.color.ground else R.color.bone)
        readerText.setTextColor(readerFg)
        readerIcon.imageTintList = ColorStateList.valueOf(readerFg)
        adsPill.visibility = if (m.webPage && (m.adsBlocked > 0 || m.adsAllowed)) View.VISIBLE else View.GONE
        adsText.text = when {
            m.adsAllowed -> "Ads allowed here"
            m.adsBlocked == 1 -> "1 ad blocked"
            else -> "${m.adsBlocked} ads blocked"
        }
        adsIcon.imageTintList = ColorStateList.valueOf(color(if (m.adsAllowed) R.color.dim else R.color.ok))
        playPill.visibility = if (m.media) View.VISIBLE else View.GONE
        zoomPill.visibility = if (m.webPage && m.zoom != 100) View.VISIBLE else View.GONE
        zoomText.text = "${m.zoom}%"
        phoneText.text = m.phoneText
        bookmarkIcon.imageTintList = ColorStateList.valueOf(color(if (m.bookmarked) R.color.signal else R.color.bone))
    }

    private var lastTabs: Triple<List<String>, Int, Boolean>? = null

    private fun rebuildTabs(m: Model) {
        val key = Triple(m.tabTitles, m.current, m.canAddTab)
        if (key == lastTabs) return
        lastTabs = key
        if (focused in tabViews) focus(null)
        tabStrip.removeAllViews()
        tabViews.clear()
        m.tabTitles.forEachIndexed { i, title ->
            val on = i == m.current
            val label = text(15, if (on) sansSemi else sans, if (on) R.color.bone else R.color.dim).apply {
                text = title
                maxWidth = px(240)
                gravity = Gravity.CENTER_VERTICAL
            }
            val close = FrameLayout(context).apply {
                addView(icon(R.drawable.ic_x, if (on) R.color.bone else R.color.dim, 14), FrameLayout.LayoutParams(px(14), px(14), Gravity.CENTER))
                contentDescription = "Close tab: $title"
                setOnClickListener { actions.onCloseTab(i) }
            }
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(4), 0)
                background = topRounded(if (on) R.color.key else R.color.panel)
                contentDescription = "Tab: $title"
                setOnClickListener { actions.onSelectTab(i) }
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(close, LinearLayout.LayoutParams(px(32), px(32)).apply { marginStart = px(4) })
            }
            tabStrip.addView(tab, tabParams())
            // The close button goes first so the pointer hit-test finds it before the tab around it.
            tabViews += close
            tabViews += tab
        }
        if (m.canAddTab) {
            val plus = FrameLayout(context).apply {
                addView(icon(R.drawable.ic_plus, R.color.dim, 18), FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
                setPadding(px(16), 0, px(16), 0)
                background = topRounded(R.color.panel)
                contentDescription = "New tab"
                setOnClickListener { actions.onNewTab() }
            }
            tabStrip.addView(plus, tabParams(width = px(50)))
            tabViews += plus
        }
    }

    /** The clickable item under a point in [root]'s coordinates, or null. */
    fun itemAt(root: View, x: Float, y: Float): View? {
        val origin = IntArray(2).also(root::getLocationInWindow)
        val loc = IntArray(2)
        return (tabViews + listOf(back, reload, urlPill, zoomPill, playPill, readerPill, adsPill, phonePill, bookmark)).firstOrNull { v ->
            if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) return@firstOrNull false
            v.getLocationInWindow(loc)
            val left = loc[0] - origin[0]
            val top = loc[1] - origin[1]
            x >= left && x < left + v.width && y >= top && y < top + v.height
        }
    }

    /** The design's TV focus: a 3px yellow ring and a slight scale on the item under the pointer. */
    fun focus(v: View?) {
        if (v === focused) return
        focused?.let {
            it.foreground = null
            it.scaleX = 1f
            it.scaleY = 1f
        }
        focused = v
        v?.let {
            val radius = when (it) {
                urlPill -> 22
                readerPill, phonePill, adsPill, playPill, zoomPill -> 18
                back, reload, bookmark -> 22
                else -> 10
            }
            it.foreground = GradientDrawable().apply {
                cornerRadius = px(radius).toFloat()
                setStroke(px(3), color(R.color.signal))
            }
            it.scaleX = 1.04f
            it.scaleY = 1.04f
        }
    }

    // ---- builders ----

    private fun gap(lp: LinearLayout.LayoutParams) = lp.apply { marginStart = px(12) }

    private fun tabParams(width: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, px(36)).apply { marginEnd = px(4) }

    private fun text(sizeDesignPx: Int, face: Typeface, colorId: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeDesignPx).toFloat())
        typeface = face
        setTextColor(color(colorId))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun icon(res: Int, colorId: Int, sizeDesignPx: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color(colorId))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumWidth = px(sizeDesignPx)
    }

    private fun circleButton(res: Int, label: String, onClick: () -> Unit) = FrameLayout(context).apply {
        addView(icon(res, R.color.bone, 22), FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        background = rounded(android.R.color.transparent, 22)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun pill(iconView: ImageView, label: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), 0, px(14), 0)
        background = rounded(R.color.panel, 18)
        addView(iconView, LinearLayout.LayoutParams(px(18), px(18)).apply { marginEnd = px(8) })
        addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun rounded(colorId: Int, radiusDesignPx: Int) = GradientDrawable().apply {
        setColor(color(colorId))
        cornerRadius = px(radiusDesignPx).toFloat()
    }

    private fun topRounded(colorId: Int) = GradientDrawable().apply {
        setColor(color(colorId))
        val r = px(10).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
}
