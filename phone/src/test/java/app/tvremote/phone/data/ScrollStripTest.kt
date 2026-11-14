package app.tvremote.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollStripTest {
    @Test fun draggingDownScrollsDownThePage() {
        // The TV treats a negative value as "scroll further down the page".
        assertTrue(stripScroll(dragPx = 40f, density = 2f, speed = 1f) < 0f)
        assertTrue(stripScroll(dragPx = -40f, density = 2f, speed = 1f) > 0f)
    }

    @Test fun distanceIsMeasuredInDpAndScaledByGainAndSpeed() {
        assertEquals(-60f, stripScroll(dragPx = 40f, density = 2f, speed = 1f), 0.001f) // 20 dp x 3
        assertEquals(-30f, stripScroll(dragPx = 40f, density = 2f, speed = 0.5f), 0.001f)
        // Same physical drag on a denser screen moves the page the same amount.
        assertEquals(stripScroll(40f, 2f, 1f), stripScroll(60f, 3f, 1f), 0.001f)
        assertEquals(0f, stripScroll(0f, 2f, 1f), 0f)
    }
}
