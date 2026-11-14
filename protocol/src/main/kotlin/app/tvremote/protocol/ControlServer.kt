package app.tvremote.protocol

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

interface ControlHandler {
    /** Called on a server thread, one command at a time, only after authentication. */
    fun onCommand(command: Command)

    /** True when a phone has authenticated, false when it goes away. */
    fun onClientChanged(connected: Boolean)

    /** A phone started pairing: show [code] on screen. Null when that attempt ends. */
    fun onPairingCode(code: String?) {}
}

/**
 * The TV side of the link. Everything runs inside TLS ([ssl] holds the TV's
 * certificate; there is no plaintext mode). A phone either proves it holds a
 * key from an earlier pairing, or pairs now with a code shown on the TV
 * ([Passkey]). Nothing it sends reaches [ControlHandler] before one of those
 * succeeds. One authenticated phone at a time; a newer one replaces the older.
 *
 * [fingerprint] is the SHA-256 of the certificate in [ssl]; every proof is
 * bound to it.
 *
 * Wire exchange, after the TLS handshake:
 *   TV   -> CHAL <nonce>
 *   Paired phone:
 *     Phone-> AUTH <id> <hmac>
 *     TV   -> OK <name> <hmac>       or  ERR badtoken / ERR locked <seconds>
 *   New phone:
 *     Phone-> PAIR
 *     TV   -> CODE                   (the code is now on the TV screen)  or  ERR busy / ERR locked <seconds>
 *     20 times, i = 0..19:
 *       Phone-> PC <i> <commitment>
 *       TV   -> PC <i> <commitment>
 *       Phone-> PN <i> <nonce>
 *       TV   -> PN <i> <nonce>       or  ERR badcode
 *     TV   -> PAIRED <name> <id> <key>
 *   then commands one way and STATE lines the other.
 */
class ControlServer(
    private val port: Int,
    private val phones: PairedPhones,
    private val deviceName: String,
    private val ssl: SSLContext,
    private val fingerprint: String,
    private val handler: ControlHandler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val authTimeoutMs: Int = 5_000,
    private val idleTimeoutMs: Int = 60_000,
    private val pairingTimeoutMs: Int = 90_000,
) : Closeable {
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "control-conn").apply { isDaemon = true } }
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "control-write").apply { isDaemon = true } }
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val lock = Any()

    private var failures = 0
    private var lockedUntil = 0L
    private var active: Connection? = null
    private var pairing = false

    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
        s.reuseAddress = true
        s.enabledProtocols = s.supportedProtocols.filter { it in TLS_PROTOCOLS }.toTypedArray()
        s.bind(InetSocketAddress(port), 4)
        server = s
        Thread({ acceptLoop(s) }, "control-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val sock = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            if (!slots.tryAcquire()) {
                runCatching { sock.close() }
                continue
            }
            workers.execute {
                try {
                    serve(sock)
                } finally {
                    slots.release()
                    runCatching { sock.close() }
                }
            }
        }
    }

    private fun serve(sock: Socket) {
        sock.tcpNoDelay = true
        sock.soTimeout = authTimeoutMs
        val out = BufferedOutputStream(sock.getOutputStream())
        val reader = LineReader(sock.getInputStream())
        val conn = Connection(sock, out)
        try {
            // A failed handshake (a plaintext client, a scanner) is not a wrong PIN and
            // doesn't count towards the lockout.
            (sock as SSLSocket).startHandshake()
            val remaining = lockedFor()
            if (remaining > 0) {
                conn.writeNow("ERR locked $remaining")
                return
            }
            val nonce = Auth.newNonce()
            conn.writeNow("CHAL $nonce")
            val parts = (reader.readLine() ?: return).split(' ')
            val name = deviceName.replace(' ', '_')
            when {
                parts.size == 3 && parts[0] == "AUTH" -> {
                    val secret = phones.secret(parts[1])
                    if (secret == null || !Auth.verify(secret, nonce, fingerprint, Auth.CLIENT, parts[2])) {
                        recordFailure()
                        conn.writeNow("ERR badtoken")
                        return
                    }
                    recordSuccess()
                    conn.writeNow("OK $name ${Auth.proof(secret, nonce, fingerprint, Auth.SERVER)}")
                }
                parts.size == 1 && parts[0] == "PAIR" -> if (!pair(sock, conn, reader, name)) return
                else -> {
                    recordFailure()
                    conn.writeNow("ERR badtoken")
                    return
                }
            }
            sock.soTimeout = idleTimeoutMs
            activate(conn)
            try {
                while (true) {
                    val l = reader.readLine() ?: break
                    val cmd = parseCommand(l) ?: continue
                    if (cmd != Command.Ping) handler.onCommand(cmd)
                }
            } finally {
                deactivate(conn)
            }
        } catch (_: SocketTimeoutException) {
            // No auth, or a silent phone: drop it.
        } catch (_: IOException) {
            // Closed, reset or oversized line.
        }
    }

    /** Runs the [Passkey] exchange. True when the phone proved it knows the code shown on screen. */
    private fun pair(sock: Socket, conn: Connection, reader: LineReader, name: String): Boolean {
        val free = synchronized(lock) { if (pairing) false else { pairing = true; true } }
        if (!free) {
            conn.writeNow("ERR busy")
            return false
        }
        var done = false
        try {
            val code = Auth.newCode()
            handler.onPairingCode(code)
            conn.writeNow("CODE")
            // The person needs time to read the code and type it.
            sock.soTimeout = pairingTimeoutMs
            for (i in 0 until Passkey.BITS) {
                val ca = expect(reader, "PC", i) ?: return false
                sock.soTimeout = authTimeoutMs
                val bit = Passkey.bit(code, i)
                val nb = Passkey.newNonce()
                conn.writeNow("PC $i ${Passkey.commit(Passkey.TV, i, nb, fingerprint, bit)}")
                val na = expect(reader, "PN", i) ?: return false
                if (!Passkey.opens(ca, Passkey.PHONE, i, na, fingerprint, bit)) {
                    conn.writeNow("ERR badcode")
                    return false
                }
                conn.writeNow("PN $i ${Passkey.hex(nb)}")
            }
            val id = Auth.newId()
            val secret = Auth.newSecret()
            phones.add(id, secret)
            done = true
            recordSuccess()
            conn.writeNow("PAIRED $name $id $secret")
            return true
        } finally {
            // A wrong code, a timeout and a dropped connection all count, so pairing can't be retried endlessly.
            if (!done) recordFailure()
            synchronized(lock) { pairing = false }
            handler.onPairingCode(null)
        }
    }

    private fun expect(reader: LineReader, tag: String, i: Int): String? {
        val p = (reader.readLine() ?: return null).split(' ')
        return if (p.size == 3 && p[0] == tag && p[1] == i.toString()) p[2] else null
    }

    private fun lockedFor(): Long = synchronized(lock) {
        val left = lockedUntil - clock()
        if (left > 0) (left + 999) / 1000 else 0
    }

    private fun recordFailure() = synchronized(lock) {
        failures++
        if (failures % ATTEMPTS_BEFORE_LOCK == 0) {
            val round = failures / ATTEMPTS_BEFORE_LOCK
            val seconds = minOf(MAX_LOCK_SECONDS, BASE_LOCK_SECONDS shl minOf(round - 1, 6))
            lockedUntil = clock() + seconds * 1000
        }
    }

    private fun recordSuccess() = synchronized(lock) {
        failures = 0
        lockedUntil = 0
    }

    private fun activate(conn: Connection) {
        val old: Connection?
        synchronized(lock) {
            old = active
            active = conn
        }
        old?.close()
        handler.onClientChanged(true)
    }

    private fun deactivate(conn: Connection) {
        val wasActive: Boolean
        synchronized(lock) {
            wasActive = active === conn
            if (wasActive) active = null
        }
        if (wasActive) handler.onClientChanged(false)
    }

    /** Pushes the video state (or its absence) to the authenticated phone, if there is one. */
    fun sendVideo(video: VideoState?) {
        val c = synchronized(lock) { active } ?: return
        val line = formatVideo(video)
        writer.execute { c.writeQuietly(line) }
    }

    /** Pushes the browser state to the authenticated phone, if there is one. */
    fun sendState(state: BrowserState) {
        val c = synchronized(lock) { active } ?: return
        val line = formatState(state)
        writer.execute { c.writeQuietly(line) }
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        synchronized(lock) { active }?.close()
        workers.shutdownNow()
        writer.shutdownNow()
    }

    private class Connection(val sock: Socket, val out: BufferedOutputStream) {
        private val writeLock = Any()

        fun writeNow(line: String) {
            synchronized(writeLock) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }

        fun writeQuietly(line: String) {
            try {
                writeNow(line)
            } catch (_: IOException) {
                close()
            }
        }

        fun close() {
            runCatching { sock.close() }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 4
        const val ATTEMPTS_BEFORE_LOCK = 5
        const val BASE_LOCK_SECONDS = 30L
        const val MAX_LOCK_SECONDS = 600L
    }
}
