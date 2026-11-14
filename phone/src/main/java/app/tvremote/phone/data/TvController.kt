package app.tvremote.phone.data

import android.content.Context
import android.os.Build
import app.tvremote.phone.atv.ClientIdentity
import app.tvremote.phone.atv.PairingException
import app.tvremote.phone.atv.PairingSession
import app.tvremote.phone.atv.RemoteException
import app.tvremote.phone.atv.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class PairingStage { Starting, EnterCode, Verifying }

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Connected : Connection
    data class Pairing(val stage: PairingStage, val error: String? = null) : Connection

    /** [needsRepair] means retrying won't help; the user has to pair again. */
    data class Failed(val message: String, val needsRepair: Boolean = false) : Connection
}

data class Volume(val level: Int, val max: Int, val muted: Boolean)

data class UiState(
    val tv: Tv? = null,
    val connection: Connection = Connection.Idle,
    val model: String? = null,
    val currentApp: String? = null,
    val volume: Volume? = null,
    val poweredOn: Boolean? = null,
    /** False when the TV reported that volume keys won't do anything; null until it says. */
    val volumeControl: Boolean? = null,
)

/**
 * Owns the connection to the TV for the whole app (main screen and share
 * sheet). Connects while the app is in use and drops the link 30 seconds
 * after it goes to the background to save battery.
 */
class TvController(context: Context) {
    private val prefs = Prefs(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity: Deferred<ClientIdentity> = scope.async {
        ClientIdentity.loadOrCreate(File(context.filesDir, "client.p12"))
    }

    private val _state = MutableStateFlow(UiState(tv = prefs.tv))
    val state: StateFlow<UiState> = _state

    @Volatile private var session: RemoteSession? = null
    private var connectJob: Job? = null
    private var pairing: PairingSession? = null
    private var pairingJob: Job? = null
    private var backgroundJob: Job? = null
    @Volatile private var inForeground = false

    val shortcuts = MutableStateFlow(prefs.shortcuts)

    /** Text sent from the Type tab with Search, newest first. Kept on this phone only. */
    val recentTexts = MutableStateFlow(prefs.recentTexts)

    fun rememberText(value: String) {
        val next = pushRecent(recentTexts.value, value, MAX_RECENT_TEXTS)
        if (next == recentTexts.value) return
        recentTexts.value = next
        prefs.recentTexts = next
    }

    fun clearRecentTexts() {
        recentTexts.value = emptyList()
        prefs.recentTexts = emptyList()
    }
    var useTouchpad: Boolean
        get() = prefs.useTouchpad
        set(value) {
            prefs.useTouchpad = value
        }

    // ---- lifecycle ----

    fun onForeground() {
        inForeground = true
        backgroundJob?.cancel()
        val c = _state.value.connection
        if (_state.value.tv != null && (c is Connection.Idle || (c is Connection.Failed && !c.needsRepair))) {
            connect()
        }
    }

    fun onBackground() {
        inForeground = false
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(30_000)
            if (!inForeground && _state.value.connection !is Connection.Pairing) disconnect()
        }
    }

    // ---- choosing a TV ----

    fun choose(tv: Tv) {
        disconnect()
        cancelPairing()
        prefs.tv = tv
        _state.value = UiState(tv = tv)
        connect()
    }

    fun forgetTv() {
        val tv = _state.value.tv
        disconnect()
        cancelPairing()
        if (tv != null) {
            prefs.clearPin(tv.host)
            prefs.setConnected(tv.host, false)
        }
        prefs.tv = null
        _state.value = UiState()
    }

    // ---- connection ----

    fun connect() {
        val tv = _state.value.tv ?: return
        connectJob?.cancel()
        session?.close()
        connectJob = scope.launch {
            var failures = 0
            var retriedAuth = false
            while (isActive) {
                setConnection(Connection.Connecting)
                val s = RemoteSession(tv.host, identity.await(), prefs.pin(tv.host), listenerFor(tv))
                session = s
                try {
                    s.run()
                    // run() returns normally only when we closed it ourselves.
                    return@launch
                } catch (e: IOException) {
                    // A superseded attempt (TV switched, disconnected) must not touch state.
                    if (!isActive) return@launch
                    when (e) {
                        is RemoteException.NotPaired -> {
                            // A phone that connected before is rarely unpaired; one retry
                            // avoids asking for a new code after a network hiccup.
                            if (prefs.hasConnected(tv.host) && !retriedAuth) {
                                retriedAuth = true
                                delay(1_000)
                                continue
                            }
                            prefs.setConnected(tv.host, false)
                            startPairing()
                            return@launch
                        }
                        is RemoteException.CertificateChanged -> {
                            setConnection(Connection.Failed(e.message!!, needsRepair = true))
                            return@launch
                        }
                        else -> {
                            if (s.isReady) failures = 0
                            failures++
                            setConnection(Connection.Failed(friendly(e, tv)))
                            if (!inForeground) return@launch
                            delay(minOf(10_000L, 1_000L shl minOf(failures, 4)))
                        }
                    }
                } finally {
                    if (session === s) session = null
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        session?.close()
        session = null
        _state.update {
            if (it.connection is Connection.Pairing) it else it.copy(connection = Connection.Idle)
        }
    }

    private fun listenerFor(tv: Tv) = object : RemoteSession.Listener {
        override fun onConnected(fingerprint: String) {
            if (prefs.pin(tv.host) == null) prefs.setPin(tv.host, fingerprint)
        }

        override fun onConfigured(model: String?, vendor: String?) {
            _state.update { it.copy(model = listOfNotNull(vendor, model).joinToString(" ").ifBlank { null }) }
        }

        override fun onReady() {
            prefs.setConnected(tv.host, true)
            setConnection(Connection.Connected)
        }

        override fun onPowerChanged(on: Boolean) = _state.update { it.copy(poweredOn = on) }

        override fun onVolumeControl(available: Boolean) = _state.update { it.copy(volumeControl = available) }

        override fun onCurrentApp(packageName: String) = _state.update { it.copy(currentApp = packageName) }

        // A Chromecast that can't control the TV's volume sends no level or maximum (both read as 0).
        // Showing "Vol 0" for that would be a made-up reading, so only report a real one.
        override fun onVolume(level: Int, max: Int, muted: Boolean) =
            _state.update { it.copy(volume = if (max > 0) Volume(level, max, muted) else null) }
    }

    // ---- pairing ----

    fun startPairing() {
        val tv = _state.value.tv ?: return
        connectJob?.cancel()
        session?.close()
        pairing?.close()
        pairingJob?.cancel()
        pairingJob = scope.launch {
            setConnection(Connection.Pairing(PairingStage.Starting))
            val p = PairingSession(tv.host, identity.await(), clientName = phoneName())
            pairing = p
            try {
                p.start()
                setConnection(Connection.Pairing(PairingStage.EnterCode))
            } catch (e: IOException) {
                p.close()
                pairing = null
                setConnection(Connection.Failed("Couldn't start pairing. ${friendly(e, tv)}", needsRepair = true))
            }
        }
    }

    fun submitCode(code: String) {
        val tv = _state.value.tv ?: return
        val p = pairing ?: return
        pairingJob = scope.launch {
            setConnection(Connection.Pairing(PairingStage.Verifying))
            try {
                p.finish(code)
                p.close()
                pairing = null
                connect()
            } catch (e: PairingException.WrongCode) {
                if (e.canRetry) {
                    setConnection(Connection.Pairing(PairingStage.EnterCode, e.message))
                } else {
                    p.close()
                    pairing = null
                    setConnection(Connection.Failed(e.message!!, needsRepair = true))
                }
            } catch (e: IOException) {
                p.close()
                pairing = null
                setConnection(Connection.Failed("Pairing failed. ${friendly(e, tv)}", needsRepair = true))
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairing?.close()
        pairing = null
        _state.update {
            if (it.connection is Connection.Pairing) it.copy(connection = Connection.Idle) else it
        }
    }

    /** "Pair again" after a certificate change or a failed pairing. */
    fun repair() {
        _state.value.tv?.let { prefs.clearPin(it.host) }
        startPairing()
    }

    // ---- commands ----

    private fun ready(): RemoteSession? = session?.takeIf { it.isReady }

    fun key(code: Int) {
        ready()?.sendKey(code)
    }

    fun text(value: String) {
        ready()?.sendText(value)
    }

    fun launch(link: String) {
        ready()?.launchLink(link)
    }

    val isConnected: Boolean get() = ready() != null

    /** How a command sent from outside the app (share sheet, widget) went. */
    enum class Outcome { Sent, NoTv, NeedsPairing, Unreachable }

    /** For the share sheet: connect if needed, then open [link] on the TV. */
    suspend fun launchWhenReady(link: String): Boolean = whenReady { launch(link) } == Outcome.Sent

    /**
     * Connects if needed, runs [send] once the remote is ready, then lets the link drop 30 s later
     * unless the app is open. Gives up after 8 s, inside the time a broadcast receiver may run.
     * Never leaves a pairing code on the TV that nobody is there to type.
     */
    suspend fun whenReady(send: TvController.() -> Unit): Outcome {
        if (_state.value.tv == null) return Outcome.NoTv
        val c = _state.value.connection
        if (c is Connection.Failed && c.needsRepair) return Outcome.NeedsPairing
        if (!isConnected && connectJob?.isActive != true) connect()
        val reached = withTimeoutOrNull(8_000) {
            _state.first { it.connection is Connection.Connected || it.connection is Connection.Pairing }
        }?.connection
        val outcome = when (reached) {
            is Connection.Connected -> {
                send()
                Outcome.Sent
            }
            is Connection.Pairing -> {
                if (!inForeground) cancelPairing()
                Outcome.NeedsPairing
            }
            else -> Outcome.Unreachable
        }
        if (!inForeground) onBackground()
        return outcome
    }

    // ---- shortcuts ----

    fun addShortcut(shortcut: Shortcut) {
        shortcuts.value = shortcuts.value + shortcut
        prefs.shortcuts = shortcuts.value
    }

    fun removeShortcut(shortcut: Shortcut) {
        shortcuts.value = shortcuts.value - shortcut
        prefs.shortcuts = shortcuts.value
    }

    // ---- helpers ----

    private fun setConnection(c: Connection) = _state.update { it.copy(connection = c) }

    private fun phoneName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Phone remote" }

    private fun friendly(e: Throwable, tv: Tv): String = when (e) {
        is RemoteException.Timeout -> e.message!!
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException ->
            "Nothing answered at ${tv.host}. Is the TV on, and on the same Wi-Fi as this phone?"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val MAX_RECENT_TEXTS = 8
    }
}
