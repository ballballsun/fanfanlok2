package com.example.fanfanlok.helper

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

/**
 * Custom overlay view for manual card positioning
 * Handles touch interactions and draws card rectangles with numbers
 */
class CardPositionOverlay(
    context: Context,
    private val helper: ManualCardHelper,
    private val settings: HelperSettings
) : View(context) {

    companion object {
        private const val TAG = "CardPositionOverlay"
        private const val LONG_PRESS_TIMEOUT = 500L // milliseconds
    }

    // Paint objects for drawing
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = settings.getBorderWidth().toFloat()
        color = Color.RED
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.RED
        alpha = settings.getOverlayAlpha()
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = settings.getTextSize().toFloat()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val backgroundTextPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 180
        isAntiAlias = true
    }

    // Touch handling
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var lastTouchX = 0
    private var lastTouchY = 0

    // Current card positions
    private var cardPositions = listOf<ManualCardHelper.CardPosition>()

    init {
        // Make view focusable for touch events
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)

        // Listen for position updates from helper
        helper.setOnPositionsUpdatedListener { positions ->
            cardPositions = positions
            invalidate() // Trigger redraw
            Log.d(TAG, "Updated overlay with ${positions.size} card positions")
        }

        Log.d(TAG, "CardPositionOverlay initialized")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        Log.d(TAG, "Drawing ${cardPositions.size} card positions")

        cardPositions.forEach { card ->
            drawCard(canvas, card)
        }

        // Draw instructions if no cards
        if (cardPositions.isEmpty()) {
            drawInstructions(canvas)
        }
    }

    private fun drawCard(canvas: Canvas, card: ManualCardHelper.CardPosition) {
        val bounds = card.getBounds()

        try {
            // Draw semi-transparent fill
            canvas.drawRect(bounds, fillPaint)

            // Draw border
            canvas.drawRect(bounds, borderPaint)

            // Draw card number if enabled
            if (settings.shouldShowNumbers()) {
                // Draw background circle for text
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()
                val textRadius = settings.getTextSize() * 0.7f

                canvas.drawCircle(centerX, centerY, textRadius, backgroundTextPaint)

                // Draw card number
                val text = card.id.toString()
                val textY = centerY + (textPaint.textSize / 3) // Slight vertical adjustment
                canvas.drawText(text, centerX, textY, textPaint)
            }

            Log.v(TAG, "Drew card ${card.id} at (${bounds.left}, ${bounds.top})")

        } catch (e: Exception) {
            Log.e(TAG, "Error drawing card ${card.id}", e)
        }
    }

    private fun drawInstructions(canvas: Canvas) {
        val instructionPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = 48f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val shadowPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val centerX = width / 2f
        val centerY = height / 2f

        // Draw shadow text
        canvas.drawText("TAP TO ADD CARDS", centerX + 2, centerY + 2, shadowPaint)

        // Draw main text
        canvas.drawText("TAP TO ADD CARDS", centerX, centerY, instructionPaint)

        // Draw subtitle
        val subtitlePaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = 24f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("Long press to remove", centerX + 1, centerY + 60 + 1, shadowPaint.apply { textSize = 24f })
        canvas.drawText("Long press to remove", centerX, centerY + 60, subtitlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Handle gesture detection first
        val gestureHandled = gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x.toInt()
                lastTouchY = event.y.toInt()

                // Start long press timer
                longPressRunnable = Runnable {
                    handleLongPress(lastTouchX, lastTouchY)
                }
                handler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT)

                Log.d(TAG, "Touch down at ($lastTouchX, $lastTouchY)")
                return true
            }

            MotionEvent.ACTION_UP -> {
                // Cancel long press
                longPressRunnable?.let { handler.removeCallbacks(it) }

                // Only handle as tap if not handled by gesture detector
                if (!gestureHandled) {
                    handleTap(lastTouchX, lastTouchY)
                }

                Log.d(TAG, "Touch up - handled by gesture: $gestureHandled")
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Cancel long press if moved too much
                val deltaX = kotlin.math.abs(event.x - lastTouchX)
                val deltaY = kotlin.math.abs(event.y - lastTouchY)

                if (deltaX > 20 || deltaY > 20) {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // Cancel long press
                longPressRunnable?.let { handler.removeCallbacks(it) }
                return true
            }
        }

        return gestureHandled || super.onTouchEvent(event)
    }

    private fun handleTap(x: Int, y: Int) {
        Log.d(TAG, "Handling tap at ($x, $y)")

        val result = helper.handleTouch(x, y)

        when (result) {
            is ManualCardHelper.TouchResult.CARD_ADDED -> {
                if (settings.shouldVibrateOnTouch()) {
                    vibrateSuccess()
                }
                showToast("Card ${result.card.id} added ✓ (${result.detectedSize.x}x${result.detectedSize.y})")
                Log.d(TAG, "Card added successfully: ${result.card.id} with size ${result.detectedSize.x}x${result.detectedSize.y}")
            }

            is ManualCardHelper.TouchResult.CARD_SELECTED -> {
                showToast("Card ${result.card.id} selected")
                Log.d(TAG, "Card selected: ${result.card.id}")
            }

            ManualCardHelper.TouchResult.TOO_CLOSE -> {
                vibrateError()
                showToast("Too close to existing card ⚠️")
                Log.d(TAG, "Touch rejected - too close to existing card")
            }

            ManualCardHelper.TouchResult.SCREENSHOT_NEEDED -> {
                showToast("📸 Taking screenshot for detection...")
                Log.d(TAG, "Screenshot needed for edge detection")
            }

            ManualCardHelper.TouchResult.IGNORED -> {
                Log.d(TAG, "Touch ignored - helper not in mode")
            }
        }
    }

    private fun handleLongPress(x: Int, y: Int) {
        Log.d(TAG, "Handling long press at ($x, $y)")

        val removed = helper.handleLongPress(x, y)

        if (removed) {
            vibrateSuccess()
            showToast("Card removed ✓")
            Log.d(TAG, "Card removed via long press")
        } else {
            vibrateError()
            showToast("No card to remove")
            Log.d(TAG, "Long press - no card found to remove")
        }
    }

    private fun vibrateSuccess() {
        if (!settings.shouldVibrateOnTouch()) return

        try {
            vibrator?.let { vib ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(50)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibration permission not granted", e)
            // Continue without vibration - don't crash the app
        } catch (e: Exception) {
            Log.w(TAG, "Error during vibration", e)
        }
    }

    private fun vibrateError() {
        if (!settings.shouldVibrateOnTouch()) return

        try {
            vibrator?.let { vib ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // Double vibration for error
                    val timings = longArrayOf(0, 100, 100, 100)
                    val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 100, 100, 100), -1)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibration permission not granted", e)
            // Continue without vibration - don't crash the app
        } catch (e: Exception) {
            Log.w(TAG, "Error during vibration", e)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Update overlay appearance based on current settings
     */
    fun updateAppearance() {
        borderPaint.strokeWidth = settings.getBorderWidth().toFloat()
        fillPaint.alpha = settings.getOverlayAlpha()
        textPaint.textSize = settings.getTextSize().toFloat()

        invalidate() // Trigger redraw
        Log.d(TAG, "Overlay appearance updated")
    }

    /**
     * Get current card positions (for external access)
     */
    fun getCurrentPositions(): List<ManualCardHelper.CardPosition> {
        return cardPositions
    }

    /**
     * Gesture detector for more complex touch handling
     */
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Single tap handling is done in onTouchEvent to ensure proper coordination
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            // Long press handling is done in onTouchEvent with custom timer
            // This provides more control over the timing
        }

        override fun onDown(e: MotionEvent): Boolean {
            return true // Consume the event
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean up any pending callbacks
        longPressRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "CardPositionOverlay detached from window")
    }
}