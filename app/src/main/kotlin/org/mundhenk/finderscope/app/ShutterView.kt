package org.mundhenk.finderscope.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The shutter button of a camera: a white ring around an orange disc. Pressed (focusing), the disc
 * shrinks and darkens; not ready (picture being taken, card full), ring and disc are grey.
 */
class ShutterView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentPressed = ContextCompat.getColor(context, R.color.primary_pressed)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth / 2
        ring.color = if (isEnabled) 0xFFFFFFFF.toInt() else 0x77FFFFFF
        canvas.drawCircle(cx, cy, r, ring)
        val gap = (if (isPressed) 14 else 9) * density
        core.color = when { !isEnabled -> 0x55FFFFFF; isPressed -> accentPressed; else -> accent }
        canvas.drawCircle(cx, cy, r - gap, core)
    }
}
