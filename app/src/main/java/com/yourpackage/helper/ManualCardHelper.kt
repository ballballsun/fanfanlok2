package com.example.fanfanlok.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.util.Log
import com.example.fanfanlok.BoardLayoutManager
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Core logic for manual card position helper with edge detection
 * Manages touch interactions, card positioning, and automatic size detection
 */
class ManualCardHelper(private val context: Context) {

    companion object {
        private const val TAG = "ManualCardHelper"

        // Default card dimensions (fallback if edge detection fails)
        private const val DEFAULT_CARD_WIDTH = 120
        private const val DEFAULT_CARD_HEIGHT = 160

        // Minimum distance between cards to prevent overlap
        private const val MIN_CARD_DISTANCE = 50

        // Edge detection parameters
        private const val DETECTION_RADIUS = 100 // Pixels around touch point to analyze
        private const val MIN_CARD_AREA = 5000    // Minimum area for valid card detection
        private const val MAX_CARD_AREA = 50000   // Maximum area for valid card detection
    }

    private val boardLayoutManager = BoardLayoutManager(context)
    private val helperSettings = HelperSettings(context)

    // Current card positions being edited
    private val cardPositions = mutableListOf<CardPosition>()
    private var nextCardId = 1
    private var isHelperMode = false

    // Detected card size (null until first successful detection)
    private var detectedCardSize: Point? = null

    // Screenshot for edge detection
    private var currentScreenshot: Bitmap? = null

    // Listeners
    private var onPositionsUpdatedListener: ((List<CardPosition>) -> Unit)? = null
    private var onHelperModeChangedListener: ((Boolean) -> Unit)? = null
    private var onScreenshotRequestListener: (() -> Unit)? = null

    /**
     * Data class representing a manually positioned card
     */
    data class CardPosition(
        val id: Int,
        val centerX: Int,
        val centerY: Int,
        val width: Int = DEFAULT_CARD_WIDTH,
        val height: Int = DEFAULT_CARD_HEIGHT
    ) {
        fun toRect(): org.opencv.core.Rect {
            return org.opencv.core.Rect(
                centerX - width / 2,
                centerY - height / 2,
                width,
                height
            )
        }

        fun getBounds(): android.graphics.Rect {
            return android.graphics.Rect(
                centerX - width / 2,
                centerY - height / 2,
                centerX + width / 2,
                centerY + height / 2
            )
        }

        fun containsPoint(x: Int, y: Int): Boolean {
            return getBounds().contains(x, y)
        }
    }

    /**
     * Start helper mode - load existing positions if available
     */
    fun startHelperMode() {
        Log.d(TAG, "Starting manual card helper mode")
        isHelperMode = true
        detectedCardSize = null // Reset detected size for new session

        // Load existing positions from cache if available
        loadExistingPositions()

        onHelperModeChangedListener?.invoke(true)
        onPositionsUpdatedListener?.invoke(cardPositions.toList())

        Log.d(TAG, "Helper mode started with ${cardPositions.size} existing positions")
    }

    /**
     * Stop helper mode and optionally save positions
     */
    fun stopHelperMode(savePositions: Boolean = true) {
        Log.d(TAG, "Stopping helper mode, save: $savePositions")

        if (savePositions && cardPositions.isNotEmpty()) {
            savePositions()
        }

        isHelperMode = false
        currentScreenshot?.recycle()
        currentScreenshot = null
        onHelperModeChangedListener?.invoke(false)

        Log.d(TAG, "Helper mode stopped")
    }

    /**
     * Set current screenshot for edge detection
     */
    fun setCurrentScreenshot(screenshot: Bitmap) {
        currentScreenshot?.recycle()
        currentScreenshot = screenshot.copy(screenshot.config, false)
        Log.d(TAG, "Screenshot updated for edge detection: ${screenshot.width}x${screenshot.height}")
    }

    /**
     * Handle touch input - detect card size and add new card
     */
    fun handleTouch(x: Int, y: Int): TouchResult {
        if (!isHelperMode) {
            return TouchResult.IGNORED
        }

        Log.d(TAG, "Touch received at ($x, $y)")

        // Check if touch is on existing card
        val existingCard = findCardAtPosition(x, y)
        if (existingCard != null) {
            Log.d(TAG, "Touch on existing card ${existingCard.id}")
            return TouchResult.CARD_SELECTED(existingCard)
        }

        // Check minimum distance from existing cards
        if (!isValidNewPosition(x, y)) {
            Log.d(TAG, "Touch too close to existing card")
            return TouchResult.TOO_CLOSE
        }

        // Request screenshot if we don't have one
        if (currentScreenshot == null) {
            Log.d(TAG, "No screenshot available, requesting one")
            onScreenshotRequestListener?.invoke()
            return TouchResult.SCREENSHOT_NEEDED
        }

        // Detect card size at touch point (if not already detected)
        val cardSize = detectedCardSize ?: detectCardSizeAtPoint(x, y)

        if (cardSize == null) {
            // Fallback to default size if detection fails
            val defaultSize = helperSettings.getCardSize()
            Log.w(TAG, "Card detection failed, using default size: ${defaultSize.x}x${defaultSize.y}")
            cardSize = defaultSize
        } else {
            // Save detected size for future cards
            detectedCardSize = cardSize
            Log.d(TAG, "Using detected card size: ${cardSize.x}x${cardSize.y}")
        }

        // Add new card with detected/default size
        val newCard = CardPosition(
            id = nextCardId++,
            centerX = x,
            centerY = y,
            width = cardSize.x,
            height = cardSize.y
        )

        cardPositions.add(newCard)
        Log.d(TAG, "Added new card ${newCard.id} at ($x, $y) with size ${cardSize.x}x${cardSize.y}")

        onPositionsUpdatedListener?.invoke(cardPositions.toList())
        return TouchResult.CARD_ADDED(newCard, cardSize)
    }

    /**
     * Detect card size at the touched point using edge detection
     */
    private fun detectCardSizeAtPoint(touchX: Int, touchY: Int): Point? {
        val screenshot = currentScreenshot ?: return null

        try {
            Log.d(TAG, "Detecting card size at ($touchX, $touchY)")

            // Convert screenshot to OpenCV Mat
            val imgMat = Mat()
            Utils.bitmapToMat(screenshot, imgMat)

            // Define region of interest around touch point
            val roiSize = DETECTION_RADIUS * 2
            val roiX = max(0, touchX - DETECTION_RADIUS)
            val roiY = max(0, touchY - DETECTION_RADIUS)
            val roiWidth = min(roiSize, screenshot.width - roiX)
            val roiHeight = min(roiSize, screenshot.height - roiY)

            val roi = org.opencv.core.Rect(roiX, roiY, roiWidth, roiHeight)
            val roiMat = Mat(imgMat, roi)

            Log.d(TAG, "Analyzing ROI: $roiX,$roiY ${roiWidth}x$roiHeight")

            // Convert to grayscale
            val grayMat = Mat()
            Imgproc.cvtColor(roiMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // Apply Gaussian blur
            val blurredMat = Mat()
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

            // Apply Canny edge detection
            val edgesMat = Mat()
            Imgproc.Canny(blurredMat, edgesMat, 50.0, 150.0)

            // Find contours
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edgesMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            Log.d(TAG, "Found ${contours.size} contours in ROI")

            // Find the best card-like contour
            val cardRect = findBestCardContour(contours, roiX, roiY, touchX, touchY)

            // Clean up
            imgMat.release()
            roiMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            hierarchy.release()
            contours.forEach { it.release() }

            return cardRect?.let {
                Log.d(TAG, "Detected card size: ${it.x}x${it.y}")
                it
            } ?: run {
                Log.w(TAG, "No suitable card contour found")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in card size detection", e)
            return null
        }
    }

    /**
     * Find the best card-like contour from detected contours
     */
    private fun findBestCardContour(contours: List<MatOfPoint>, roiX: Int, roiY: Int, touchX: Int, touchY: Int): Point? {
        val touchXInRoi = touchX - roiX
        val touchYInRoi = touchY - roiY

        var bestContour: MatOfPoint? = null
        var bestScore = 0.0

        contours.forEach { contour ->
            try {
                val area = Imgproc.contourArea(contour)
                if (area < MIN_CARD_AREA || area > MAX_CARD_AREA) {
                    return@forEach
                }

                val boundingRect = Imgproc.boundingRect(contour)

                // Check if touch point is inside this contour
                if (!isPointInRect(touchXInRoi, touchYInRoi, boundingRect)) {
                    return@forEach
                }

                // Calculate aspect ratio (cards should be roughly rectangular)
                val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.toDouble()
                if (aspectRatio < 0.5 || aspectRatio > 2.0) {
                    return@forEach
                }

                // Score based on area and aspect ratio (prefer typical card ratios)
                val idealAspectRatio = 0.75 // Typical card width/height ratio
                val aspectRatioScore = 1.0 - abs(aspectRatio - idealAspectRatio)
                val areaScore = area / MAX_CARD_AREA.toDouble()
                val score = aspectRatioScore * 0.7 + areaScore * 0.3

                Log.v(TAG, "Contour: area=$area, aspect=$aspectRatio, score=$score")

                if (score > bestScore) {
                    bestScore = score
                    bestContour = contour
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error analyzing contour", e)
            }
        }

        return bestContour?.let { contour ->
            val boundingRect = Imgproc.boundingRect(contour)
            Log.d(TAG, "Best contour: ${boundingRect.width}x${boundingRect.height}, score=$bestScore")
            Point(boundingRect.width, boundingRect.height)
        }
    }

    private fun isPointInRect(x: Int, y: Int, rect: org.opencv.core.Rect): Boolean {
        return x >= rect.x && x < rect.x + rect.width &&
                y >= rect.y && y < rect.y + rect.height
    }

    /**
     * Handle long press - remove card at position
     */
    fun handleLongPress(x: Int, y: Int): Boolean {
        if (!isHelperMode) return false

        val cardToRemove = findCardAtPosition(x, y)
        if (cardToRemove != null) {
            cardPositions.remove(cardToRemove)
            Log.d(TAG, "Removed card ${cardToRemove.id}")

            onPositionsUpdatedListener?.invoke(cardPositions.toList())
            return true
        }

        return false
    }

    /**
     * Get current card positions
     */
    fun getCurrentPositions(): List<CardPosition> {
        return cardPositions.toList()
    }

    /**
     * Get card count
     */
    fun getCardCount(): Int = cardPositions.size

    /**
     * Get detected card size (if any)
     */
    fun getDetectedCardSize(): Point? = detectedCardSize

    /**
     * Clear all positions
     */
    fun clearAllPositions() {
        cardPositions.clear()
        nextCardId = 1
        detectedCardSize = null // Reset detected size
        onPositionsUpdatedListener?.invoke(emptyList())
        Log.d(TAG, "Cleared all card positions and reset detected size")
    }

    /**
     * Save positions to persistent storage
     */
    fun savePositions() {
        if (cardPositions.isEmpty()) {
            Log.w(TAG, "No positions to save")
            return
        }

        // Convert to Rect format for BoardLayoutManager
        val rects = cardPositions.map { it.toRect() }

        // Get screen dimensions (assuming full screen)
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        boardLayoutManager.saveBoardLayout(rects, screenWidth, screenHeight)

        Log.d(TAG, "Saved ${cardPositions.size} card positions to board layout")
    }

    /**
     * Load existing positions from storage
     */
    private fun loadExistingPositions() {
        val cachedPositions = boardLayoutManager.getCachedPositions()
        if (cachedPositions != null && cachedPositions.isNotEmpty()) {
            cardPositions.clear()
            nextCardId = 1

            cachedPositions.forEach { rect ->
                val centerX = rect.x + rect.width / 2
                val centerY = rect.y + rect.height / 2

                val cardPosition = CardPosition(
                    id = nextCardId++,
                    centerX = centerX,
                    centerY = centerY,
                    width = rect.width,
                    height = rect.height
                )

                cardPositions.add(cardPosition)

                // Update detected size from first loaded card
                if (detectedCardSize == null) {
                    detectedCardSize = Point(rect.width, rect.height)
                }
            }

            Log.d(TAG, "Loaded ${cardPositions.size} existing positions from cache")
        }
    }

    /**
     * Find card at given position
     */
    private fun findCardAtPosition(x: Int, y: Int): CardPosition? {
        return cardPositions.find { it.containsPoint(x, y) }
    }

    /**
     * Check if new position is valid (not too close to existing cards)
     */
    private fun isValidNewPosition(x: Int, y: Int): Boolean {
        return cardPositions.none { card ->
            val dx = (x - card.centerX).toDouble()
            val dy = (y - card.centerY).toDouble()
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            distance < MIN_CARD_DISTANCE
        }
    }

    /**
     * Set listener for position updates
     */
    fun setOnPositionsUpdatedListener(listener: (List<CardPosition>) -> Unit) {
        onPositionsUpdatedListener = listener
    }

    /**
     * Set listener for helper mode changes
     */
    fun setOnHelperModeChangedListener(listener: (Boolean) -> Unit) {
        onHelperModeChangedListener = listener
    }

    /**
     * Set listener for screenshot requests
     */
    fun setOnScreenshotRequestListener(listener: () -> Unit) {
        onScreenshotRequestListener = listener
    }

    /**
     * Check if currently in helper mode
     */
    fun isInHelperMode(): Boolean = isHelperMode

    /**
     * Get helper statistics
     */
    fun getHelperStats(): HelperStats {
        return HelperStats(
            totalCards = cardPositions.size,
            isHelperModeActive = isHelperMode,
            hasSavedLayout = boardLayoutManager.getCacheStats().hasLayout,
            detectedCardSize = detectedCardSize
        )
    }

    /**
     * Touch interaction results
     */
    sealed class TouchResult {
        object IGNORED : TouchResult()
        object TOO_CLOSE : TouchResult()
        object SCREENSHOT_NEEDED : TouchResult()
        data class CARD_ADDED(val card: CardPosition, val detectedSize: Point) : TouchResult()
        data class CARD_SELECTED(val card: CardPosition) : TouchResult()
    }

    /**
     * Helper statistics
     */
    data class HelperStats(
        val totalCards: Int,
        val isHelperModeActive: Boolean,
        val hasSavedLayout: Boolean,
        val detectedCardSize: Point?
    )
}