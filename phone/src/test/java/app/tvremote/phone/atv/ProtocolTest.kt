package app.tvremote.phone.atv

import app.tvremote.phone.proto.Framing
import app.tvremote.phone.proto.ProtoMessage
import app.tvremote.phone.proto.ProtoWriter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class ProtocolTest {

    // ---- wire format ----

    @Test
    fun keyMessageMatchesProtobufEncoding() {
        // RemoteMessage{remote_key_inject{key_code: 19, direction: SHORT}}
        val bytes = RemoteMessages.key(Keys.DPAD_UP)
        assertArrayEquals(byteArrayOf(0x52, 0x04, 0x08, 0x13, 0x10, 0x03), bytes)
    }

    @Test
    fun framingRoundTrip() {
        val out = ByteArrayOutputStream()
        val big = ByteArray(300) { it.toByte() }
        Framing.write(out, byteArrayOf(1, 2, 3))
        Framing.write(out, big)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), Framing.read(input))
        assertArrayEquals(big, Framing.read(input))
        assertNull(Framing.read(input))
    }

    @Test
    fun negativeInt32AndNestedMessagesDecode() {
        val bytes = ProtoWriter().int(1, -5).message(2) { string(3, "hi") }.toByteArray()
        val msg = ProtoMessage.parse(bytes)
        assertEquals(-5, msg.int(1))
        assertEquals("hi", msg.message(2)?.string(3))
    }

    // ---- pairing secret, checked against Python's androidtvremote2 formula ----

    private fun rsaKey(modulus: BigInteger, exponent: BigInteger): RSAPublicKey =
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey

    @Test
    fun pairingSecretMatchesPythonReference() {
        val e = BigInteger.valueOf(65537)
        val base = BigInteger.TWO.pow(2047)
        val client = rsaKey(base + BigInteger.valueOf(12345), e)
        val server = rsaKey(base + BigInteger.valueOf(999), e)
        // From python3: hashlib.sha256(...) over the same numbers, nonce ABCD.
        val expected = "840af94ccad149156fac75666702b4bc3937302daa56d3e796fd8cee7bac2389"
        val secret = PairingSecret.compute(client, server, "84abcd")
        assertEquals(expected, secret!!.joinToString("") { "%02x".format(it) })
        assertNull("wrong check byte must be caught locally", PairingSecret.compute(client, server, "85ABCD"))
    }

    // ---- loopback against a fake TV over real TLS ----

    private val tvIdentity = ClientIdentity.generate("faketv")
    private val phoneIdentity = ClientIdentity.generate("atvremote")
    private val servers = mutableListOf<SSLServerSocket>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    private class CapturingTrust(private val accept: Boolean) : X509TrustManager {
        @Volatile var client: X509Certificate? = null
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            client = chain?.firstOrNull()
            if (!accept) throw CertificateException("unknown client")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun fakeTvServer(trust: X509TrustManager): SSLServerSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(tvIdentity.keyManager()), arrayOf(trust), SecureRandom())
        val server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.needClientAuth = true
        servers += server
        return server
    }

    private fun outer(block: ProtoWriter.() -> Unit, status: Long = 200) =
        ProtoWriter().varint(1, 2).varint(2, status).apply(block).toByteArray()

    private fun SSLSocket.readMsg() = ProtoMessage.parse(Framing.read(inputStream)!!)
    private fun SSLSocket.writeMsg(bytes: ByteArray) = Framing.write(outputStream, bytes)

    @Test
    fun pairingHandshakeCompletesWithCorrectCode() {
        val trust = CapturingTrust(accept = true)
        val server = fakeTvServer(trust)
        val code = CompletableFuture<String>()
        val serverError = CompletableFuture<Throwable?>()

        thread {
            try {
                (server.accept() as SSLSocket).use { s ->
                    s.startHandshake()
                    val req = s.readMsg()
                    assertEquals("atvremote", req.message(Polo.F_PAIRING_REQUEST)?.string(1))
                    assertEquals("Test phone", req.message(Polo.F_PAIRING_REQUEST)?.string(2))
                    s.writeMsg(outer({ message(Polo.F_PAIRING_REQUEST_ACK) { string(1, "Fake TV") } }))
                    assertTrue(s.readMsg().has(Polo.F_OPTIONS))
                    s.writeMsg(outer({ message(Polo.F_OPTIONS) { varint(3, 1) } }))
                    val config = s.readMsg().message(Polo.F_CONFIGURATION)!!
                    assertEquals(3, config.message(1)!!.int(1)) // hexadecimal
                    assertEquals(6, config.message(1)!!.int(2))
                    s.writeMsg(outer({ message(Polo.F_CONFIGURATION_ACK) {} }))

                    // What a real TV does: hash both keys with a random nonce and show the code.
                    val clientKey = trust.client!!.publicKey as RSAPublicKey
                    val serverKey = tvIdentity.certificate.publicKey as RSAPublicKey
                    val nonce = byteArrayOf(0x3c, 0x7f)
                    val md = MessageDigest.getInstance("SHA-256")
                    listOf(clientKey.modulus, clientKey.publicExponent, serverKey.modulus, serverKey.publicExponent)
                        .forEach { md.update(PairingSecret.unsignedHexBytes(it)) }
                    md.update(nonce)
                    val hash = md.digest()
                    code.complete("%02X%02X%02X".format(hash[0], nonce[0], nonce[1]))

                    val secret = s.readMsg().message(Polo.F_SECRET)!!.bytes(1)!!
                    assertArrayEquals(hash, secret)
                    s.writeMsg(outer({ message(Polo.F_SECRET_ACK) { bytes(1, secret) } }))
                }
                serverError.complete(null)
            } catch (t: Throwable) {
                serverError.complete(t)
                code.completeExceptionally(t)
            }
        }

        PairingSession("127.0.0.1", phoneIdentity, "Test phone", server.localPort).use { session ->
            session.start()
            val shown = code.get(10, TimeUnit.SECONDS)
            // A typo in the first byte is caught before anything is sent.
            val typo = (shown.substring(0, 2).toInt(16) xor 1).let { "%02X".format(it) } + shown.substring(2)
            try {
                session.finish(typo)
                fail("expected WrongCode")
            } catch (_: PairingException.WrongCode) {
            }
            session.finish(shown.lowercase())
        }
        serverError.get(10, TimeUnit.SECONDS)?.let { throw it }
    }

    @Test
    fun remoteSessionConfiguresAnswersPingsAndSendsKeys() {
        val server = fakeTvServer(CapturingTrust(accept = true))
        val ready = CountDownLatch(1)
        val volume = CompletableFuture<Triple<Int, Int, Boolean>>()
        val app = CompletableFuture<String>()
        val serverError = CompletableFuture<Throwable?>()
        lateinit var session: RemoteSession

        thread {
            try {
                (server.accept() as SSLSocket).use { s ->
                    s.startHandshake()
                    val supported = Features.WANTED or Features.VOICE
                    s.writeMsg(ProtoWriter().message(1) {
                        int(1, supported)
                        message(2) { string(1, "Chromecast"); string(2, "Google") }
                    }.toByteArray())
                    val cfg = s.readMsg().message(RemoteMessages.F_CONFIGURE)!!
                    assertEquals(Features.WANTED, cfg.int(1))
                    assertEquals("atvremote", cfg.message(2)!!.string(5))

                    s.writeMsg(ProtoWriter().message(2) { int(1, 0) }.toByteArray())
                    assertEquals(Features.WANTED, s.readMsg().message(RemoteMessages.F_SET_ACTIVE)!!.int(1))

                    s.writeMsg(ProtoWriter().message(8) { int(1, 7); int(2, 9) }.toByteArray())
                    assertEquals(7, s.readMsg().message(RemoteMessages.F_PING_RESPONSE)!!.int(1))

                    s.writeMsg(ProtoWriter().message(20) { message(1) { string(12, "com.google.android.youtube.tv") } }.toByteArray())
                    s.writeMsg(ProtoWriter().message(50) { int(6, 100); int(7, 30); bool(8, false) }.toByteArray())
                    s.writeMsg(ProtoWriter().message(40) { bool(1, true) }.toByteArray())

                    val key = s.readMsg().message(RemoteMessages.F_KEY_INJECT)!!
                    assertEquals(Keys.DPAD_UP, key.int(1))
                    assertEquals(Direction.SHORT, key.int(2))

                    val link = s.readMsg().message(RemoteMessages.F_APP_LINK_LAUNCH)!!
                    assertEquals("https://www.youtube.com", link.string(1))

                    val text = s.readMsg().message(RemoteMessages.F_IME_BATCH_EDIT)!!
                    val status = text.message(3)!!.message(2)!!
                    assertEquals("hello", status.string(3))
                    assertEquals(4, status.int(1))
                }
                serverError.complete(null)
            } catch (t: Throwable) {
                serverError.complete(t)
            }
        }

        session = RemoteSession("127.0.0.1", phoneIdentity, null, object : RemoteSession.Listener {
            override fun onReady() {
                ready.countDown()
            }
            override fun onVolume(level: Int, max: Int, muted: Boolean) {
                volume.complete(Triple(level, max, muted))
            }
            override fun onCurrentApp(packageName: String) {
                app.complete(packageName)
            }
        }, server.localPort)

        val runner = thread { runCatching { session.run() } }
        assertTrue("TV never became ready", ready.await(10, TimeUnit.SECONDS))
        assertEquals(Triple(30, 100, false), volume.get(5, TimeUnit.SECONDS))
        assertEquals("com.google.android.youtube.tv", app.get(5, TimeUnit.SECONDS))
        session.sendKey(Keys.DPAD_UP)
        session.launchLink("https://www.youtube.com")
        session.sendText("hello")
        serverError.get(10, TimeUnit.SECONDS)?.let { throw it }
        session.close()
        runner.join(5000)
    }

    @Test
    fun volumeControlFlagFollowsWhatTheTvSends() {
        fun volume(build: ProtoWriter.() -> Unit): ProtoMessage =
            ProtoMessage.parse(ProtoWriter().message(RemoteMessages.F_SET_VOLUME_LEVEL) { build() }.toByteArray())
                .message(RemoteMessages.F_SET_VOLUME_LEVEL)!!

        // A Chromecast with Google TV, HDMI-CEC volume control off: no level, no maximum, field 4 = 0.
        assertFalse(volumeControlAvailable(volume { int(1, 23); int(2, 9); string(3, "chromecast"); int(4, 0) }))
        // The same Chromecast with it switched on.
        assertTrue(volumeControlAvailable(volume { int(1, 23); int(2, 9); string(3, "chromecast"); int(4, 1) }))
        // A TV that reports its own level and maximum.
        assertTrue(volumeControlAvailable(volume { int(6, 100); int(7, 30); bool(8, false) }))
    }

    @Test
    fun unknownPhoneIsReportedAsNotPaired() {
        val server = fakeTvServer(CapturingTrust(accept = false))
        thread { runCatching { (server.accept() as SSLSocket).use { it.startHandshake(); it.inputStream.read() } } }
        val session = RemoteSession("127.0.0.1", phoneIdentity, null, object : RemoteSession.Listener {}, server.localPort)
        try {
            session.run()
            fail("expected NotPaired")
        } catch (_: RemoteException.NotPaired) {
        }
    }

    @Test
    fun changedTvCertificateIsRefused() {
        val server = fakeTvServer(CapturingTrust(accept = true))
        thread { runCatching { (server.accept() as SSLSocket).use { it.startHandshake() } } }
        val session = RemoteSession("127.0.0.1", phoneIdentity, "00".repeat(32), object : RemoteSession.Listener {}, server.localPort)
        try {
            session.run()
            fail("expected CertificateChanged")
        } catch (_: RemoteException.CertificateChanged) {
        }
    }

    @Test
    fun identitySurvivesSaveAndLoad() {
        val file = java.io.File.createTempFile("client", ".p12")
        try {
            phoneIdentity.save(file)
            val loaded = ClientIdentity.load(file)
            assertEquals(phoneIdentity.certificate, loaded.certificate)
            assertEquals(phoneIdentity.certificate.sha256Fingerprint(), loaded.certificate.sha256Fingerprint())
        } finally {
            file.delete()
        }
    }
}
