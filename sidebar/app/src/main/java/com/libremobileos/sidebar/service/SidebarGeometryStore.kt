package com.libremobileos.sidebar.service

import android.content.Context
import android.content.SharedPreferences
import com.libremobileos.sidebar.app.SidebarApplication

class SidebarGeometryStore(prefs: SharedPreferences) {
    private val prefs: SharedPreferences = prefs

    fun get(): Triple<Float, Float, Float> = Triple(
        prefs.getFloat(KEY_WIDTH_DP, 160f),
        prefs.getFloat(KEY_HEIGHT_DP, 550f),
        prefs.getFloat(KEY_OFFSET_PX, 0f)
    )

    fun save(widthDp: Float, heightDp: Float, verticalOffsetPx: Float) {
        prefs.edit()
            .putFloat(KEY_WIDTH_DP, widthDp)
            .putFloat(KEY_HEIGHT_DP, heightDp)
            .putFloat(KEY_OFFSET_PX, verticalOffsetPx)
            .apply()
    }

    companion object {
        const val KEY_WIDTH_DP = "sidebar_width_dp"
        const val KEY_HEIGHT_DP = "sidebar_height_dp"
        const val KEY_OFFSET_PX = "sidebar_vertical_offset_px"

        fun create(ctx: Context): SidebarGeometryStore {
            val prefs = ctx.applicationContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
            return SidebarGeometryStore(prefs)
        }
    }
}
