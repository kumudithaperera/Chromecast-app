package app.tvremote.phone.data

import kotlin.math.hypot
import kotlin.math.min

data class Pt(val x: Float, val y: Float)

sealed interface PadAction {
    data class Move(val dx: Float, val dy: Float) : PadAction
    data object Click : PadAction
    data class Scroll(val dy: Float) : PadAction
}

/**
 * Turns one touch gesture on the trackpad into cursor actions.
 *
 * One finger moves the cursor, with more distance per swipe the faster it goes.
 * A short tap that barely moved is a click. Two fingers scroll. Everything is
 * measured in dp so the feel doesn't depend on the phone's screen density.
 * Feed it every pointer update between first touch and last release.
 */
class TrackpadTracker(
    private val density: Float,
    private val speed: () -> Float,
    private val emit: (PadAction) -> Unit,
) {
    private var started = false
    private var startTime = 0L
    private var lastTime = 0L
    private var last: Pt? = null
    private var lastCount = 0
    private var maxPointers = 0
    private var travelPx = 0f
    private var armed = false

    fun update(timeMs: Long, points: List<Pt>) {
        if (points.isEmpty()) return
        if (!started) {
            started = true
            startTime = timeMs
            lastTime = timeMs
        }
        maxPointers = maxOf(maxPointers, points.size)
        val c = Pt(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size)
        val prev = last
        // A finger landing or lifting shifts the centroid; don't turn that into movement.
        if (prev == null || points.size != lastCount) {
            last = c
            lastCount = points.size
            lastTime = timeMs
            return
        }
        val dx = c.x - prev.x
        val dy = c.y - prev.y
        val dt = maxOf(1L, timeMs - lastTime)
        last = c
        lastTime = timeMs
        travelPx += hypot(dx, dy)
        if (!armed) {
            if (travelPx <= SLOP_DP * density) return
            armed = true
        }
        val dxDp = dx / density
        val dyDp = dy / density
        val s = speed()
        when (points.size) {
            1 -> {
                val v = hypot(dxDp, dyDp) / dt // dp per millisecond
                val gain = BASE_GAIN * s * (1f + min(MAX_BOOST, v * BOOST_PER_DP_MS))
                emit(PadAction.Move(dxDp * gain, dyDp * gain))
            }
            2 -> emit(PadAction.Scroll(dyDp * SCROLL_GAIN * s))
        }
    }

    /** Call when the last finger lifts. */
    fun end(timeMs: Long) {
        if (started && maxPointers == 1 && !armed && timeMs - startTime <= TAP_MS) emit(PadAction.Click)
        started = false
        last = null
        lastCount = 0
        maxPointers = 0
        travelPx = 0f
        armed = false
    }

    companion object {
        const val SLOP_DP = 8f
        const val TAP_MS = 250L
        const val BASE_GAIN = 1.7f
        const val MAX_BOOST = 1.8f
        const val BOOST_PER_DP_MS = 1.2f
        const val SCROLL_GAIN = 1.5f
    }
}
