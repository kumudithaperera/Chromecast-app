package app.tvremote.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** The on-screen pointer. Sits above everything, never takes input, fades when idle. */
class CursorView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 22f * d)
        lineTo(5.5f * d, 17f * d)
        lineTo(9.5f * d, 26f * d)
        lineTo(13f * d, 24.5f * d)
        lineTo(9f * d, 16f * d)
        lineTo(16f * d, 16f * d)
        close()
    }
    private var px = 0f
    private var py = 0f
    private val fade = Runnable { animate().alpha(0f).setDuration(300).start() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    fun moveTo(x: Float, y: Float) {
        px = x
        py = y
        invalidate()
        wake()
    }

    /** Show the pointer and start the idle timer again. */
    fun wake() {
        animate().cancel()
        alpha = 1f
        removeCallbacks(fade)
        postDelayed(fade, IDLE_MS)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(px, py)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fade)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val IDLE_MS = 5_000L
    }
}
