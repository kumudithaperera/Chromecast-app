package app.tvremote.tv

import android.net.Uri
import android.text.TextUtils
import app.tvremote.protocol.isWebUrl
import app.tvremote.protocol.timeAgo

/**
 * The History section of the start page, as HTML. Everything that came from a page or from
 * the person is escaped; the actions are links to [Tab.HOME_ACTION] that the tab intercepts.
 */
object HomeContent {
    private const val HISTORY_SHOWN = 8
    private const val HISTORY_LISTED = 60

    private fun esc(s: String): String = TextUtils.htmlEncode(s)

    private fun host(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.")

    /** Only real web addresses become links. */
    private fun link(url: String): String? = url.takeIf { isWebUrl(it) }?.let { esc(it) }

    fun history(entries: List<HistoryEntry>, now: Long): String {
        if (entries.isEmpty()) return "<p class=\"none\">Pages you open will be listed here.</p>"
        val listed = entries.take(HISTORY_LISTED)
        val sb = StringBuilder("<ul class=\"hist\">")
        listed.forEachIndexed { i, e ->
            val h = host(e.url)
            val title = e.title.ifBlank { h.ifBlank { e.url } }
            sb.append("<li class=\"row").append(if (i >= HISTORY_SHOWN) " more" else "").append("\">")
            sb.append("<a class=\"page\" href=\"").append(link(e.url) ?: "#").append("\"><b>").append(esc(title)).append("</b>")
            sb.append("<span class=\"mono\">").append(esc(h)).append(" · ").append(esc(timeAgo(now, e.at))).append("</span></a>")
            sb.append("<a class=\"x\" title=\"Remove\" href=\"").append(Tab.HOME_ACTION).append("hdel?u=").append(Uri.encode(e.url)).append("\">×</a>")
            sb.append("</li>")
        }
        sb.append("</ul>")
        if (listed.size > HISTORY_SHOWN) {
            val label = "Show all ${listed.size}"
            sb.append("<button type=\"button\" class=\"quiet\" data-more=\"1\" data-label=\"").append(label).append("\">").append(label).append("</button>")
        }
        return sb.toString()
    }
}
