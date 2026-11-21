package org.mundhenk.finderscope.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mundhenk.finderscope.core.ViewfinderLayout.Arrangement

/**
 * The window shapes of a foldable (cover screen, inner screen upright and landscape, split
 * screen both ways) at density 2.625: where the picture and the controls go.
 */
class ViewfinderLayoutTest {
    private val d = 2.625
    private fun dp(v: Int) = (v * d).toInt()
    private val none = Box(0, 0, 0, 0)

    /** [zoom]: the camera zooms over Wi-Fi (compacts), so the shutter column also holds the zoom pill. */
    private fun plan(w: Int, h: Int, aspect: Double = 4.0 / 3, insets: Box = none, zoom: Boolean = true) = ViewfinderLayout.plan(
        Box(0, 0, w, h), insets, aspect,
        barStacked = dp(56), barSide = dp(72), controlsStacked = dp(if (zoom) 176 else 120), controlsSide = dp(if (zoom) 176 else 112), minNotice = dp(48),
    )

    /** Picture, top bar and controls of a stacked plan do not overlap; the picture is centred between bar and controls. */
    private fun assertClearStacked(p: ViewfinderLayout.Plan, w: Int, h: Int, controls: Int = dp(176)) {
        assertEquals(Arrangement.STACKED, p.arrangement); assertFalse(p.overlay)
        assertTrue(p.picture.top >= dp(56))
        assertTrue(p.picture.bottom <= h - dp(120))
        // centred between the top bar and the controls (rounding: a pixel either way)
        assertTrue("${p.picture}", kotlin.math.abs((p.picture.top - dp(56)) - (h - controls - p.picture.bottom)) <= 1)
        assertEquals(w, p.picture.width)
    }

    @Test
    fun coverScreenUpright() { // 1248 x 1972
        val p = plan(1248, 1972)
        assertClearStacked(p, 1248, 1972)
        assertEquals(936, p.picture.height)
        assertTrue(p.noticeSpace!!.height >= dp(48)) // the gap under the picture takes the notices
    }

    @Test
    fun splitSideBySide() { // a tall half of the inner screen, 993 x 1392
        val p = plan(993, 1392)
        assertClearStacked(p, 993, 1392)
    }

    @Test
    fun innerScreenUpright() { // 1510 x 2000
        assertClearStacked(plan(1510, 2000), 1510, 2000)
    }

    @Test
    fun landscapeWide() { // 1972 x 1248: the controls move into the columns beside the picture
        for (zoom in listOf(true, false)) {
            val p = plan(1972, 1248, zoom = zoom)
            assertEquals(Arrangement.SIDE, p.arrangement); assertFalse(p.overlay)
            assertTrue(p.picture.left >= dp(72)); assertTrue(p.picture.right <= 1972 - dp(if (zoom) 176 else 112))
            assertNull(p.noticeSpace)
        }
    }

    @Test
    fun innerScreenLandscape() { // 2000 x 1510, nearly square: any clear layout halves the picture, so it stays full with the controls over its edges
        val p = plan(2000, 1510)
        assertTrue(p.overlay)
        assertEquals(Arrangement.SIDE, p.arrangement)
        assertEquals(2000, p.picture.width)
    }

    @Test
    fun splitTopBottom() { // a wide, low half: 1602 x 940
        val nx = plan(1602, 940, zoom = false) // no zoom pill: room for clear columns
        assertEquals(Arrangement.SIDE, nx.arrangement); assertFalse(nx.overlay)
        assertTrue(nx.picture.height > 940 * 0.85)
        val compact = plan(1602, 940) // with the zoom column the clear picture would be too small
        assertEquals(Arrangement.SIDE, compact.arrangement); assertTrue(compact.overlay)
    }

    @Test
    fun tinyWindowFallsBackToOverlay() { // pop-up window: keeping the picture clear would make it a stamp
        val p = plan(700, 600)
        assertTrue(p.overlay)
        assertEquals(Arrangement.SIDE, p.arrangement)
        assertEquals(700, p.picture.width)
        val upright = plan(500, 620)
        assertTrue(upright.overlay); assertEquals(Arrangement.STACKED, upright.arrangement)
    }

    @Test
    fun portraitPictureAndInsets() { // the camera held upright: a 3:4 picture fills an upright phone, the controls go over it
        val p = plan(1248, 1972, aspect = 3.0 / 4, insets = Box(0, 60, 0, 40))
        assertTrue(p.overlay); assertEquals(Arrangement.STACKED, p.arrangement)
        assertTrue(p.picture.top >= 60)
        assertTrue(p.picture.bottom <= 1972 - 40)
        // the same picture beside the controls in a landscape window, clear of them
        val wide = plan(1972, 1248, aspect = 3.0 / 4, insets = Box(0, 60, 0, 40))
        assertFalse(wide.overlay); assertEquals(Arrangement.SIDE, wide.arrangement)
    }

    @Test
    fun fitAndBox() {
        assertEquals(Box(0, 0, 0, 0), ViewfinderLayout.fit(Box(0, 0, 0, 10), 1.0, top = false))
        assertEquals(Box(5, 5, 5, 5), ViewfinderLayout.fit(Box(5, 5, 20, 20), 0.0, top = false))
        assertEquals(Box(0, 25, 100, 75), ViewfinderLayout.fit(Box(0, 0, 100, 100), 2.0, top = false))
        assertEquals(Box(0, 0, 100, 50), ViewfinderLayout.fit(Box(0, 0, 100, 100), 2.0, top = true))
        assertEquals(Box(25, 0, 75, 100), ViewfinderLayout.fit(Box(0, 0, 100, 100), 0.5, top = true))
        assertTrue(Box(0, 0, 10, 10).intersects(Box(5, 5, 15, 15)))
        assertFalse(Box(0, 0, 10, 10).intersects(Box(10, 0, 20, 10)))
        assertEquals(100L, Box(0, 0, 10, 10).area)
    }
}
