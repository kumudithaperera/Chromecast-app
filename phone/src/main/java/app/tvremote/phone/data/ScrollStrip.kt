package app.tvremote.phone.data

/** How far the page moves per dp of finger travel along the scroll strip. */
const val STRIP_GAIN = 3f

/** One tap on the strip's arrows moves the page about a screenful. */
const val STRIP_PAGE_STEP_DP = 280f

/**
 * Turns a drag along the scroll strip into a scroll command for the TV.
 *
 * The TV takes dp, positive meaning the page content moves down (scrolls toward the top).
 * The strip works like a scrollbar, so dragging DOWN scrolls DOWN the page, which is the opposite sign.
 * The strip is short and pages are long, hence the gain.
 */
fun stripScroll(dragPx: Float, density: Float, speed: Float): Float = -(dragPx / density) * STRIP_GAIN * speed
