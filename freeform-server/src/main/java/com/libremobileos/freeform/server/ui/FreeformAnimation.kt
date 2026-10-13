package com.libremobileos.freeform.server.ui

object FreeformAnimation {
    fun moveInScreenAnimator(start: Int, end: Int, dur: Long, moveX: Boolean, window: FreeformWindow) {
        if (moveX) window.animateTo(x = end, dur = dur)
        else window.animateTo(y = end, dur = dur)
    }
}
