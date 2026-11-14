package app.tvremote.protocol

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

sealed interface ClientFailure {
    data object WrongPin : ClientFailure
    data class Locked(val seconds: Int) : ClientFailure
    data class Unreachable(val message: String) : ClientFailure
    data object Dropped : ClientFailure
}

interface ControlListener {
    fun onReady(deviceName: String)
    fun onState(state: BrowserState)

    /** The video in the TV browser changed, or went away (null). */
    fun onVideo(video: VideoState?) {}
    fun onFailure(failure: ClientFailure)
}

/**
 * The phone side. [start] connects and authenticates on a background thread;
 * results come through [ControlListener] on that thread. Cursor movement is
 * batched to about 60 sends a second so a fast swipe doesn't flood the TV.
 */
class ControlClient(
    private val host: String,
    private val pin: String,
    private val listener: ControlListener,
    private val port: Int = PORT,
    private val connectTimeoutMs: Int = 4_000,
) : Closeable {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: BufferedOutputStream? = null
    @Volatile private var closed = false
    @Volatile private var ready = false

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

    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = 5_000
            val o = BufferedOutputStream(s.getOutputStream())
            val reader = LineReader(s.getInputStream())

            val chal = reader.readLine() ?: return fail(ClientFailure.Dropped)
            if (chal.startsWith("ERR locked")) {
                return fail(ClientFailure.Locked(chal.substringAfterLast(' ').toIntOrNull() ?: 30))
            }
            val parts = chal.split(' ')
            if (parts.size != 2 || parts[0] != "CHAL") return fail(ClientFailure.Dropped)
            o.write("AUTH ${Auth.proof(pin, parts[1])}\n".toByteArray())
            o.flush()

            val reply = reader.readLine() ?: return fail(ClientFailure.Dropped)
            when {
                reply.startsWith("OK") -> Unit
                reply.startsWith("ERR badpin") -> return fail(ClientFailure.WrongPin)
                reply.startsWith("ERR locked") ->
                    return fail(ClientFailure.Locked(reply.substringAfterLast(' ').toIntOrNull() ?: 30))
                else -> return fail(ClientFailure.Dropped)
            }
            out = o
            ready = true
            // Keep-alive: the TV drops a phone that has been silent for a minute.
            timer.scheduleAtFixedRate({ send(Command.Ping) }, 20, 20, TimeUnit.SECONDS)
            s.soTimeout = 0
            listener.onReady(reply.removePrefix("OK").trim().replace('_', ' '))

            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("STATE ")) parseState(line)?.let(listener::onState)
                else if (line.startsWith("VIDEO ")) parseVideo(line)?.let { listener.onVideo(it.state) }
            }
            fail(ClientFailure.Dropped)
        } catch (e: SocketTimeoutException) {
            fail(ClientFailure.Unreachable("The TV didn't answer."))
        } catch (e: IOException) {
            fail(ClientFailure.Unreachable(e.message ?: "Connection failed."))
        }
    }

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
        runCatching { socket?.close() }
        timer.shutdownNow()
        io.shutdown()
    }
}
