package com.shilapi.xcertplay

import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/** Owns one touch sequence, including letterbox suppression and the settings gesture. */
internal class CarPlayTouchController(
    private val menuOpen: () -> Boolean,
    private val gestureFingerCount: () -> Int,
    private val swipeDistancePixels: () -> Int,
    private val swipeDirectionRatio: Float,
    private val contentRect: (Int, Int) -> CarPlayVideoLayout,
    private val sendTouch: (List<AirPlayContact>) -> Boolean,
    private val openSettingsMenu: () -> Unit,
    private val appendLog: (String) -> Unit,
) {
    var ignoringOutsideContent = false
        private set
    var trackingSettingsGesture = false
        private set
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    fun onTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen()) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingSettingsGesture = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gestureFingerCount() > 0 && event.pointerCount == gestureFingerCount() && !trackingSettingsGesture) {
                    trackingSettingsGesture = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    sendTouch(emptyList())
                    appendLog("Settings swipe tracking started; fingers=${gestureFingerCount()}")
                    return true
                }
            }
        }

        if (trackingSettingsGesture) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount()) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount()) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    trackingSettingsGesture = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= swipeDistancePixels() &&
                    deltaY >= deltaX * swipeDirectionRatio
                ) {
                    trackingSettingsGesture = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoringOutsideContent = !content.contains(event.x, event.y)
        }
        if (ignoringOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                ignoringOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = sendTouch(contacts)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                "xcertplay-usb",
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

}
