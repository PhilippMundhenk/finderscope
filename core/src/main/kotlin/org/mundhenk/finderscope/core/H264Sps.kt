package org.mundhenk.finderscope.core

/**
 * Tells an H.264 decoder that the stream never reorders frames, so it can show each frame as soon
 * as it is decoded.
 *
 * Without the VUI "bitstream restriction" in the SPS a decoder must assume the worst and keeps
 * a whole picture buffer of frames before it outputs anything. On a phone that was 0.5 to 1 s of
 * delay for the NX1000 live view, with the frames coming out in bursts (field log: 15 frames/s
 * from the camera, "decoder 553 ms"). The cameras' encoders use no B-frames, so the restriction
 * `max_num_reorder_frames = 0` is true for their streams; [lowLatency] adds it (or sets it, where
 * the SPS has a restriction already) and leaves everything else bit for bit as it was.
 */
object H264Sps {
    /**
     * [frame] (Annex B access unit) with every SPS rewritten for low latency; the same array when
     * there is no SPS in it or one cannot be parsed.
     */
    fun lowLatencyFrame(frame: ByteArray): ByteArray {
        val units = AviFrameReader.nalUnits(frame)
        if (units.none { it.first == 7 }) return frame
        val out = java.io.ByteArrayOutputStream(frame.size + 16)
        // anything before the first start code stays as it is
        val first = indexOfStart(frame)
        if (first > 0) out.write(frame, 0, first)
        for ((type, unit) in units) {
            if (type != 7) { out.write(unit); continue }
            val sc = if (unit.size > 3 && unit[2].toInt() == 0) 4 else 3
            val nal = unit.copyOfRange(sc, unit.size)
            val fixed = lowLatency(nal)
            out.write(unit, 0, sc)
            out.write(fixed ?: nal)
        }
        return out.toByteArray()
    }

    private fun indexOfStart(d: ByteArray): Int {
        for (i in 0 until d.size - 2) if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && (d[i + 2].toInt() == 1 || (i + 3 < d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1))) return i
        return 0
    }

    /**
     * One SPS NAL unit (header byte first, no start code; trailing zero bytes allowed) rewritten
     * with a bitstream restriction of no reordering; null if it is no SPS or cannot be parsed.
     */
    fun lowLatency(nal: ByteArray): ByteArray? = runCatching {
        if (nal.isEmpty() || (nal[0].toInt() and 0x1f) != 7) return null
        val rbsp = unescape(nal.copyOfRange(1, nal.size))
        val r = BitReader(rbsp)
        val profile = r.u(8); r.u(8); r.u(8); r.ue()
        if (profile in HIGH_PROFILES) {
            val chroma = r.ue()
            if (chroma == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) for (i in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        val refFrames = r.ue()
        r.u(1); r.ue(); r.ue()
        if (r.u(1) == 0) r.u(1)
        r.u(1)
        if (r.u(1) == 1) { r.ue(); r.ue(); r.ue(); r.ue() }
        val vuiFlagAt = r.pos
        val w = BitWriter()
        if (r.u(1) == 0) {
            w.copy(rbsp, 0, vuiFlagAt)
            w.u(1, 1)
            // a VUI with nothing but the restriction
            repeat(8) { w.u(1, 0) } // aspect ratio, overscan, video signal, chroma location, timing, NAL HRD, VCL HRD, pic_struct
            w.u(1, 1); writeRestriction(w, refFrames)
        } else {
            if (r.u(1) == 1 && r.u(8) == 255) { r.u(16); r.u(16) }
            if (r.u(1) == 1) r.u(1)
            if (r.u(1) == 1) { r.u(3); r.u(1); if (r.u(1) == 1) { r.u(8); r.u(8); r.u(8) } }
            if (r.u(1) == 1) { r.ue(); r.ue() }
            if (r.u(1) == 1) { r.u(32); r.u(32); r.u(1) }
            val nalHrd = r.u(1) == 1; if (nalHrd) skipHrd(r)
            val vclHrd = r.u(1) == 1; if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.u(1)
            r.u(1)
            val restrictionAt = r.pos
            w.copy(rbsp, 0, restrictionAt)
            w.u(1, 1)
            if (r.u(1) == 1) {
                // keep the encoder's motion vector limits, only the reordering changes
                val mv = r.u(1); val bytes = r.ue(); val bits = r.ue(); val h = r.ue(); val v = r.ue(); r.ue()
                val dec = r.ue()
                w.u(1, mv); w.ue(bytes); w.ue(bits); w.ue(h); w.ue(v); w.ue(0); w.ue(maxOf(dec, 1))
            } else writeRestriction(w, refFrames)
        }
        w.trailing()
        byteArrayOf(nal[0]) + escape(w.bytes())
    }.getOrNull()

    private fun writeRestriction(w: BitWriter, refFrames: Int) {
        w.u(1, 1) // motion vectors over picture boundaries
        w.ue(2); w.ue(1); w.ue(16); w.ue(16) // max bytes per picture / bits per MB / MV lengths: no limit
        w.ue(0) // max_num_reorder_frames
        w.ue(maxOf(refFrames, 1)) // max_dec_frame_buffering
    }

    private fun skipHrd(r: BitReader) {
        val n = r.ue()
        r.u(4); r.u(4)
        repeat(n + 1) { r.ue(); r.ue(); r.u(1) }
        r.u(5); r.u(5); r.u(5); r.u(5)
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8; var next = 8
        repeat(size) { if (next != 0) { next = (last + r.se() + 256) % 256 }; if (next != 0) last = next }
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Removes the emulation prevention bytes (00 00 03 -> 00 00). */
    fun unescape(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xff
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Puts the emulation prevention bytes back (00 00 0x with x <= 3 -> 00 00 03 0x). */
    fun escape(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size + 8)
        var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xff
            if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal class BitReader(private val b: ByteArray) {
        var pos = 0; private set
        fun u(n: Int): Int {
            var v = 0L
            repeat(n) {
                if (pos >= b.size * 8) throw IllegalArgumentException("SPS ends early")
                v = (v shl 1) or ((b[pos / 8].toInt() shr (7 - pos % 8)) and 1).toLong(); pos++
            }
            return v.toInt()
        }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) { zeros++; if (zeros > 31) throw IllegalArgumentException("bad exp-Golomb code") }
            return ((1L shl zeros) - 1 + u(zeros)).toInt()
        }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
    }

    internal class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun u(bits: Int, value: Int) { for (i in bits - 1 downTo 0) bit((value shr i) and 1) }
        fun ue(value: Int) {
            val v = value + 1
            val len = 32 - Integer.numberOfLeadingZeros(v)
            repeat(len - 1) { bit(0) }
            u(len, v)
        }
        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)
        /** The first [bits] bits of [src]. */
        fun copy(src: ByteArray, from: Int, bits: Int) { for (p in from until bits) bit((src[p / 8].toInt() shr (7 - p % 8)) and 1) }
        fun trailing() { bit(1); while (n != 0) bit(0) }
        private fun bit(x: Int) { cur = (cur shl 1) or x; n++; if (n == 8) { out.write(cur); cur = 0; n = 0 } }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
