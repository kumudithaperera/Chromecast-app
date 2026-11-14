package app.tvremote.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Challenge-response with the shared PIN, so the PIN itself never crosses the
 * network. This stops casual and accidental connections. It does not stop
 * someone who records the handshake and guesses the PIN offline; that needs TLS.
 */
object Auth {
    private val random = SecureRandom()

    fun newNonce(): String = hex(ByteArray(16).also(random::nextBytes))

    fun newPin(): String = String.format("%06d", random.nextInt(1_000_000))

    fun proof(pin: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(nonce.toByteArray(Charsets.UTF_8)))
    }

    fun verify(pin: String, nonce: String, proof: String): Boolean =
        MessageDigest.isEqual(
            proof(pin, nonce).toByteArray(Charsets.UTF_8),
            proof.toByteArray(Charsets.UTF_8),
        )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
