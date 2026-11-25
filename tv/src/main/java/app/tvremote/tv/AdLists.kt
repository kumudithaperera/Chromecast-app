package app.tvremote.tv

import android.content.Context
import app.tvremote.protocol.AdFilter
import app.tvremote.protocol.AdLevel
import java.io.File
import java.io.IOException
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL

/**
 * The ad blocking settings and filter lists. The small list bundled with the app is always there; the
 * public lists for the chosen [level] are downloaded by the TV once a week and kept one file each. A
 * download is only used if it looks like a real list; otherwise the last good copy stays in use.
 *
 * The lists are GPL-3.0 or CC BY-SA (EasyList, EasyPrivacy, uBlock Origin's lists, HaGeZi's). The TV
 * downloads them; the app doesn't ship them.
 */
class AdLists(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("adlists", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "adlists")

    init {
        // The single combined file of the previous version.
        File(dir, "domains.txt").delete()
    }

    var level: AdLevel
        get() = prefs.getString("level", null)?.let { name -> AdLevel.entries.firstOrNull { it.name == name } } ?: AdLevel.STANDARD
        set(value) = prefs.edit().putString("level", value.name).apply()

    var hideElements: Boolean
        get() = prefs.getBoolean("hide", true)
        set(value) = prefs.edit().putBoolean("hide", value).apply()

    var scriptlets: Boolean
        get() = prefs.getBoolean("scriptlets", true)
        set(value) = prefs.edit().putBoolean("scriptlets", value).apply()

    /** When the lists were last all downloaded, or 0. */
    val fetchedAt: Long get() = prefs.getLong("fetched", 0L)

    /** The last download didn't get every list. */
    val lastFailed: Boolean get() = prefs.getBoolean("failed", false)

    private fun sourcesFor(level: AdLevel) = SOURCES.filter { level >= it.level }

    private fun fileOf(s: Source) = File(dir, "${s.id}.txt")

    /**
     * True when a list the level needs is missing, or the lists are over a week old; either way not when
     * the last try was very recent.
     */
    fun isStale(level: AdLevel = this.level, now: Long = System.currentTimeMillis()): Boolean {
        if (level < AdLevel.STANDARD) return false
        val tried = prefs.getLong("tried", 0L)
        val missing = sourcesFor(level).any { !fileOf(it).isFile }
        return if (missing) now - tried > MISSING_RETRY_MS else now - fetchedAt > REFRESH_MS && now - tried > RETRY_MS
    }

    /**
     * Builds the filter for [level] from the bundled list and whatever lists are on disk. Slow: off the main
     * thread. The result is kept for the process, so a recreated screen gets it at once instead of building
     * a second copy.
     */
    fun build(level: AdLevel = this.level): AdFilter {
        if (level == AdLevel.OFF) return AdFilter.EMPTY
        val files = sourcesFor(level).map { fileOf(it) to it }.filter { it.first.isFile }
        val key = level.name + files.joinToString("") { "|" + it.first.name + it.first.lastModified() + ":" + it.first.length() }
        synchronized(lock) {
            cached?.let { (k, f) -> if (k == key) return f }
            val started = System.nanoTime()
            val b = AdFilter.Builder()
            runCatching { app.assets.open("blocklist.txt").bufferedReader().useLines { b.addDomains(it) } }
            for ((f, s) in files) {
                try {
                    f.bufferedReader().useLines { if (s.domains) b.addDomains(it) else b.addFilterList(it) }
                } catch (_: IOException) {
                }
            }
            // Drop the old one first: two big filters at once is more than the TV needs to hold.
            cached = null
            return try {
                b.build().also {
                    cached = key to it
                    android.util.Log.i(TAG, "$level: ${it.ruleCount} rules, built in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            } catch (_: OutOfMemoryError) {
                AdFilter.EMPTY
            }
        }
    }

    /**
     * Downloads every list [level] needs. A list that fails keeps its last good copy. Returns true when all
     * of them came. Blocking network calls: off the main thread.
     */
    fun download(level: AdLevel = this.level): Boolean {
        prefs.edit().putLong("tried", System.currentTimeMillis()).apply()
        dir.mkdirs()
        var allOk = true
        for (s in sourcesFor(level)) if (!downloadOne(s)) allOk = false
        prefs.edit().putBoolean("failed", !allOk).apply()
        if (allOk) prefs.edit().putLong("fetched", System.currentTimeMillis()).apply()
        return allOk
    }

    private fun downloadOne(s: Source): Boolean {
        val tmp = File(dir, "${s.id}.tmp")
        try {
            var lines = 0
            // Written as they are read, so the TV never holds two copies of a big list in memory.
            tmp.bufferedWriter().use { w ->
                val text = fetch(s.url) ?: return false
                lines += write(w, text)
                if (!s.domains) {
                    // uBlock Origin's main list is mostly "!#include" lines naming files beside it.
                    var included = 0
                    for (line in text.lineSequence()) {
                        val name = line.trim().takeIf { it.startsWith("!#include ") }?.removePrefix("!#include ")?.trim() ?: continue
                        if (!INCLUDE.matches(name) || ++included > MAX_INCLUDES) continue
                        val sub = fetch(s.url.substringBeforeLast('/') + "/" + name) ?: return false
                        lines += write(w, sub)
                    }
                }
            }
            if (lines < s.minLines || !tmp.renameTo(fileOf(s))) return false
            return true
        } catch (_: IOException) {
            return false
        } finally {
            tmp.delete()
        }
    }

    /** Copies [text] out, leaving its own "!#include" lines behind (their files follow it). Returns the rule lines. */
    private fun write(w: Writer, text: String): Int {
        var n = 0
        for (line in text.lineSequence()) {
            if (line.startsWith("!#include")) continue
            w.write(line)
            w.write('\n'.code)
            if (line.isNotBlank() && !line.startsWith("!") && !line.startsWith("#")) n++
        }
        return n
    }

    private fun fetch(url: String): String? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            out.toString("UTF-8")
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** A public list: plain [domains] or filter syntax, used from [level] up. */
    private class Source(val id: String, val url: String, val level: AdLevel, val domains: Boolean, val minLines: Int)

    companion object {
        private const val TAG = "AdLists"
        private val lock = Any()

        /** The last filter built, with the level and files it came from. */
        @Volatile private var cached: Pair<String, AdFilter>? = null
        private const val UBO = "https://ublockorigin.github.io/uAssets/filters"

        /** HTTPS only, fixed here, never taken from a page or the phone. */
        private val SOURCES = listOf(
            Source("easylist", "https://easylist.to/easylist/easylist.txt", AdLevel.STANDARD, false, 20_000),
            Source("easyprivacy", "https://easylist.to/easylist/easyprivacy.txt", AdLevel.STANDARD, false, 20_000),
            // uBlock Origin's own lists: its ads list, malware sites, privacy, and fixes for sites the
            // other lists break ("unbreak", "quick fixes").
            Source("ubo-filters", "$UBO/filters.txt", AdLevel.STANDARD, false, 5_000),
            Source("ubo-badware", "$UBO/badware.txt", AdLevel.STANDARD, false, 1_000),
            Source("ubo-privacy", "$UBO/privacy.txt", AdLevel.STANDARD, false, 500),
            Source("ubo-quick-fixes", "$UBO/quick-fixes.txt", AdLevel.STANDARD, false, 50),
            Source("ubo-unbreak", "$UBO/unbreak.txt", AdLevel.STANDARD, false, 500),
            // Pop-up and pop-under ad networks, the main nuisance on video sites.
            Source("hagezi-popupads", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt", AdLevel.STANDARD, true, 1_000),
            // Ads, trackers and telemetry by host. It also blocked hosts some video players load from.
            Source("hagezi-light", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt", AdLevel.STRICT, true, 10_000),
        )

        /** A file beside the list: no folders, no other site. */
        private val INCLUDE = Regex("^[a-z0-9][a-z0-9._-]{0,80}\\.txt$")
        private const val MAX_INCLUDES = 20
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        private const val RETRY_MS = 6L * 60 * 60 * 1000
        private const val MISSING_RETRY_MS = 10L * 60 * 1000
    }
}
