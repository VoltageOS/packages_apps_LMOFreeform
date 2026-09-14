package com.libremobileos.freeform.server

import com.libremobileos.freeform.server.ui.FreeformConfig
import com.libremobileos.freeform.server.ui.gesture.GeometryMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryMathTest {

    @Test
    fun triggerPx_usesWidthFractionWithDpFloor() {
        val density = 3f
        assertEquals(1000f * 0.12f, GeometryMath.triggerPxFor(1000, density), 0.001f)
        assertEquals(24f * density, GeometryMath.triggerPxFor(100, density), 0.001f)
        assertEquals(64f * density, GeometryMath.triggerPxFor(0, density), 0.001f)
        assertEquals(64f * density, GeometryMath.triggerPxFor(-5, density), 0.001f)
    }

    @Test
    fun isBackSwipe_rejectsDiagonalAndShortSwipes() {
        val trigger = 72f
        assertTrue(GeometryMath.isBackSwipe(80f, 10f, trigger))
        assertTrue(GeometryMath.isBackSwipe(72f, 54f, trigger))
        assertFalse(GeometryMath.isBackSwipe(71f, 0f, trigger))
        assertFalse(GeometryMath.isBackSwipe(80f, 61f, trigger))
        assertFalse(GeometryMath.isBackSwipe(-80f, 0f, trigger))
    }

    @Test
    fun viewToDisplay_roundTripWithin1px() {
        val sizes = listOf(810 to 1200, 400 to 800, 1080 to 600, 140 to 200)
        val points = listOf(0f to 0f, 10.5f to 300.25f, 799f to 1199f)
        for ((vw, vh) in sizes) {
            for (swapped in listOf(false, true)) {
                val w = if (swapped) vh else vw
                val h = if (swapped) vw else vh
                val scale = minOf(1080f / minOf(w, h), 2400f / maxOf(w, h))
                val dw = (w * scale).toInt()
                val dh = (h * scale).toInt()
                for ((x, y) in points) {
                    if (x > w - 1 || y > h - 1) continue
                    val dx = GeometryMath.viewToDisplay(x, w, dw)
                    val dy = GeometryMath.viewToDisplay(y, h, dh)
                    assertEquals(x, GeometryMath.displayToView(dx, w, dw), 1f)
                    assertEquals(y, GeometryMath.displayToView(dy, h, dh), 1f)
                }
            }
        }
    }

    @Test
    fun computeDensityDpi_modes() {
        val base = 420
        val scale = 1.333f
        assertEquals(base, GeometryMath.computeDensityDpi(base, scale, 810, FreeformConfig.DENSITY_FIT))
        assertEquals((base * scale).toInt(), GeometryMath.computeDensityDpi(base, scale, 810, FreeformConfig.DENSITY_MATCH))
        val balanced = GeometryMath.computeDensityDpi(base, scale, 810, FreeformConfig.DENSITY_BALANCED)
        assertTrue(balanced in (base + 1)..((base * scale).toInt()))
        val tiny = GeometryMath.computeDensityDpi(base, 3f, 140, FreeformConfig.DENSITY_BALANCED)
        assertTrue(140f * 160f / tiny >= 320f)
        assertTrue(abs(tiny - 140f * 160f / 320f) < 2f)
    }
}
