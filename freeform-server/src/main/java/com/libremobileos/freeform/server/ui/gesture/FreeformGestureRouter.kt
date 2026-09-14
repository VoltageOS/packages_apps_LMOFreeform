package com.libremobileos.freeform.server.ui.gesture

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.libremobileos.freeform.server.Debug.dlog
import kotlin.math.abs
import kotlin.math.max

class FreeformGestureRouter(
    context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun surfaceWidth(): Int
        fun isAlive(): Boolean
        fun onDownFocus()
        fun forward(event: MotionEvent)
        fun fireBack()
        fun haptic(feedbackConstant: Int)
    }

    enum class Mode { IDLE, PENDING_EDGE, FORWARDING, CONSUMED }

    var mode: Mode = Mode.IDLE
        private set

    private val buffered = ArrayList<MotionEvent>(8)
    private var startX = 0f
    private var startY = 0f
    private var edge = Edge.NONE

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density

    private enum class Edge { NONE, LEFT, RIGHT }

    private fun edgeWidthPx(): Float = EDGE_WIDTH_DP * density

    private fun triggerPx(): Float =
        GeometryMath.triggerPxFor(
            callbacks.surfaceWidth(), density,
            TRIGGER_FALLBACK_DP, TRIGGER_MIN_DP, TRIGGER_FRACTION,
        )

    private fun atEdge(x: Float): Edge {
        val w = callbacks.surfaceWidth()
        if (w <= 0) return Edge.NONE
        val ew = edgeWidthPx()
        return when {
            x <= ew -> Edge.LEFT
            x >= w - ew -> Edge.RIGHT
            else -> Edge.NONE
        }
    }

    fun onSurfaceTouch(view: View, event: MotionEvent): Boolean {
        if (!callbacks.isAlive()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearBuffered()
                callbacks.onDownFocus()
                startX = event.x
                startY = event.y
                mode = if (event.pointerCount == 1 && atEdge(event.x).also { edge = it } != Edge.NONE) {
                    buffered.add(MotionEvent.obtain(event))
                    Mode.PENDING_EDGE
                } else {
                    edge = Edge.NONE
                    callbacks.forward(event)
                    Mode.FORWARDING
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING_EDGE -> {
                    val dx = event.x - startX
                    val dy = abs(event.y - startY)
                    val inward = when (edge) {
                        Edge.LEFT -> dx
                        Edge.RIGHT -> -dx
                        Edge.NONE -> 0f
                    }
                    when {
                        event.pointerCount > 1 -> {
                            dlog(TAG, "edge: second finger, forwarding")
                            flushBuffered(event)
                        }
                        GeometryMath.isBackSwipe(inward, dy, triggerPx(), BACK_DY_RATIO) -> {
                            dlog(TAG, "edge: back fired")
                            clearBuffered()
                            callbacks.fireBack()
                            callbacks.haptic(HAPTIC_GESTURE_END)
                            mode = Mode.CONSUMED
                        }
                        max(abs(dx), dy) > slop -> {
                            if (abs(dx) > dy * DIRECTION_RATIO && inward > 0) {
                                buffered.add(MotionEvent.obtain(event))
                            } else {
                                flushBuffered(event)
                            }
                        }
                        else -> buffered.add(MotionEvent.obtain(event))
                    }
                }
                Mode.FORWARDING -> callbacks.forward(event)
                Mode.IDLE -> callbacks.forward(event)
                Mode.CONSUMED -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.PENDING_EDGE -> {
                        if (event.actionMasked == MotionEvent.ACTION_UP) flushBuffered(event)
                        else clearBuffered()
                    }
                    Mode.FORWARDING, Mode.IDLE -> callbacks.forward(event)
                    Mode.CONSUMED -> {}
                }
                clearBuffered()
                mode = Mode.IDLE
                edge = Edge.NONE
            }
            else -> if (mode == Mode.FORWARDING) callbacks.forward(event)
        }
        return true
    }

    private fun flushBuffered(current: MotionEvent) {
        for (e in buffered) {
            runCatching { callbacks.forward(e) }
            e.recycle()
        }
        buffered.clear()
        runCatching { callbacks.forward(current) }
        mode = Mode.FORWARDING
    }

    private fun clearBuffered() {
        for (e in buffered) runCatching { e.recycle() }
        buffered.clear()
    }

    fun destroy() {
        clearBuffered()
        mode = Mode.IDLE
    }

    companion object {
        private const val TAG = "LMOFreeform/GestureRouter"
        private const val EDGE_WIDTH_DP = 24f
        private const val TRIGGER_MIN_DP = 24f
        private const val TRIGGER_FALLBACK_DP = 64f
        private const val TRIGGER_FRACTION = 0.12f
        private const val BACK_DY_RATIO = 0.75f
        private const val DIRECTION_RATIO = 1.5f
        const val HAPTIC_GESTURE_END = 13
    }
}
