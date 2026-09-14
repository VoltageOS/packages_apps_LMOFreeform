package com.libremobileos.freeform.server.ui

data class FreeformConfig @JvmOverloads constructor(
    var width: Int,
    var height: Int,
    var densityDpi: Int,
    var secure: Boolean = true,
    var ownContentOnly: Boolean = true,
    var shouldShowSystemDecorations: Boolean = false,
    var refreshRate: Float = 60.0f,
    var presentationDeadlineNanos: Long = 1666666L,
    var hangUpWidth: Int = 216,
    var hangUpHeight: Int = 216,
    var isHangUp: Boolean = false,
    // 记录挂起前的位置，以便恢复
    var notInHangUpX: Int = 0,
    var notInHangUpY: Int = 0,
    //小窗屏幕宽高，与view的比例
    var freeformWidth: Int = 1080,
    var freeformHeight: Int = 1920,
    //小窗屏幕尺寸/小窗界面尺寸
    var scale: Float = 1.0f,
    var baseDensityDpi: Int = 0,
    var densityMode: Int = DENSITY_BALANCED,
) {
    companion object {
        const val DENSITY_FIT = 0
        const val DENSITY_BALANCED = 1
        const val DENSITY_MATCH = 2
    }
}
