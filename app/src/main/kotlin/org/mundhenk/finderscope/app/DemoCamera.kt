package org.mundhenk.finderscope.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import org.mundhenk.finderscope.core.RvfCameraSimulator
import org.mundhenk.finderscope.core.VideoCodec
import java.io.ByteArrayOutputStream

/**
 * The demo: a simulated camera on this phone (loopback), showing a drawn landscape that drifts so
 * the live view looks alive. Pictures are the same landscape at a larger size. No real photo, no
 * camera needed; the screenshots in the docs come from here too.
 */
object DemoCamera {
    @Volatile var running: RvfCameraSimulator? = null; private set

    fun start(): RvfCameraSimulator {
        running?.let { return it }
        val frames = (0 until 24).map { jpeg(scene(480, 360, it / 24f), 70) }
        val sim = RvfCameraSimulator(
            RvfCameraSimulator.Profile.DEMO, frames, VideoCodec.MJPEG, 480, 360, fps = 12,
            photo = jpeg(scene(1600, 1200, 0.3f), 85), log = { LogBus.log("Demo camera: $it") },
        ).start()
        running = sim
        return sim
    }

    fun stop() { running?.stop(); running = null }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Sky, sun, two ranges of hills and a lake; [t] in 0..1 moves the sun and the clouds. */
    fun scene(w: Int, h: Int, t: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, Color.rgb(64, 120, 200), Color.rgb(250, 200, 150), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.rgb(255, 214, 102)
        c.drawCircle(w * (0.2f + 0.6f * t), h * 0.3f, h * 0.08f, p)
        p.color = Color.argb(200, 255, 255, 255)
        for (i in 0 until 3) {
            val x = ((w * (i * 0.4f + t)) % (w * 1.2f)) - w * 0.1f
            c.drawOval(x, h * (0.12f + i * 0.06f), x + w * 0.22f, h * (0.18f + i * 0.06f), p)
        }
        fun hills(color: Int, base: Float, amp: Float, phase: Float) {
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                for (x in 0..w step 8) lineTo(x.toFloat(), h * base - amp * h * kotlin.math.sin((x / w.toFloat()) * 6.3f + phase).toFloat())
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.color = color; c.drawPath(path, p)
        }
        hills(Color.rgb(90, 120, 150), 0.62f, 0.06f, 1f)
        hills(Color.rgb(50, 95, 70), 0.72f, 0.05f, 2.5f)
        p.color = Color.rgb(70, 130, 170)
        c.drawRect(0f, h * 0.82f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
