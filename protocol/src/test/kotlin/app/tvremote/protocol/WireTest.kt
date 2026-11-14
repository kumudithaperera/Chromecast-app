package app.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test fun commandsRoundTrip() {
        val all = listOf(
            Command.Move(3.5f, -12.0f),
            Command.Click,
            Command.Scroll(-40.2f),
            Command.Text("héllo wörld & 日本 \n+%"),
            Command.Key(KeyName.BACKSPACE),
            Command.Nav(NavAction.ALLOW_BLOCKED),
            Command.Nav(NavAction.READER),
            Command.Go("cats in space"),
            Command.Tab(TabAction.CLOSE),
            Command.Desktop(false),
            Command.Video(VideoAction.TOGGLE),
            Command.Video(VideoAction.SEEK, 754.5f),
            Command.Video(VideoAction.SKIP, -10f),
            Command.Video(VideoAction.RATE, 1.5f),
            Command.Video(VideoAction.VOLUME, -0.1f),
            Command.Video(VideoAction.MUTE),
            Command.Video(VideoAction.EXIT_FULLSCREEN),
            Command.Video(VideoAction.FULLSCREEN),
            Command.Nav(NavAction.ADS),
            Command.Nav(NavAction.HELPERS),
            Command.Tab(TabAction.SPLIT),
            Command.Zoom(ZoomAction.IN),
            Command.Zoom(ZoomAction.RESET),
            Command.Ping,
        )
        for (c in all) assertEquals(c, parseCommand(formatCommand(c)))
    }

    @Test fun malformedCommandsAreDropped() {
        listOf(
            "", " ", "X", "M", "M 1", "M 1 2 3", "M a b", "M NaN 1", "M Infinity 1", "S", "S x",
            "T", "T %zz", "K ESCAPE", "N EXPLODE", "B SIDEWAYS", "D 2", "G", "G %20",
            "V", "V PLAY", "V SEEK", "V SEEK abc", "V SEEK NaN", "V RATE", "V SKIP Infinity",
            "Z", "Z BIGGER", "V VOLUME", "V VOLUME NaN", "N PLAYER", "V SUBTITLE_SHIFT 1",
        ).forEach { assertNull("'$it'", parseCommand(it)) }
    }

    @Test fun movementIsClamped() {
        assertEquals(Command.Move(2000f, -2000f), parseCommand("M 99999 -99999"))
    }

    @Test fun videoValuesAreClamped() {
        assertEquals(Command.Video(VideoAction.SEEK, 0f), parseCommand("V SEEK -50"))
        assertEquals(Command.Video(VideoAction.SKIP, 3600f), parseCommand("V SKIP 99999"))
        assertEquals(Command.Video(VideoAction.RATE, 4f), parseCommand("V RATE 40"))
        assertEquals(Command.Video(VideoAction.RATE, 0.25f), parseCommand("V RATE 0"))
        assertEquals(Command.Video(VideoAction.VOLUME, 1f), parseCommand("V VOLUME 5"))
        assertEquals(Command.Video(VideoAction.VOLUME, -1f), parseCommand("V VOLUME -5"))
    }

    @Test fun standardPlaybackSpeedsSurviveTheWire() {
        // One decimal turned 0.75 into 0.8 and 1.25 into 1.3, which made the speed key skip speeds.
        for (r in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            assertEquals(Command.Video(VideoAction.RATE, r), parseCommand(formatCommand(Command.Video(VideoAction.RATE, r))))
            val v = VideoState(playing = true, position = 1f, duration = 60f, rate = r)
            assertEquals(VideoUpdate(v), parseVideo(formatVideo(v)))
        }
    }

    @Test fun videoStateRoundTripsAndRejectsGarbage() {
        val v = VideoState(playing = true, position = 754.5f, duration = 2241f, muted = true, rate = 1.5f, fullscreen = true)
        assertEquals(VideoUpdate(v), parseVideo(formatVideo(v)))
        assertEquals(VideoUpdate(null), parseVideo(formatVideo(null)))
        // A live stream and a player we can't reach both use -1.
        val live = VideoState(playing = true, position = 90f, duration = -1f)
        assertEquals(VideoUpdate(live), parseVideo(formatVideo(live)))
        assertTrue(live.isLive)
        assertFalse(VideoState(fullscreen = true).isLive)
        listOf("VIDEO", "VIDEO x", "VIDEO 1 2 3", "VIDEO 2 1 1 0 1 0", "VIDEO 1 NaN 1 0 1 0", "VIDEO 1 -5 1 0 1 0", "STATE a b")
            .forEach { assertNull("'$it'", parseVideo(it)) }
    }

    @Test fun videoLinesHaveExactlySixFields() {
        assertEquals(VideoUpdate(VideoState(true, 1f, 2f, false, 1f, false)), parseVideo("VIDEO 1 1.0 2.0 0 1.00 0"))
        assertNull(parseVideo("VIDEO 1 1 2 0 1 0 1"))
        assertNull(parseVideo("VIDEO 1 1 2 0 1 0 - 0 extra"))
    }

    @Test fun newStateFieldsRoundTripAndOldLinesStillParse() {
        val s = BrowserState("https://a.test/", "T", adsAllowed = true, zoom = 150, split = true, helpers = false)
        assertEquals(s, parseState(formatState(s)))
        val old = parseState("STATE https%3A%2F%2Fa.test%2F T 1 0 1 0 ~")!!
        assertEquals(100, old.zoom)
        assertTrue(old.helpers)
        assertEquals(MAX_ZOOM, parseState(formatState(BrowserState(zoom = 900)))!!.zoom)
        assertNull(parseState("STATE ~ ~ 1 0 1 0 ~ 0 abc 0 1"))
    }

    @Test fun overlongTextIsRejected() {
        assertNull(parseCommand("T " + "a".repeat(1001)))
    }

    @Test fun stateRoundTripAndTruncation() {
        val s = BrowserState("https://a.test/?q=x y", "Tïtle", 3, 1, desktop = false, loading = true, blocked = "ads.example")
        assertEquals(s, parseState(formatState(s)))
        val huge = BrowserState(url = "https://a.test/" + "x".repeat(10_000))
        assertTrue(formatState(huge).length < MAX_LINE)
        assertNull(parseState("STATE only three"))
    }

    @Test fun emptyFieldsSurviveTheRoundTrip() {
        val empty = BrowserState()
        assertEquals(empty, parseState(formatState(empty)))
        val tilde = BrowserState(url = "https://a.test/~me", title = "~")
        assertEquals(tilde, parseState(formatState(tilde)))
    }
}
