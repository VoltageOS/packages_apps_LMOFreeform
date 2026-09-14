package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.os.Build
import android.util.Slog
import android.view.Display
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MoveTouchListener(
    private val window: FreeformWindow
) : View.OnTouchListener{
    private var downRawX = 0f
    private var downRawY = 0f
    private var startWinX = 0
    private var startWinY = 0
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                window.cancelGeometryAnimations()
                downRawX = event.rawX
                downRawY = event.rawY
                startWinX = window.windowParams.x
                startWinY = window.windowParams.y
            }
            MotionEvent.ACTION_MOVE -> {
                window.requestMove(
                    (startWinX + event.rawX - downRawX).roundToInt(),
                    (startWinY + event.rawY - downRawY).roundToInt()
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                window.makeSureFreeformInScreen()
            }
        }
        return true
    }
}

class LeftViewClickListener(private val window: FreeformWindow) : View.OnClickListener {
    override fun onClick(v: View) {
        window.close()
    }

}

/**
 * maximize freeform screen
 */
class MaximizeClickListener(private val window: FreeformWindow): View.OnClickListener {
    companion object {
        private const val TAG = "LMOFreeform/TouchListener"
    }
    override fun onClick(v: View) {
        if (null != window.freeformTaskStackListener) {
            if (window.freeformTaskStackListener!!.taskId == -1) {
                Slog.e(TAG, "taskId is -1, can`t move")
                return
            }
            runCatching { SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(window.freeformTaskStackListener!!.taskId, Display.DEFAULT_DISPLAY) }
        }
    }
}

/**
 * Pin freeform
 */
class PinClickListener(private val window: FreeformWindow): View.OnClickListener {
    override fun onClick(v: View) {
        window.handler.post {
            // hangup
            window.handleHangUp()
        }
    }
}

class RightViewClickListener(private val displayId: Int) : View.OnClickListener {
    override fun onClick(v: View) {
        LMOFreeformServiceHolder.back(displayId)
    }
}

class ScaleTouchListener(private val window: FreeformWindow, private val isRight: Boolean = true, private val uniform: Boolean = false, private val useHorizontal: Boolean = true, private val useVertical: Boolean = true): View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var startW = 0
    private var startH = 0
    private var resizing = false
    private var lastW = 0
    private var lastH = 0
    private val slop by lazy { ViewConfiguration.get(window.context).scaledTouchSlop }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                window.cancelGeometryAnimations()
                downX = event.rawX; downY = event.rawY
                startW = window.freeformConfig.width
                startH = window.freeformConfig.height
                lastW = startW
                lastH = startH
                resizing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dxRaw = if (isRight) event.rawX - downX else downX - event.rawX
                val dyRaw = event.rawY - downY
                if (!resizing && max(abs(dxRaw), abs(dyRaw)) < slop) return true
                resizing = true
                val (w, h) = if (uniform) {
                    var d = 0f
                    if (useHorizontal) d += dxRaw
                    if (useVertical) d += dyRaw
                    val f = (1f + d / max(1, startW + startH)).coerceIn(0.25f, 4f)
                    (startW * f).roundToInt() to (startH * f).roundToInt()
                } else {
                    (startW + if (useHorizontal) dxRaw.roundToInt() else 0) to
                    (startH + if (useVertical) dyRaw.roundToInt() else 0)
                }
                lastW = w
                lastH = h
                window.requestResize(w, h)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizing) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && window.exceedsMax(lastW, lastH)) {
                        MaximizeClickListener(window).onClick(v)
                    } else {
                        window.commitResize()
                        window.handler.post { window.makeSureFreeformInScreen() }
                    }
                }
                resizing = false
            }
        }
        return true
    }
}

class HangUpGestureListener(private val window: FreeformWindow) : SimpleOnGestureListener() {
    private var startX = 0
    private var startY = 0
    override fun onDown(e: MotionEvent): Boolean {
        startX = window.windowParams.x
        startY = window.windowParams.y
        return super.onDown(e)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        window.handler.post { window.handleHangUp() }
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (!isValidMotionEvent(e1) || !isValidMotionEvent(e2)) {
            return true
        }

        val e1RawX = e1?.rawX ?: 0f
        val e1RawY = e1?.rawY ?: 0f

        if (!isValidCoordinate(e1RawX) || !isValidCoordinate(e1RawY) 
                || !isValidCoordinate(e2.rawX) || !isValidCoordinate(e2.rawY)) {
            return true
        }
        
        val newX = (startX + e2.rawX - e1RawX).roundToInt()
        val newY = (startY + e2.rawY - e1RawY).roundToInt()

        window.handler.post { window.requestMove(newX, newY) }
        return true
    }

    fun isValidMotionEvent(event: MotionEvent?): Boolean {
        return event != null &&
                !event.rawX.isNaN() &&
                !event.rawY.isNaN() &&
                event.rawX.isFinite() &&
                event.rawY.isFinite()
    }
    
    fun isValidCoordinate(coordinate: Float): Boolean {
        return !coordinate.isNaN() && coordinate.isFinite()
    }
}
