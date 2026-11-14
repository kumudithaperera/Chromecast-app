package app.tvremote.phone.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNotesTest {
    private fun note(id: Long, show: String, ep: String = "", url: String = "") =
        WatchNote(id, show, ep, "", url, -1, id)

    @Test fun groupsByShowIgnoringCaseAndKeepsNewestFirst() {
        val groups = groupByShow(
            listOf(note(5, "Severance", "S2 E4"), note(4, "Dark"), note(3, "severance", "S2 E3"), note(2, " DARK ")),
        )
        assertEquals(listOf("Severance", "Dark"), groups.map { it.show })
        assertEquals(listOf(5L, 3L), groups[0].notes.map { it.id })
        assertEquals(listOf(4L, 2L), groups[1].notes.map { it.id })
    }

    @Test fun showsAreOrderedByTheirLatestNote() {
        val groups = groupByShow(listOf(note(9, "B"), note(8, "A"), note(7, "B")))
        assertEquals(listOf("B", "A"), groups.map { it.show })
    }

    @Test fun lastEpisodeAndResumeLinkComeFromTheNewestNoteThatHasOne() {
        val g = groupByShow(
            listOf(note(3, "Show"), note(2, "Show", "S1 E2", "https://a.example/2"), note(1, "Show", "S1 E1", "https://a.example/1")),
        ).single()
        assertEquals("S1 E2", g.lastEpisode)
        assertEquals("https://a.example/2", g.resumeUrl)
    }

    @Test fun noEpisodeOrLinkGivesEmptyStrings() {
        val g = groupByShow(listOf(note(1, "Film"))).single()
        assertEquals("", g.lastEpisode)
        assertEquals("", g.resumeUrl)
    }
}
