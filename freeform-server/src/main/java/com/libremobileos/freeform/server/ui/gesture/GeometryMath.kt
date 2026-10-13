package com.libremobileos.freeform.server.ui.gesture

import kotlin.math.max
import kotlin.math.roundToInt

object GeometryMath {

    fun triggerPxFor(
        surfaceWidthPx: Int,
        density: Float,
        fallbackDp: Float = 64f,
        minDp: Float = 24f,
        fraction: Float = 0.12f,
    ): Float {
        if (surfaceWidthPx <= 0) return fallbackDp * density
        return max(minDp * density, surfaceWidthPx * fraction)
    }

    fun isBackSwipe(
        inwardPx: Float,
        dyAbsPx: Float,
        triggerPx: Float,
        dyRatio: Float = 0.75f,
    ): Boolean = inwardPx >= triggerPx && dyAbsPx <= inwardPx * dyRatio

    fun computeDensityDpi(baseDpi: Int, scale: Float, widthPx: Int): Int {
        val balanced = (baseDpi * (1 + (scale - 1) * 0.5f)).roundToInt()
        val swDp = widthPx * 160f / balanced
        val capped = if (swDp < 320f) (widthPx * 160f / 320f).toInt() else balanced
        return ((capped / 20) * 20).coerceAtLeast(baseDpi)
    }
}
