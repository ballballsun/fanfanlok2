package com.example.fanfanlok

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.opencv.core.Rect
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Manages static board layout for card position detection
 */
class BoardLayoutManager(private val context: Context) {

    companion object {
        private const val TAG = "BoardLayoutManager"
        private const val PREFS_NAME = "board_layouts"
        private const val KEY_ENABLE_CACHE = "enable_position_cache"
        private const val KEY_STATIC_LAYOUT = "static_layout"
        private const val POSITION_TOLERANCE = 30 // Increased tolerance for static board
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private var staticLayout: StaticBoardLayout? = null
    private var isCacheEnabled: Boolean = true

    init {
        isCacheEnabled = prefs.getBoolean(KEY_ENABLE_CACHE, true)
        loadStaticLayout()
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
     * Get static board positions if available and cache is enabled
     */
    fun getCachedPositions(): List<Rect>? {
        return if (isCacheEnabled && staticLayout != null) {
            staticLayout?.cardPositions?.map { it.toRect() }
        } else {
            null
        }
    }

    /**
     * Save detected board layout as static layout
     */
    fun saveBoardLayout(positions: List<Rect>, screenWidth: Int, screenHeight: Int) {
        if (!isCacheEnabled) return

        // Filter and sort positions for consistency
        val filteredPositions = filterAndSortPositions(positions)

        val layout = StaticBoardLayout(
            cardPositions = filteredPositions.map { SerializableRect.fromRect(it) },
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            savedAt = System.currentTimeMillis(),
            positionCount = filteredPositions.size
        )

        staticLayout = layout

        try {
            val layoutJson = json.encodeToString(layout)
            prefs.edit().putString(KEY_STATIC_LAYOUT, layoutJson).apply()
            Log.d(TAG, "Saved static board layout with ${filteredPositions.size} positions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save static board layout", e)
        }
    }

    /**
     * Filter and sort positions for consistent layout
     */
    private fun filterAndSortPositions(positions: List<Rect>): List<Rect> {
        // Remove duplicates (positions that are too close to each other)
        val filtered = mutableListOf<Rect>()

        positions.forEach { pos ->
            val isDuplicate = filtered.any { existing ->
                isPositionSimilar(pos, existing)
            }

            if (!isDuplicate) {
                filtered.add(pos)
            }
        }

        // Sort positions by row then column (top-left to bottom-right)
        val sorted = filtered.sortedWith(compareBy<Rect> { it.y }.thenBy { it.x })

        Log.d(TAG, "Filtered positions: ${positions.size} -> ${filtered.size}, sorted by position")
        return sorted
    }

    /**
     * Check if current layout is still valid for the given screen dimensions
     */
    fun isLayoutValid(screenWidth: Int, screenHeight: Int): Boolean {
        return staticLayout?.let { layout ->
            isCacheEnabled &&
                    layout.screenWidth == screenWidth &&
                    layout.screenHeight == screenHeight
        } ?: false
    }

    /**
     * Validate detected positions against static layout
     */
    fun validatePositions(detectedPositions: List<Rect>): ValidationResult {
        val cachedPositions = getCachedPositions() ?: return ValidationResult.NO_CACHE

        Log.d(TAG, "Validating ${detectedPositions.size} detected vs ${cachedPositions.size} cached positions")

        var matchingPositions = 0
        var totalExpected = cachedPositions.size

        cachedPositions.forEach { cached ->
            val hasMatch = detectedPositions.any { detected ->
                isPositionSimilar(cached, detected)
            }
            if (hasMatch) {
                matchingPositions++
                Log.v(TAG, "Found match for cached position (${cached.x}, ${cached.y})")
            } else {
                Log.v(TAG, "No match for cached position (${cached.x}, ${cached.y})")
            }
        }

        val matchRate = if (totalExpected > 0) matchingPositions.toFloat() / totalExpected else 0f

        Log.d(TAG, "Position validation: $matchingPositions/$totalExpected matched (${matchRate * 100}%)")

        return when {
            matchRate >= 0.8f -> ValidationResult.MATCH
            matchRate >= 0.5f -> ValidationResult.PARTIAL_MATCH
            else -> ValidationResult.MISMATCH
        }
    }

    /**
     * Update static layout with newly detected positions
     */
    fun updateStaticLayout(newPositions: List<Rect>, screenWidth: Int, screenHeight: Int) {
        if (!isCacheEnabled) return

        Log.d(TAG, "Updating static layout with ${newPositions.size} new positions")
        saveBoardLayout(newPositions, screenWidth, screenHeight)
    }

    /**
     * Clear all cached layouts
     */
    fun clearCache() {
        staticLayout = null
        prefs.edit().remove(KEY_STATIC_LAYOUT).apply()
        Log.d(TAG, "Static board layout cache cleared")
    }

    /**
     * Get cache statistics for UI display
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            isEnabled = isCacheEnabled,
            hasLayout = staticLayout != null,
            positionCount = staticLayout?.positionCount ?: 0,
            lastDetectedAt = staticLayout?.savedAt,
            screenResolution = staticLayout?.let { "${it.screenWidth}x${it.screenHeight}" }
        )
    }

    private fun loadStaticLayout() {
        try {
            val layoutJson = prefs.getString(KEY_STATIC_LAYOUT, null)
            if (layoutJson != null) {
                staticLayout = json.decodeFromString<StaticBoardLayout>(layoutJson)
                Log.d(TAG, "Loaded static board layout with ${staticLayout?.positionCount} positions")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static layout", e)
            staticLayout = null
        }
    }

    private fun isPositionSimilar(pos1: Rect, pos2: Rect): Boolean {
        return kotlin.math.abs(pos1.x - pos2.x) <= POSITION_TOLERANCE &&
                kotlin.math.abs(pos1.y - pos2.y) <= POSITION_TOLERANCE &&
                kotlin.math.abs(pos1.width - pos2.width) <= POSITION_TOLERANCE &&
                kotlin.math.abs(pos1.height - pos2.height) <= POSITION_TOLERANCE
    }

    // Data classes
    @Serializable
    data class StaticBoardLayout(
        val cardPositions: List<SerializableRect>,
        val screenWidth: Int,
        val screenHeight: Int,
        val savedAt: Long,
        val positionCount: Int
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
        MATCH,           // Positions match static layout
        PARTIAL_MATCH,   // Most positions match (50-80%)
        MISMATCH,        // Positions don't match static layout
        NO_CACHE         // No static layout available
    }

    data class CacheStats(
        val isEnabled: Boolean,
        val hasLayout: Boolean,
        val positionCount: Int,
        val lastDetectedAt: Long?,
        val screenResolution: String?
    )
}