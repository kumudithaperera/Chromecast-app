package app.tvremote.tv

import android.content.Context
import app.tvremote.protocol.Auth

/** The PIN the phone must prove it knows. Made once, kept in app-private storage (backups are off). */
class PinStore(context: Context) {
    private val sp = context.getSharedPreferences("tv_browser", Context.MODE_PRIVATE)

    val pin: String
        @Synchronized get() {
            sp.getString("pin", null)?.let { return it }
            val fresh = Auth.newPin()
            sp.edit().putString("pin", fresh).apply()
            return fresh
        }

    @Synchronized
    fun regenerate(): String {
        val fresh = Auth.newPin()
        sp.edit().putString("pin", fresh).apply()
        return fresh
    }
}
