package app.tvremote.phone.data

import app.tvremote.protocol.BrowserState
import app.tvremote.protocol.ClientFailure
import app.tvremote.protocol.Command
import app.tvremote.protocol.ControlClient
import app.tvremote.protocol.ControlListener
import app.tvremote.protocol.VideoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class NeedPin(val wrong: Boolean) : LinkState
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

    /** Connects with the PIN remembered for [host], or asks for one. */
    fun connect(host: String) {
        val pin = prefs.browserPin(host)
        if (pin == null) {
            close()
            _state.value = LinkState.NeedPin(wrong = false)
            this.host = host
            return
        }
        open(host, pin)
    }

    fun submitPin(host: String, pin: String) {
        prefs.setBrowserPin(host, pin)
        open(host, pin)
    }

    private fun open(host: String, pin: String) {
        client?.close()
        this.host = host
        _state.value = LinkState.Connecting
        lateinit var self: ControlClient
        self = ControlClient(
            host, pin,
            object : ControlListener {
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
                    _state.value = when (failure) {
                        ClientFailure.WrongPin -> {
                            prefs.clearBrowserPin(host)
                            LinkState.NeedPin(wrong = true)
                        }
                        is ClientFailure.Locked -> LinkState.Locked(failure.seconds)
                        is ClientFailure.Unreachable -> LinkState.Failed(friendly(failure.message))
                        ClientFailure.Dropped -> LinkState.Failed("Lost the connection to the TV browser.", dropped = true)
                    }
                }
            },
        )
        client = self
        self.start()
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
