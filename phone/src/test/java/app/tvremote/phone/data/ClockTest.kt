package app.tvremote.phone.data

import org.junit.Assert.assertEquals
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
}
