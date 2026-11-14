package app.tvremote.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadTrackerTest {
    private val actions = mutableListOf<PadAction>()
    private fun tracker(density: Float = 2f, speed: Float = 1f) = TrackpadTracker(density, { speed }) { actions += it }

    /** Drags one finger from (x0, y0) by (dx, dy) px in [steps] frames of [frameMs]. */
    private fun TrackpadTracker.drag(dx: Float, dy: Float, steps: Int, frameMs: Long, x0: Float = 100f, y0: Float = 100f) {
        var t = 0L
        update(t, listOf(Pt(x0, y0)))
        for (i in 1..steps) {
            t += frameMs
            update(t, listOf(Pt(x0 + dx * i / steps, y0 + dy * i / steps)))
        }
        end(t)
    }

    @Test fun quickTapClicks() {
        val t = tracker()
        t.update(0, listOf(Pt(50f, 50f)))
        t.update(60, listOf(Pt(52f, 51f)))
        t.end(90)
        assertEquals(listOf<PadAction>(PadAction.Click), actions)
    }

    @Test fun slowPressWithoutMovingIsNotAClick() {
        val t = tracker()
        t.update(0, listOf(Pt(50f, 50f)))
        t.end(600)
        assertTrue(actions.isEmpty())
    }

    @Test fun jitterInsideTheSlopMovesNothing() {
        val t = tracker(density = 2f)
        t.update(0, listOf(Pt(50f, 50f)))
        t.update(16, listOf(Pt(54f, 50f))) // 4 px = 2 dp, below the 8 dp slop
        t.update(32, listOf(Pt(50f, 54f)))
        t.end(48)
        assertEquals(listOf<PadAction>(PadAction.Click), actions)
    }

    @Test fun dragMovesTheCursorInTheSameDirection() {
        tracker().drag(dx = 200f, dy = -100f, steps = 20, frameMs = 16)
        val moves = actions.filterIsInstance<PadAction.Move>()
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.dx > 0 && it.dy < 0 })
        assertTrue(actions.none { it is PadAction.Click })
    }

    @Test fun fasterSwipesTravelFartherThanSlowOnes() {
        val slow = mutableListOf<PadAction>()
        val fast = mutableListOf<PadAction>()
        TrackpadTracker(2f, { 1f }) { slow += it }.drag(400f, 0f, steps = 40, frameMs = 32)
        TrackpadTracker(2f, { 1f }) { fast += it }.drag(400f, 0f, steps = 40, frameMs = 4)
        fun total(l: List<PadAction>) = l.filterIsInstance<PadAction.Move>().sumOf { it.dx.toDouble() }
        assertTrue("fast ${total(fast)} should beat slow ${total(slow)}", total(fast) > total(slow) * 1.3)
    }

    @Test fun densityDoesNotChangeTheFeel() {
        val low = mutableListOf<PadAction>()
        val high = mutableListOf<PadAction>()
        // Same physical swipe: 100 dp over 30 frames.
        TrackpadTracker(1f, { 1f }) { low += it }.drag(100f, 0f, 30, 16)
        TrackpadTracker(3f, { 1f }) { high += it }.drag(300f, 0f, 30, 16)
        fun total(l: List<PadAction>) = l.filterIsInstance<PadAction.Move>().sumOf { it.dx.toDouble() }
        assertEquals(total(low), total(high), total(low) * 0.15)
    }

    @Test fun speedSettingScalesMovement() {
        val a = mutableListOf<PadAction>()
        val b = mutableListOf<PadAction>()
        TrackpadTracker(2f, { 0.5f }) { a += it }.drag(300f, 0f, 30, 16)
        TrackpadTracker(2f, { 2f }) { b += it }.drag(300f, 0f, 30, 16)
        fun total(l: List<PadAction>) = l.filterIsInstance<PadAction.Move>().sumOf { it.dx.toDouble() }
        assertTrue(total(b) > total(a) * 3)
    }

    @Test fun twoFingersScrollAndNeverMoveOrClick() {
        val t = tracker()
        var time = 0L
        t.update(time, listOf(Pt(100f, 100f), Pt(160f, 100f)))
        for (i in 1..15) {
            time += 16
            t.update(time, listOf(Pt(100f, 100f + i * 10), Pt(160f, 100f + i * 10)))
        }
        t.end(time)
        val scrolls = actions.filterIsInstance<PadAction.Scroll>()
        assertTrue(scrolls.isNotEmpty())
        assertTrue(scrolls.all { it.dy > 0 })
        assertTrue(actions.none { it is PadAction.Move || it is PadAction.Click })
    }

    @Test fun secondFingerLandingDoesNotJumpTheCursor() {
        val t = tracker()
        t.update(0, listOf(Pt(100f, 100f)))
        t.update(16, listOf(Pt(100f, 100f), Pt(300f, 100f))) // centroid jumps 100 px
        t.update(32, listOf(Pt(100f, 100f), Pt(300f, 100f)))
        t.end(48)
        assertTrue(actions.none { it is PadAction.Move })
    }

    @Test fun trackerIsReusableAcrossGestures() {
        val t = tracker()
        t.update(0, listOf(Pt(10f, 10f))); t.end(50)
        t.update(1000, listOf(Pt(10f, 10f))); t.end(1050)
        assertEquals(2, actions.count { it is PadAction.Click })
    }
}
