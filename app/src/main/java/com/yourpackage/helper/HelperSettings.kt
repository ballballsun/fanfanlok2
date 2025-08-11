package com.example.fanfanlok.helper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.util.Log

/**
 * Configuration settings for the manual card helper
 */
class HelperSettings(private val context: Context) {

    companion object {
        private const val TAG = "HelperSettings"
        private const val PREFS_NAME = "helper_settings"

        // Default settings
        private const val DEFAULT_CARD_WIDTH = 120
        private const val DEFAULT_CARD_HEIGHT = 160
        private const val DEFAULT_OVERLAY_ALPHA = 180 // 0-255
        private const val DEFAULT_BORDER_WIDTH = 4
        private const val DEFAULT_TEXT_SIZE = 24

        // Setting keys
        private const val KEY_CARD_WIDTH = "card_width"
        private const val KEY_CARD_HEIGHT = "card_height"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_BORDER_WIDTH = "border_width"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_SHOW_NUMBERS = "show_numbers"
        private const val KEY_VIBRATE_ON_TOUCH = "vibrate_on_touch"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get card size for new cards
     */
    fun getCardSize(): Point {
        val width = prefs.getInt(KEY_CARD_WIDTH, DEFAULT_CARD_WIDTH)
        val height = prefs.getInt(KEY_CARD_HEIGHT, DEFAULT_CARD_HEIGHT)
        return Point(width, height)
    }

    /**
     * Set card size
     */
    fun setCardSize(width: Int, height: Int) {
        prefs.edit()
            .putInt(KEY_CARD_WIDTH, width)
            .putInt(KEY_CARD_HEIGHT, height)
            .apply()
        Log.d(TAG, "Card size updated to ${width}x$height")
    }

    /**
     * Get overlay alpha (transparency)
     */
    fun getOverlayAlpha(): Int {
        return prefs.getInt(KEY_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
    }

    /**
     * Set overlay alpha
     */
    fun setOverlayAlpha(alpha: Int) {
        val clampedAlpha = alpha.coerceIn(0, 255)
        prefs.edit().putInt(KEY_OVERLAY_ALPHA, clampedAlpha).apply()
        Log.d(TAG, "Overlay alpha updated to $clampedAlpha")
    }

    /**
     * Get border width for card rectangles
     */
    fun getBorderWidth(): Int {
        return prefs.getInt(KEY_BORDER_WIDTH, DEFAULT_BORDER_WIDTH)
    }

    /**
     * Set border width
     */
    fun setBorderWidth(width: Int) {
        val clampedWidth = width.coerceIn(1, 20)
        prefs.edit().putInt(KEY_BORDER_WIDTH, clampedWidth).apply()
        Log.d(TAG, "Border width updated to $clampedWidth")
    }

    /**
     * Get text size for card numbers
     */
    fun getTextSize(): Int {
        return prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)
    }

    /**
     * Set text size
     */
    fun setTextSize(size: Int) {
        val clampedSize = size.coerceIn(12, 48)
        prefs.edit().putInt(KEY_TEXT_SIZE, clampedSize).apply()
        Log.d(TAG, "Text size updated to $clampedSize")
    }

    /**
     * Check if numbers should be shown on cards
     */
    fun shouldShowNumbers(): Boolean {
        return prefs.getBoolean(KEY_SHOW_NUMBERS, true)
    }

    /**
     * Set whether to show numbers
     */
    fun setShowNumbers(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_NUMBERS, show).apply()
        Log.d(TAG, "Show numbers: $show")
    }

    /**
     * Check if device should vibrate on touch
     */
    fun shouldVibrateOnTouch(): Boolean {
        return prefs.getBoolean(KEY_VIBRATE_ON_TOUCH, true)
    }

    /**
     * Set vibration on touch
     */
    fun setVibrateOnTouch(vibrate: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATE_ON_TOUCH, vibrate).apply()
        Log.d(TAG, "Vibrate on touch: $vibrate")
    }

    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Settings reset to defaults")
    }

    /**
     * Get all settings as a summary
     */
    fun getSettingsSummary(): SettingsSummary {
        return SettingsSummary(
            cardSize = getCardSize(),
            overlayAlpha = getOverlayAlpha(),
            borderWidth = getBorderWidth(),
            textSize = getTextSize(),
            showNumbers = shouldShowNumbers(),
            vibrateOnTouch = shouldVibrateOnTouch()
        )
    }

    /**
     * Auto-adjust card size based on screen dimensions
     */
    fun autoAdjustCardSize() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Calculate reasonable card size (assuming 6x4 grid)
        val cardWidth = (screenWidth * 0.12).toInt() // ~12% of screen width
        val cardHeight = (cardWidth * 1.33).toInt()   // Standard card aspect ratio

        setCardSize(cardWidth, cardHeight)
        Log.d(TAG, "Auto-adjusted card size to ${cardWidth}x$cardHeight based on ${screenWidth}x$screenHeight screen")
    }

    /**
     * Settings summary data class
     */
    data class SettingsSummary(
        val cardSize: Point,
        val overlayAlpha: Int,
        val borderWidth: Int,
        val textSize: Int,
        val showNumbers: Boolean,
        val vibrateOnTouch: Boolean
    )
}