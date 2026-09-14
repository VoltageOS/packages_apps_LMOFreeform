package com.libremobileos.sidebar.service

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

class MGestureManager(context: Context, private val mListener: MGestureListener) {
    private val mGestureDetector: GestureDetector
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    companion object {
        private const val TAG = "MGestureManager"
    }

    interface MGestureListener {
        fun singleFingerSlipAction(
            gestureEvent: GestureEvent?,
            startEvent: MotionEvent?,
            endEvent: MotionEvent?,
            velocity: Float
        ): Boolean

        fun onTouchEvent(event: MotionEvent)
    }

    enum class GestureEvent {
        SINGLE_FINGER_LEFT_SLIP, SINGLE_FINGER_RIGHT_SLIP, SINGLE_FINGER_UP_SLIP, SINGLE_FINGER_DOWN_SLIP
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        mListener.onTouchEvent(event)
        return mGestureDetector.onTouchEvent(event)
    }

    private inner class SimpleGesture : SimpleOnGestureListener() {

        @Suppress("NOTHING_TO_OVERRIDE", "ACCIDENTAL_OVERRIDE")
        override fun onFling(
            e1: MotionEvent, e2: MotionEvent, velocityX: Float,
            velocityY: Float
        ): Boolean {
            val dx = e1.x - e2.x
            val dy = e1.y - e2.y
            if (dx > 0 && abs(dx) > abs(dy) && abs(velocityX) > minVelocity) {
                return mListener.singleFingerSlipAction(
                    GestureEvent.SINGLE_FINGER_LEFT_SLIP,
                    e1,
                    e2,
                    abs(velocityX)
                )
            }
            else if (dx < 0 && abs(dx) > abs(dy) && abs(velocityX) > minVelocity) {
                return mListener.singleFingerSlipAction(
                    GestureEvent.SINGLE_FINGER_RIGHT_SLIP,
                    e1,
                    e2,
                    abs(velocityX)
                )
            } else if (dy > 0 && abs(dy) > abs(dx) && abs(velocityY) > minVelocity
            ) {
                return mListener.singleFingerSlipAction(
                    GestureEvent.SINGLE_FINGER_UP_SLIP,
                    e1,
                    e2,
                    abs(velocityY)
                )
            } else if (dy < 0 && abs(dy) > abs(dx) && abs(velocityY) > minVelocity
            ) {
                return mListener.singleFingerSlipAction(
                    GestureEvent.SINGLE_FINGER_DOWN_SLIP,
                    e1,
                    e2,
                    abs(velocityY)
                )
            } else return false
        }
    }

    init {
        mGestureDetector =
            GestureDetector(context, SimpleGesture(), null, true)
    }
}
