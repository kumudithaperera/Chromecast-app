package app.tvremote.tv

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tvremote.protocol.MAX_ZOOM
import app.tvremote.protocol.MIN_ZOOM
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scripts the browser adds to pages: per-site zoom and the built-in site helpers (reject cookie
 * banners, press "skip intro"). Both are fixed code; the only data they carry is built here with
 * JSONObject from values checked below, so nothing from a page or the network ends up as script.
 */
class SiteScripts(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("site_scripts", Context.MODE_PRIVATE)

    /** Zoom in percent per site (as `siteOf` gives it). 100 is never stored. */
    private val zoom: MutableMap<String, Int> = loadZoom()

    /** The bundled helper rules, re-serialised after checking every field. */
    private val rulesJson: String = loadRules()

    val supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    var helpersOn: Boolean
        get() = prefs.getBoolean("helpers", true)
        set(value) = prefs.edit().putBoolean("helpers", value).apply()

    fun zoomFor(site: String): Int = zoom[site] ?: 100

    /** Stores the zoom for [site]. Returns the value actually stored (clamped, in steps). */
    fun setZoom(site: String, percent: Int): Int {
        val z = percent.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (!SITE.matches(site)) return 100
        if (z == 100) zoom.remove(site) else zoom[site] = z
        // Keep the stored map small; the oldest entries go first.
        while (zoom.size > MAX_SITES) zoom.remove(zoom.keys.first())
        prefs.edit().putString("zoom", JSONObject(zoom as Map<*, *>).toString()).apply()
        return z
    }

    /** Adds the scripts to [web]. Returns handles to remove them again, empty if unsupported. */
    fun install(web: WebView): List<ScriptHandler> {
        if (!supported) return emptyList()
        val out = ArrayList<ScriptHandler>(2)
        if (zoom.isNotEmpty()) {
            out += WebViewCompat.addDocumentStartJavaScript(web, ZOOM_SCRIPT.replace("__ZOOM__", jsonLiteral(JSONObject(zoom as Map<*, *>).toString())), setOf("*"))
        }
        if (helpersOn && rulesJson != "[]") {
            out += WebViewCompat.addDocumentStartJavaScript(web, HELPER_SCRIPT.replace("__RULES__", jsonLiteral(rulesJson)), setOf("*"))
        }
        return out
    }

    /** Zooms the page on screen now, without reloading. */
    fun applyNow(web: WebView, percent: Int) {
        val value = if (percent == 100) "''" else "'" + (percent / 100f).toString() + "'"
        web.evaluateJavascript("(function(){var e=document.documentElement;if(e)e.style.zoom=$value})()", null)
    }

    private fun loadZoom(): MutableMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val raw = prefs.getString("zoom", null) ?: return out
        runCatching {
            val o = JSONObject(raw)
            o.keys().forEach { k ->
                val v = o.optInt(k, 100)
                if (SITE.matches(k) && v != 100) out[k] = v.coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
        }
        return out
    }

    private fun loadRules(): String {
        val clean = JSONArray()
        runCatching {
            val rules = JSONObject(app.assets.open("site-helpers.json").bufferedReader().use { it.readText() }).getJSONArray("rules")
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                val sites = strings(r.optJSONArray("sites"), 100) { it == "*" || SITE.matches(it) }
                val click = strings(r.optJSONArray("click"), 200) { true }
                val text = strings(r.optJSONArray("clickText"), 60) { true }
                if (sites.length() == 0 || (click.length() == 0 && text.length() == 0)) continue
                clean.put(JSONObject().put("sites", sites).put("click", click).put("clickText", text))
            }
        }
        return clean.toString()
    }

    private fun strings(arr: JSONArray?, maxLen: Int, ok: (String) -> Boolean): JSONArray {
        val out = JSONArray()
        if (arr == null) return out
        for (i in 0 until minOf(arr.length(), 20)) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty() && s.length <= maxLen && ok(s)) out.put(s)
        }
        return out
    }

    /** JSON is valid JavaScript except for two line separators, which end a string literal in older engines. */
    private fun jsonLiteral(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    companion object {
        private val SITE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
        private const val MAX_SITES = 200

        /** Top frame only. Works out the site the same way as `siteOf`, then sets CSS zoom. */
        private const val ZOOM_SCRIPT = """
(function () {
  if (window.top !== window) return;
  var Z = __ZOOM__;
  var h = (location.hostname || '').toLowerCase().replace(/\.${'$'}/, '');
  var s = (/^\d{1,3}(\.\d{1,3}){3}${'$'}/.test(h) || h.indexOf(':') >= 0) ? h : h.split('.').slice(-2).join('.');
  var z = Z[s];
  if (!z || z === 100) return;
  function apply() { var e = document.documentElement; if (e) e.style.zoom = String(z / 100); }
  apply();
  document.addEventListener('DOMContentLoaded', apply);
})();
"""

        /**
         * Every frame. Presses the rule's buttons when they are visible. Rules for every site (cookie
         * banners) only look for the first 30 s of a page; site-specific ones (skip intro) keep looking.
         * The same button is pressed at most once every 10 s, and at most 3 buttons per scan.
         */
        private const val HELPER_SCRIPT = """
(function () {
  if (window.__tvhInit) return;
  window.__tvhInit = true;
  var R = __RULES__;
  var h = (location.hostname || '').toLowerCase();
  function on(sites) {
    for (var i = 0; i < sites.length; i++) {
      var s = sites[i];
      if (s === '*' || h === s || h.slice(-(s.length + 1)) === '.' + s) return true;
    }
    return false;
  }
  var mine = R.filter(function (r) { return on(r.sites); });
  if (!mine.length) return;
  var started = Date.now(), budget = 0, pressed = new WeakMap();
  function visible(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function press(e) {
    var now = Date.now();
    if (budget <= 0 || !visible(e) || now - (pressed.get(e) || 0) < 10000) return;
    budget--;
    pressed.set(e, now);
    try { e.click(); } catch (x) {}
  }
  function scan() {
    budget = 3;
    var early = Date.now() - started < 30000;
    mine.forEach(function (r) {
      if (!early && r.sites.indexOf('*') >= 0) return;
      r.click.forEach(function (sel) {
        try { document.querySelectorAll(sel).forEach(press); } catch (x) {}
      });
      if (r.clickText.length) {
        document.querySelectorAll('button,[role=button]').forEach(function (b) {
          if (r.clickText.indexOf((b.innerText || b.textContent || '').trim()) >= 0) press(b);
        });
      }
    });
  }
  document.addEventListener('DOMContentLoaded', scan);
  setInterval(scan, 1000);
})();
"""
    }
}
