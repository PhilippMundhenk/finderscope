package org.mundhenk.finderscope.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SPS rewrite that lets a decoder show each frame at once (no reordering buffer). */
class H264SpsTest {
    private fun sps(high: Boolean = false, vui: (H264Sps.BitWriter) -> Unit = { it.u(1, 0) }): ByteArray {
        val w = H264Sps.BitWriter()
        w.u(8, if (high) 100 else 66); w.u(8, 0); w.u(8, 30); w.ue(0)
        if (high) {
            w.ue(1); w.ue(0); w.ue(0); w.u(1, 0)
            w.u(1, 1) // scaling matrix present: list 0 sent (all deltas 0 = flat 8), the rest not
            w.u(1, 1); repeat(16) { w.se(0) }
            repeat(7) { w.u(1, 0) }
        }
        w.ue(0) // log2_max_frame_num - 4
        w.ue(0); w.ue(2) // poc type 0, log2_max_poc_lsb - 4
        w.ue(1) // ref frames
        w.u(1, 0); w.ue(19); w.ue(14) // 320 x 240
        w.u(1, 1); w.u(1, 1) // frame_mbs_only, direct_8x8
        w.u(1, 0) // no cropping
        vui(w)
        w.trailing()
        return byteArrayOf(0x67) + H264Sps.escape(w.bytes())
    }

    private fun restriction(w: H264Sps.BitWriter, reorder: Int, dec: Int) {
        w.u(1, 1); w.u(1, 1); w.ue(2); w.ue(1); w.ue(16); w.ue(16); w.ue(reorder); w.ue(dec)
    }

    /** VUI with timing (15 fps) and the given restriction or none. */
    private fun vuiTiming(w: H264Sps.BitWriter, restriction: Pair<Int, Int>?) {
        w.u(1, 1) // vui present
        w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0)
        w.u(1, 1); w.u(32, 1); w.u(32, 30); w.u(1, 1) // timing
        w.u(1, 0); w.u(1, 0); w.u(1, 0) // no HRD, pic_struct
        if (restriction == null) w.u(1, 0) else restriction(w, restriction.first, restriction.second)
    }

    @Test
    fun noVui_getsOneWithTheRestriction() {
        val expected = sps { w -> w.u(1, 1); repeat(8) { w.u(1, 0) }; restriction(w, 0, 1) }
        assertArrayEquals(expected, H264Sps.lowLatency(sps()))
        assertArrayEquals(expected, H264Sps.lowLatency(expected)) // already low latency: unchanged
    }

    @Test
    fun vuiWithTiming_keepsItAndAddsTheRestriction() {
        assertArrayEquals(sps(high = true) { vuiTiming(it, 0 to 1) }, H264Sps.lowLatency(sps(high = true) { vuiTiming(it, null) }))
        // an encoder's own restriction: reordering set to 0, the rest kept
        assertArrayEquals(sps { vuiTiming(it, 0 to 4) }, H264Sps.lowLatency(sps { vuiTiming(it, 2 to 4) }))
    }

    @Test
    fun hrdParametersAreSkipped() {
        fun hrd(w: H264Sps.BitWriter) { w.ue(0); w.u(4, 1); w.u(4, 2); w.ue(100); w.ue(200); w.u(1, 0); w.u(5, 23); w.u(5, 23); w.u(5, 23); w.u(5, 24) }
        fun vui(w: H264Sps.BitWriter, r: Pair<Int, Int>?) {
            w.u(1, 1); w.u(1, 1); w.u(8, 255); w.u(16, 1); w.u(16, 1) // aspect ratio 1:1 (extended)
            w.u(1, 1); w.u(1, 0) // overscan
            w.u(1, 1); w.u(3, 5); w.u(1, 0); w.u(1, 1); w.u(8, 1); w.u(8, 1); w.u(8, 1) // video signal + colour
            w.u(1, 1); w.ue(0); w.ue(0) // chroma location
            w.u(1, 0) // no timing
            w.u(1, 1); hrd(w); w.u(1, 1); hrd(w); w.u(1, 0) // NAL + VCL HRD, low_delay
            w.u(1, 0)
            if (r == null) w.u(1, 0) else restriction(w, r.first, r.second)
        }
        assertArrayEquals(sps { vui(it, 0 to 1) }, H264Sps.lowLatency(sps { vui(it, null) }))
    }

    @Test
    fun framesAndOddInput() {
        val s = sps()
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x33)
        val sc = byteArrayOf(0, 0, 0, 1)
        val frame = sc + s + sc + pps + sc + idr
        assertArrayEquals(sc + H264Sps.lowLatency(s)!! + sc + pps + sc + idr, H264Sps.lowLatencyFrame(frame))
        val noSps = sc + idr
        assertSame(noSps, H264Sps.lowLatencyFrame(noSps))
        // an SPS that cannot be parsed goes through unchanged; anything else is not an SPS
        val broken = sc + byteArrayOf(0x67, 0x42) + sc + idr
        assertArrayEquals(broken, H264Sps.lowLatencyFrame(broken))
        assertNull(H264Sps.lowLatency(byteArrayOf(0x67)))
        assertNull(H264Sps.lowLatency(idr))
        assertNull(H264Sps.lowLatency(ByteArray(0)))
        // 3-byte start codes and bytes before the first one
        val short = byteArrayOf(9) + byteArrayOf(0, 0, 1) + s + byteArrayOf(0, 0, 1) + idr
        assertArrayEquals(byteArrayOf(9) + byteArrayOf(0, 0, 1) + H264Sps.lowLatency(s)!! + byteArrayOf(0, 0, 1) + idr, H264Sps.lowLatencyFrame(short))
    }

    @Test
    fun emulationPrevention() {
        val raw = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 5)
        val escaped = H264Sps.escape(raw)
        assertArrayEquals(byteArrayOf(0, 0, 3, 0, 0, 3, 0, 1, 0, 0, 3, 2, 0, 0, 3, 3, 5), escaped)
        assertArrayEquals(raw, H264Sps.unescape(escaped))
        // a value that needs escaping inside the SPS survives the rewrite
        val zeros = sps { w -> w.u(1, 1); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 1); w.u(32, 0); w.u(32, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0) }
        assertTrue(zeros.toList().windowed(3).any { it == listOf<Byte>(0, 0, 3) })
        assertArrayEquals(sps { w -> w.u(1, 1); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 1); w.u(32, 0); w.u(32, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0); w.u(1, 0); restriction(w, 0, 1) }, H264Sps.lowLatency(zeros))
    }
}
