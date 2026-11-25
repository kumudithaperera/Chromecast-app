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

    @Test fun formatsTimeAgo() {
        val now = 10_000_000_000L
        assertEquals("never", formatAgo(0L, now))
        assertEquals("just now", formatAgo(now - 30_000L, now))
        assertEquals("1 minute ago", formatAgo(now - 60_000L, now))
        assertEquals("5 hours ago", formatAgo(now - 5 * 3_600_000L, now))
        assertEquals("2 days ago", formatAgo(now - 2 * 86_400_000L, now))
        assertEquals("just now", formatAgo(now + 60_000L, now)) // a clock that runs ahead
    }
}
