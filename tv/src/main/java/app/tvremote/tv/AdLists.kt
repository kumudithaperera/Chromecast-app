package app.tvremote.tv

import android.content.Context
import app.tvremote.protocol.HostBlocklist
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The ad and pop-up block list: the small list bundled with the app, plus maintained public lists
 * downloaded once a week. The download is only ever used if it looks like a real list; otherwise
 * the last good copy (or the bundled list alone) stays in use.
 *
 * Both public lists are HaGeZi's (github.com/hagezi/dns-blocklists, GPL-3.0), fetched by the TV.
 * The app doesn't ship them.
 */
class AdLists(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("adlists", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "adlists")
    private val saved = File(dir, "domains.txt")

    val seed: HostBlocklist = runCatching {
        HostBlocklist.parse(app.assets.open("blocklist.txt").bufferedReader().use { it.readText() })
    }.getOrDefault(HostBlocklist(emptyList()))

    /** Loads the last downloaded list. Slow (a few hundred ms on the TV): call off the main thread. */
    fun loadSaved(): HostBlocklist? = try {
        if (saved.isFile) saved.useLines { HostBlocklist.parseLines(it) }.takeIf { it.size >= MIN_DOMAINS } else null
    } catch (_: IOException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** True when the saved list is over a week old and the last try wasn't in the past few hours. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        val fetched = prefs.getLong("fetched", 0L)
        val tried = prefs.getLong("tried", 0L)
        return now - fetched > REFRESH_MS && now - tried > RETRY_MS
    }

    /**
     * Whether the downloaded lists are used. Off by default: they also blocked hosts some sites' video
     * players load from, so only the small bundled list blocks unless the person turns them on.
     */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** When the list was last downloaded, or 0. */
    val fetchedAt: Long get() = prefs.getLong("fetched", 0L)

    /**
     * Downloads every source, keeps only plain domains, and saves them if there are enough.
     * Blocking network call: run off the main thread. Returns the new list, or null if it failed.
     */
    fun download(): HostBlocklist? {
        prefs.edit().putLong("tried", System.currentTimeMillis()).apply()
        val tmp = File(dir, "domains.tmp")
        try {
            dir.mkdirs()
            var count = 0
            // Written as they are read, so the TV never holds two copies of a big list in memory.
            tmp.bufferedWriter().use { w ->
                for (source in SOURCES) {
                    val text = fetch(source) ?: return null
                    text.lineSequence().mapNotNull(HostBlocklist::domainOf).forEach {
                        w.write(it)
                        w.write('\n'.code)
                        count++
                    }
                }
            }
            if (count < MIN_DOMAINS || !tmp.renameTo(saved)) return null
        } catch (_: IOException) {
            return null
        } finally {
            tmp.delete()
        }
        prefs.edit().putLong("fetched", System.currentTimeMillis()).apply()
        return loadSaved()
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

    companion object {
        /** HTTPS only, fixed here, never taken from a page or the phone. */
        private val SOURCES = listOf(
            // Ads, trackers and telemetry, made not to break normal sites.
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt",
            // Pop-up and pop-under ad networks, the main nuisance on video sites.
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt",
        )
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MIN_DOMAINS = 10_000
        private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        private const val RETRY_MS = 6L * 60 * 60 * 1000
    }
}
