package com.example.fanfanlok

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.opencv.core.Rect
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Manages board layout detection and caching for faster subsequent recognitions
 */
class BoardLayoutManager(private val context: Context) {

    companion object {
        private const val TAG = "BoardLayoutManager"
        private const val PREFS_NAME = "board_layouts"
        private const val KEY_ENABLE_CACHE = "enable_position_cache"
        private const val KEY_CURRENT_LAYOUT = "current_layout"
        private const val POSITION_TOLERANCE = 20 // pixels tolerance for position matching
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    
    private var currentLayout: BoardLayout? = null
    private var isCacheEnabled: Boolean = true

    init {
        isCacheEnabled = prefs.getBoolean(KEY_ENABLE_CACHE, true)
        loadCurrentLayout()
    }

    /**
     * Enable or disable position caching
     */
    fun setPositionCacheEnabled(enabled: Boolean) {
        isCacheEnabled = enabled
        prefs.edit().putBoolean(KEY_ENABLE_CACHE, enabled).apply()
        
        if (!enabled) {
            clearCache()
        }
        
        Log.d(TAG, "Position cache ${if (enabled) "enabled" else "disabled"}")
    }

    fun isPositionCacheEnabled(): Boolean = isCacheEnabled

    /**
     * Get cached board positions if available and cache is enabled
     */
    fun getCachedPositions(): List<Rect>? {
        return if (isCacheEnabled && currentLayout != null) {
            currentLayout?.cardPositions?.map { it.toRect() }
        } else {
            null
        }
    }

    /**
     * Save detected board layout for future use
     */
    fun saveBoardLayout(positions: List<Rect>, screenWidth: Int, screenHeight: Int) {
        if (!isCacheEnabled) return

        val layout = BoardLayout(
            cardPositions = positions.map { SerializableRect.fromRect(it) },
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            detectedAt = System.currentTimeMillis()
        )

        currentLayout = layout
        
        try {
            val layoutJson = json.encodeToString(layout)
            prefs.edit().putString(KEY_CURRENT_LAYOUT, layoutJson).apply()
            Log.d(TAG, "Saved board layout with ${positions.size} positions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save board layout", e)
        }
    }

    /**
     * Check if current layout is still valid for the given screen dimensions
     */
    fun isLayoutValid(screenWidth: Int, screenHeight: Int): Boolean {
        return currentLayout?.let { layout ->
            isCacheEnabled && 
            layout.screenWidth == screenWidth && 
            layout.screenHeight == screenHeight &&
            isLayoutRecentlyDetected(layout)
        } ?: false
    }

    /**
     * Validate detected positions against cached layout
     */
    fun validatePositions(detectedPositions: List<Rect>): ValidationResult {
        val cachedPositions = getCachedPositions() ?: return ValidationResult.NO_CACHE
        
        if (cachedPositions.size != detectedPositions.size) {
            Log.d(TAG, "Position count mismatch: cached=${cachedPositions.size}, detected=${detectedPositions.size}")
            return ValidationResult.MISMATCH
        }

        var matchingPositions = 0
        cachedPositions.forEach { cached ->
            val hasMatch = detectedPositions.any { detected ->
                isPositionSimilar(cached, detected)
            }
            if (hasMatch) matchingPositions++
        }

        val matchRate = matchingPositions.toFloat() / cachedPositions.size
        
        return when {
            matchRate >= 0.9f -> ValidationResult.MATCH
            matchRate >= 0.7f -> ValidationResult.PARTIAL_MATCH
            else -> ValidationResult.MISMATCH
        }
    }

    /**
     * Update cached positions with newly detected ones (for minor adjustments)
     */
    fun updateCachedPositions(newPositions: List<Rect>, screenWidth: Int, screenHeight: Int) {
        if (!isCacheEnabled) return
        
        Log.d(TAG, "Updating cached positions with ${newPositions.size} new positions")
        saveBoardLayout(newPositions, screenWidth, screenHeight)
    }

    /**
     * Clear all cached layouts
     */
    fun clearCache() {
        currentLayout = null
        prefs.edit().remove(KEY_CURRENT_LAYOUT).apply()
        Log.d(TAG, "Board layout cache cleared")
    }

    /**
     * Get cache statistics for UI display
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            isEnabled = isCacheEnabled,
            hasLayout = currentLayout != null,
            positionCount = currentLayout?.cardPositions?.size ?: 0,
            lastDetectedAt = currentLayout?.detectedAt,
            screenResolution = currentLayout?.let { "${it.screenWidth}x${it.screenHeight}" }
        )
    }

    private fun loadCurrentLayout() {
        try {
            val layoutJson = prefs.getString(KEY_CURRENT_LAYOUT, null)
            if (layoutJson != null) {
                currentLayout = json.decodeFromString<BoardLayout>(layoutJson)
                Log.d(TAG, "Loaded cached board layout with ${currentLayout?.cardPositions?.size} positions")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached layout", e)
            currentLayout = null
        }
    }

    private fun isLayoutRecentlyDetected(layout: BoardLayout): Boolean {
        val maxAge = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        return System.currentTimeMillis() - layout.detectedAt < maxAge
    }

    private fun isPositionSimilar(pos1: Rect, pos2: Rect): Boolean {
        return Math.abs(pos1.x - pos2.x) <= POSITION_TOLERANCE &&
               Math.abs(pos1.y - pos2.y) <= POSITION_TOLERANCE &&
               Math.abs(pos1.width - pos2.width) <= POSITION_TOLERANCE &&
               Math.abs(pos1.height - pos2.height) <= POSITION_TOLERANCE
    }

    // Data classes
    @Serializable
    data class BoardLayout(
        val cardPositions: List<SerializableRect>,
        val screenWidth: Int,
        val screenHeight: Int,
        val detectedAt: Long
    )

    @Serializable
    data class SerializableRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    ) {
        fun toRect() = Rect(x, y, width, height)
        
        companion object {
            fun fromRect(rect: Rect) = SerializableRect(rect.x, rect.y, rect.width, rect.height)
        }
    }

    enum class ValidationResult {
        MATCH,           // Positions match cached layout
        PARTIAL_MATCH,   // Most positions match (70-90%)
        MISMATCH,        // Positions don't match cached layout
        NO_CACHE         // No cached layout available
    }

    data class CacheStats(
        val isEnabled: Boolean,
        val hasLayout: Boolean,
        val positionCount: Int,
        val lastDetectedAt: Long?,
        val screenResolution: String?
    )
}