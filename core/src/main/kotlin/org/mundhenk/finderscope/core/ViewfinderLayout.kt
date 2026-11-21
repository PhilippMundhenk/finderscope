package org.mundhenk.finderscope.core

import kotlin.math.roundToInt

/** A rectangle in pixels: left, top, right, bottom. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height
    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/**
 * Where the live picture and the controls go, for any window shape: a phone held upright, a
 * foldable's cover or inner screen, landscape, split screen. Like a camera app: the picture comes
 * first and is never covered when that can be avoided.
 *
 * * [Arrangement.STACKED] (tall windows): the top bar, the picture right below it, the controls
 *   in the space underneath.
 * * [Arrangement.SIDE] (wide windows): the settings in a column on the left, the shutter column on
 *   the right, the picture between them.
 * * Only when keeping the picture clear would shrink it below [minShare] of the largest picture
 *   the window can hold does the picture take the whole window and the controls float over its
 *   edges ([Plan.overlay]).
 */
object ViewfinderLayout {
    enum class Arrangement { STACKED, SIDE }

    data class Plan(
        val arrangement: Arrangement,
        /** The controls lie over the picture (then they get scrims). */
        val overlay: Boolean,
        /** The picture as it appears on screen (after rotation). */
        val picture: Box,
        /** Free space for notices that does not cover the picture, if there is enough of it. */
        val noticeSpace: Box?,
    )

    /**
     * [window] in pixels; [insets] the system bars / cutout (amounts on each side); [aspect] the
     * picture's width / height as shown; [barStacked] the top bar's height, [barSide] the
     * settings column's width; [controlsStacked] the height of the controls under the picture,
     * [controlsSide] the width of the shutter column; [minNotice] the least height for a notice.
     */
    fun plan(
        window: Box, insets: Box, aspect: Double,
        barStacked: Int, barSide: Int, controlsStacked: Int, controlsSide: Int, minNotice: Int, minShare: Double = 0.6,
    ): Plan {
        val usable = Box(window.left + insets.left, window.top + insets.top, window.right - insets.right, window.bottom - insets.bottom)
        val full = fit(usable, aspect, top = false)
        val stackedFree = Box(usable.left, usable.top + barStacked, usable.right, usable.bottom - controlsStacked)
        val sideFree = Box(usable.left + barSide, usable.top, usable.right - controlsSide, usable.bottom)
        val stacked = fit(stackedFree, aspect, top = true)
        val side = fit(sideFree, aspect, top = false)
        val best = if (stacked.area >= side.area) Arrangement.STACKED to stacked else Arrangement.SIDE to side
        if (best.second.area >= minShare * full.area) {
            val (arrangement, picture) = best
            // stacked: the gap between the picture and the controls takes the notices
            val gap = if (arrangement == Arrangement.STACKED) Box(usable.left, picture.bottom, usable.right, stackedFree.bottom) else null
            return Plan(arrangement, false, picture, gap?.takeIf { it.height >= minNotice })
        }
        val arrangement = if (usable.width > usable.height) Arrangement.SIDE else Arrangement.STACKED
        return Plan(arrangement, true, full, null)
    }

    /** The largest box of [aspect] in [space], centred (or at its top, for [top]). Empty if there is no room. */
    fun fit(space: Box, aspect: Double, top: Boolean): Box {
        if (space.width <= 0 || space.height <= 0 || aspect <= 0) return Box(space.left, space.top, space.left, space.top)
        var w = space.width.toDouble(); var h = w / aspect
        if (h > space.height) { h = space.height.toDouble(); w = h * aspect }
        val wi = w.roundToInt(); val hi = h.roundToInt()
        val l = space.left + (space.width - wi) / 2
        val t = if (top) space.top else space.top + (space.height - hi) / 2
        return Box(l, t, l + wi, t + hi)
    }
}
