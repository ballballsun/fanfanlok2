package com.example.fanfanlok

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class CardRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "CardRecognizer"

        // Card detection parameters for 4x6 memory game
        private const val EXPECTED_CARDS = 24  // 4 rows × 6 columns
        private const val MIN_CARD_AREA = 1000      // Minimum card area in pixels (around 50x40)
        private const val MAX_CARD_AREA = 20000     // Maximum card area in pixels (around 200x100)
        private const val MIN_ASPECT_RATIO = 0.5    // Minimum width/height ratio
        private const val MAX_ASPECT_RATIO = 2.5    // Maximum width/height ratio
        private const val CONTOUR_APPROXIMATION_EPSILON = 0.02  // For rectangle detection

        // Edge detection parameters
        private const val GAUSSIAN_BLUR_SIZE = 5
        private const val CANNY_THRESHOLD_1 = 50.0
        private const val CANNY_THRESHOLD_2 = 150.0

        // Morphological operations
        private const val MORPH_KERNEL_SIZE = 3

        // Grid detection parameters
        private const val POSITION_TOLERANCE = 50  // Tolerance for grid alignment
    }

    /**
     * Detect all card positions on the memory game board
     */
    fun detectAllCards(screenshot: Bitmap): CardDetectionResult {
        val detectedCards = mutableListOf<DetectedCard>()

        val imgMat = Mat()
        Utils.bitmapToMat(screenshot, imgMat)

        try {
            // Convert to grayscale for processing
            val grayMat = Mat()
            Imgproc.cvtColor(imgMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // Apply Gaussian blur to reduce noise
            val blurredMat = Mat()
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(GAUSSIAN_BLUR_SIZE.toDouble(), GAUSSIAN_BLUR_SIZE.toDouble()), 0.0)

            // Apply Canny edge detection
            val edgesMat = Mat()
            Imgproc.Canny(blurredMat, edgesMat, CANNY_THRESHOLD_1, CANNY_THRESHOLD_2)

            // Apply morphological operations to connect edges
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(MORPH_KERNEL_SIZE.toDouble(), MORPH_KERNEL_SIZE.toDouble()))
            val morphMat = Mat()
            Imgproc.morphologyEx(edgesMat, morphMat, Imgproc.MORPH_CLOSE, kernel)

            // Find contours
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(morphMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            Log.d(TAG, "Found ${contours.size} contours")

            // Filter contours to find card-like rectangles
            val cardCandidates = mutableListOf<DetectedCard>()
            contours.forEachIndexed { index, contour ->
                val cardRect = analyzeContourForCard(contour, index)
                if (cardRect != null) {
                    cardCandidates.add(cardRect)
                }
                contour.release()
            }

            Log.d(TAG, "Found ${cardCandidates.size} card candidates")

            // Filter and organize cards into grid
            val finalCards = filterAndOrganizeCards(cardCandidates)

            detectedCards.addAll(finalCards)

            // Clean up
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            morphMat.release()
            kernel.release()
            hierarchy.release()

        } catch (e: Exception) {
            Log.e(TAG, "Error in card detection", e)
        } finally {
            imgMat.release()
        }

        Log.d(TAG, "Final result: detected ${detectedCards.size} cards")

        return CardDetectionResult(
            cards = detectedCards,
            boardPositions = detectedCards.map { it.position }
        )
    }

    /**
     * Analyze a contour to determine if it represents a card
     */
    private fun analyzeContourForCard(contour: MatOfPoint, index: Int): DetectedCard? {
        try {
            // Calculate contour area
            val area = Imgproc.contourArea(contour)
            if (area < MIN_CARD_AREA || area > MAX_CARD_AREA) {
                Log.v(TAG, "Contour $index rejected: area $area outside range [$MIN_CARD_AREA, $MAX_CARD_AREA]")
                return null
            }

            // Get bounding rectangle
            val boundingRect = Imgproc.boundingRect(contour)
            val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.toDouble()

            if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
                Log.v(TAG, "Contour $index rejected: aspect ratio $aspectRatio outside range [$MIN_ASPECT_RATIO, $MAX_ASPECT_RATIO]")
                return null
            }

            // Check if contour is approximately rectangular
            if (!isRectangular(contour)) {
                Log.v(TAG, "Contour $index rejected: not rectangular enough")
                return null
            }

            // Additional filtering: check area vs bounding rectangle ratio
            val boundingArea = boundingRect.width * boundingRect.height
            val areaRatio = area / boundingArea
            if (areaRatio < 0.6) { // Card should fill at least 60% of its bounding rectangle
                Log.v(TAG, "Contour $index rejected: area ratio $areaRatio too low")
                return null
            }

            // Convert OpenCV Rect to our Rect format
            val cardPosition = Rect(
                boundingRect.x,
                boundingRect.y,
                boundingRect.width,
                boundingRect.height
            )

            Log.d(TAG, "Card candidate detected at (${cardPosition.x}, ${cardPosition.y}) ${cardPosition.width}x${cardPosition.height}, area: $area, ratio: $aspectRatio")

            return DetectedCard(
                templateIndex = index,
                templateName = "card_$index",
                position = cardPosition,
                isFaceUp = true, // Assume all detected cards are face up for now
                confidence = areaRatio // Use area ratio as confidence measure
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing contour $index", e)
            return null
        }
    }

    /**
     * Filter card candidates and organize them into a 4x6 grid
     */
    private fun filterAndOrganizeCards(candidates: List<DetectedCard>): List<DetectedCard> {
        if (candidates.isEmpty()) {
            Log.w(TAG, "No card candidates to organize")
            return emptyList()
        }

        // Remove overlapping cards (keep the larger one)
        val filteredCards = removeOverlappingCards(candidates)
        Log.d(TAG, "After removing overlaps: ${filteredCards.size} cards")

        // Sort cards by position (top-left to bottom-right)
        val sortedCards = filteredCards.sortedWith(compareBy<DetectedCard> { it.position.y }.thenBy { it.position.x })

        // Try to organize into 4x6 grid
        val gridCards = organizeIntoGrid(sortedCards)
        Log.d(TAG, "After grid organization: ${gridCards.size} cards")

        return gridCards.take(EXPECTED_CARDS) // Limit to expected number of cards
    }

    /**
     * Remove overlapping cards, keeping the one with higher confidence
     */
    private fun removeOverlappingCards(cards: List<DetectedCard>): List<DetectedCard> {
        val result = mutableListOf<DetectedCard>()

        cards.forEach { card ->
            val hasOverlap = result.any { existing ->
                isOverlapping(card.position, existing.position)
            }

            if (!hasOverlap) {
                result.add(card)
            } else {
                // Replace if current card has higher confidence
                val overlappingIndex = result.indexOfFirst { existing ->
                    isOverlapping(card.position, existing.position)
                }
                if (overlappingIndex >= 0 && card.confidence > result[overlappingIndex].confidence) {
                    result[overlappingIndex] = card
                }
            }
        }

        return result
    }

    /**
     * Check if two rectangles overlap significantly
     */
    private fun isOverlapping(rect1: Rect, rect2: Rect): Boolean {
        val overlapX = maxOf(0, minOf(rect1.x + rect1.width, rect2.x + rect2.width) - maxOf(rect1.x, rect2.x))
        val overlapY = maxOf(0, minOf(rect1.y + rect1.height, rect2.y + rect2.height) - maxOf(rect1.y, rect2.y))
        val overlapArea = overlapX * overlapY

        val area1 = rect1.width * rect1.height
        val area2 = rect2.width * rect2.height
        val minArea = minOf(area1, area2)

        return overlapArea > minArea * 0.3 // 30% overlap threshold
    }

    /**
     * Organize cards into a 4x6 grid pattern
     */
    private fun organizeIntoGrid(sortedCards: List<DetectedCard>): List<DetectedCard> {
        if (sortedCards.size < 12) { // Need at least half the expected cards
            Log.w(TAG, "Too few cards (${sortedCards.size}) to organize into grid")
            return sortedCards
        }

        // Try to detect grid structure
        val gridCards = mutableListOf<DetectedCard>()

        // Group cards by approximate Y position (rows)
        val rowGroups = mutableListOf<MutableList<DetectedCard>>()

        sortedCards.forEach { card ->
            val rowIndex = rowGroups.indexOfFirst { row ->
                row.isNotEmpty() && abs(card.position.y - row.first().position.y) < POSITION_TOLERANCE
            }

            if (rowIndex >= 0) {
                rowGroups[rowIndex].add(card)
            } else {
                rowGroups.add(mutableListOf(card))
            }
        }

        Log.d(TAG, "Detected ${rowGroups.size} rows")

        // Sort each row by X position and take up to 6 cards per row
        rowGroups.forEach { row ->
            val sortedRow = row.sortedBy { it.position.x }.take(6)
            gridCards.addAll(sortedRow)
        }

        // Limit to 4 rows
        return gridCards.take(24)
    }

    /**
     * Check if a contour is approximately rectangular
     */
    private fun isRectangular(contour: MatOfPoint): Boolean {
        // Approximate the contour to a polygon
        val epsilon = CONTOUR_APPROXIMATION_EPSILON * Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
        val approxCurve = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approxCurve, epsilon, true)

        val vertices = approxCurve.toArray()
        approxCurve.release()

        // A rectangle should have 4 vertices
        if (vertices.size != 4) {
            Log.v(TAG, "Contour has ${vertices.size} vertices, not rectangular")
            return false
        }

        // Check if angles are approximately 90 degrees
        val angles = mutableListOf<Double>()
        for (i in vertices.indices) {
            val p1 = vertices[i]
            val p2 = vertices[(i + 1) % vertices.size]
            val p3 = vertices[(i + 2) % vertices.size]

            val angle = calculateAngle(p1, p2, p3)
            angles.add(angle)
        }

        // All angles should be close to 90 degrees (allow ±20 degrees tolerance)
        val isRectangular = angles.all { abs(it - 90.0) < 20.0 }

        if (!isRectangular) {
            Log.v(TAG, "Angles not rectangular: $angles")
        }

        return isRectangular
    }

    /**
     * Calculate angle between three points in degrees
     */
    private fun calculateAngle(p1: Point, p2: Point, p3: Point): Double {
        val v1x = p1.x - p2.x
        val v1y = p1.y - p2.y
        val v2x = p3.x - p2.x
        val v2y = p3.y - p2.y

        val dot = v1x * v2x + v1y * v2y
        val mag1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
        val mag2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)

        if (mag1 == 0.0 || mag2 == 0.0) return 0.0

        val cos = dot / (mag1 * mag2)
        val angle = kotlin.math.acos(cos.coerceIn(-1.0, 1.0))
        return Math.toDegrees(angle)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        // No templates to clean up in this implementation
        Log.d(TAG, "CardRecognizer cleanup completed")
    }

    // Data classes
    data class DetectedCard(
        val templateIndex: Int,
        val templateName: String,
        val position: Rect,
        val isFaceUp: Boolean,
        val confidence: Double
    )

    data class CardDetectionResult(
        val cards: List<DetectedCard>,
        val boardPositions: List<Rect>
    )
}