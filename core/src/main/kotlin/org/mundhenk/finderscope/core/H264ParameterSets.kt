package org.mundhenk.finderscope.core

/**
 * Remembers the stream's latest SPS / PPS (H.264 parameter sets). A decoder must see them before
 * its first key frame; the cameras send them in front of key frames, but a viewer that starts in
 * the middle of the stream (or after its surface came up late) may have missed them. [prime]
 * puts the remembered ones in front of a key frame that lacks them.
 */
class H264ParameterSets {
    @Volatile var sps: ByteArray? = null; private set
    @Volatile var pps: ByteArray? = null; private set

    /** Looks at every frame, also those that are not decoded. */
    fun observe(frame: ByteArray) {
        for ((type, nal) in AviFrameReader.nalUnits(frame)) when (type) { 7 -> sps = nal; 8 -> pps = nal }
    }

    val known: Boolean get() = sps != null && pps != null

    /** [frame] with the remembered SPS/PPS in front, unless it carries its own. */
    fun prime(frame: ByteArray): ByteArray {
        val types = AviFrameReader.nalUnits(frame).map { it.first }
        val s = sps; val p = pps
        return if (7 in types || s == null || p == null) frame else s + p + frame
    }
}
