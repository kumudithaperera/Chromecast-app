package app.tvremote.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a phone keeps after pairing: an id the TV looks the key up by, a
 * random 256-bit key, and the TV certificate it paired with.
 */
data class Credential(val id: String, val secret: String, val fingerprint: String) {
    fun encode(): String = "$id:$secret:$fingerprint"

    companion object {
        fun decode(s: String?): Credential? {
            val p = s?.split(':') ?: return null
            return if (p.size == 3 && p.all { HEX.matches(it) }) Credential(p[0], p[1], p[2]) else null
        }

        private val HEX = Regex("[0-9a-f]{16,128}")
    }
}

/** The TV's list of paired phones. Implementations must be thread-safe. */
interface PairedPhones {
    fun secret(id: String): String?
    fun add(id: String, secret: String)
}

/**
 * Reconnection proof for a paired phone, run inside TLS. The key is 256 random
 * bits, so seeing a proof gives nothing to guess offline. Each proof covers the
 * TLS certificate fingerprint both sides saw and a role label, so it can't be
 * replayed on another connection or played back as the other side's proof.
 */
object Auth {
    const val CLIENT = "client"
    const val SERVER = "server"

    private val random = SecureRandom()

    fun newNonce(): String = hex(randomBytes(16))

    /** Six decimal digits, shown on the TV for one pairing attempt. */
    fun newCode(): String = String.format("%06d", random.nextInt(1_000_000))

    fun newId(): String = hex(randomBytes(8))

    fun newSecret(): String = hex(randomBytes(32))

    fun proof(secret: String, nonce: String, fingerprint: String, role: String): String =
        hex(hmac(secret.toByteArray(Charsets.UTF_8), "$role|$nonce|$fingerprint"))

    fun verify(secret: String, nonce: String, fingerprint: String, role: String, proof: String): Boolean =
        MessageDigest.isEqual(
            proof(secret, nonce, fingerprint, role).toByteArray(Charsets.UTF_8),
            proof.toByteArray(Charsets.UTF_8),
        )

    internal fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    internal fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/**
 * First pairing, checked one bit of the code at a time, following Bluetooth LE
 * Secure Connections "passkey entry" (Core spec, Vol 3 Part H, 2.3.5.6.3).
 *
 * For each of the 20 bits of the code, the phone commits to its bit, then the
 * TV commits to its bit, then the phone opens its commitment and the TV checks
 * it, then the TV opens its commitment and the phone checks it. A side that
 * doesn't know the code has to commit to a guess before it can learn the
 * other side's bit, so it is caught at the first wrong guess, having learnt
 * at most one bit. The TV picks a new code for every attempt, so those single
 * bits don't add up across attempts. Every commitment covers the TLS
 * certificate fingerprint the committing side sees: a machine in the middle
 * (with its own certificate) can't relay one side's commitments to the other.
 */
object Passkey {
    const val BITS = 20
    const val PHONE = "phone"
    const val TV = "tv"

    private val CODE = Regex("[0-9]{6}")

    fun isCode(s: String) = CODE.matches(s)

    fun bit(code: String, i: Int): Int = (code.toInt() shr i) and 1

    fun newNonce(): ByteArray = Auth.randomBytes(16)

    fun commit(role: String, i: Int, nonce: ByteArray, fingerprint: String, bit: Int): String =
        Auth.hex(Auth.hmac(nonce, "pair|$role|$i|$fingerprint|$bit"))

    /** True when [nonceHex] opens [commitment] to [bit]. */
    fun opens(commitment: String, role: String, i: Int, nonceHex: String, fingerprint: String, bit: Int): Boolean {
        val nonce = unhex(nonceHex) ?: return false
        if (nonce.size != 16) return false
        return MessageDigest.isEqual(
            commit(role, i, nonce, fingerprint, bit).toByteArray(Charsets.UTF_8),
            commitment.toByteArray(Charsets.UTF_8),
        )
    }

    fun hex(b: ByteArray) = Auth.hex(b)

    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.any { it !in "0123456789abcdef" }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
