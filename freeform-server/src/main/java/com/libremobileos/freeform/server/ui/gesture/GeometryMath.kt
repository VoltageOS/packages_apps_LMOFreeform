package com.libremobileos.freeform.server.ui.gesture

import com.libremobileos.freeform.server.ui.FreeformConfig
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

    fun viewToDisplay(viewCoord: Float, viewSize: Int, displaySize: Int): Float =
        if (viewSize <= 0) viewCoord else viewCoord * displaySize / viewSize

    fun displayToView(displayCoord: Float, viewSize: Int, displaySize: Int): Float =
        if (displaySize <= 0) displayCoord else displayCoord * viewSize / displaySize

    fun computeDensityDpi(baseDpi: Int, scale: Float, widthPx: Int, mode: Int): Int =
        when (mode) {
            FreeformConfig.DENSITY_MATCH -> (baseDpi * scale).roundToInt()
            FreeformConfig.DENSITY_BALANCED -> {
                val balanced = (baseDpi * (1 + (scale - 1) * 0.5f)).roundToInt()
                val swDp = widthPx * 160f / balanced
                if (swDp < 320f) (widthPx * 160f / 320f).roundToInt() else balanced
            }
            else -> baseDpi
        }
}
