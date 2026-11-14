package app.tvremote.phone.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentTest {
    @Test
    fun newestFirstAndTrimmed() {
        assertEquals(listOf("lo-fi beats", "pasta"), pushRecent(listOf("pasta"), "  lo-fi beats ", 8))
    }

    @Test
    fun repeatMovesToFrontIgnoringCase() {
        assertEquals(listOf("Severance", "pasta"), pushRecent(listOf("pasta", "severance"), "Severance", 8))
    }

    @Test
    fun keepsAtMostMax() {
        assertEquals(listOf("d", "a", "b"), pushRecent(listOf("a", "b", "c"), "d", 3))
    }

    @Test
    fun blankChangesNothing() {
        val list = listOf("a")
        assertEquals(list, pushRecent(list, "   ", 8))
    }
}
