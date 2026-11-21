package org.mundhenk.finderscope.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.mundhenk.finderscope.core.AfCell
import org.mundhenk.finderscope.core.AfGrid

/** The camera's AF frames over the live view: green where it focused, a red centre frame when it could not. */
class AfOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density }
    private val okColor = ContextCompat.getColor(context, R.color.af_ok)
    private val failColor = ContextCompat.getColor(context, R.color.af_fail)
    var grid: AfGrid = AfGrid.COMPACT
    var cells: List<AfCell> = emptyList(); private set
    var failed = false; private set

    fun show(ok: Boolean, focused: List<AfCell>) {
        failed = !ok
        cells = if (ok) focused else listOf(AfCell(grid.rows / 2, grid.columns / 2))
        invalidate()
    }

    fun clear() { cells = emptyList(); failed = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (cells.isEmpty()) return
        // the frames cover the middle 60 % x 50 % of the picture, as on the cameras' screens
        val areaW = width * 0.6f; val areaH = height * 0.5f
        val left = (width - areaW) / 2; val top = (height - areaH) / 2
        val cw = areaW / grid.columns; val ch = areaH / grid.rows
        val inset = 3f * resources.displayMetrics.density
        paint.color = if (failed) failColor else okColor
        for (c in cells) {
            val x = left + c.column * cw; val y = top + c.row * ch
            canvas.drawRect(x + inset, y + inset, x + cw - inset, y + ch - inset, paint)
        }
    }
}
