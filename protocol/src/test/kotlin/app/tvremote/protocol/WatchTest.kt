package app.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTest {
    private fun guess(title: String) = guessEpisode(title)

    @Test fun readsSeasonAndEpisodeFromCommonTitleShapes() {
        assertEquals(EpisodeGuess("Severance", "S2 E4"), guess("Severance Season 2 Episode 4 - Watch Online | Site"))
        assertEquals(EpisodeGuess("Breaking Bad", "S5 E14"), guess("Breaking Bad S05E14 - Ozymandias - Netflix"))
        assertEquals(EpisodeGuess("The Bear", "S3 E2"), guess("Watch The Bear S3 E2 free"))
        assertEquals(EpisodeGuess("Severance", "S2 E4"), guess("S02E04 - Severance"))
        assertEquals(EpisodeGuess("Dark", "S1 E7"), guess("Dark 1x07 | Streaming"))
        assertEquals(EpisodeGuess("Show", "S2 E10"), guess("Show S02.E10"))
        assertEquals(EpisodeGuess("Show", "S2 E3"), guess("Show - Season 2, Ep. 3"))
    }

    @Test fun episodeAloneHasNoSeason() {
        assertEquals(EpisodeGuess("One Piece", "E1100"), guess("One Piece Episode 1100 | Site"))
        assertEquals(EpisodeGuess("Anime", "E12"), guess("Anime Ep 12"))
    }

    @Test fun titlesWithoutAnEpisodeUseTheFirstPart() {
        assertEquals(EpisodeGuess("Cool video", ""), guess("Cool video - YouTube"))
        assertEquals(EpisodeGuess("Spider-Man: No Way Home", ""), guess("Spider-Man: No Way Home | Movies"))
        assertEquals(EpisodeGuess("Dune", ""), guess("Watch Dune (2021) online".substringBefore(" (")))
        assertEquals(EpisodeGuess("", ""), guess(""))
        assertEquals(EpisodeGuess("", ""), guess("   "))
    }

    @Test fun resolutionsAndWordsAreNotEpisodes() {
        assertEquals("", guess("Video 1920x1080 test").episode)
        assertEquals("", guess("Seven Deadly Sins").episode)
        assertEquals("", guess("Se7en").episode)
    }

    @Test fun showNameIsCapped() {
        val long = "A".repeat(200) + " S1E1"
        assertEquals(MAX_SHOW, guess(long).show.length)
    }

    @Test fun normalizesTypedEpisodeLabels() {
        assertEquals("S2 E4", normalizeEpisode("s02e04"))
        assertEquals("S2 E4", normalizeEpisode("2x04"))
        assertEquals("S2 E4", normalizeEpisode(" Season 2 Episode 4 "))
        assertEquals("E4", normalizeEpisode("ep 4"))
        assertEquals("E4", normalizeEpisode("4"))
        assertEquals("S2", normalizeEpisode("season 2"))
        assertEquals("", normalizeEpisode("  "))
        assertEquals("Finale", normalizeEpisode("Finale"))
        assertEquals("A very long ", normalizeEpisode("A very long episode name"))
    }

    @Test fun cleanLineRemovesControlCharactersAndCuts() {
        assertEquals("a b c", cleanLine("  a\nb\tc  ", 10))
        assertEquals("abc", cleanLine("abcdef", 3))
        assertEquals("", cleanLine("\n\n", 10))
    }

    @Test fun clockText() {
        assertEquals("0:00", clockText(0))
        assertEquals("12:34", clockText(754))
        assertEquals("1:02:05", clockText(3725))
        assertEquals("0:00", clockText(-5))
    }

    @Test fun timeAgo() {
        val now = 10_000_000_000L
        fun ago(ms: Long) = timeAgo(now, now - ms)
        assertEquals("just now", ago(20_000))
        assertEquals("5 min ago", ago(5 * 60_000L))
        assertEquals("3 h ago", ago(3 * 3_600_000L))
        assertEquals("yesterday", ago(30 * 3_600_000L))
        assertEquals("3 days ago", ago(3 * 86_400_000L))
        assertEquals("3 weeks ago", ago(21 * 86_400_000L))
        assertEquals("3 months ago", ago(95 * 86_400_000L))
        assertEquals("just now", timeAgo(now, now + 5_000)) // a clock set back never shows a negative age
    }

    @Test fun nextEpisodeCountsUpWithinTheSeason() {
        assertEquals("S2 E5", nextEpisode("S2 E4"))
        assertEquals("E13", nextEpisode("E12"))
        assertEquals("S1 E10", nextEpisode(" S1 E9 "))
        assertEquals("", nextEpisode(""))
        assertEquals("", nextEpisode("finale"))
        assertEquals("", nextEpisode("S2"))
    }

    @Test fun theOldNoteCommandIsNoLongerUnderstood() {
        // Notes live on the phone now; a stale phone build sending one is ignored, not an error.
        assertNull(parseCommand("W Show S1 text 1.0"))
    }
}
