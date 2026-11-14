package app.tvremote.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Turns the phone's cursor commands into real input events for a WebView:
 * hover and wheel events as a mouse, taps as a finger. Main thread only.
 */
class PointerInjector(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val scrollFactor = ViewConfiguration.get(context).scaledVerticalScrollFactor.takeIf { it > 0f } ?: 64f

    private fun obtain(
        action: Int, x: Float, y: Float, source: Int, tool: Int, downTime: Long, vscroll: Float = 0f,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                size = 1f
                pressure = if (tool == MotionEvent.TOOL_TYPE_FINGER) 1f else 0f
                if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
            },
        )
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0,
        )
    }

    fun hover(target: View, x: Float, y: Float) {
        val e = obtain(MotionEvent.ACTION_HOVER_MOVE, x, y, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, SystemClock.uptimeMillis())
        target.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    fun tap(target: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = obtain(MotionEvent.ACTION_DOWN, x, y, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, t)
        target.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = obtain(MotionEvent.ACTION_UP, x, y, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, t)
            target.dispatchTouchEvent(up)
            up.recycle()
        }, TAP_MS)
    }

    /** [pixels] > 0 moves the page down with the fingers (scrolls toward the top). */
    fun scroll(target: View, x: Float, y: Float, pixels: Float) {
        val e = obtain(
            MotionEvent.ACTION_SCROLL, x, y, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE,
            SystemClock.uptimeMillis(), vscroll = pixels / scrollFactor,
        )
        target.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    private companion object {
        const val TAP_MS = 45L
    }
}
