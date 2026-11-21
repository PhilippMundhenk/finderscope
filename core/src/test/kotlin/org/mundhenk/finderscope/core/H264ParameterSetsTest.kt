package org.mundhenk.finderscope.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class H264ParameterSetsTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e)
    private val pps = byteArrayOf(0, 0, 1, 0x68, 0xce.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
    private val p = byteArrayOf(0, 0, 1, 0x41, 0x33)

    @Test
    fun nalUnits() {
        assertEquals(listOf(7, 8, 5), AviFrameReader.nalUnits(sps + pps + idr).map { it.first })
        assertArrayEquals(pps, AviFrameReader.nalUnits(sps + pps + idr)[1].second)
        assertEquals(emptyList<Int>(), AviFrameReader.nalUnits(byteArrayOf(1, 2, 3)).map { it.first })
        assertEquals(emptyList<Int>(), AviFrameReader.nalUnits(byteArrayOf(0, 0, 1)).map { it.first }) // start code, no payload
    }

    /** A viewer that joined after the parameter sets went by gets them in front of the next key frame. */
    @Test
    fun primesAKeyFrameThatLacksThem() {
        val cache = H264ParameterSets()
        assertFalse(cache.known)
        assertSame(idr, cache.prime(idr)) // nothing known yet
        cache.observe(p)
        cache.observe(sps + pps + idr) // seen while no decoder existed
        assertTrue(cache.known)
        assertArrayEquals(sps + pps + idr, cache.prime(idr))
        val own = sps + pps + idr
        assertSame(own, cache.prime(own)) // carries its own
        assertArrayEquals(sps, cache.sps); assertArrayEquals(pps, cache.pps)
    }
}
