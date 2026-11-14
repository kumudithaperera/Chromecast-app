package app.tvremote.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ControlLinkTest {
    private val received = LinkedBlockingQueue<Command>()
    private val clientEvents = CopyOnWriteArrayList<Boolean>()
    private var now = 1_000_000L
    private val servers = mutableListOf<ControlServer>()
    private val clients = mutableListOf<ControlClient>()

    private val handler = object : ControlHandler {
        override fun onCommand(command: Command) { received.add(command) }
        override fun onClientChanged(connected: Boolean) { clientEvents.add(connected) }
    }

    private fun server(pin: String = "123456", idle: Int = 60_000, auth: Int = 5_000): ControlServer =
        ControlServer(0, { pin }, "Test TV", handler, clock = { now }, authTimeoutMs = auth, idleTimeoutMs = idle)
            .also { it.start(); servers += it }

    private class Result : ControlListener {
        val ready = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<ClientFailure>()
        val states = LinkedBlockingQueue<BrowserState>()
        val videos = LinkedBlockingQueue<List<VideoState?>>()
        override fun onReady(deviceName: String) { ready.add(deviceName) }
        override fun onState(state: BrowserState) { states.add(state) }
        override fun onVideo(video: VideoState?) { videos.add(listOf(video)) }
        override fun onFailure(failure: ClientFailure) { failures.add(failure) }
    }

    private fun client(s: ControlServer, pin: String, r: Result) =
        ControlClient("127.0.0.1", pin, r, port = s.boundPort).also { it.start(); clients += it }

    @After fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.close() }
    }

    @Test fun correctPinConnectsAndCommandsArrive() {
        val s = server()
        val r = Result()
        val c = client(s, "123456", r)
        assertEquals("Test TV", r.ready.poll(3, TimeUnit.SECONDS))
        c.send(Command.Move(1f, 2f))
        c.send(Command.Click)
        c.send(Command.Text("hi"))
        assertEquals(Command.Move(1f, 2f), received.poll(3, TimeUnit.SECONDS))
        assertEquals(Command.Click, received.poll(3, TimeUnit.SECONDS))
        assertEquals(Command.Text("hi"), received.poll(3, TimeUnit.SECONDS))
        assertEquals(true, clientEvents.firstOrNull())
    }

    @Test fun moveBatchingSumsDeltasAndKeepsOrderBeforeClick() {
        val s = server()
        val r = Result()
        val c = client(s, "123456", r)
        r.ready.poll(3, TimeUnit.SECONDS)
        repeat(10) { c.send(Command.Move(1f, 1f)) }
        c.send(Command.Click)
        var dx = 0f
        while (true) {
            val cmd = received.poll(3, TimeUnit.SECONDS) ?: error("timeout")
            if (cmd is Command.Move) dx += cmd.dx else { assertEquals(Command.Click, cmd); break }
        }
        assertEquals(10f, dx, 0.01f)
    }

    @Test fun wrongPinIsRejectedAndNothingReachesTheHandler() {
        val s = server()
        val r = Result()
        val c = client(s, "000000", r)
        assertEquals(ClientFailure.WrongPin, r.failures.poll(3, TimeUnit.SECONDS))
        c.send(Command.Click)
        assertTrue(received.poll(300, TimeUnit.MILLISECONDS) == null)
        assertTrue(clientEvents.isEmpty())
    }

    @Test fun commandsBeforeAuthAreIgnored() {
        val s = server()
        Socket("127.0.0.1", s.boundPort).use { sock ->
            sock.getOutputStream().write("C\nM 5 5\nT aGk%3D\n".toByteArray())
            sock.getOutputStream().flush()
            Thread.sleep(300)
        }
        assertTrue(received.isEmpty())
    }

    @Test fun repeatedWrongPinsLockEvenTheRightPinUntilTheTimeoutPasses() {
        val s = server()
        repeat(ControlServer.ATTEMPTS_BEFORE_LOCK) {
            val r = Result()
            client(s, "999999", r)
            assertEquals(ClientFailure.WrongPin, r.failures.poll(3, TimeUnit.SECONDS))
        }
        val locked = Result()
        client(s, "123456", locked)
        val f = locked.failures.poll(3, TimeUnit.SECONDS)
        assertTrue("expected Locked, got $f", f is ClientFailure.Locked)
        assertTrue(locked.ready.isEmpty())

        now += 31_000
        val after = Result()
        client(s, "123456", after)
        assertEquals("Test TV", after.ready.poll(3, TimeUnit.SECONDS))
    }

    @Test fun oversizedLineClosesTheConnection() {
        val s = server()
        val r = Result()
        client(s, "123456", r)
        r.ready.poll(3, TimeUnit.SECONDS)
        // A second, raw connection that authenticates and then sends a huge line.
        Socket("127.0.0.1", s.boundPort).use { sock ->
            val reader = LineReader(sock.getInputStream())
            val nonce = reader.readLine()!!.removePrefix("CHAL ")
            sock.getOutputStream().write("AUTH ${Auth.proof("123456", nonce)}\n".toByteArray())
            assertTrue(reader.readLine()!!.startsWith("OK"))
            sock.getOutputStream().write(("T " + "a".repeat(MAX_LINE * 2) + "\n").toByteArray())
            sock.getOutputStream().flush()
            sock.soTimeout = 3000
            // The server hangs up; depending on timing the client sees EOF or a reset.
            val hungUp = try { reader.readLine() == null } catch (_: java.net.SocketException) { true }
            assertTrue(hungUp)
        }
        assertTrue(received.none { it is Command.Text })
    }

    @Test fun silentUnauthenticatedConnectionIsDropped() {
        val s = server(auth = 200)
        Socket("127.0.0.1", s.boundPort).use { sock ->
            sock.soTimeout = 3000
            val reader = LineReader(sock.getInputStream())
            assertTrue(reader.readLine()!!.startsWith("CHAL"))
            assertEquals(null, reader.readLine())
        }
    }

    @Test fun newerPhoneReplacesTheOlderOne() {
        val s = server()
        val r1 = Result(); client(s, "123456", r1); r1.ready.poll(3, TimeUnit.SECONDS)
        val r2 = Result(); client(s, "123456", r2); r2.ready.poll(3, TimeUnit.SECONDS)
        assertEquals(ClientFailure.Dropped, r1.failures.poll(3, TimeUnit.SECONDS))
    }

    @Test fun stateReachesThePhone() {
        val s = server()
        val r = Result()
        client(s, "123456", r)
        r.ready.poll(3, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 3000
        while (clientEvents.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        s.sendState(BrowserState("https://a.test", "A", 2, 1))
        assertEquals("https://a.test", r.states.poll(3, TimeUnit.SECONDS)?.url)
    }

    @Test fun videoStateAndCommandsCrossTheLink() {
        val s = server()
        val r = Result()
        val c = client(s, "123456", r)
        r.ready.poll(3, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 3000
        while (clientEvents.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val v = VideoState(playing = true, position = 12f, duration = 60f, fullscreen = true)
        s.sendVideo(v)
        s.sendVideo(null)
        assertEquals(listOf<VideoState?>(v), r.videos.poll(3, TimeUnit.SECONDS))
        assertEquals(listOf<VideoState?>(null), r.videos.poll(3, TimeUnit.SECONDS))

        c.send(Command.Video(VideoAction.SEEK, 33f))
        c.send(Command.Video(VideoAction.TOGGLE))
        assertEquals(Command.Video(VideoAction.SEEK, 33f), received.poll(3, TimeUnit.SECONDS))
        assertEquals(Command.Video(VideoAction.TOGGLE), received.poll(3, TimeUnit.SECONDS))
    }

    @Test fun authProofVerification() {
        val n = Auth.newNonce()
        assertTrue(Auth.verify("123456", n, Auth.proof("123456", n)))
        assertFalse(Auth.verify("123456", n, Auth.proof("123457", n)))
        assertFalse(Auth.verify("123456", n, ""))
        assertEquals(6, Auth.newPin().length)
    }
}
