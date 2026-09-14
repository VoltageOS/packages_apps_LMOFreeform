package com.libremobileos.freeform.server.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView

/**
 * @author KindBrave
 * @since 2023/9/16
 */
class FreeformTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    companion object {
        private const val TAG = "LMOFreeform/FreeformTextureView"
    }

    var onGenericMotion: ((MotionEvent) -> Boolean)? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        onGenericMotion?.invoke(event) ?: super.onGenericMotionEvent(event)

    override fun onHoverEvent(event: MotionEvent): Boolean =
        onGenericMotion?.invoke(event) ?: super.onHoverEvent(event)
}
