package app.tvremote.tv

import android.content.Context
import app.tvremote.protocol.PairedPhones

/**
 * Keys of the phones paired with this TV browser, in app-private storage
 * (backups are off). Keeps the most recent [MAX_PHONES]; an older phone that
 * falls off the list is asked to pair again.
 */
class PhoneStore(context: Context) : PairedPhones {
    private val sp = context.getSharedPreferences("tv_browser", Context.MODE_PRIVATE)

    init {
        // The PIN from before pairing codes existed is no longer used.
        if (sp.contains("pin")) sp.edit().remove("pin").apply()
    }

    @Synchronized
    override fun secret(id: String): String? = sp.getString(KEY + id, null)?.substringAfter(':')

    @Synchronized
    override fun add(id: String, secret: String) {
        val e = sp.edit().putString(KEY + id, "${System.currentTimeMillis()}:$secret")
        val all = sp.all.filterKeys { it.startsWith(KEY) }
            .mapValues { (it.value as? String)?.substringBefore(':')?.toLongOrNull() ?: 0L }
        if (all.size >= MAX_PHONES) {
            all.entries.sortedBy { it.value }.take(all.size - MAX_PHONES + 1).forEach { e.remove(it.key) }
        }
        e.apply()
    }

    @Synchronized
    fun count(): Int = sp.all.keys.count { it.startsWith(KEY) }

    private companion object {
        const val KEY = "phone_"
        const val MAX_PHONES = 8
    }
}
