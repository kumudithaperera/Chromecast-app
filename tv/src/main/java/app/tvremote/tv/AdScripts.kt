package app.tvremote.tv

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tvremote.protocol.AdFilter
import app.tvremote.protocol.siteOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page side of ad blocking in one tab: element hiding and scriptlets.
 *
 * Element hiding: `assets/adblock/cosmetic.js` runs in every frame and asks [LISTENER] for CSS. A page can
 * see and call that object too, so what it sends is only ever read as a request for CSS: the host comes
 * from the frame's real origin (given by WebView, not the page), class names are checked, and the answer
 * is CSS from the lists that only hides things.
 *
 * Scriptlets must run before the page's own scripts, and WebView only gives a page the document-start
 * scripts that were in place well before it started loading. So they are all put in place ahead of time:
 * the rules are split into [GROUPS] groups by site, and each group is one script (the fixed
 * `assets/adblock/scriptlets.js` with that group's rules as JSON data) limited to the origins of its
 * sites. A page only ever parses its own group's rules. Sites named only as "name.*" can't be written as
 * an origin, so they get no scriptlets unless another rule names their domain.
 */
class AdScripts(context: Context, private val web: WebView, private val owner: Owner) {
    interface Owner {
        val adFilter: AdFilter
        val hideElements: Boolean
        val scriptletsOn: Boolean

        /** The sites where the person allowed ads. */
        val allowedSites: Set<String>

        /** False when blocking is off for the page in this tab (allowed site, page exception, start page). */
        fun blockingOnPage(): Boolean

        /** The page exceptions ([app.tvremote.protocol.PageFlags]) of the page in this tab. */
        val pageFlags: Int
    }

    private val assets = context.applicationContext.assets
    private val supported =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private val handlers = ArrayList<ScriptHandler>()

    init {
        if (supported) {
            WebViewCompat.addWebMessageListener(web, LISTENER, setOf("*")) { _, message, origin, _, proxy ->
                onMessage(message, origin, proxy)
            }
        }
        install()
    }

    /**
     * Puts the scripts in place for the current settings, lists and allowed sites. Main thread. They apply
     * from the next page load.
     */
    fun install() {
        removeAll()
        if (!supported) return
        if (owner.hideElements) handlers += WebViewCompat.addDocumentStartJavaScript(web, cosmeticScript(assets), setOf("*"))
        if (owner.scriptletsOn) {
            for (g in scriptletGroups(assets, owner.adFilter, owner.allowedSites)) {
                try {
                    handlers += WebViewCompat.addDocumentStartJavaScript(web, g.script, g.origins)
                } catch (_: IllegalArgumentException) {
                    // An origin rule WebView won't take; that group goes without.
                }
            }
        }
    }

    fun destroy() = removeAll()

    private fun removeAll() {
        handlers.forEach { it.remove() }
        handlers.clear()
    }

    private fun onMessage(message: WebMessageCompat, origin: Uri, proxy: JavaScriptReplyProxy) {
        val data = message.data ?: return
        if (data.length > MAX_MESSAGE || !owner.hideElements || !owner.blockingOnPage()) return
        val host = origin.host?.lowercase()?.trimEnd('.') ?: return
        val filter = owner.adFilter
        val flags = owner.pageFlags
        val css = when {
            data == "H" -> filter.cosmeticCss(host, flags)
            data.startsWith("T ") -> {
                val names = data.substring(2).split(' ').filter(::isName).take(MAX_NAMES)
                filter.genericCss(names, host, flags)
            }
            else -> return
        }
        if (css.isNotEmpty()) proxy.postMessage("C$css")
    }

    /** One scriptlet script and the origins it runs on. */
    private class Group(val script: String, val origins: Set<String>)

    companion object {
        /** The object the element hiding script talks to. */
        const val LISTENER = "tvAdblock"

        /** How many scripts the scriptlet rules are split into. More: less for each page to parse, more scripts. */
        private const val GROUPS = 32

        private const val MAX_MESSAGE = 48 * 1024
        private const val MAX_NAMES = 500
        // Checked by hand, not with regular expressions, which are slow on Android.

        /** ".name" or "#name" as the page script sends them. */
        private fun isName(s: String): Boolean {
            if (s.length < 2 || s.length > 101 || (s[0] != '.' && s[0] != '#')) return false
            if (!(s[1] in 'a'..'z' || s[1] in 'A'..'Z' || s[1] == '_')) return false
            for (i in 2 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) return false
            }
            return true
        }

        /** A host that can go in an origin rule: lowercase labels of letters, digits and "-". */
        private fun isHost(s: String): Boolean {
            var label = 0
            var labels = 0
            for (c in s) {
                if (c == '.') {
                    if (label == 0) return false
                    labels++
                    label = 0
                } else if (c in 'a'..'z' || c in '0'..'9' || c == '-') {
                    label++
                } else {
                    return false
                }
            }
            return label > 0 && labels >= 1
        }

        @Volatile private var cosmeticJs: String? = null
        @Volatile private var scriptletJs: String? = null

        /** The groups last built, for the filter and allowed sites they were built from: every tab shares them. */
        private var cachedFor: Pair<AdFilter, Set<String>>? = null
        private var cachedGroups: List<Group> = emptyList()

        private fun read(assets: AssetManager, name: String) = assets.open(name).bufferedReader().use { it.readText() }

        private fun cosmeticScript(assets: AssetManager): String =
            cosmeticJs ?: read(assets, "adblock/cosmetic.js").also { cosmeticJs = it }

        /** Which group a rule key goes in: by the site's name, so "google.*" and "www.google.com" share one. */
        private fun groupOf(key: String): Int {
            val name = if (key.endsWith(".*")) key.dropLast(2).substringAfterLast('.') else siteOf(key).substringBefore('.')
            return Math.floorMod(name.hashCode(), GROUPS)
        }

        /**
         * Builds the scriptlet scripts for [filter] ahead of time (a few hundred milliseconds on a TV), so
         * the tabs only pick them up. Off the main thread.
         */
        fun prepare(context: Context, filter: AdFilter, allowed: Set<String>) {
            scriptletGroups(context.applicationContext.assets, filter, allowed)
        }

        @Synchronized
        private fun scriptletGroups(assets: AssetManager, filter: AdFilter, allowed: Set<String>): List<Group> {
            cachedFor?.let { (f, a) -> if (f === filter && a == allowed) return cachedGroups }
            val started = System.nanoTime()
            val lib = scriptletJs ?: read(assets, "adblock/scriptlets.js").also { scriptletJs = it }
            val table = filter.scriptletTable()
            val specific = Array(GROUPS) { JSONObject() }
            val exceptions = Array(GROUPS) { JSONObject() }
            val origins = Array(GROUPS) { LinkedHashSet<String>() }
            for ((key, entries) in table.specific) {
                if (siteOf(key.removeSuffix(".*")) in allowed) continue
                val g = groupOf(key)
                val arr = JSONArray()
                for ((call, excl) in entries) {
                    arr.put(JSONArray().put(excl?.let { JSONArray(it.toList()) } ?: JSONObject.NULL).put(call.name).apply { call.args.forEach { put(it) } })
                }
                specific[g].put(key, arr)
                if (!key.endsWith(".*") && isHost(key)) {
                    origins[g] += "https://$key"
                    origins[g] += "https://*.$key"
                }
            }
            for ((key, offs) in table.exceptions) exceptions[groupOf(key)].put(key, JSONArray(offs.toList()))
            val groups = (0 until GROUPS).filter { origins[it].isNotEmpty() }.map { g ->
                val data = JSONObject().put("s", specific[g]).put("x", exceptions[g]).toString()
                Group(lib.replace("__DATA__", jsonLiteral(data)), origins[g])
            }
            android.util.Log.i("AdScripts", "${groups.size} scriptlet groups, ${origins.sumOf { it.size }} origins, built in ${(System.nanoTime() - started) / 1_000_000} ms")
            cachedFor = filter to allowed
            cachedGroups = groups
            return groups
        }

        /** JSON is valid JavaScript except for two line separators, which end a string literal in older engines. */
        private fun jsonLiteral(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }
}
