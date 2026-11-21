package org.mundhenk.finderscope.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.mundhenk.finderscope.core.H264ParameterSets
import org.mundhenk.finderscope.core.VideoFrame
import java.util.concurrent.atomic.AtomicLong

/** Decodes the H.264 live view straight onto a Surface (hardware decoder where there is one). */
/**
 * [onSize] gets the picture size the decoder really produces: the AVI header of some cameras (the
 * NX1000) always says 320x240 whatever the stream is.
 */
class H264Decoder(
    surface: Surface, width: Int, height: Int, private val parameterSets: H264ParameterSets,
    private val onSize: (Int, Int) -> Unit = { _, _ -> },
    /** A line for the log every few seconds: frames per second and how long the decoder took. */
    private val onStats: (String) -> Unit = {},
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    @Volatile private var started = false
    private var keySeen = false
    val decoded = AtomicLong(0)
    /** When each frame went in (by presentation time), to measure the decoder's delay. */
    private val queuedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private var statsFrom = System.nanoTime()
    private var statsIn = 0
    private var statsOut = 0
    private var statsDelayNs = 0L
    private val output: Thread

    init {
        // no csd: the cameras send SPS/PPS in band in front of every key frame
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, if (width > 0) width else 640, if (height > 0) height else 480)
        // live view: every frame out as soon as it is decoded, no reordering buffer, real-time priority
        if (android.os.Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        codec.configure(format, surface, null, 0)
        codec.start()
        started = true
        // pictures are shown the moment the decoder has them, not when the next frame comes in
        output = Thread({ drain() }, "h264-output").apply { isDaemon = true; start() }
    }

    fun feed(frame: VideoFrame, ptsUs: Long) {
        // a decoder needs SPS/PPS and a key frame first; the remembered parameter sets go in front of it
        if (!keySeen && (!frame.keyFrame || !parameterSets.known)) return
        val data = if (!keySeen) parameterSets.prime(frame.data) else frame.data
        keySeen = true
        val idx = codec.dequeueInputBuffer(100_000)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx)!!
            buf.clear(); buf.put(data)
            queuedAt[ptsUs] = System.nanoTime()
            codec.queueInputBuffer(idx, 0, data.size, ptsUs, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            synchronized(this) { statsIn++ }
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (started) {
            val out = try { codec.dequeueOutputBuffer(info, 20_000) } catch (e: IllegalStateException) { return }
            when {
                out >= 0 -> {
                    try { codec.releaseOutputBuffer(out, true) } catch (e: IllegalStateException) { return }
                    decoded.incrementAndGet()
                    queuedAt.remove(info.presentationTimeUs)?.let { t -> synchronized(this) { statsOut++; statsDelayNs += System.nanoTime() - t } }
                    report()
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sizeOf(codec.outputFormat)?.let { (w, h) -> onSize(w, h) }
            }
        }
    }

    private fun report() {
        val line = synchronized(this) {
            val now = System.nanoTime()
            val secs = (now - statsFrom) / 1e9
            if (secs < 10 || statsOut == 0) return
            val l = "live view: %.1f frames/s from the camera, %.1f shown, decoder %d ms".format(statsIn / secs, statsOut / secs, statsDelayNs / statsOut / 1_000_000)
            statsFrom = now; statsIn = 0; statsOut = 0; statsDelayNs = 0
            if (queuedAt.size > 100) queuedAt.clear()
            l
        }
        onStats(line)
    }

    /** The visible size: the crop rectangle where the decoder gives one, else width x height. */
    private fun sizeOf(f: MediaFormat): Pair<Int, Int>? {
        fun int(k: String) = if (f.containsKey(k)) f.getInteger(k) else null
        val l = int("crop-left"); val r = int("crop-right"); val t = int("crop-top"); val b = int("crop-bottom")
        val w = if (l != null && r != null) r - l + 1 else int(MediaFormat.KEY_WIDTH)
        val h = if (t != null && b != null) b - t + 1 else int(MediaFormat.KEY_HEIGHT)
        return if (w != null && h != null && w > 0 && h > 0) w to h else null
    }

    /** After the live view was reopened: start from the next key frame. */
    fun resync() { keySeen = false }

    fun release() {
        if (!started) return
        started = false
        runCatching { output.join(200) }
        runCatching { codec.stop() }
        codec.release()
    }
}
