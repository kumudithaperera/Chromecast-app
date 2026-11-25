package app.tvremote.protocol

import java.net.URI
import java.net.URLEncoder

const val SEARCH_URL = "https://duckduckgo.com/?q="

private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val HOSTISH = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")

/** True for http and https URLs that have a host. Everything else must never be loaded. */
fun isWebUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
}

/**
 * Turns what someone typed into something to load: a URL, or a search for a
 * phrase. Returns null for input that must not be loaded (javascript:, file:,
 * data:, intent: and similar).
 */
fun normalizeInput(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty()) return null
    if (input.any { it.isWhitespace() }) return search(input)

    if (input.contains("://")) {
        return if (isWebUrl(input)) input else null
    }
    val hostPart = input.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = hostPart.substringBefore(':')
    val port = hostPart.substringAfter(':', "")
    val portOk = port.isEmpty() || (port.all { it.isDigit() } && port.length in 1..5)

    // "localhost:8096" and "192.168.1.5:8096" look like a scheme; treat them as hosts first.
    val local = host.equals("localhost", true) || IPV4.matches(host) || host.endsWith(".local", true)
    if (portOk && (local || HOSTISH.matches(host))) {
        val candidate = (if (local) "http://" else "https://") + input
        return if (isWebUrl(candidate)) candidate else null
    }
    // Anything else with a scheme-like prefix (javascript:alert(1), data:…) is refused.
    if (SCHEME.containsMatchIn(input)) return null
    return search(input)
}

private fun search(q: String) = SEARCH_URL + URLEncoder.encode(q, "UTF-8")

/**
 * Matches a host against a list of blocked domains, including their subdomains.
 *
 * Public lists run to about 100,000 domains, which as a HashSet of Strings would cost the TV
 * 10 MB or more. Each domain is kept as a 64-bit hash in a sorted array instead (8 bytes each),
 * looked up with a binary search. Two different domains sharing a 64-bit hash is vanishingly unlikely.
 */
class HostBlocklist internal constructor(private val hashes: LongArray) {
    constructor(domains: Collection<String>) : this(hashAll(domains.asSequence()))

    /** Number of distinct domains in the list. */
    val size: Int get() = hashes.size

    fun isBlocked(host: String?): Boolean {
        if (hashes.isEmpty()) return false
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (hashes.binarySearch(hash(h)) >= 0) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false // never match a bare TLD
        }
    }

    /** A list holding the domains of both. */
    operator fun plus(other: HostBlocklist): HostBlocklist =
        HostBlocklist((hashes + other.hashes).also { it.sort() }.distinctSorted())

    companion object {

        /**
         * Reads plain domain lists, hosts files ("0.0.0.0 ads.example.com") and simple adblock rules
         * ("||ads.example.com^"). Comments, IP-only lines, cosmetic and exception rules and anything
         * that isn't a plain domain are skipped.
         */
        fun parse(text: String) = parseLines(text.lineSequence())

        /** Same as [parse], for a list read line by line (a big file never has to be one String). */
        fun parseLines(lines: Sequence<String>) = HostBlocklist(hashAll(lines.mapNotNull(::domainOf)))

        /** The domain a list line names, or null. */
        fun domainOf(raw: String): String? {
            var line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("@@") || line.startsWith("[")) return null
            if (line.startsWith("||")) {
                line = line.removePrefix("||")
                if (!line.endsWith("^")) return null
                line = line.removeSuffix("^")
            } else {
                // Hosts file: an address, then the name.
                val gap = line.indexOfFirst { it.isWhitespace() }
                if (gap >= 0) {
                    val rest = line.substring(gap).trimStart()
                    line = rest.substring(0, rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it })
                }
            }
            line = line.lowercase().removePrefix("*.").trimEnd('.')
            if (line.length > 253 || line == "localhost" || line.endsWith(".localhost") || line == "local") return null
            if (!isDomainName(line)) return null
            // A bare IP address is not a domain to block by suffix.
            if (line.all { it.isDigit() || it == '.' }) return null
            return line
        }

        internal fun hashAll(domains: Sequence<String>): LongArray {
            val out = domains
                .map { it.trim().lowercase().trimEnd('.') }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map(::hash)
                .toList()
                .toLongArray()
            out.sort()
            return out.distinctSorted()
        }

        internal fun LongArray.distinctSorted(): LongArray {
            if (size < 2) return this
            var n = 1
            for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
            return if (n == size) this else copyOf(n)
        }

        /** 64-bit FNV-1a over the characters. */
        internal fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

/**
 * The registrable part of a host, to tell "same site" from "different site": the last two labels, or the
 * last three under a country's second-level names ("bbc.co.uk", not "co.uk"). Not the full Public Suffix
 * List, but it keeps one country's sites apart for third-party checks and per-site settings.
 */
fun siteOf(host: String?): String {
    val h = host?.lowercase()?.trimEnd('.') ?: return ""
    if (isIpv4(h) || ':' in h) return h // IP literals are compared whole
    val labels = h.split('.')
    if (labels.size <= 2) return labels.joinToString(".")
    val n = if (labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
    return labels.takeLast(n).joinToString(".")
}

/** Second-level names countries register under ("co.uk", "com.au", "ne.jp"). */
private val SECOND_LEVEL = setOf(
    "ac", "co", "com", "edu", "gob", "go", "gov", "ltd", "mil", "ne", "net", "nic", "nom", "or", "org", "plc", "sch",
)

/**
 * Lowercase labels of letters, digits, "_" and "-", not starting or ending with "-", at least two of them.
 * Checked by hand: filter lists run to hundreds of thousands of lines, and regular expressions are slow on
 * Android (the TV took 40 s to read the lists with them).
 */
internal fun isDomainName(s: String, strictHyphens: Boolean = true): Boolean {
    if (s.isEmpty()) return false
    var labelStart = 0
    var labels = 0
    for (i in 0..s.length) {
        if (i == s.length || s[i] == '.') {
            if (i == labelStart) return false
            if (strictHyphens && (s[labelStart] == '-' || s[i - 1] == '-')) return false
            labels++
            labelStart = i + 1
            continue
        }
        val c = s[i]
        if (!(c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-')) return false
    }
    return labels >= 2
}

/** Four dot-separated groups of one to three digits. By hand: this runs for every request the TV makes. */
internal fun isIpv4(s: String): Boolean {
    var groups = 0
    var digits = 0
    for (c in s) {
        when {
            c in '0'..'9' -> if (++digits > 3) return false
            c == '.' -> {
                if (digits == 0) return false
                groups++
                digits = 0
            }
            else -> return false
        }
    }
    return digits > 0 && groups == 3
}
