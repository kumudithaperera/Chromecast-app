package app.tvremote.phone.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

/**
 * Finds Android TV / Google TV devices on the Wi-Fi network. They advertise
 * the remote service over mDNS as _androidtvremote2._tcp.
 */
class TvDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _found = MutableStateFlow<List<Tv>>(emptyList())
    val found: StateFlow<List<Tv>> = _found

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private var listener: NsdManager.DiscoveryListener? = null

    /** NSD callbacks arrive on a binder thread; all state is touched on the main thread. */
    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        _found.value = emptyList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = ui { _searching.value = true }
            override fun onDiscoveryStopped(serviceType: String) = ui { _searching.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = ui {
                _searching.value = false
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = ui { enqueue(info) }
            override fun onServiceLost(info: NsdServiceInfo) = ui {
                _found.value = _found.value.filterNot { it.name == info.serviceName }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(l) }
        pending.clear()
        _searching.value = false
    }

    // Older Android versions allow only one resolve at a time, so queue them.
    private fun enqueue(info: NsdServiceInfo) {
        pending.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = ui {
                resolving = false
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) = ui {
                resolving = false
                hostOf(info)?.let { host ->
                    val tv = Tv(host = host, name = info.serviceName ?: host)
                    _found.value = (_found.value.filterNot { it.host == host || it.name == tv.name } + tv)
                        .sortedBy { it.name.lowercase() }
                }
                resolveNext()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
        }
        return info.host?.hostAddress
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
