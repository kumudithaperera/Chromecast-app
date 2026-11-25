package app.tvremote.protocol

import app.tvremote.protocol.HostBlocklist.Companion.distinctSorted
import java.util.regex.PatternSyntaxException

/**
 * Request types, as bits of a mask. A rule has the mask of the types it applies to. WebView does not say
 * what a request is for, so [guess] works out the types it may be.
 */
object ReqType {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLESHEET = 4
    const val XHR = 8
    const val SUBDOCUMENT = 16
    const val MEDIA = 32
    const val FONT = 64
    const val OBJECT = 128
    const val PING = 256
    const val WEBSOCKET = 512
    const val OTHER = 1024

    /** The page itself (main frame). Rules apply to it only when they say `$document`. */
    const val DOCUMENT = 2048

    /** A new window a page opens. Rules apply to it only when they say `$popup`. */
    const val POPUP = 4096

    /** What a rule without type options applies to: everything but the page itself and popups. */
    const val DEFAULT = DOCUMENT - 1
    const val ALL = POPUP * 2 - 1

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
    private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA_EXT = setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "m4a", "m4v", "mp3", "aac", "ogg", "oga", "vtt")

    /**
     * The types a request may be, from its Accept header, the extension of its path and whether it asks
     * for a byte range (video and audio do). When unsure the answer is several types: a rule for one of
     * them applies, and so does an exception for one of them.
     */
    fun guess(accept: String?, url: String, isMainFrame: Boolean, hasRange: Boolean = false): Int {
        if (isMainFrame) return DOCUMENT
        val a = accept?.lowercase().orEmpty()
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/')
        val ext = if ('.' in last) last.substringAfterLast('.').lowercase() else ""
        return when {
            a.startsWith("text/html") || a.contains("application/xhtml") -> SUBDOCUMENT
            a.startsWith("image/") -> IMAGE
            a.startsWith("text/css") -> STYLESHEET
            ext == "js" || ext == "mjs" -> SCRIPT
            ext == "css" -> STYLESHEET
            ext in IMAGE_EXT -> IMAGE
            ext in FONT_EXT -> FONT
            ext in MEDIA_EXT || hasRange -> MEDIA
            ext == "json" -> XHR
            // "*/*" without a telling extension: a script, a fetch or something else. Never a ping, or
            // "*$ping,third-party" would block every third-party script.
            else -> SCRIPT or XHR or OTHER
        }
    }
}

/** Page-wide exceptions found by [AdFilter.pageFlags]. */
object PageFlags {
    /** `@@…$document`: nothing is blocked or hidden on this page. */
    const val NO_BLOCKING = 1

    /** `@@…$elemhide`: no element hiding. */
    const val NO_ELEMHIDE = 2

    /** `@@…$generichide`: only site-specific element hiding. */
    const val NO_GENERICHIDE = 4

    /** `@@…$specifichide`: only generic element hiding. */
    const val NO_SPECIFICHIDE = 8
}

/** One scriptlet to run on a page: its canonical name and arguments, from a `##+js(…)` rule. */
data class ScriptletCall(val name: String, val args: List<String>) {
    /** Identifies the call for `#@#+js(…)` exceptions: name and arguments joined by NUL. */
    val key: String get() = (listOf(name) + args).joinToString("\u0000")
}

/**
 * Every scriptlet rule, for a browser that has to put them in place before it knows which page will load.
 * [specific] and [exceptions] are filed under a host, a parent domain or "name.*" (see [AdFilter.lookupKeys]);
 * an exception holds [ScriptletCall.key]s, "" meaning every scriptlet.
 */
class ScriptletTable(
    val specific: Map<String, List<Pair<ScriptletCall, Array<String>?>>>,
    val exceptions: Map<String, Set<String>>,
    val generic: List<ScriptletCall>,
)

/**
 * An ad blocker for the common part of the Adblock Plus / uBlock Origin filter syntax, as EasyList,
 * EasyPrivacy and uBlock Origin's lists use it:
 *  - request rules: `||host^`, `|` anchors, `*` and `^`, `/regex/`, `@@` exceptions, and the options for
 *    party, types, `domain=`, `important`, `badfilter`, and the page exceptions (`document`, `elemhide`,
 *    `generichide`, `specifichide`);
 *  - element hiding: `##` and `#@#` with plain CSS selectors (`:has()` included), per site and generic;
 *  - scriptlets: `##+js(…)` and `#@#+js(…)`, for the scriptlets this browser has its own version of.
 * A rule with an option or a selector it doesn't understand is dropped, never guessed at, so an unknown
 * rule can't block more than its authors meant.
 *
 * Immutable once built and safe to use from any thread: WebView asks from several at once.
 */
class AdFilter private constructor(
    private val hosts: HostBlocklist,
    private val hosts3p: HostBlocklist,
    private val important: RuleIndex,
    private val block: RuleIndex,
    private val allow: RuleIndex,
    private val pageRules: Array<NetRule>,
    private val cosmetics: Cosmetics,
    private val scriptlets: Scriptlets,
    /** Rules in use, for the settings screen. */
    val ruleCount: Int,
) {
    /**
     * True when the request should be blocked. [pageHost] is the host of the page that made it, "" when
     * unknown (a service worker); rules that depend on the page are then skipped.
     */
    fun shouldBlock(url: String, host: String, pageHost: String, types: Int): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        val req = Req(url, h, pageHost.lowercase().trimEnd('.'), types)
        if (important.matches(req)) return true
        // Plain host lists block everything from those hosts: pages and popups too.
        val blocked = hosts.isBlocked(h) || (req.thirdParty && hosts3p.isBlocked(h)) || block.matches(req)
        return blocked && !allow.matches(req)
    }

    /** The page-wide exceptions ([PageFlags]) for a page at [pageUrl]. */
    fun pageFlags(pageUrl: String, host: String): Int {
        if (pageRules.isEmpty()) return 0
        val h = host.lowercase().trimEnd('.')
        val req = Req(pageUrl, h, h, ReqType.DOCUMENT)
        var flags = 0
        for (r in pageRules) if (r.matches(req)) flags = flags or r.pageBits
        return flags
    }

    /**
     * CSS that hides ads in a frame of [frameHost]: its site-specific selectors and the generic ones that
     * are not plain classes or ids (those come from [genericCss] as the page shows them). "" for none.
     */
    fun cosmeticCss(frameHost: String, flags: Int): String =
        if (flags and (PageFlags.NO_BLOCKING or PageFlags.NO_ELEMHIDE) != 0) "" else cosmetics.css(frameHost.lowercase(), flags)

    /** CSS for the generic class and id selectors among [tokens] (".name" and "#name") found on a page. */
    fun genericCss(tokens: Collection<String>, frameHost: String, flags: Int): String =
        if (flags and (PageFlags.NO_BLOCKING or PageFlags.NO_ELEMHIDE or PageFlags.NO_GENERICHIDE) != 0) ""
        else cosmetics.genericFor(tokens, frameHost.lowercase())

    /** Request rules with no word to file them under, checked against every request (for tests and tuning). */
    internal val unindexed: Int get() = important.unindexed + block.unindexed + allow.unindexed

    /** The scriptlets to run in a frame of [frameHost]. */
    fun scriptletsFor(frameHost: String): List<ScriptletCall> = scriptlets.forHost(frameHost.lowercase().trimEnd('.'))

    /** Every scriptlet rule, for injecting ahead of time. */
    fun scriptletTable(): ScriptletTable = scriptlets.table()

    /** Builds an [AdFilter] from lists, read line by line so a big list never has to be in memory twice. */
    class Builder {
        private val hostHashes = LongList()
        private val host3pHashes = LongList()
        private val badHostHashes = HashSet<Long>()
        private val rules = ArrayList<Pair<String, NetRule>>()
        private val badRules = HashSet<String>()
        private val cosmetics = Cosmetics.Builder()
        private val scriptlets = Scriptlets.Builder()

        /** A plain list of domains, or a hosts file: every request to them (and their subdomains) is blocked. */
        fun addDomains(lines: Sequence<String>): Builder {
            for (line in lines) HostBlocklist.domainOf(line)?.let { hostHashes.add(HostBlocklist.hash(it)) }
            return this
        }

        /** An Adblock Plus / uBlock Origin filter list. */
        fun addFilterList(lines: Sequence<String>): Builder {
            val skip = ArrayDeque<Boolean>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("!#")) {
                    directive(line, skip)
                    continue
                }
                if (skip.any { it }) continue
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
                if (line.length > MAX_RULE) continue
                addRule(line)
            }
            return this
        }

        private fun directive(line: String, skip: ArrayDeque<Boolean>) {
            when {
                line.startsWith("!#if ") -> skip.addLast(!condition(line.removePrefix("!#if ").trim()))
                line == "!#else" -> if (skip.isNotEmpty()) skip.addLast(!skip.removeLast())
                line == "!#endif" -> skip.removeLastOrNull()
            }
        }

        /** `!#if` conditions as uBlock Origin writes them: names joined by `&&` or `||`, each maybe negated. */
        private fun condition(expr: String): Boolean = expr.split("||").any { part ->
            part.split("&&").all { term ->
                val t = term.trim().removeSurrounding("(", ")").trim()
                if (t.startsWith("!")) t.drop(1).trim() !in TRUE_ENV else t in TRUE_ENV
            }
        }

        private fun addRule(line: String) {
            val at = cosmeticSeparator(line)
            if (at >= 0) {
                val end = line.indexOf('#', at + 1)
                val domains = line.substring(0, at)
                if (domains.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".,~*_-" }) {
                    addCosmetic(domains, line.substring(at, end + 1), line.substring(end + 1))
                    return
                }
            }
            if (line.contains("##") || line.contains("#@#")) return
            addNetwork(line)
        }

        private fun addCosmetic(domainList: String, sep: String, body: String) {
            // Only plain hiding ("##") and its exceptions ("#@#"); "#?#", "#$#" and "#%#" need a full engine.
            if (sep != "##" && sep != "#@#") return
            val exception = sep == "#@#"
            val (include, exclude) = splitDomains(domainList, ',')
            if (body.startsWith("+js(") && body.endsWith(")")) {
                scriptlets.add(body.substring(4, body.length - 1), include, exclude, exception)
                return
            }
            if (body.startsWith("^") || !Cosmetics.isPlainSelector(body)) return
            cosmetics.add(body, include, exclude, exception)
        }

        private fun addNetwork(text: String) {
            var line = text
            val exception = line.startsWith("@@")
            if (exception) line = line.substring(2)
            var options = ""
            val dollar = line.lastIndexOf('$')
            if (dollar >= 0) {
                val tail = line.substring(dollar + 1)
                if (looksLikeOptions(tail)) {
                    options = tail
                    line = line.substring(0, dollar)
                } else if (!(line.startsWith("/") && line.endsWith("/"))) {
                    return // options it can't read, or a regex domain list
                }
            }
            val opts = parseOptions(options, exception) ?: return
            if (opts.badfilter) {
                val key = (if (exception) "@@" else "") + line + opts.key
                badRules.add(key)
                if (!exception && opts.isPlainHost) simpleHost(line)?.let { badHostHashes.add(HostBlocklist.hash(it)) }
                return
            }
            // `||host^` with no options, or only "third-party": one hash each instead of a rule.
            if (!exception && !opts.important && opts.domainsInclude == null && opts.domainsExclude == null && opts.typesGiven.not()) {
                val host = simpleHost(line)
                if (host != null && (opts.party == PARTY_ANY || opts.party == PARTY_THIRD)) {
                    (if (opts.party == PARTY_ANY) hostHashes else host3pHashes).add(HostBlocklist.hash(host))
                    return
                }
            }
            var flags = 0
            var pattern = line
            if (pattern.length >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
                // Regular expressions are slow on Android; only kept when they are limited to some sites.
                if (opts.domainsInclude == null) return
                flags = flags or NetRule.REGEX
                pattern = pattern.substring(1, pattern.length - 1)
            } else {
                pattern = pattern.lowercase()
                if (pattern.startsWith("||")) {
                    flags = flags or NetRule.HOST_ANCHOR
                    pattern = pattern.substring(2)
                } else if (pattern.startsWith("|")) {
                    flags = flags or NetRule.LEFT_ANCHOR
                    pattern = pattern.substring(1)
                }
                if (pattern.endsWith("|")) {
                    flags = flags or NetRule.RIGHT_ANCHOR
                    pattern = pattern.dropLast(1)
                }
                // A leading or trailing "*" matches anything, which also makes an anchor on that side pointless.
                if (pattern.startsWith("*")) {
                    pattern = pattern.trimStart('*')
                    flags = flags and (NetRule.HOST_ANCHOR or NetRule.LEFT_ANCHOR).inv()
                }
                if (pattern.endsWith("*")) {
                    pattern = pattern.trimEnd('*')
                    flags = flags and NetRule.RIGHT_ANCHOR.inv()
                }
            }
            if (opts.important) flags = flags or NetRule.IMPORTANT
            if (exception) flags = flags or NetRule.EXCEPTION
            // An empty pattern matches every request: only accepted when limited to some sites or types.
            if (pattern.isEmpty() && opts.domainsInclude == null && !opts.typesGiven && opts.pageBits == 0) return
            val rule = NetRule(pattern, flags, opts.types, opts.party, opts.domainsInclude, opts.domainsExclude, opts.pageBits)
            rules += ((if (exception) "@@" else "") + line + opts.key) to rule
        }

        fun build(): AdFilter {
            val hostsArr = hostHashes.toSortedDistinct().let { a -> if (badHostHashes.isEmpty()) a else a.filter { it !in badHostHashes }.toLongArray() }
            val hosts3pArr = host3pHashes.toSortedDistinct()
            val important = RuleIndex.Builder()
            val block = RuleIndex.Builder()
            val allow = RuleIndex.Builder()
            val page = ArrayList<NetRule>()
            for ((key, rule) in rules) {
                if (key in badRules) continue
                when {
                    rule.pageBits != 0 -> {
                        page += rule
                        // `@@…$document` also lets the page itself load.
                        if (rule.pageBits and PageFlags.NO_BLOCKING != 0) allow.add(rule)
                    }
                    rule.flags and NetRule.EXCEPTION != 0 -> allow.add(rule)
                    rule.flags and NetRule.IMPORTANT != 0 -> important.add(rule)
                    else -> block.add(rule)
                }
            }
            val c = cosmetics.build()
            val s = scriptlets.build()
            val count = hostsArr.size + hosts3pArr.size + important.size + block.size + allow.size + page.size + c.size + s.size
            rules.clear()
            return AdFilter(
                HostBlocklist(hostsArr), HostBlocklist(hosts3pArr), important.build(), block.build(), allow.build(),
                page.toTypedArray(), c, s, count,
            )
        }
    }

    companion object {
        /** Blocks nothing. */
        val EMPTY: AdFilter = Builder().build()

        private const val MAX_RULE = 4096

        internal const val PARTY_ANY = 0
        internal const val PARTY_FIRST = 1
        internal const val PARTY_THIRD = 2

        /** What `!#if` treats as true here: a Chromium engine, uBlock Origin's own syntax. */
        private val TRUE_ENV = setOf("env_chromium", "ext_ublock", "cap_user_stylesheet")

        /** Where "##", "#@#", "#?#", "#@?#", "#$#" and the like start in [line], or -1. By hand: see [isDomainName]. */
        private fun cosmeticSeparator(line: String): Int {
            var i = line.indexOf('#')
            while (i >= 0) {
                var j = i + 1
                if (j < line.length && line[j] == '@') j++
                if (j < line.length && (line[j] == '?' || line[j] == '$' || line[j] == '%')) j++
                if (j < line.length && line[j] == '#') return i
                i = line.indexOf('#', i + 1)
            }
            return -1
        }

        /** "name", "~name" or "name=value", comma-separated, names in [a-z0-9_-], values without "," or "$". */
        private fun looksLikeOptions(tail: String): Boolean {
            if (tail.isEmpty()) return false
            for (part in tail.split(',')) {
                val name = part.removePrefix("~").substringBefore('=')
                if (name.isEmpty() || name.any { !(it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') }) return false
                if ('$' in part) return false
            }
            return true
        }

        /** The host of `||host^`, or null for anything else. */
        private fun simpleHost(line: String): String? {
            if (!line.startsWith("||") || !line.endsWith("^")) return null
            val h = line.substring(2, line.length - 1).lowercase()
            return if (isDomainName(h, strictHyphens = false)) h else null
        }

        private val TYPE_NAMES = mapOf(
            "script" to ReqType.SCRIPT, "image" to ReqType.IMAGE, "stylesheet" to ReqType.STYLESHEET, "css" to ReqType.STYLESHEET,
            "xmlhttprequest" to ReqType.XHR, "xhr" to ReqType.XHR, "subdocument" to ReqType.SUBDOCUMENT, "frame" to ReqType.SUBDOCUMENT,
            "media" to ReqType.MEDIA, "font" to ReqType.FONT, "object" to ReqType.OBJECT, "ping" to ReqType.PING, "beacon" to ReqType.PING,
            "websocket" to ReqType.WEBSOCKET, "other" to ReqType.OTHER, "document" to ReqType.DOCUMENT, "doc" to ReqType.DOCUMENT,
            "popup" to ReqType.POPUP, "popunder" to ReqType.POPUP,
        )

        /** Options that change nothing here. */
        private val IGNORED = setOf("reason", "_", "match-case-ignored")

        private class Options(
            val types: Int,
            val typesGiven: Boolean,
            val party: Int,
            val important: Boolean,
            val badfilter: Boolean,
            val domainsInclude: Array<String>?,
            val domainsExclude: Array<String>?,
            val pageBits: Int,
            /** The options as written (without `badfilter`), for matching a `$badfilter` rule to its target. */
            val key: String,
        ) {
            val isPlainHost: Boolean get() = !typesGiven && party == PARTY_ANY && !important && domainsInclude == null && domainsExclude == null
        }

        private fun parseOptions(text: String, exception: Boolean): Options? {
            var include = 0
            var exclude = 0
            var party = PARTY_ANY
            var important = false
            var badfilter = false
            var domIn: Array<String>? = null
            var domOut: Array<String>? = null
            var pageBits = 0
            val kept = ArrayList<String>()
            if (text.isNotEmpty()) for (raw in text.split(',')) {
                if (raw.isEmpty()) return null
                val neg = raw.startsWith("~")
                val name = raw.removePrefix("~").substringBefore('=')
                val value = raw.substringAfter('=', "")
                if (name != "badfilter") kept += raw
                when (name) {
                    in TYPE_NAMES -> {
                        val bit = TYPE_NAMES.getValue(name)
                        if (neg) exclude = exclude or bit else include = include or bit
                        // `@@…$document` turns the whole page off; it is a page exception too.
                        if (exception && bit == ReqType.DOCUMENT && !neg) pageBits = pageBits or PageFlags.NO_BLOCKING
                    }
                    "all" -> include = include or ReqType.ALL
                    "third-party", "3p", "strict3p" -> party = if (neg) PARTY_FIRST else PARTY_THIRD
                    "first-party", "1p", "strict1p" -> party = if (neg) PARTY_THIRD else PARTY_FIRST
                    "important" -> important = true
                    "badfilter" -> badfilter = true
                    "domain", "from" -> {
                        if (neg || value.isEmpty()) return null
                        if (value.contains('/')) return null // regular-expression domains
                        val (a, b) = splitDomains(value, '|')
                        domIn = a
                        domOut = b
                    }
                    "elemhide", "ehide" -> if (exception) pageBits = pageBits or PageFlags.NO_ELEMHIDE else return null
                    "generichide", "ghide" -> if (exception) pageBits = pageBits or PageFlags.NO_GENERICHIDE else return null
                    "specifichide", "shide" -> if (exception) pageBits = pageBits or PageFlags.NO_SPECIFICHIDE else return null
                    in IGNORED -> Unit
                    // redirect, removeparam, csp, header, to, denyallow, match-case, method and the rest:
                    // they change what a rule does in ways this blocker can't follow, so the rule is dropped.
                    else -> return null
                }
            }
            val typesGiven = include != 0 || exclude != 0
            var types = (if (include != 0) include else ReqType.DEFAULT) and exclude.inv()
            // A page exception ("@@||x^$generichide") is checked against the page itself.
            if (pageBits != 0) types = types or ReqType.DOCUMENT
            if (types == 0) return null
            val key = if (kept.isEmpty()) "" else "$" + kept.joinToString(",")
            return Options(types, typesGiven, party, important, badfilter, domIn, domOut, pageBits, key)
        }

        /** "a.com,~b.a.com" → included and excluded domains, lowercase. Null for an empty side. */
        internal fun splitDomains(text: String, sep: Char): Pair<Array<String>?, Array<String>?> {
            if (text.isEmpty()) return null to null
            val inc = ArrayList<String>()
            val exc = ArrayList<String>()
            for (d in text.split(sep)) {
                val t = d.trim().lowercase()
                if (t.isEmpty()) continue
                if (t.startsWith("~")) exc += t.substring(1) else inc += t
            }
            return inc.takeIf { it.isNotEmpty() }?.toTypedArray() to exc.takeIf { it.isNotEmpty() }?.toTypedArray()
        }

        /** True when [host] is [domain] or under it. "name.*" matches that name under any ending ("google.*"). */
        internal fun hostMatches(host: String, domain: String): Boolean {
            if (domain.endsWith(".*")) {
                val site = siteOf(host)
                val dot = site.indexOf('.')
                if (dot < 0) return false
                val expanded = domain.dropLast(1) + site.substring(dot + 1)
                return host == expanded || host.endsWith(".$expanded")
            }
            return host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')
        }

        /**
         * The keys a site's rules are stored under for [host]: the host and each parent domain, and the
         * same with the ending replaced by "*" ("www.google.co.uk" → "www.google.*", "google.*").
         */
        internal fun lookupKeys(host: String): List<String> {
            if (host.isEmpty()) return emptyList()
            val out = ArrayList<String>(8)
            val site = siteOf(host)
            val ending = site.substringAfter('.', "")
            var h = host
            while (true) {
                out += h
                if (ending.isNotEmpty() && h.endsWith(".$ending") && h.length > ending.length + 1) out += h.dropLast(ending.length) + "*"
                val dot = h.indexOf('.')
                if (dot < 0) break
                h = h.substring(dot + 1)
            }
            return out
        }
    }
}

/** A request as the rules see it. */
internal class Req(url: String, val host: String, val pageHost: String, val types: Int) {
    val url: String = url.lowercase()
    val thirdParty: Boolean = pageHost.isNotEmpty() && siteOf(host) != siteOf(pageHost)

    /** Where the host starts and ends in [url]. */
    val hostStart: Int
    val hostEnd: Int

    init {
        val scheme = this.url.indexOf("://")
        var s = if (scheme >= 0) scheme + 3 else 0
        var e = s
        while (e < this.url.length && this.url[e] != '/' && this.url[e] != '?' && this.url[e] != '#') e++
        val at = this.url.lastIndexOf('@', e - 1)
        if (at >= s) s = at + 1
        var colon = e
        // A port isn't part of the host; an IPv6 literal has colons of its own.
        if (s < e && this.url[s] != '[') {
            val c = this.url.indexOf(':', s)
            if (c in s until e) colon = c
        }
        hostStart = s
        hostEnd = colon
    }
}

/** One request rule. [pattern] is lowercase, without its anchors; for [REGEX] it is the expression. */
internal class NetRule(
    val pattern: String,
    val flags: Int,
    val types: Int,
    val party: Int,
    val include: Array<String>?,
    val exclude: Array<String>?,
    /** For page exceptions: the [PageFlags] they set. */
    val pageBits: Int,
) {
    @Volatile private var regex: Regex? = null
    @Volatile private var badRegex = false

    fun matches(req: Req): Boolean {
        if (types and req.types == 0) return false
        if (party != AdFilter.PARTY_ANY) {
            if (req.pageHost.isEmpty()) return false
            if (party == AdFilter.PARTY_THIRD != req.thirdParty) return false
        }
        if (include != null || exclude != null) {
            if (req.pageHost.isEmpty()) return false
            if (include != null && include.none { AdFilter.hostMatches(req.pageHost, it) }) return false
            if (exclude != null && exclude.any { AdFilter.hostMatches(req.pageHost, it) }) return false
        }
        return matchesUrl(req)
    }

    private fun matchesUrl(req: Req): Boolean {
        val url = req.url
        if (flags and REGEX != 0) {
            if (badRegex) return false
            val r = regex ?: try {
                Regex(pattern, RegexOption.IGNORE_CASE).also { regex = it }
            } catch (_: PatternSyntaxException) {
                badRegex = true
                return false
            }
            return r.containsMatchIn(url)
        }
        val right = flags and RIGHT_ANCHOR != 0
        return when {
            flags and HOST_ANCHOR != 0 -> {
                var p = req.hostStart
                while (p < req.hostEnd) {
                    if ((p == req.hostStart || url[p - 1] == '.') && Glob.at(url, p, pattern, 0, right)) return true
                    p++
                }
                false
            }
            flags and LEFT_ANCHOR != 0 -> Glob.at(url, 0, pattern, 0, right)
            else -> Glob.find(url, pattern, right)
        }
    }

    companion object {
        const val HOST_ANCHOR = 1
        const val LEFT_ANCHOR = 2
        const val RIGHT_ANCHOR = 4
        const val REGEX = 8
        const val IMPORTANT = 16
        const val EXCEPTION = 32
    }
}

/** Adblock-style patterns: `*` is any run of characters, `^` a separator or the end of the address. */
internal object Glob {
    /** Anything but a letter, a digit or one of `_ - . %`. */
    fun isSeparator(c: Char): Boolean = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    /** True when [p] (from [pi]) matches [s] starting at [si]; with [right] the match must reach the end. */
    fun at(s: String, si: Int, p: String, pi: Int, right: Boolean): Boolean {
        var i = si
        var j = pi
        while (j < p.length) {
            when (val c = p[j]) {
                '*' -> {
                    var k = j
                    while (k < p.length && p[k] == '*') k++
                    if (k == p.length) return true
                    val next = p[k]
                    var start = i
                    while (start <= s.length) {
                        if (next != '^') {
                            start = s.indexOf(next, start)
                            if (start < 0) return false
                        }
                        if (at(s, start, p, k, right)) return true
                        start++
                    }
                    return false
                }
                '^' -> {
                    if (i == s.length) {
                        j++
                        continue
                    }
                    if (!isSeparator(s[i])) return false
                    i++
                    j++
                }
                else -> {
                    if (i >= s.length || s[i] != c) return false
                    i++
                    j++
                }
            }
        }
        return !right || i == s.length
    }

    /** True when [p] matches anywhere in [s]. */
    fun find(s: String, p: String, right: Boolean): Boolean {
        if (p.isEmpty()) return true
        // The literal start of the pattern tells where a match can begin.
        var lit = 0
        while (lit < p.length && p[lit] != '*' && p[lit] != '^') lit++
        if (lit == 0) {
            for (start in 0..s.length) if (at(s, start, p, 0, right)) return true
            return false
        }
        val head = p.substring(0, lit)
        var start = s.indexOf(head)
        while (start >= 0) {
            if (at(s, start + lit, p, lit, right)) return true
            start = s.indexOf(head, start + 1)
        }
        return false
    }
}

/**
 * Rules found through the rarest-looking word they contain: a request is only checked against the rules
 * filed under the words of its own address, plus the few rules with no usable word.
 */
internal class RuleIndex private constructor(private val byToken: HashMap<Int, Array<NetRule>>, private val rest: Array<NetRule>) {
    val unindexed: Int get() = rest.size

    fun matches(req: Req): Boolean {
        if (rest.isNotEmpty()) for (r in rest) if (r.matches(req)) return true
        if (byToken.isEmpty()) return false
        val s = req.url
        var i = 0
        while (i < s.length) {
            if (!isTokenChar(s[i])) {
                i++
                continue
            }
            var h = 0
            val start = i
            while (i < s.length && isTokenChar(s[i])) {
                h = 31 * h + s[i].code
                i++
            }
            if (i - start < 2) continue
            val bucket = byToken[h] ?: continue
            for (r in bucket) if (r.matches(req)) return true
        }
        return false
    }

    class Builder {
        private val byToken = HashMap<Int, ArrayList<NetRule>>()
        private val rest = ArrayList<NetRule>()
        var size = 0
            private set

        fun add(rule: NetRule) {
            size++
            val token = bestToken(rule)
            if (token == null) rest += rule else byToken.getOrPut(token.hashCode()) { ArrayList(2) } += rule
        }

        fun build(): RuleIndex {
            val m = HashMap<Int, Array<NetRule>>(byToken.size * 2)
            for ((k, v) in byToken) m[k] = v.toTypedArray()
            return RuleIndex(m, rest.toTypedArray())
        }
    }

    companion object {
        fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '%'

        /** Words too common to narrow anything down. */
        private val COMMON = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "cdn", "static", "assets")

        /**
         * The longest word of the pattern that is certain to be a whole word of any address it matches:
         * not next to a `*`, and not at an open end of the pattern. Null when there is none.
         */
        fun bestToken(rule: NetRule): String? {
            if (rule.flags and NetRule.REGEX != 0) return null
            val p = rule.pattern
            val leftOpen = rule.flags and (NetRule.HOST_ANCHOR or NetRule.LEFT_ANCHOR) == 0
            val rightOpen = rule.flags and NetRule.RIGHT_ANCHOR == 0
            var best: String? = null
            var i = 0
            while (i < p.length) {
                if (!isTokenChar(p[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < p.length && isTokenChar(p[i])) i++
                val end = i
                val before = if (start == 0) null else p[start - 1]
                val after = if (end == p.length) null else p[end]
                val okLeft = if (before == null) !leftOpen else before != '*'
                val okRight = if (after == null) !rightOpen else after != '*'
                // "^" right after the word is a separator or the end: the word is still whole.
                if (!okLeft || !okRight || end - start < 2) continue
                val word = p.substring(start, end)
                if (word in COMMON) continue
                if (best == null || word.length > best.length) best = word
            }
            return best
        }
    }
}

/** Element hiding rules. */
internal class Cosmetics private constructor(
    /** Generic ".class" and "#id" selectors, sent as a page shows those names. */
    private val genericSimple: HashSet<String>,
    /** The other generic selectors, as CSS. */
    private val genericComplex: List<String>,
    private val genericComplexCss: String,
    /** Generic selectors with sites they don't apply to ("~a.com##.ad"). */
    private val genericExcept: List<Pair<String, Array<String>>>,
    private val specific: HashMap<String, MutableList<Pair<String, Array<String>?>>>,
    private val unhide: HashMap<String, HashSet<String>>,
    val size: Int,
) {
    fun css(host: String, flags: Int): String {
        val keys = AdFilter.lookupKeys(host)
        val unhidden = HashSet<String>()
        for (k in keys) unhide[k]?.let { unhidden.addAll(it) }
        val out = StringBuilder()
        if (flags and PageFlags.NO_SPECIFICHIDE == 0) {
            for (k in keys) specific[k]?.forEach { (sel, excl) ->
                if (sel !in unhidden && (excl == null || excl.none { AdFilter.hostMatches(host, it) })) rule(out, sel)
            }
        }
        if (flags and PageFlags.NO_GENERICHIDE == 0) {
            if (unhidden.isEmpty()) out.append(genericComplexCss)
            else genericComplex.forEach { if (it !in unhidden) rule(out, it) }
            for ((sel, excl) in genericExcept) {
                if (sel !in unhidden && excl.none { AdFilter.hostMatches(host, it) }) rule(out, sel)
            }
        }
        return out.toString()
    }

    fun genericFor(tokens: Collection<String>, host: String): String {
        if (genericSimple.isEmpty()) return ""
        var unhidden: HashSet<String>? = null
        val out = StringBuilder()
        for (t in tokens) {
            if (t !in genericSimple) continue
            if (unhidden == null) {
                unhidden = HashSet()
                for (k in AdFilter.lookupKeys(host)) unhide[k]?.let { unhidden.addAll(it) }
            }
            if (t !in unhidden) rule(out, t)
        }
        return out.toString()
    }

    class Builder {
        private val generic = LinkedHashSet<String>()
        private val genericExcept = ArrayList<Pair<String, Array<String>>>()
        private val genericUnhide = HashSet<String>()
        private val specific = HashMap<String, MutableList<Pair<String, Array<String>?>>>()
        private val unhide = HashMap<String, HashSet<String>>()

        fun add(selector: String, include: Array<String>?, exclude: Array<String>?, exception: Boolean) {
            if (exception) {
                if (include == null) genericUnhide += selector
                else include.forEach { unhide.getOrPut(it) { HashSet() } += selector }
                return
            }
            when {
                include != null -> include.forEach { specific.getOrPut(it) { ArrayList(2) } += selector to exclude }
                exclude != null -> genericExcept += selector to exclude
                else -> generic += selector
            }
        }

        fun build(): Cosmetics {
            val simple = HashSet<String>()
            val complex = ArrayList<String>()
            for (sel in generic) {
                if (sel in genericUnhide) continue
                if (isSimple(sel)) simple += sel else complex += sel
            }
            val css = StringBuilder()
            complex.forEach { rule(css, it) }
            val except = genericExcept.filter { it.first !in genericUnhide }
            val size = simple.size + complex.size + except.size + specific.values.sumOf { it.size }
            return Cosmetics(simple, complex, css.toString(), except, specific, unhide, size)
        }
    }

    companion object {
        /** ".name" or "#name": a plain class or id selector. */
        private fun isSimple(s: String): Boolean {
            if (s.length < 2 || (s[0] != '.' && s[0] != '#')) return false
            val first = s[1]
            if (!(first in 'a'..'z' || first in 'A'..'Z' || first == '_')) return false
            for (i in 2 until s.length) {
                val c = s[i]
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) return false
            }
            return true
        }

        /** uBlock Origin's procedural selectors and other things CSS can't do. */
        private val NOT_CSS = listOf(
            ":has-text(", ":upward(", ":xpath(", ":style(", ":remove(", ":remove-attr(", ":remove-class(", ":matches-css",
            ":matches-attr(", ":matches-path(", ":matches-prop(", ":min-text-length(", ":watch-attr(", ":others(", ":-abp-",
            ":contains(", ":if(", ":if-not(", ":nth-ancestor(", ":shadow(", ":spath(", ":matches-media(",
        )

        fun isPlainSelector(s: String): Boolean {
            if (s.isEmpty() || s.length > 1000) return false
            if (s.contains('{') || s.contains('}') || s.contains("/*") || s.contains('<')) return false
            return NOT_CSS.none { s.contains(it) }
        }

        private fun rule(out: StringBuilder, selector: String) {
            out.append(selector).append("{display:none!important}\n")
        }
    }
}

/** Scriptlet rules. */
internal class Scriptlets private constructor(
    private val generic: List<ScriptletCall>,
    private val specific: HashMap<String, MutableList<Pair<ScriptletCall, Array<String>?>>>,
    private val exceptions: HashMap<String, HashSet<String>>,
    val size: Int,
) {
    fun forHost(host: String): List<ScriptletCall> {
        if (host.isEmpty()) return emptyList()
        val keys = AdFilter.lookupKeys(host)
        val off = HashSet<String>()
        for (k in keys) exceptions[k]?.let { off.addAll(it) }
        // "#@#+js()" with nothing in it turns every scriptlet off on that site.
        if ("" in off) return emptyList()
        val out = LinkedHashMap<String, ScriptletCall>()
        for (c in generic) keyOf(c).let { if (it !in off) out[it] = c }
        for (k in keys) specific[k]?.forEach { (c, excl) ->
            if (excl != null && excl.any { AdFilter.hostMatches(host, it) }) return@forEach
            val key = keyOf(c)
            if (key !in off) out[key] = c
        }
        return out.values.take(MAX_PER_PAGE)
    }

    fun table() = ScriptletTable(specific, exceptions, generic)

    class Builder {
        private val generic = ArrayList<ScriptletCall>()
        private val specific = HashMap<String, MutableList<Pair<ScriptletCall, Array<String>?>>>()
        private val exceptions = HashMap<String, HashSet<String>>()
        private val genericExceptions = HashSet<String>()

        fun add(body: String, include: Array<String>?, exclude: Array<String>?, exception: Boolean) {
            val args = splitArgs(body) ?: return
            if (exception) {
                val key = if (args.isEmpty() || args[0].isEmpty()) "" else canonical(args[0])?.let { keyOf(ScriptletCall(it, args.drop(1))) } ?: return
                if (include == null) genericExceptions += key else include.forEach { exceptions.getOrPut(it) { HashSet() } += key }
                return
            }
            if (args.isEmpty()) return
            val name = canonical(args[0]) ?: return
            val rest = args.drop(1)
            if (rest.size > 8 || rest.any { it.length > MAX_ARG }) return
            val call = ScriptletCall(name, rest)
            when {
                include != null -> include.forEach { specific.getOrPut(it) { ArrayList(2) } += call to exclude }
                // Generic scriptlets run on every page: only accepted without exclusions, as uBlock Origin does.
                exclude == null -> generic += call
            }
        }

        fun build(): Scriptlets {
            val g = generic.filter { keyOf(it) !in genericExceptions && "" !in genericExceptions }
            return Scriptlets(g, specific, exceptions, g.size + specific.values.sumOf { it.size })
        }
    }

    companion object {
        private const val MAX_ARG = 500
        private const val MAX_PER_PAGE = 60

        /** Names (and uBlock Origin's short names) of the scriptlets this browser has, by canonical name. */
        val NAMES: Map<String, String> = buildMap {
            fun alias(canon: String, vararg names: String) = (names.toList() + canon).forEach { put(it, canon) }
            alias("abort-on-property-read", "aopr")
            alias("abort-on-property-write", "aopw")
            alias("set-constant", "set")
            alias("abort-current-script", "acs", "abort-current-inline-script", "acis")
            alias("prevent-addEventListener", "aeld", "addEventListener-defuser")
            alias("no-setTimeout-if", "nostif", "prevent-setTimeout", "setTimeout-defuser")
            alias("no-setInterval-if", "nosiif", "prevent-setInterval", "setInterval-defuser")
            alias("no-window-open-if", "nowoif", "window.open-defuser", "prevent-window-open")
            alias("no-xhr-if", "prevent-xhr")
            alias("no-fetch-if", "prevent-fetch")
            alias("remove-attr", "ra")
            alias("remove-class", "rc")
            alias("json-prune")
            alias("noeval", "silent-noeval")
            alias("nano-setInterval-booster", "nano-sib")
            alias("nano-setTimeout-booster", "nano-stb")
            alias("set-local-storage-item")
        }

        fun canonical(raw: String): String? = NAMES[raw.trim().removePrefix("ubo-").removeSuffix(".js")]

        private fun keyOf(c: ScriptletCall): String = c.key

        /** Splits "name, arg, 'quoted, arg', a\,b" into its parts. Null when malformed. */
        fun splitArgs(body: String): List<String>? {
            val out = ArrayList<String>()
            var i = 0
            val s = body
            while (i <= s.length) {
                while (i < s.length && s[i] == ' ') i++
                val sb = StringBuilder()
                if (i < s.length && (s[i] == '\'' || s[i] == '"' || s[i] == '`')) {
                    val q = s[i++]
                    var closed = false
                    while (i < s.length) {
                        val c = s[i]
                        if (c == '\\' && i + 1 < s.length && s[i + 1] == q) {
                            sb.append(q)
                            i += 2
                            continue
                        }
                        if (c == q) {
                            closed = true
                            i++
                            break
                        }
                        sb.append(c)
                        i++
                    }
                    if (!closed) return null
                    while (i < s.length && s[i] == ' ') i++
                    if (i < s.length && s[i] != ',') return null
                } else {
                    while (i < s.length && s[i] != ',') {
                        if (s[i] == '\\' && i + 1 < s.length && s[i + 1] == ',') {
                            sb.append(',')
                            i += 2
                            continue
                        }
                        sb.append(s[i])
                        i++
                    }
                }
                out += sb.toString().trim()
                i++ // past the comma (or the end)
            }
            if (out.size == 1 && out[0].isEmpty()) return emptyList()
            return out
        }
    }
}

/** A growable array of longs, so a big list's hashes are never boxed. */
internal class LongList {
    private var a = LongArray(1024)
    private var n = 0

    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }

    fun toSortedDistinct(): LongArray {
        val out = a.copyOf(n)
        out.sort()
        return out.distinctSorted()
    }
}
