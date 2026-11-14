package app.tvremote.protocol

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

interface ControlHandler {
    /** Called on a server thread, one command at a time, only after authentication. */
    fun onCommand(command: Command)

    /** True when a phone has authenticated, false when it goes away. */
    fun onClientChanged(connected: Boolean)
}

/**
 * The TV side of the link. Every client has to answer a challenge with the
 * PIN's HMAC before anything it sends reaches [ControlHandler]. One
 * authenticated phone at a time; a newer one replaces the older.
 *
 * Wire exchange:
 *   TV   -> CHAL <nonce>
 *   Phone-> AUTH <hmac>
 *   TV   -> OK <name>        or  ERR badpin / ERR locked <seconds>
 *   then commands one way and STATE lines the other.
 */
class ControlServer(
    private val port: Int,
    private val pin: () -> String,
    private val deviceName: String,
    private val handler: ControlHandler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val authTimeoutMs: Int = 5_000,
    private val idleTimeoutMs: Int = 60_000,
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

    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = ServerSocket(port, 4, null as InetAddress?)
        s.reuseAddress = true
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
            val remaining = lockedFor()
            if (remaining > 0) {
                conn.writeNow("ERR locked $remaining")
                return
            }
            val nonce = Auth.newNonce()
            conn.writeNow("CHAL $nonce")
            val line = reader.readLine() ?: return
            val parts = line.split(' ')
            val ok = parts.size == 2 && parts[0] == "AUTH" && Auth.verify(pin(), nonce, parts[1])
            if (!ok) {
                recordFailure()
                conn.writeNow("ERR badpin")
                return
            }
            recordSuccess()
            sock.soTimeout = idleTimeoutMs
            conn.writeNow("OK ${deviceName.replace(' ', '_')}")
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
