package app.tvremote.phone.data

import app.tvremote.protocol.BrowserState
import app.tvremote.protocol.ClientFailure
import app.tvremote.protocol.Command
import app.tvremote.protocol.ControlClient
import app.tvremote.protocol.ControlListener
import app.tvremote.protocol.Credential
import app.tvremote.protocol.VideoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    /** Waiting for the code shown on the TV. [identityChanged]: the TV's certificate changed since it was paired. */
    data class NeedPin(val wrong: Boolean, val identityChanged: Boolean = false) : LinkState
    data class Locked(val seconds: Int) : LinkState
    /** [dropped] means a working link was lost, which is worth retrying without being asked. */
    data class Failed(val message: String, val dropped: Boolean = false) : LinkState
    data class Ready(val tvName: String) : LinkState
}

/** The phone's connection to the browser app running on the TV. */
class BrowserLink(private val prefs: Prefs) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state

    private val _browser = MutableStateFlow(BrowserState())
    val browser: StateFlow<BrowserState> = _browser

    private val _video = MutableStateFlow<VideoState?>(null)

    /** The video playing in the TV browser, or null when there is none. */
    val video: StateFlow<VideoState?> = _video

    private var client: ControlClient? = null
    private var host: String? = null

    /** The TV whose certificate changed since it was paired; shown as a warning while pairing again. */
    private var repairingHost: String? = null

    /**
     * Connects with the key from an earlier pairing, or starts pairing: the TV
     * then shows a code and [state] becomes [LinkState.NeedPin].
     */
    fun connect(host: String) {
        open(host, prefs.browserCredential(host), wrong = false)
    }

    /** The code the person typed from the TV screen. */
    fun submitPin(host: String, code: String) {
        val c = client
        if (c != null && this.host == host && _state.value is LinkState.NeedPin) {
            _state.value = LinkState.Connecting
            c.submitCode(code)
        } else {
            connect(host)
        }
    }

    private fun open(host: String, credential: Credential?, wrong: Boolean) {
        client?.close()
        this.host = host
        _state.value = LinkState.Connecting
        var pairing = false
        lateinit var self: ControlClient
        self = ControlClient(
            host, credential,
            object : ControlListener {
                override fun onCodeRequested() {
                    if (client !== self) return
                    pairing = true
                    _state.value = LinkState.NeedPin(wrong = wrong, identityChanged = repairingHost == host)
                }

                override fun onPaired(credential: Credential) {
                    if (client !== self) return
                    prefs.setBrowserCredential(host, credential)
                    if (repairingHost == host) repairingHost = null
                }

                override fun onReady(deviceName: String) {
                    if (client === self) _state.value = LinkState.Ready(deviceName)
                }

                override fun onState(state: BrowserState) {
                    if (client === self) _browser.value = state
                }

                override fun onVideo(video: VideoState?) {
                    if (client === self) _video.value = video
                }

                override fun onFailure(failure: ClientFailure) {
                    if (client !== self) return
                    when (failure) {
                        // Each attempt has its own code; start a new one so the TV shows the next code.
                        ClientFailure.WrongPin -> open(host, null, wrong = true)
                        // The TV no longer knows this phone (its list was full, or it was reset): pair again.
                        ClientFailure.NotPaired -> {
                            prefs.clearBrowserCredential(host)
                            open(host, null, wrong = false)
                        }
                        // Pairing again is safe: the code check catches a device in the middle.
                        ClientFailure.IdentityChanged -> {
                            prefs.clearBrowserCredential(host)
                            repairingHost = host
                            open(host, null, wrong = false)
                        }
                        else -> _state.value = failureState(failure, pairing)
                    }
                }
            },
        )
        client = self
        self.start()
    }

    private fun failureState(failure: ClientFailure, pairing: Boolean): LinkState = when (failure) {
        ClientFailure.Busy ->
            LinkState.Failed("The TV is pairing with another phone. Try again in a minute.")
        ClientFailure.SecureFailed ->
            LinkState.Failed("Couldn't make a secure connection to the TV browser. Update the browser app on the TV to the latest version.")
        ClientFailure.Untrusted ->
            LinkState.Failed(
                "Pairing stopped: the device at this address couldn't prove it's the TV showing the code. " +
                    "If you typed the code correctly, something on this network may be intercepting the connection.",
            )
        is ClientFailure.Locked -> LinkState.Locked(failure.seconds)
        is ClientFailure.Unreachable -> LinkState.Failed(friendly(failure.message))
        // A pairing that timed out isn't retried unasked: each try counts towards the TV's lockout.
        ClientFailure.Dropped ->
            if (pairing) LinkState.Failed("Pairing timed out. Try again and type the code shown on the TV.")
            else LinkState.Failed("Lost the connection to the TV browser.", dropped = true)
        ClientFailure.WrongPin, ClientFailure.NotPaired, ClientFailure.IdentityChanged -> LinkState.Idle
    }

    private fun friendly(raw: String): String =
        if (raw.contains("ECONNREFUSED", true) || raw.contains("refused", true)) {
            "The browser isn't running on the TV."
        } else {
            "Can't reach the TV browser. Check it's on the same Wi-Fi."
        }

    fun send(c: Command) {
        client?.send(c)
    }

    fun close() {
        client?.close()
        client = null
        _browser.value = BrowserState()
        _video.value = null
        if (_state.value is LinkState.Ready || _state.value is LinkState.Connecting) _state.value = LinkState.Idle
    }
}
