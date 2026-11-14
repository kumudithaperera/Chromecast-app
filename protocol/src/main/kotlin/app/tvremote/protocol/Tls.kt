package app.tvremote.protocol

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** TLS versions the control link accepts. Anything older is refused. */
val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

/** SHA-256 of the certificate, as lowercase hex. This is the TV's identity on the control link. */
fun X509Certificate.certFingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

/**
 * Trusts exactly one certificate. The TV's certificate is self-signed, so
 * normal CA checks can't apply; instead the phone remembers the fingerprint
 * the first time a PIN pairing succeeds and refuses any other certificate
 * afterwards. With [expectedFingerprint] null (first pairing) any certificate
 * is accepted for this one handshake, and the PIN proof that follows is bound
 * to the fingerprint seen here.
 */
class PinnedTrust(private val expectedFingerprint: String?) : X509TrustManager {
    @Volatile var seenFingerprint: String? = null
        private set

    /** True when the TV presented a certificate other than the one we expected. */
    @Volatile var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
        val fp = leaf.certFingerprint()
        seenFingerprint = fp
        if (expectedFingerprint != null && !MessageDigest.isEqual(fp.toByteArray(), expectedFingerprint.toByteArray())) {
            mismatch = true
            throw CertificateException("TV certificate changed")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client mode only")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
