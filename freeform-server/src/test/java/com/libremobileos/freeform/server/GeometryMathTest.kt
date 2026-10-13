package com.libremobileos.freeform.server

import com.libremobileos.freeform.server.ui.gesture.GeometryMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun computeDensityDpi_balanced() {
        val base = 420
        val scale = 1.333f
        val balanced = GeometryMath.computeDensityDpi(base, scale, 1080)
        assertEquals(480, balanced)
        assertTrue(balanced % 20 == 0)
        assertTrue(balanced >= base)
        val swDp = 1080f * 160f / balanced
        assertTrue(swDp >= 320f && swDp < 600f)
    }

    @Test
    fun computeDensityDpi_balancedQuantizedTable() {
        val base = 420
        val cases = listOf(
            Triple(1083, 1.18f, 440),
            Triple(1080, 1.333f, 480),
            Triple(1080, 1.66f, 540),
            Triple(1080, 2.0f, 540),
            Triple(1080, 2.7f, 540),
            Triple(1080, 3.0f, 540),
        )
        for ((virtualMinPx, scale, expectedDpi) in cases) {
            val dpi = GeometryMath.computeDensityDpi(base, scale, virtualMinPx)
            assertEquals("virtualMinPx=$virtualMinPx scale=$scale", expectedDpi, dpi)
            assertEquals(0, dpi % 20)
            assertTrue(dpi >= base)
            val swDp = virtualMinPx * 160f / dpi
            assertTrue("swDp=$swDp for virtualMinPx=$virtualMinPx", swDp >= 320f && swDp < 600f)
        }
        val tiny = GeometryMath.computeDensityDpi(base, 3f, 1080)
        assertEquals(540, tiny)
        assertTrue(tiny >= base)
        assertEquals(0, tiny % 20)
        assertTrue(1080f * 160f / tiny >= 320f)
    }
}
