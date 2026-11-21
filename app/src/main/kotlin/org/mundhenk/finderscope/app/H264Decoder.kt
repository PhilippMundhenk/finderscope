package org.mundhenk.finderscope.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.mundhenk.finderscope.core.VideoFrame
import java.util.concurrent.atomic.AtomicLong

/** Decodes the H.264 live view straight onto a Surface (hardware decoder where there is one). */
class H264Decoder(surface: Surface, width: Int, height: Int) {
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
        if (!keySeen && !frame.keyFrame) return // a decoder needs SPS/PPS + IDR first
        keySeen = true
        val idx = codec.dequeueInputBuffer(100_000)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx)!!
            buf.clear(); buf.put(frame.data)
            codec.queueInputBuffer(idx, 0, frame.data.size, ptsUs, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out >= 0) { codec.releaseOutputBuffer(out, true); decoded.incrementAndGet() } else if (out != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
        }
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
