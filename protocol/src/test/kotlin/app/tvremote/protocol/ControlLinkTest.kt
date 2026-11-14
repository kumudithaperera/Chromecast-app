package app.tvremote.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket

class ControlLinkTest {
    private val received = LinkedBlockingQueue<Command>()
    private val clientEvents = CopyOnWriteArrayList<Boolean>()
    /** Codes the TV put on screen, and null when it took one down. */
    private val shown = LinkedBlockingQueue<Optional>()
    private var now = 1_000_000L
    private val servers = mutableListOf<ControlServer>()
    private val clients = mutableListOf<ControlClient>()

    data class Optional(val code: String?)

    private val handler = object : ControlHandler {
        override fun onCommand(command: Command) { received.add(command) }
        override fun onClientChanged(connected: Boolean) { clientEvents.add(connected) }
        override fun onPairingCode(code: String?) { shown.add(Optional(code)) }
    }

    private class Phones : PairedPhones {
        val map = ConcurrentHashMap<String, String>()
        override fun secret(id: String) = map[id]
        override fun add(id: String, secret: String) { map[id] = secret }
    }

    private val tv = TestTls.a
    private val phones = Phones()

    private fun server(idle: Int = 60_000, auth: Int = 5_000, pairing: Int = 90_000, id: TestIdentity = tv): ControlServer =
        ControlServer(
            0, phones, "Test TV", id.ssl, id.fingerprint, handler,
            clock = { now }, authTimeoutMs = auth, idleTimeoutMs = idle, pairingTimeoutMs = pairing,
        ).also { it.start(); servers += it }

    private class Result : ControlListener {
        val codeRequested = LinkedBlockingQueue<Unit>()
        val paired = LinkedBlockingQueue<Credential>()
        val ready = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<ClientFailure>()
        val states = LinkedBlockingQueue<BrowserState>()
        val videos = LinkedBlockingQueue<List<VideoState?>>()
        override fun onCodeRequested() { codeRequested.add(Unit) }
        override fun onPaired(credential: Credential) { paired.add(credential) }
        override fun onReady(deviceName: String) { ready.add(deviceName) }
        override fun onState(state: BrowserState) { states.add(state) }
        override fun onVideo(video: VideoState?) { videos.add(listOf(video)) }
        override fun onFailure(failure: ClientFailure) { failures.add(failure) }
    }

    private fun client(port: Int, r: Result, credential: Credential? = null) =
        ControlClient("127.0.0.1", credential, r, port = port).also { it.start(); clients += it }

    /** Starts pairing and waits for the TV to show a code. Returns the client and that code. */
    private fun startPairing(s: ControlServer, r: Result): Pair<ControlClient, String> {
        val c = client(s.boundPort, r)
        assertNotNull("phone was never asked for a code", r.codeRequested.poll(3, TimeUnit.SECONDS))
        val code = shown.poll(3, TimeUnit.SECONDS)?.code ?: error("TV showed no code")
        return c to code
    }

    /** Pairs a phone the normal way: the person reads the code off the TV and types it. */
    private fun pair(s: ControlServer, r: Result = Result()): Credential {
        val (c, code) = startPairing(s, r)
        c.submitCode(code)
        val cred = r.paired.poll(5, TimeUnit.SECONDS) ?: error("not paired: ${r.failures.peek()}")
        assertEquals("Test TV", r.ready.poll(3, TimeUnit.SECONDS))
        return cred
    }

    private fun wrongCode(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @After fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.close() }
    }

    // ---- pairing ----

    @Test fun typingTheCodeShownOnTheTvPairsAndCommandsArrive() {
        val s = server()
        val r = Result()
        val (c, code) = startPairing(s, r)
        c.submitCode(code)
        val cred = r.paired.poll(5, TimeUnit.SECONDS)!!
        assertEquals(tv.fingerprint, cred.fingerprint)
        assertEquals(cred.secret, phones.secret(cred.id))
        assertEquals(64, cred.secret.length)
        assertEquals("Test TV", r.ready.poll(3, TimeUnit.SECONDS))
        // The code comes off the screen once pairing is over.
        assertEquals(Optional(null), shown.poll(3, TimeUnit.SECONDS))

        c.send(Command.Move(1f, 2f))
        c.send(Command.Click)
        c.send(Command.Text("hi"))
        assertEquals(Command.Move(1f, 2f), received.poll(3, TimeUnit.SECONDS))
        assertEquals(Command.Click, received.poll(3, TimeUnit.SECONDS))
        assertEquals(Command.Text("hi"), received.poll(3, TimeUnit.SECONDS))
        assertEquals(true, clientEvents.firstOrNull())
    }

    @Test fun aPairedPhoneReconnectsWithItsKeyAndNoCode() {
        val s = server()
        val cred = pair(s)
        shown.clear()
        val r = Result()
        client(s.boundPort, r, cred)
        assertEquals("Test TV", r.ready.poll(3, TimeUnit.SECONDS))
        assertTrue(r.codeRequested.isEmpty())
        assertTrue(shown.isEmpty())
    }

    @Test fun aWrongCodeIsRejectedAndNothingReachesTheHandler() {
        val s = server()
        val r = Result()
        val (c, code) = startPairing(s, r)
        c.submitCode(wrongCode(code))
        assertEquals(ClientFailure.WrongPin, r.failures.poll(5, TimeUnit.SECONDS))
        assertTrue(r.paired.isEmpty())
        assertTrue(phones.map.isEmpty())
        assertTrue(clientEvents.isEmpty())
        assertTrue(received.isEmpty())
        assertEquals(Optional(null), shown.poll(3, TimeUnit.SECONDS))
    }

    @Test fun somethingThatIsNotACodeIsRejected() {
        val s = server()
        val r = Result()
        val (c, _) = startPairing(s, r)
        c.submitCode("12ab")
        assertEquals(ClientFailure.WrongPin, r.failures.poll(5, TimeUnit.SECONDS))
        assertTrue(phones.map.isEmpty())
    }

    @Test fun everyPairingAttemptGetsAFreshCode() {
        val s = server()
        val seen = mutableSetOf<String>()
        repeat(4) {
            val r = Result()
            val (c, code) = startPairing(s, r)
            seen += code
            c.close()
            shown.poll(3, TimeUnit.SECONDS) // taken down
            now += 3_600_000 // stay clear of the lockout these abandoned attempts add up to
        }
        assertTrue("codes repeated: $seen", seen.size >= 3)
    }

    @Test fun repeatedWrongCodesLockEvenTheRightCodeUntilTheTimeoutPasses() {
        val s = server()
        repeat(ControlServer.ATTEMPTS_BEFORE_LOCK) {
            val r = Result()
            val (c, code) = startPairing(s, r)
            c.submitCode(wrongCode(code))
            assertEquals(ClientFailure.WrongPin, r.failures.poll(5, TimeUnit.SECONDS))
            shown.poll(3, TimeUnit.SECONDS)
        }
        val locked = Result()
        client(s.boundPort, locked)
        val f = locked.failures.poll(3, TimeUnit.SECONDS)
        assertTrue("expected Locked, got $f", f is ClientFailure.Locked)
        assertTrue(shown.isEmpty())

        now += 31_000
        pair(s)
    }

    @Test fun onlyOnePairingAtATime() {
        val s = server()
        startPairing(s, Result())
        val second = Result()
        client(s.boundPort, second)
        assertEquals(ClientFailure.Busy, second.failures.poll(3, TimeUnit.SECONDS))
    }

    @Test fun anAbandonedPairingTimesOutAndTheCodeComesDown() {
        val s = server(pairing = 300)
        startPairing(s, Result())
        assertEquals(Optional(null), shown.poll(3, TimeUnit.SECONDS))
        // The TV is free for the next phone.
        now += 3_600_000
        pair(s)
    }

    @Test fun anUnknownKeyIsRejected() {
        val s = server()
        val r = Result()
        client(s.boundPort, r, Credential("0011223344556677", "ab".repeat(32), tv.fingerprint))
        assertEquals(ClientFailure.NotPaired, r.failures.poll(3, TimeUnit.SECONDS))
        assertTrue(clientEvents.isEmpty())
    }

    // ---- attacks on the first pairing ----

    /**
     * A machine in the middle: the phone connects to it (it has its own certificate, b),
     * and it connects to the real TV and relays every line both ways. The person types
     * the real code shown on the TV. Pairing must fail and the TV must not hand out a key.
     */
    @Test fun aRelayInTheMiddleCannotCompletePairing() {
        val s = server()
        val mitm = TestTls.b.ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val relay = Thread {
            runCatching {
                mitm.accept().use { phoneSide ->
                    TestTls.rawClient(s.boundPort).use { tvSide ->
                        val a = Thread { runCatching { phoneSide.inputStream.copyTo(tvSide.outputStream.also { }) } }
                        a.isDaemon = true
                        a.start()
                        runCatching {
                            val buf = ByteArray(4096)
                            while (true) {
                                val n = tvSide.inputStream.read(buf)
                                if (n < 0) break
                                phoneSide.outputStream.write(buf, 0, n)
                                phoneSide.outputStream.flush()
                            }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val r = Result()
        val c = client(mitm.localPort, r)
        assertNotNull(r.codeRequested.poll(3, TimeUnit.SECONDS))
        val code = shown.poll(3, TimeUnit.SECONDS)!!.code!!
        c.submitCode(code) // the person did everything right
        val f = r.failures.poll(5, TimeUnit.SECONDS)
        assertTrue("expected the pairing to fail, got $f", f == ClientFailure.WrongPin || f == ClientFailure.Untrusted)
        assertTrue(r.paired.isEmpty())
        assertTrue(phones.map.isEmpty())
        assertTrue(clientEvents.isEmpty())
        mitm.close()
        relay.join(1000)
    }

    /** An impostor TV that doesn't know the code shown on the real TV is caught by the phone. */
    @Test fun aTvThatDoesNotKnowTheCodeIsCaughtByThePhone() {
        val fake = TestTls.b.ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val itsGuess = "135791"
        val t = Thread {
            runCatching {
                fake.accept().use { sock ->
                    val reader = LineReader(sock.getInputStream())
                    val out = sock.getOutputStream()
                    fun w(l: String) { out.write("$l\n".toByteArray()); out.flush() }
                    w("CHAL 00112233445566778899aabbccddeeff")
                    reader.readLine() // PAIR
                    w("CODE")
                    for (i in 0 until Passkey.BITS) {
                        reader.readLine() // PC
                        val n = Passkey.newNonce()
                        w("PC $i ${Passkey.commit(Passkey.TV, i, n, TestTls.b.fingerprint, Passkey.bit(itsGuess, i))}")
                        reader.readLine() ?: break // PN
                        w("PN $i ${Passkey.hex(n)}")
                    }
                    w("PAIRED Fake_TV 0011223344556677 ${"ab".repeat(32)}")
                    Thread.sleep(500)
                }
            }
        }.apply { isDaemon = true; start() }
        val r = Result()
        val c = client(fake.localPort, r)
        assertNotNull(r.codeRequested.poll(3, TimeUnit.SECONDS))
        c.submitCode("246802") // the real code, which the impostor doesn't know
        assertEquals(ClientFailure.Untrusted, r.failures.poll(5, TimeUnit.SECONDS))
        assertTrue(r.paired.isEmpty())
        assertTrue(r.ready.isEmpty())
        fake.close()
        t.join(1000)
    }

    @Test fun aTvThatCannotProveThePairedKeyIsRejected() {
        val fake = TestTls.b.ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val t = Thread {
            runCatching {
                fake.accept().use { sock ->
                    val reader = LineReader(sock.getInputStream())
                    sock.getOutputStream().write("CHAL 00112233445566778899aabbccddeeff\n".toByteArray())
                    reader.readLine()
                    sock.getOutputStream().write("OK Fake_TV ${"0".repeat(64)}\n".toByteArray())
                    sock.getOutputStream().flush()
                    Thread.sleep(500)
                }
            }
        }.apply { isDaemon = true; start() }
        val r = Result()
        // A phone that once paired with a TV using certificate b, so the handshake passes.
        client(fake.localPort, r, Credential("0011223344556677", "ab".repeat(32), TestTls.b.fingerprint))
        assertEquals(ClientFailure.Untrusted, r.failures.poll(3, TimeUnit.SECONDS))
        assertTrue(r.ready.isEmpty())
        fake.close()
        t.join(1000)
    }

    @Test fun aDifferentCertificateIsRefusedBeforeTheKeyIsUsed() {
        val impostor = server(id = TestTls.b)
        val r = Result()
        client(impostor.boundPort, r, Credential("0011223344556677", "ab".repeat(32), TestTls.a.fingerprint))
        assertEquals(ClientFailure.IdentityChanged, r.failures.poll(3, TimeUnit.SECONDS))
        assertTrue(r.ready.isEmpty())
        assertTrue(clientEvents.isEmpty())
    }

    // ---- transport ----

    @Test fun nothingIsSentInTheClearAndAPlaintextClientGetsNoChallenge() {
        val s = server()
        Socket("127.0.0.1", s.boundPort).use { sock ->
            sock.soTimeout = 1_000
            val input = sock.getInputStream()
            // A TLS server waits for the client to speak first; a plaintext one would send CHAL here.
            val early = try { input.read() } catch (_: java.net.SocketTimeoutException) { -2 }
            assertEquals(-2, early)
            sock.getOutputStream().write("PAIR\n".toByteArray())
            sock.getOutputStream().flush()
            val buf = ByteArray(256)
            val n = try { input.read(buf) } catch (_: java.io.IOException) { -1 }
            val text = if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
            assertFalse(text.contains("CHAL") || text.contains("CODE"))
        }
        assertTrue(received.isEmpty())
        assertTrue(shown.isEmpty())
    }

    @Test fun failedHandshakesDoNotCountAsWrongCodes() {
        val s = server()
        repeat(ControlServer.ATTEMPTS_BEFORE_LOCK + 2) {
            Socket("127.0.0.1", s.boundPort).use { it.getOutputStream().write("garbage\n".toByteArray()) }
        }
        pair(s)
    }

    @Test fun aPlaintextServerIsRefusedByTheClient() {
        // What an old TV app looks like: it answers with CHAL in the clear.
        val plain = ServerSocket(0)
        val t = Thread {
            runCatching {
                plain.accept().use { sock ->
                    sock.getOutputStream().write("CHAL 00112233445566778899aabbccddeeff\n".toByteArray())
                    sock.getOutputStream().flush()
                    Thread.sleep(500)
                }
            }
        }.apply { isDaemon = true; start() }
        val r = Result()
        client(plain.localPort, r)
        assertEquals(ClientFailure.SecureFailed, r.failures.poll(3, TimeUnit.SECONDS))
        assertTrue(r.ready.isEmpty())
        plain.close()
        t.join(1000)
    }

    @Test fun commandsBeforeAuthAreIgnored() {
        val s = server()
        TestTls.rawClient(s.boundPort).use { sock ->
            sock.getOutputStream().write("C\nM 5 5\nT aGk%3D\n".toByteArray())
            sock.getOutputStream().flush()
            Thread.sleep(300)
        }
        assertTrue(received.isEmpty())
    }

    @Test fun oversizedLineClosesTheConnection() {
        val s = server()
        val cred = pair(s)
        // A second, raw connection that authenticates and then sends a huge line.
        TestTls.rawClient(s.boundPort).use { sock ->
            val reader = LineReader(sock.getInputStream())
            val nonce = reader.readLine()!!.removePrefix("CHAL ")
            sock.getOutputStream().write("AUTH ${cred.id} ${Auth.proof(cred.secret, nonce, tv.fingerprint, Auth.CLIENT)}\n".toByteArray())
            assertTrue(reader.readLine()!!.startsWith("OK"))
            received.clear()
            sock.getOutputStream().write(("T " + "a".repeat(MAX_LINE * 2) + "\n").toByteArray())
            sock.getOutputStream().flush()
            sock.soTimeout = 3000
            val hungUp = try { reader.readLine() == null } catch (_: java.io.IOException) { true }
            assertTrue(hungUp)
        }
        assertTrue(received.none { it is Command.Text })
    }

    @Test fun silentUnauthenticatedConnectionIsDropped() {
        val s = server(auth = 200)
        TestTls.rawClient(s.boundPort).use { sock ->
            sock.soTimeout = 3000
            val reader = LineReader(sock.getInputStream())
            assertTrue(reader.readLine()!!.startsWith("CHAL"))
            assertNull(reader.readLine())
        }
    }

    // ---- after connecting ----

    @Test fun moveBatchingSumsDeltasAndKeepsOrderBeforeClick() {
        val s = server()
        val cred = pair(s)
        val r = Result()
        val c = client(s.boundPort, r, cred)
        r.ready.poll(3, TimeUnit.SECONDS)
        received.clear()
        repeat(10) { c.send(Command.Move(1f, 1f)) }
        c.send(Command.Click)
        var dx = 0f
        while (true) {
            val cmd = received.poll(3, TimeUnit.SECONDS) ?: error("timeout")
            if (cmd is Command.Move) dx += cmd.dx else { assertEquals(Command.Click, cmd); break }
        }
        assertEquals(10f, dx, 0.01f)
    }

    @Test fun newerPhoneReplacesTheOlderOne() {
        val s = server()
        val cred = pair(s)
        val r1 = Result(); client(s.boundPort, r1, cred); r1.ready.poll(3, TimeUnit.SECONDS)
        val r2 = Result(); client(s.boundPort, r2, cred); r2.ready.poll(3, TimeUnit.SECONDS)
        assertEquals(ClientFailure.Dropped, r1.failures.poll(3, TimeUnit.SECONDS))
    }

    @Test fun stateAndVideoCrossTheLink() {
        val s = server()
        val cred = pair(s)
        val r = Result()
        val c = client(s.boundPort, r, cred)
        r.ready.poll(3, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 3000
        while (clientEvents.count { it } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        received.clear()

        s.sendState(BrowserState("https://a.test", "A", 2, 1))
        assertEquals("https://a.test", r.states.poll(3, TimeUnit.SECONDS)?.url)

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

    // ---- the primitives ----

    @Test fun keyProofsAreBoundToTheCertificateAndTheRole() {
        val n = Auth.newNonce()
        val key = Auth.newSecret()
        val p = Auth.proof(key, n, TestTls.a.fingerprint, Auth.CLIENT)
        assertTrue(Auth.verify(key, n, TestTls.a.fingerprint, Auth.CLIENT, p))
        assertFalse(Auth.verify(Auth.newSecret(), n, TestTls.a.fingerprint, Auth.CLIENT, p))
        assertFalse(Auth.verify(key, n, TestTls.b.fingerprint, Auth.CLIENT, p))
        assertFalse(Auth.verify(key, n, TestTls.a.fingerprint, Auth.SERVER, p))
        assertFalse(Auth.verify(key, n, TestTls.a.fingerprint, Auth.CLIENT, ""))
    }

    @Test fun passkeyCommitmentsOpenOnlyToTheCommittedBitRoleRoundAndCertificate() {
        val fp = TestTls.a.fingerprint
        val n = Passkey.newNonce()
        val c = Passkey.commit(Passkey.PHONE, 3, n, fp, 1)
        val nh = Passkey.hex(n)
        assertTrue(Passkey.opens(c, Passkey.PHONE, 3, nh, fp, 1))
        assertFalse(Passkey.opens(c, Passkey.PHONE, 3, nh, fp, 0))
        assertFalse(Passkey.opens(c, Passkey.TV, 3, nh, fp, 1))
        assertFalse(Passkey.opens(c, Passkey.PHONE, 4, nh, fp, 1))
        assertFalse(Passkey.opens(c, Passkey.PHONE, 3, nh, TestTls.b.fingerprint, 1))
        assertFalse(Passkey.opens(c, Passkey.PHONE, 3, "zz", fp, 1))
        assertFalse(Passkey.opens(c, Passkey.PHONE, 3, Passkey.hex(Passkey.newNonce()), fp, 1))
    }

    @Test fun codesAreSixDigitsAndCoverAllTwentyBits() {
        repeat(50) { assertTrue(Passkey.isCode(Auth.newCode())) }
        assertEquals(1, Passkey.bit("999999", 19)) // 999999 needs all 20 bits
        assertEquals(0, Passkey.bit("000001", 1))
        assertNotEquals(Auth.newSecret(), Auth.newSecret())
        assertEquals(Credential("0011223344556677", "ab".repeat(32), "cd".repeat(32)),
            Credential.decode(Credential("0011223344556677", "ab".repeat(32), "cd".repeat(32)).encode()))
        assertNull(Credential.decode("x:y:z"))
    }
}
