package app.tvremote.protocol

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Builds the filter from real downloaded lists and checks a corpus of requests. Skipped unless
 * ADLISTS_DIR names a folder with the lists (and optionally ADLISTS_CORPUS a "type\tpage\turl" file,
 * whose verdicts go to ADLISTS_OUT). Used to compare with another engine and to measure, not in CI.
 */
class AdFilterListsCheck {
    private fun hostOf(u: String) = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()

    @Test fun realLists() {
        val dir = System.getenv("ADLISTS_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = (System.getenv("ADLISTS_FILES") ?: "easylist.txt,easyprivacy.txt,ubo-filters-full.txt,ubo-badware.txt,ubo-privacy.txt,ubo-quick-fixes.txt,ubo-unbreak.txt")
            .split(',').map { File(dir, it) }
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val b = AdFilter.Builder()
        files.forEach { f -> f.useLines { b.addFilterList(it) } }
        val filter = b.build()
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        println("build ${buildMs} ms, rules ${filter.ruleCount}, unindexed ${filter.unindexed}, heap +${(after - before) / 1024 / 1024} MB")

        val table = filter.scriptletTable()
        val entity = table.specific.keys.count { it.endsWith(".*") }
        val size = table.specific.entries.sumOf { (k, v) -> k.length + 4 + v.sumOf { (c, _) -> c.name.length + c.args.sumOf { it.length + 3 } + 6 } }
        println("scriptlets: ${table.specific.size} keys ($entity entity), ${table.specific.values.sumOf { it.size }} calls, ~${size / 1024} KB as JSON, ${table.exceptions.size} exception keys, ${table.generic.size} generic")
        val corpus = System.getenv("ADLISTS_CORPUS")?.let(::File) ?: return
        val types = mapOf(
            "document" to ReqType.DOCUMENT, "script" to ReqType.SCRIPT, "image" to ReqType.IMAGE, "stylesheet" to ReqType.STYLESHEET,
            "subdocument" to ReqType.SUBDOCUMENT, "xmlhttprequest" to ReqType.XHR, "media" to ReqType.MEDIA, "ping" to ReqType.PING,
            "popup" to ReqType.POPUP,
        )
        val lines = corpus.readLines().filter { it.isNotBlank() }
        val out = StringBuilder()
        var blocked = 0
        val t1 = System.nanoTime()
        repeat(5) { round ->
            for (l in lines) {
                val (type, page, url) = l.split('\t')
                val host = hostOf(url)
                val pageHost = hostOf(page)
                val v = filter.shouldBlock(url, host, pageHost, types.getValue(type))
                if (round == 0) {
                    if (v) blocked++
                    out.append(if (v) "B" else "-").append('\t').append(l).append('\n')
                }
            }
        }
        val perReqUs = (System.nanoTime() - t1) / 1000.0 / (lines.size * 5)
        println("corpus ${lines.size} requests, blocked $blocked, %.1f µs per request".format(perReqUs))
        System.getenv("ADLISTS_OUT")?.let { File(it).writeText(out.toString()) }
        val css = filter.cosmeticCss("www.cnn.com", 0)
        println("cosmetic css for cnn.com: ${css.length} chars; scriptlets for youtube: ${filter.scriptletsFor("www.youtube.com").size}")
    }
}
