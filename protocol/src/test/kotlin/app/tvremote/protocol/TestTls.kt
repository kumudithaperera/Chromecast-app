package app.tvremote.protocol

import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** A throwaway TV identity for tests, made with the JDK's own keytool (no extra dependency). */
class TestIdentity(val ssl: SSLContext, val fingerprint: String)

object TestTls {
    private const val PASSWORD = "changeit"

    val a: TestIdentity by lazy { create("a") }
    val b: TestIdentity by lazy { create("b") }

    private fun create(name: String): TestIdentity {
        val dir = File(System.getProperty("java.io.tmpdir"), "tvremote-test-${System.nanoTime()}-$name").apply { mkdirs() }
        val file = File(dir, "id.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val proc = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "tv", "-keyalg", "EC", "-groupname", "secp256r1",
            "-sigalg", "SHA256withECDSA", "-dname", "CN=Test TV $name", "-validity", "3650",
            "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD, "-keystore", file.path,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "keytool failed: $out" }

        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, PASSWORD.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD.toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        val fp = (ks.getCertificate("tv") as X509Certificate).certFingerprint()
        dir.deleteRecursively()
        return TestIdentity(ctx, fp)
    }

    /** A raw TLS client socket that trusts any certificate, for tests that speak the wire format by hand. */
    fun rawClient(port: Int): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(PinnedTrust(null)), SecureRandom())
        val s = ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        s.startHandshake()
        return s
    }
}
