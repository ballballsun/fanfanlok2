package com.example.fanfanlok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AccessibilityTapService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityTapService"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for this service
    }

    override fun onInterrupt() {
        // Required override, do nothing for now
    }

    /**
     * Simulates a tap gesture at the specified (x, y) coordinates on the screen.
     */
    fun simulateTap(x: Int, y: Int) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

        val gesture = gestureBuilder.build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Tap gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Tap gesture cancelled at ($x, $y)")
            }
        }, null)
    }
}
