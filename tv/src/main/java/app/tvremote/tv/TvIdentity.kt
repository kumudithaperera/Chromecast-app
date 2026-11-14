package app.tvremote.tv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.tvremote.protocol.certFingerprint
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The TV browser's TLS identity: an EC key that lives in the Android Keystore
 * (it can't be read out of the device) with a self-signed certificate. It is
 * made on first use and kept until the app is uninstalled or its data cleared.
 * The phone pins this certificate's fingerprint when it pairs.
 */
object TvIdentity {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "tv_browser_tls_v2"
    private const val OLD_ALIAS = "tv_browser_tls"
    private const val TEN_YEARS_MS = 3650L * 24 * 60 * 60 * 1000

    class Loaded(val ssl: SSLContext, val fingerprint: String)

    @Synchronized
    fun load(): Loaded {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        // The first version of this key didn't allow the digest mode Android's TLS stack signs with.
        if (ks.containsAlias(OLD_ALIAS)) ks.deleteEntry(OLD_ALIAS)
        if (!ks.containsAlias(ALIAS)) generate()
        return try {
            build(ks)
        } catch (e: Exception) {
            // A key that can no longer be used is replaced; phones will have to pair again.
            ks.deleteEntry(ALIAS)
            generate()
            build(ks)
        }
    }

    private fun build(ks: KeyStore): Loaded {
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return Loaded(ctx, cert.certFingerprint())
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // Conscrypt hashes the handshake itself and asks the keystore to sign the raw digest (NONEwithECDSA).
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setCertificateSubject(X500Principal("CN=TV Browser"))
            .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
            .setCertificateNotAfter(Date(now + TEN_YEARS_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(spec)
            generateKeyPair()
        }
    }
}
