package app.tvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlesTest {
    @Test fun readsSrt() {
        val cues = parseSubtitles(
            "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i> there\r\nsecond line\r\n\r\n" +
                "2\r\n00:01:00,250 --> 00:01:03,000\r\n{\\an8}Top &amp; centre\r\n",
        )
        assertEquals(listOf(Cue(1f, 2.5f, "Hello there\nsecond line"), Cue(60.25f, 63f, "Top & centre")), cues)
    }

    @Test fun readsVttWithHeaderIdsAndSettings() {
        val cues = parseSubtitles(
            "WEBVTT\n\nNOTE made up\n\nintro\n00:05.000 --> 00:07.000 align:start line:0\n<v Ann>Hi <b>you</b>\n\n" +
                "1:00:00.000 --> 1:00:01.000\nLate\n",
        )
        assertEquals(listOf(Cue(5f, 7f, "Hi you"), Cue(3600f, 3601f, "Late")), cues)
    }

    @Test fun skipsBrokenCuesAndFindsTextAtATime() {
        val cues = parseSubtitles(
            "00:00:05.000 --> 00:00:04.000\nbackwards\n\n00:00:10.000 --> 00:00:20.000\nlong\n\n" +
                "00:00:12.000 --> 00:00:13.000\noverlap\n\ngarbage --> more\nno\n",
        )
        assertEquals(2, cues.size)
        assertEquals("", cueTextAt(cues, 9f))
        assertEquals("long", cueTextAt(cues, 10f))
        assertEquals("long\noverlap", cueTextAt(cues, 12.5f))
        assertEquals("", cueTextAt(cues, 25f))
        assertEquals("", cueTextAt(emptyList(), 1f))
    }
}
