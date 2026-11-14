package app.tvremote.phone.atv

import app.tvremote.phone.proto.Framing
import app.tvremote.phone.proto.ProtoMessage
import app.tvremote.phone.proto.ProtoWriter
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Pairing ("polo") protocol on TCP 6467. Message layout follows polo.proto
 * from AOSP's google-tv-pairing-protocol, as used by androidtvremote2.
 */
object Polo {
    const val PORT = 6467
    const val PROTOCOL_VERSION = 2L
    const val STATUS_OK = 200L
    const val STATUS_BAD_SECRET = 402L

    // OuterMessage fields
    const val F_VERSION = 1
    const val F_STATUS = 2
    const val F_PAIRING_REQUEST = 10
    const val F_PAIRING_REQUEST_ACK = 11
    const val F_OPTIONS = 20
    const val F_CONFIGURATION = 30
    const val F_CONFIGURATION_ACK = 31
    const val F_SECRET = 40
    const val F_SECRET_ACK = 41

    private const val ENCODING_HEXADECIMAL = 3L
    private const val ROLE_INPUT = 1L
    private const val SYMBOL_LENGTH = 6L

    private fun outer(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter()
        .varint(F_VERSION, PROTOCOL_VERSION)
        .varint(F_STATUS, STATUS_OK)
        .apply(block)
        .toByteArray()

    fun pairingRequest(clientName: String) = outer {
        message(F_PAIRING_REQUEST) {
            string(1, "atvremote")
            string(2, clientName)
        }
    }

    fun options() = outer {
        message(F_OPTIONS) {
            message(1) { // input_encodings
                varint(1, ENCODING_HEXADECIMAL)
                varint(2, SYMBOL_LENGTH)
            }
            varint(3, ROLE_INPUT) // preferred_role
        }
    }

    fun configuration() = outer {
        message(F_CONFIGURATION) {
            message(1) { // encoding
                varint(1, ENCODING_HEXADECIMAL)
                varint(2, SYMBOL_LENGTH)
            }
            varint(2, ROLE_INPUT) // client_role
        }
    }

    fun secret(secret: ByteArray) = outer {
        message(F_SECRET) { bytes(1, secret) }
    }
}

object PairingSecret {
    /** True if [code] looks like a TV pairing code: 6 hex characters. */
    fun isWellFormed(code: String): Boolean = code.length == 6 && code.all { it.isHexDigitChar() }

    /**
     * secret = SHA-256(client modulus, client exponent, server modulus,
     * server exponent, last 2 bytes of the code). The first byte of the
     * code must equal the first byte of the hash, which lets us catch a
     * mistyped code before sending it.
     *
     * Returns null when the code doesn't match these certificates.
     */
    fun compute(client: RSAPublicKey, server: RSAPublicKey, rawCode: String): ByteArray? {
        val code = rawCode.trim().uppercase()
        require(isWellFormed(code)) { "Pairing code must be 6 hex characters" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsignedHexBytes(client.modulus))
        md.update(unsignedHexBytes(client.publicExponent))
        md.update(unsignedHexBytes(server.modulus))
        md.update(unsignedHexBytes(server.publicExponent))
        md.update(hexToBytes(code.substring(2)))
        val hash = md.digest()
        val check = code.substring(0, 2).toInt(16)
        return if ((hash[0].toInt() and 0xFF) == check) hash else null
    }

    /** Big-endian bytes with no sign byte, matching Python's bytes.fromhex(f"{n:X}"). */
    fun unsignedHexBytes(value: BigInteger): ByteArray {
        var hex = value.toString(16)
        if (hex.length % 2 == 1) hex = "0$hex"
        return hexToBytes(hex)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
}

sealed class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /**
     * @param canRetry true when the mistake was caught on the phone, so the
     * TV is still waiting and the user can simply type the code again.
     */
    class WrongCode(val canRetry: Boolean) :
        PairingException("That code doesn't match. Check the code on the TV and try again.")
    class Rejected(status: Long) : PairingException("The TV rejected pairing (status $status).")
    class Unexpected(what: String) : PairingException("Unexpected reply from the TV: $what")
}

/**
 * One pairing attempt. [start] makes the TV show a code; [finish] sends the
 * code back. Both block, so call them off the main thread.
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String,
    private val port: Int = Polo.PORT,
) : Closeable {
    private var socket: SSLSocket? = null
    private var serverKey: RSAPublicKey? = null

    fun start() {
        val trust = PinningTrustManager(expectedFingerprint = null)
        val s = TlsSockets.connect(host, port, identity, trust)
        socket = s
        serverKey = trust.serverCertificate?.publicKey as? RSAPublicKey
            ?: throw PairingException.Unexpected("TV certificate is not RSA")
        s.soTimeout = 10_000
        send(Polo.pairingRequest(clientName))
        expect(Polo.F_PAIRING_REQUEST_ACK)
        send(Polo.options())
        expect(Polo.F_OPTIONS)
        send(Polo.configuration())
        expect(Polo.F_CONFIGURATION_ACK)
        // The TV is now showing the code. Waiting for the user can take a while.
        s.soTimeout = 0
    }

    fun finish(code: String) {
        val clientKey = identity.certificate.publicKey as RSAPublicKey
        val secret = PairingSecret.compute(clientKey, serverKey ?: error("start() first"), code)
            ?: throw PairingException.WrongCode(canRetry = true)
        socket?.soTimeout = 10_000
        send(Polo.secret(secret))
        expect(Polo.F_SECRET_ACK)
    }

    private fun send(bytes: ByteArray) {
        val s = socket ?: error("Not connected")
        Framing.write(s.outputStream, bytes)
    }

    private fun expect(field: Int): ProtoMessage {
        val s = socket ?: error("Not connected")
        val raw = Framing.read(s.inputStream) ?: throw IOException("The TV closed the pairing connection")
        val msg = ProtoMessage.parse(raw)
        val status = msg.long(Polo.F_STATUS)
        if (status == Polo.STATUS_BAD_SECRET) throw PairingException.WrongCode(canRetry = false)
        if (status != Polo.STATUS_OK) throw PairingException.Rejected(status)
        if (!msg.has(field)) throw PairingException.Unexpected("fields ${msg.fieldNumbers}, wanted $field")
        return msg.message(field)!!
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
