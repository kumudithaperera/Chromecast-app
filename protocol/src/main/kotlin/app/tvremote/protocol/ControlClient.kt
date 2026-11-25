package app.tvremote.protocol

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

sealed interface ClientFailure {
    /** The code typed on the phone isn't the one the TV showed. */
    data object WrongPin : ClientFailure

    /** The TV doesn't know this phone's key any more (it was forgotten); pair again. */
    data object NotPaired : ClientFailure

    /** Another phone is pairing with the TV right now. */
    data object Busy : ClientFailure

    /** The TV presented a different certificate from the one remembered. */
    data object IdentityChanged : ClientFailure

    /** The TLS handshake failed for another reason, for example a TV app that is too old for it. */
    data object SecureFailed : ClientFailure

    /** The other end couldn't prove it is the TV: a wrong key proof, or pairing answers that don't match the code. */
    data object Untrusted : ClientFailure
    data class Locked(val seconds: Int) : ClientFailure
    data class Unreachable(val message: String) : ClientFailure
    data object Dropped : ClientFailure
}

interface ControlListener {
    /** Pairing: the TV now shows a code. Ask the person for it and pass it to [ControlClient.submitCode]. */
    fun onCodeRequested() {}

    /** Pairing finished. Store [credential] and pass it to the next [ControlClient]. */
    fun onPaired(credential: Credential) {}

    fun onReady(deviceName: String)
    fun onState(state: BrowserState)

    /** The video in the TV browser changed, or went away (null). */
    fun onVideo(video: VideoState?) {}

    /** The TV's ad blocking settings or counts changed. Never called by a TV app too old to send them. */
    fun onAdBlock(state: AdBlockState) {}
    fun onFailure(failure: ClientFailure)
}

/**
 * The phone side. Connects over TLS. With a [credential] it accepts only the
 * certificate it paired with and proves it holds the key; without one it
 * pairs, which asks for the code shown on the TV ([ControlListener.onCodeRequested]).
 * [start] connects on a background thread; results come through
 * [ControlListener] on that thread. Cursor movement is batched to about 60
 * sends a second so a fast swipe doesn't flood the TV.
 */
class ControlClient(
    private val host: String,
    private val credential: Credential?,
    private val listener: ControlListener,
    private val port: Int = PORT,
    private val connectTimeoutMs: Int = 4_000,
    private val codeTimeoutMs: Long = 85_000,
) : Closeable {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: BufferedOutputStream? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    private val codes = LinkedBlockingQueue<String>(1)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "control-client-write").apply { isDaemon = true } }
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "control-client-timer").apply { isDaemon = true } }

    private val moveLock = Any()
    private var moveX = 0f
    private var moveY = 0f
    private var moveScheduled = false

    fun start() {
        Thread({ run() }, "control-client-read").apply { isDaemon = true }.start()
    }

    /** The code the person typed, after [ControlListener.onCodeRequested]. */
    fun submitCode(code: String) {
        codes.offer(code)
    }

    private fun run() {
        val trust = PinnedTrust(credential?.fingerprint)
        try {
            val raw = Socket()
            socket = raw
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = 5_000
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trust), SecureRandom())
            val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket = s
            s.enabledProtocols = s.supportedProtocols.filter { it in TLS_PROTOCOLS }.toTypedArray()
            s.startHandshake()
            val fingerprint = trust.seenFingerprint ?: return fail(ClientFailure.SecureFailed)
            val o = BufferedOutputStream(s.getOutputStream())
            val reader = LineReader(s.getInputStream())
            fun write(line: String) {
                o.write((line + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            }

            val chal = reader.readLine() ?: return fail(ClientFailure.Dropped)
            if (chal.startsWith("ERR locked")) return fail(locked(chal))
            val parts = chal.split(' ')
            if (parts.size != 2 || parts[0] != "CHAL") return fail(ClientFailure.Dropped)
            val nonce = parts[1]

            val tvName: String
            if (credential != null) {
                write("AUTH ${credential.id} ${Auth.proof(credential.secret, nonce, fingerprint, Auth.CLIENT)}")
                val reply = reader.readLine() ?: return fail(ClientFailure.Dropped)
                when {
                    reply.startsWith("OK ") -> {
                        val ok = reply.split(' ')
                        // The TV has to prove it holds the key too, over this connection's certificate.
                        if (ok.size != 3 || !Auth.verify(credential.secret, nonce, fingerprint, Auth.SERVER, ok[2])) {
                            return fail(ClientFailure.Untrusted)
                        }
                        tvName = ok[1]
                    }
                    reply.startsWith("ERR badtoken") -> return fail(ClientFailure.NotPaired)
                    reply.startsWith("ERR locked") -> return fail(locked(reply))
                    else -> return fail(ClientFailure.Dropped)
                }
            } else {
                tvName = pair(s, reader, ::write, fingerprint) ?: return
            }

            out = o
            ready = true
            // Keep-alive: the TV drops a phone that has been silent for a minute.
            timer.scheduleAtFixedRate({ send(Command.Ping) }, 20, 20, TimeUnit.SECONDS)
            s.soTimeout = 0
            listener.onReady(tvName.replace('_', ' '))

            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("STATE ")) parseState(line)?.let(listener::onState)
                else if (line.startsWith("VIDEO ")) parseVideo(line)?.let { listener.onVideo(it.state) }
                else if (line.startsWith("ADBLOCK ")) parseAdBlock(line)?.let(listener::onAdBlock)
            }
            fail(ClientFailure.Dropped)
        } catch (e: SocketTimeoutException) {
            fail(ClientFailure.Unreachable("The TV didn't answer."))
        } catch (e: SSLException) {
            fail(if (trust.mismatch) ClientFailure.IdentityChanged else ClientFailure.SecureFailed)
        } catch (e: IOException) {
            fail(ClientFailure.Unreachable(e.message ?: "Connection failed."))
        }
    }

    /** The [Passkey] exchange. Returns the TV's name, or null after reporting a failure. */
    private fun pair(s: SSLSocket, reader: LineReader, write: (String) -> Unit, fingerprint: String): String? {
        write("PAIR")
        val first = reader.readLine() ?: return null.also { fail(ClientFailure.Dropped) }
        when {
            first == "CODE" -> Unit
            first.startsWith("ERR busy") -> return null.also { fail(ClientFailure.Busy) }
            first.startsWith("ERR locked") -> return null.also { fail(locked(first)) }
            else -> return null.also { fail(ClientFailure.Dropped) }
        }
        listener.onCodeRequested()
        val code = try {
            codes.poll(codeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
        if (code == null || closed) return null.also { fail(ClientFailure.Dropped) }
        // Anything that isn't six digits can't match. Bit value 2 never opens on the TV,
        // so the attempt fails at the first round and counts towards the lockout.
        val valid = Passkey.isCode(code)

        for (i in 0 until Passkey.BITS) {
            val bit = if (valid) Passkey.bit(code, i) else 2
            val na = Passkey.newNonce()
            write("PC $i ${Passkey.commit(Passkey.PHONE, i, na, fingerprint, bit)}")
            val cb = expect(reader, "PC", i) ?: return null
            write("PN $i ${Passkey.hex(na)}")
            val reply = reader.readLine() ?: return null.also { fail(ClientFailure.Dropped) }
            if (reply.startsWith("ERR badcode")) return null.also { fail(ClientFailure.WrongPin) }
            val p = reply.split(' ')
            if (p.size != 3 || p[0] != "PN" || p[1] != i.toString()) return null.also { fail(ClientFailure.Dropped) }
            // The TV committed before seeing our nonce; if it opens to a different bit, it doesn't know the code.
            if (!Passkey.opens(cb, Passkey.TV, i, p[2], fingerprint, bit)) return null.also { fail(ClientFailure.Untrusted) }
        }
        val done = (reader.readLine() ?: return null.also { fail(ClientFailure.Dropped) }).split(' ')
        if (done.size != 4 || done[0] != "PAIRED") return null.also { fail(ClientFailure.Dropped) }
        val cred = Credential(done[2], done[3], fingerprint)
        if (Credential.decode(cred.encode()) == null) return null.also { fail(ClientFailure.Dropped) }
        listener.onPaired(cred)
        return done[1]
    }

    private fun expect(reader: LineReader, tag: String, i: Int): String? {
        val line = reader.readLine() ?: return null.also { fail(ClientFailure.Dropped) }
        if (line.startsWith("ERR badcode")) return null.also { fail(ClientFailure.WrongPin) }
        val p = line.split(' ')
        return if (p.size == 3 && p[0] == tag && p[1] == i.toString()) p[2] else null.also { fail(ClientFailure.Dropped) }
    }

    private fun locked(line: String) = ClientFailure.Locked(line.substringAfterLast(' ').toIntOrNull() ?: 30)

    private fun fail(f: ClientFailure) {
        if (closed) return
        closed = true
        ready = false
        runCatching { socket?.close() }
        timer.shutdownNow()
        io.shutdown()
        listener.onFailure(f)
    }

    val isReady: Boolean get() = ready && !closed

    fun send(c: Command) {
        if (!isReady) return
        if (c is Command.Move) {
            synchronized(moveLock) {
                moveX += c.dx
                moveY += c.dy
                if (!moveScheduled) {
                    moveScheduled = true
                    runCatching { timer.schedule({ io.execute(::flushMove) }, 16, TimeUnit.MILLISECONDS) }
                }
            }
        } else {
            runCatching {
                io.execute {
                    flushMove()
                    write(formatCommand(c))
                }
            }
        }
    }

    private fun flushMove() {
        val dx: Float
        val dy: Float
        synchronized(moveLock) {
            dx = moveX
            dy = moveY
            moveX = 0f
            moveY = 0f
            moveScheduled = false
        }
        if (dx != 0f || dy != 0f) write(formatCommand(Command.Move(dx, dy)))
    }

    private fun write(line: String) {
        try {
            val o = out ?: return
            o.write((line + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        } catch (_: IOException) {
            runCatching { socket?.close() } // the read loop reports the drop
        }
    }

    override fun close() {
        closed = true
        ready = false
        codes.offer("")
        runCatching { socket?.close() }
        timer.shutdownNow()
        io.shutdown()
    }
}
