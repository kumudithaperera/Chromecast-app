package app.tvremote.phone.data

import android.content.Context
import app.tvremote.protocol.Credential
import org.json.JSONArray
import org.json.JSONObject

data class Tv(val host: String, val name: String)

/** A home-screen style button that opens an app or link on the TV. */
data class Shortcut(val name: String, val link: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)

    var tv: Tv?
        get() {
            val host = sp.getString("tv_host", null) ?: return null
            return Tv(host, sp.getString("tv_name", null) ?: host)
        }
        set(value) {
            sp.edit().apply {
                if (value == null) {
                    remove("tv_host")
                    remove("tv_name")
                } else {
                    putString("tv_host", value.host)
                    putString("tv_name", value.name)
                }
            }.apply()
        }

    /** SHA-256 of the TV's certificate, remembered on first connection. */
    fun pin(host: String): String? = sp.getString("pin_$host", null)

    fun setPin(host: String, fingerprint: String) = sp.edit().putString("pin_$host", fingerprint).apply()

    fun clearPin(host: String) = sp.edit().remove("pin_$host").apply()

    /** True once the remote has fully connected to this TV at least once. */
    fun hasConnected(host: String): Boolean = sp.getBoolean("ok_$host", false)

    fun setConnected(host: String, value: Boolean) = sp.edit().putBoolean("ok_$host", value).apply()

    /** Key and certificate from pairing with the TV browser, per TV. */
    fun browserCredential(host: String): Credential? = Credential.decode(sp.getString("bcred_$host", null))

    fun setBrowserCredential(host: String, credential: Credential) =
        sp.edit().putString("bcred_$host", credential.encode()).remove("bpin_$host").remove("bfp_$host").apply()

    fun clearBrowserCredential(host: String) = sp.edit().remove("bcred_$host").apply()

    /**
     * What the Video tab's volume keys change: true for the video's own volume, false for the TV's
     * (over HDMI-CEC), null until the person picks (then the Chromecast's own report decides).
     */
    var videoVolume: Boolean?
        get() = if (sp.contains("video_volume")) sp.getBoolean("video_volume", false) else null
        set(value) = sp.edit().apply { if (value == null) remove("video_volume") else putBoolean("video_volume", value) }.apply()

    /** Trackpad speed multiplier, 0.5 to 2.0. */
    var trackpadSpeed: Float
        get() = sp.getFloat("trackpad_speed", 1f)
        set(value) = sp.edit().putFloat("trackpad_speed", value.coerceIn(0.5f, 2f)).apply()

    var useTouchpad: Boolean
        get() = sp.getBoolean("touchpad", false)
        set(value) = sp.edit().putBoolean("touchpad", value).apply()

    var shortcuts: List<Shortcut>
        get() {
            val raw = sp.getString("shortcuts", null) ?: return DEFAULT_SHORTCUTS
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Shortcut(o.getString("name"), o.getString("link"))
                }
            }.getOrDefault(DEFAULT_SHORTCUTS)
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(JSONObject().put("name", it.name).put("link", it.link)) }
            sp.edit().putString("shortcuts", arr.toString()).apply()
        }

    /** Recent Type-tab searches, newest first. */
    var recentTexts: List<String>
        get() {
            val raw = sp.getString("recent_texts", null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.getString(it) }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it) }
            sp.edit().putString("recent_texts", arr.toString()).apply()
        }

    companion object {
        // Deep links from the Home Assistant Android TV Remote docs, which are
        // tested against real devices. Package-name launching is less reliable.
        val DEFAULT_SHORTCUTS = listOf(
            Shortcut("YouTube", "https://www.youtube.com"),
            Shortcut("Netflix", "netflix://"),
            Shortcut("Prime Video", "https://app.primevideo.com"),
            Shortcut("Disney+", "https://www.disneyplus.com"),
            Shortcut("Plex", "plex://"),
            Shortcut("Twitch", "twitch://home"),
        )
    }
}
