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
class H264Decoder(surface: Surface, width: Int, height: Int, private val parameterSets: H264ParameterSets, private val onSize: (Int, Int) -> Unit = { _, _ -> }) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private var started = false
    private var keySeen = false
    val decoded = AtomicLong(0)

    init {
        // no csd: the cameras send SPS/PPS in band in front of every key frame
        codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, if (width > 0) width else 640, if (height > 0) height else 480), surface, null, 0)
        codec.start()
        started = true
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
            codec.queueInputBuffer(idx, 0, data.size, ptsUs, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out >= 0) { codec.releaseOutputBuffer(out, true); decoded.incrementAndGet() }
            else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) sizeOf(codec.outputFormat)?.let { (w, h) -> onSize(w, h) }
            else return
        }
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
        runCatching { codec.stop() }
        codec.release()
    }
}
