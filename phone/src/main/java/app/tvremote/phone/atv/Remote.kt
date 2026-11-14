package app.tvremote.phone.atv

import app.tvremote.phone.proto.Framing
import app.tvremote.phone.proto.ProtoMessage
import app.tvremote.phone.proto.ProtoWriter
import java.io.Closeable
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Android key codes the remote protocol accepts (same numbers as android.view.KeyEvent). */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MUTE = 164 // KEYCODE_VOLUME_MUTE: mutes the speaker, not the mic
    const val INFO = 165
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val SLEEP = 223
    const val WAKEUP = 224
    const val ALL_APPS = 284
}

object Direction {
    const val START_LONG = 1
    const val END_LONG = 2
    const val SHORT = 3
}

/** Feature bits exchanged in remote_configure / remote_set_active. */
object Features {
    const val PING = 1 shl 0
    const val KEY = 1 shl 1
    const val IME = 1 shl 2
    const val VOICE = 1 shl 3
    const val POWER = 1 shl 5
    const val VOLUME = 1 shl 6
    const val APP_LINK = 1 shl 9

    const val WANTED = PING or KEY or IME or POWER or VOLUME or APP_LINK
}

/**
 * Whether volume keys do anything, from the TV's set-volume-level message (field 50).
 *
 * A TV with its own volume sends a maximum (field 6) and a level (field 7). A Chromecast with Google TV
 * sends neither, because it adjusts the TV over HDMI-CEC and never learns the level. For it, field 4 was
 * 0 while HDMI-CEC volume control was off (volume keys silently ignored) and 1 once it was switched on
 * (observed on a Chromecast with Google TV, Android 14, 2026-11-10).
 */
internal fun volumeControlAvailable(volumeMessage: ProtoMessage): Boolean =
    volumeMessage.int(6) > 0 || volumeMessage.bool(4)

/** Builders for RemoteMessage (remotemessage.proto). */
object RemoteMessages {
    const val PORT = 6466

    const val F_CONFIGURE = 1
    const val F_SET_ACTIVE = 2
    const val F_ERROR = 3
    const val F_PING_REQUEST = 8
    const val F_PING_RESPONSE = 9
    const val F_KEY_INJECT = 10
    const val F_IME_KEY_INJECT = 20
    const val F_IME_BATCH_EDIT = 21
    const val F_START = 40
    const val F_SET_VOLUME_LEVEL = 50
    const val F_APP_LINK_LAUNCH = 90

    /** Same device_info values androidtvremote2 sends, which TVs accept. */
    fun configure(features: Int) = ProtoWriter().message(F_CONFIGURE) {
        int(1, features)
        message(2) { // device_info
            int(3, 1)
            string(4, "1")
            string(5, "atvremote")
            string(6, "1.0.0")
        }
    }.toByteArray()

    fun setActive(features: Int) = ProtoWriter().message(F_SET_ACTIVE) { int(1, features) }.toByteArray()

    fun pingResponse(value: Int) = ProtoWriter().message(F_PING_RESPONSE) { int(1, value) }.toByteArray()

    fun key(code: Int, direction: Int = Direction.SHORT) = ProtoWriter().message(F_KEY_INJECT) {
        int(1, code)
        int(2, direction)
    }.toByteArray()

    /** Replaces the focused TV text field's content with [text]. */
    fun text(text: String, imeCounter: Int, fieldCounter: Int) = ProtoWriter().message(F_IME_BATCH_EDIT) {
        val cursor = text.length - 1
        int(1, imeCounter)
        int(2, fieldCounter)
        message(3) { // edit_info
            int(1, 1) // insert
            message(2) { // text_field_status
                int(1, cursor)
                int(2, cursor)
                string(3, text)
            }
        }
    }.toByteArray()

    fun appLink(link: String) = ProtoWriter().message(F_APP_LINK_LAUNCH) { string(1, link) }.toByteArray()
}

sealed class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** The TV refused our certificate. It needs pairing (again). */
    class NotPaired(cause: Throwable?) : RemoteException("This phone isn't paired with the TV.", cause)

    class CertificateChanged : RemoteException(
        "The TV's security certificate changed. Forget the TV and pair again.",
    )

    class Timeout : RemoteException("The TV stopped responding.")
}

/**
 * Control connection on TCP 6466. [run] blocks for the life of the
 * connection; the send methods can be called from any thread.
 */
class RemoteSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val pinnedFingerprint: String?,
    private val listener: Listener,
    private val port: Int = RemoteMessages.PORT,
) : Closeable {

    interface Listener {
        /** Called once the TLS handshake succeeds, with the TV certificate's fingerprint. */
        fun onConnected(fingerprint: String) {}
        fun onConfigured(model: String?, vendor: String?) {}
        fun onReady() {}
        fun onPowerChanged(on: Boolean) {}
        fun onCurrentApp(packageName: String) {}
        fun onVolume(level: Int, max: Int, muted: Boolean) {}

        /** False when the TV says it can't pass volume keys on (see [volumeControlAvailable]). */
        fun onVolumeControl(available: Boolean) {}
        fun onTvError(description: String) {}
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    @Volatile var isReady = false
        private set
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    private var activeFeatures = Features.WANTED

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tv-remote-writer").apply { isDaemon = true }
    }

    fun run() {
        val trust = PinningTrustManager(pinnedFingerprint)
        val s = try {
            TlsSockets.connect(host, port, identity, trust)
        } catch (e: TlsHandshakeException) {
            if (trust.mismatch) throw RemoteException.CertificateChanged()
            throw RemoteException.NotPaired(e)
        }
        if (closed) {
            runCatching { s.close() }
            return
        }
        socket = s
        trust.serverCertificate?.let { listener.onConnected(it.sha256Fingerprint()) }
        // The TV pings every 5 seconds, so 20 seconds of silence means it's gone.
        s.soTimeout = 20_000

        var configured = false
        try {
            while (!closed) {
                val raw = Framing.read(s.inputStream)
                    ?: if (closed) return
                    else if (!configured) throw RemoteException.NotPaired(null)
                    else throw IOException("The TV closed the connection.")
                val msg = ProtoMessage.parse(raw)
                if (msg.has(RemoteMessages.F_CONFIGURE)) configured = true
                handle(msg)
            }
        } catch (e: SocketTimeoutException) {
            if (!closed) throw RemoteException.Timeout()
        } catch (e: SSLException) {
            if (closed) return
            // A TLS 1.3 server rejects a client certificate after the handshake,
            // so an unknown phone fails here rather than in connect().
            if (!configured) throw RemoteException.NotPaired(e)
            throw e
        } catch (e: SocketException) {
            if (closed) return
            if (!configured) throw RemoteException.NotPaired(e)
            throw e
        } finally {
            close()
        }
    }

    private fun handle(msg: ProtoMessage) {
        msg.message(RemoteMessages.F_CONFIGURE)?.let { cfg ->
            val supported = cfg.int(1)
            activeFeatures = Features.WANTED and supported
            val info = cfg.message(2)
            listener.onConfigured(info?.string(1), info?.string(2))
            send(RemoteMessages.configure(activeFeatures))
        }
        if (msg.has(RemoteMessages.F_SET_ACTIVE)) {
            send(RemoteMessages.setActive(activeFeatures))
        }
        msg.message(RemoteMessages.F_PING_REQUEST)?.let { ping ->
            send(RemoteMessages.pingResponse(ping.int(1)))
        }
        msg.message(RemoteMessages.F_IME_KEY_INJECT)?.let { ime ->
            ime.message(1)?.string(12)?.takeIf { it.isNotEmpty() }?.let(listener::onCurrentApp)
        }
        msg.message(RemoteMessages.F_IME_BATCH_EDIT)?.let { edit ->
            imeCounter = edit.int(1)
            fieldCounter = edit.int(2)
        }
        msg.message(RemoteMessages.F_START)?.let { start ->
            if (!isReady) {
                isReady = true
                listener.onReady()
            }
            listener.onPowerChanged(start.bool(1))
        }
        msg.message(RemoteMessages.F_SET_VOLUME_LEVEL)?.let { v ->
            listener.onVolume(level = v.int(7), max = v.int(6), muted = v.bool(8))
            listener.onVolumeControl(volumeControlAvailable(v))
        }
        msg.message(RemoteMessages.F_ERROR)?.let { err ->
            listener.onTvError("TV reported an error (fields ${err.fieldNumbers})")
        }
    }

    fun sendKey(code: Int, direction: Int = Direction.SHORT) = send(RemoteMessages.key(code, direction))

    fun sendText(text: String) {
        if (text.isEmpty()) return
        send(RemoteMessages.text(text, imeCounter, fieldCounter))
    }

    fun launchLink(link: String) = send(RemoteMessages.appLink(link))

    private fun send(bytes: ByteArray) {
        if (closed) return
        try {
            writer.execute {
                val s = socket ?: return@execute
                try {
                    Framing.write(s.outputStream, bytes)
                } catch (e: IOException) {
                    // Closing the socket ends run(), which reports the failure.
                    runCatching { s.close() }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Session already closed.
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        writer.shutdown()
    }
}
