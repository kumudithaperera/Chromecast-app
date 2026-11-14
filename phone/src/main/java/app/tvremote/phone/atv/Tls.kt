package app.tvremote.phone.atv

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The phone's identity towards the TV: an RSA key and a self-signed
 * certificate. The TV remembers this certificate after pairing, so it must
 * be kept for as long as the phone should stay paired.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    fun save(file: File) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf(certificate))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { ks.store(it, PASSWORD) }
        if (!tmp.renameTo(file)) throw java.io.IOException("Could not save client certificate")
    }

    /** Always offers our certificate, whatever CA names the TV asks for. */
    fun keyManager(): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = privateKey
    }

    companion object {
        private const val ALIAS = "client"
        private val PASSWORD = "tvremote".toCharArray()

        fun loadOrCreate(file: File, commonName: String = "atvremote"): ClientIdentity {
            if (file.exists()) {
                runCatching { return load(file) }
                // A corrupt file is replaced; the TV will ask to pair again.
            }
            return generate(commonName).also { it.save(file) }
        }

        fun load(file: File): ClientIdentity {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, PASSWORD) }
            val key = ks.getKey(ALIAS, PASSWORD) as PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            return ClientIdentity(key, cert)
        }

        /** Same shape of certificate as the androidtvremote2 Python library, which TVs accept. */
        fun generate(commonName: String): ClientIdentity {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val keyPair = kpg.generateKeyPair()
            val name = X500Name("CN=$commonName")
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - day),
                Date(now + 3650 * day),
                name,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, false, BasicConstraints(0))
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, commonName)),
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return ClientIdentity(keyPair.private, cert)
        }
    }
}

fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

/**
 * The TV's certificate is self-signed, so normal CA checks can't apply.
 * Instead we remember its fingerprint the first time (trust on first use)
 * and refuse a different certificate afterwards.
 */
class PinningTrustManager(private val expectedFingerprint: String?) : X509TrustManager {
    @Volatile var serverCertificate: X509Certificate? = null
        private set
    @Volatile var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
        serverCertificate = leaf
        if (expectedFingerprint != null && leaf.sha256Fingerprint() != expectedFingerprint) {
            mismatch = true
            throw CertificateException("TV certificate changed")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client mode only")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The TV was reachable but the TLS handshake failed. When the TV rejects
 * our certificate this can surface as a TLS alert or as a plain broken
 * pipe / reset, so both are reported as this one type.
 */
class TlsHandshakeException(cause: Throwable) : java.io.IOException("TLS handshake with the TV failed", cause)

object TlsSockets {
    const val CONNECT_TIMEOUT_MS = 5_000

    /**
     * @throws java.io.IOException when the TV can't be reached at all.
     * @throws TlsHandshakeException when it was reached but TLS failed.
     */
    fun connect(host: String, port: Int, identity: ClientIdentity, trust: X509TrustManager): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(identity.keyManager()), arrayOf(trust), SecureRandom())
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        try {
            raw.soTimeout = 10_000
            val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.startHandshake()
            return ssl
        } catch (e: java.io.IOException) {
            runCatching { raw.close() }
            throw TlsHandshakeException(e)
        }
    }
}
