package app.tvremote.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockTest {
    @Test fun formatsMinutesAndHours() {
        assertEquals("0:00", formatClock(0f))
        assertEquals("0:09", formatClock(9.9f))
        assertEquals("12:34", formatClock(754.5f))
        assertEquals("59:59", formatClock(3599f))
        assertEquals("1:02:05", formatClock(3725f))
    }

    @Test fun unknownTimesShowDashes() {
        assertEquals("--:--", formatClock(-1f))
        assertEquals("--:--", formatClock(Float.NaN))
        assertEquals("--:--", formatClock(Float.POSITIVE_INFINITY))
    }

    @Test fun endTimeAllowsForPlaybackSpeed() {
        // 100 s left at 1x ends 100 s from now; at 2x, 50 s.
        assertEquals(1_100_000L, endsAtMillis(1_000_000L, 20f, 120f, 1f))
        assertEquals(1_050_000L, endsAtMillis(1_000_000L, 20f, 120f, 2f))
        assertNull(endsAtMillis(0L, -1f, 120f, 1f)) // position unknown
        assertNull(endsAtMillis(0L, 20f, -1f, 1f)) // live
    }
}
