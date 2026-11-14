package app.tvremote.phone.proto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf encoder. The TV protocol only uses varints, strings,
 * bytes and nested messages, so a full protobuf runtime isn't needed.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        writeRawVarint(out, value)
        return this
    }

    /** int32 fields: negative values sign-extend to 10 bytes, as protobuf requires. */
    fun int(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LEN)
        writeRawVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = writeRawVarint(out, ((field shl 3) or wire).toLong())

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5

        fun writeRawVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}

/** Decoded protobuf message: field number to the values seen for it, in order. */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int, default: Long = 0L): Long = (fields[field]?.lastOrNull() as? Long) ?: default

    fun int(field: Int, default: Int = 0): Int = (fields[field]?.lastOrNull() as? Long)?.toInt() ?: default

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    fun messages(field: Int): List<ProtoMessage> =
        fields[field].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }

    val fieldNumbers: Set<Int> get() = fields.keys

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw IOException("Varint too long")
                }
            }

            fun take(n: Int): ByteArray {
                if (n < 0 || pos + n > data.size) throw IOException("Truncated field")
                val slice = data.copyOfRange(pos, pos + n)
                pos += n
                return slice
            }

            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 0x7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> readVarint()
                    ProtoWriter.WIRE_LEN -> take(readVarint().toInt())
                    ProtoWriter.WIRE_FIXED64 -> take(8)
                    ProtoWriter.WIRE_FIXED32 -> take(4)
                    else -> throw IOException("Unsupported wire type in key $key")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

/** Messages on the wire are prefixed with their length as a varint. */
object Framing {
    private const val MAX_MESSAGE_BYTES = 1 shl 20

    fun write(out: OutputStream, message: ByteArray) {
        val buf = ByteArrayOutputStream(message.size + 5)
        ProtoWriter.writeRawVarint(buf, message.size.toLong())
        buf.write(message)
        out.write(buf.toByteArray())
        out.flush()
    }

    /** Returns null when the peer closed the connection cleanly between messages. */
    fun read(input: InputStream): ByteArray? {
        var length = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (shift == 0) return null
                throw EOFException("Connection closed inside a length prefix")
            }
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 35) throw IOException("Length prefix too long")
        }
        if (length > MAX_MESSAGE_BYTES) throw IOException("Message too large: $length bytes")
        val message = ByteArray(length.toInt())
        var read = 0
        while (read < message.size) {
            val n = input.read(message, read, message.size - read)
            if (n < 0) throw EOFException("Connection closed inside a message")
            read += n
        }
        return message
    }
}
