package app.tvremote.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class LineTooLongException : IOException("line longer than $MAX_LINE bytes")

/** Reads '\n'-terminated UTF-8 lines and refuses any longer than [MAX_LINE]. */
class LineReader(private val input: InputStream) {
    private val buf = ByteArrayOutputStream()

    /** Returns null at end of stream. */
    fun readLine(): String? {
        buf.reset()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buf.size() >= MAX_LINE) throw LineTooLongException()
            buf.write(b)
        }
    }
}
