package com.example.fanfanlok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AccessibilityTapService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityTap"
        private const val TAP_DURATION_MS = 100L
        private const val TAP_ACTION = "com.example.fanfanlok.PERFORM_TAP"
    }

    private var isServiceActive = false

    private val tapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TAP_ACTION && isServiceActive) {
                val x = intent.getIntExtra("x", -1)
                val y = intent.getIntExtra("y", -1)
                
                if (x >= 0 && y >= 0) {
                    simulateTap(x, y)
                } else {
                    Log.w(TAG, "Invalid tap coordinates received: ($x, $y)")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceActive = true

        // Register broadcast receiver for tap requests
        val filter = IntentFilter(TAP_ACTION)

        registerReceiver(tapReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "Accessibility service connected and ready")
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to monitor accessibility events for this automation
        // This service is purely for gesture simulation
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        isServiceActive = false
        try {
            unregisterReceiver(tapReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Simulate a tap gesture at the specified (x, y) coordinates on the screen
     */
    private fun simulateTap(x: Int, y: Int) {
        if (!isServiceActive) {
            Log.w(TAG, "Service not active, cannot perform tap")
            return
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))

        val gesture = gestureBuilder.build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "✅ Tap completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "❌ Tap cancelled at ($x, $y)")
            }
        }, null)

        if (!result) {
            Log.e(TAG, "Failed to dispatch tap gesture at ($x, $y)")
        }
    }

    /**
     * Public method for direct tap simulation (if needed)
     */
    fun performTap(x: Int, y: Int): Boolean {
        if (!isServiceActive) {
            Log.w(TAG, "Cannot perform tap - service not active")
            return false
        }
        
        simulateTap(x, y)
        return true
    }

    /**
     * Simulate a swipe gesture (for potential future use)
     */
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500L): Boolean {
        if (!isServiceActive) {
            Log.w(TAG, "Cannot perform swipe - service not active")
            return false
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))

        val gesture = gestureBuilder.build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Swipe cancelled from ($startX, $startY) to ($endX, $endY)")
            }
        }, null)

        return result
    }

    /**
     * Check if the service is ready to perform gestures
     */
    fun isReady(): Boolean = isServiceActive
}