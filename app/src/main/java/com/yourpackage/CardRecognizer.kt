package com.example.fanfanlok

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.IOException

class CardRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "CardRecognizer"
        private const val MATCH_THRESHOLD = 0.65 // Lowered for better detection
        private const val CARD_BACK_THRESHOLD = 0.70 // Lowered for better face-down detection
        private const val ASSETS_CARDS_FOLDER = "cards"
        private const val CARD_BACK_FILENAME = "card_back.png"
    }

    private val cardTemplates = mutableListOf<Mat>()
    private val templateNames = mutableListOf<String>()
    private var cardBackTemplate: Mat? = null

    // Cache for optimization
    private val templateCache = mutableMapOf<String, Mat>()

    init {
        loadTemplates()
    }

    /**
     * Load all card templates and card back from assets folder
     */
    private fun loadTemplates() {
        try {
            // Load card back template first
            loadCardBackTemplate()

            // Load all card templates from assets/cards folder
            val assetManager = context.assets
            val cardFiles = assetManager.list(ASSETS_CARDS_FOLDER) ?: emptyArray()

            cardFiles.filter { it.endsWith(".png") || it.endsWith(".jpg") }
                .filterNot { it == CARD_BACK_FILENAME } // Exclude card back
                .sorted()
                .forEach { filename ->
                    loadCardTemplate(filename)
                }

            Log.d(TAG, "Loaded ${cardTemplates.size} card templates and card back template")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }

    private fun loadCardBackTemplate() {
        try {
            val inputStream = context.assets.open("$ASSETS_CARDS_FOLDER/$CARD_BACK_FILENAME")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            cardBackTemplate = Mat()
            Utils.bitmapToMat(bitmap, cardBackTemplate!!)
            Imgproc.cvtColor(cardBackTemplate!!, cardBackTemplate!!, Imgproc.COLOR_BGR2GRAY)

            Log.d(TAG, "Loaded card back template: $CARD_BACK_FILENAME")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load card back template", e)
        }
    }

    private fun loadCardTemplate(filename: String) {
        try {
            val inputStream = context.assets.open("$ASSETS_CARDS_FOLDER/$filename")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val template = Mat()
            Utils.bitmapToMat(bitmap, template)
            Imgproc.cvtColor(template, template, Imgproc.COLOR_BGR2GRAY)

            cardTemplates.add(template)
            templateNames.add(filename)
            templateCache[filename] = template

            Log.d(TAG, "Loaded card template: $filename")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load template: $filename", e)
        }
    }

    /**
     * Detect all cards on the screenshot, including their state (face-up/face-down)
     */
    fun detectAllCards(screenshot: Bitmap, knownPositions: List<Rect>? = null): CardDetectionResult {
        val imgMat = Mat()
        Utils.bitmapToMat(screenshot, imgMat)
        Imgproc.cvtColor(imgMat, imgMat, Imgproc.COLOR_BGR2GRAY)

        val detectedCards = mutableListOf<DetectedCard>()
        val searchRegions = knownPositions ?: detectCardPositions(imgMat)

        Log.d(TAG, "Searching for cards in ${searchRegions.size} regions")

        // If we have known positions, search only in those areas for better performance
        if (knownPositions != null && knownPositions.isNotEmpty()) {
            searchRegions.forEach { position ->
                try {
                    val cardRegion = Mat(imgMat, position)
                    val cardInfo = analyzeCardRegion(cardRegion, position)
                    if (cardInfo != null) {
                        detectedCards.add(cardInfo)
                        Log.d(TAG, "Card detected at cached position: ${cardInfo.templateName}, faceUp=${cardInfo.isFaceUp}")
                    } else {
                        // If we can't detect anything in a known position, assume it's face-down
                        detectedCards.add(DetectedCard(
                            templateIndex = -1,
                            templateName = "card_back",
                            position = position,
                            isFaceUp = false,
                            confidence = 0.5
                        ))
                        Log.d(TAG, "Assuming face-down card at cached position")
                    }
                    cardRegion.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error analyzing cached position: ${e.message}")
                }
            }
        } else {
            // Full screen detection - slower but finds new board layouts
            detectedCards.addAll(performFullScreenDetection(imgMat))
        }

        imgMat.release()

        val result = CardDetectionResult(
            cards = detectedCards,
            boardPositions = if (searchRegions.isNotEmpty()) searchRegions else detectedCards.map { it.position }
        )

        Log.d(TAG, "Detection complete: ${result.cards.size} cards, ${result.boardPositions.size} positions")
        return result
    }

    /**
     * Detect card positions on the board (for first-time setup or when layout changes)
     */
    private fun detectCardPositions(imgMat: Mat): List<Rect> {
        val positions = mutableListOf<Rect>()

        cardBackTemplate?.let { backTemplate ->
            val result = Mat()
            Imgproc.matchTemplate(imgMat, backTemplate, result, Imgproc.TM_CCOEFF_NORMED)

            // Create a binary mask of potential matches - convert to proper type for findContours
            val thresholdMat = Mat()
            Imgproc.threshold(result, thresholdMat, CARD_BACK_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)

            // Convert to CV_8UC1 for findContours
            val binaryMat = Mat()
            thresholdMat.convertTo(binaryMat, CvType.CV_8UC1)

            // Find contours to get potential card regions
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // Convert contours to rectangles and filter by size
            contours.forEach { contour ->
                val boundingRect = Imgproc.boundingRect(contour)

                // Filter by reasonable card size (adjust these values based on your card dimensions)
                val minCardArea = 1000 // Minimum card area in pixels
                val maxCardArea = 50000 // Maximum card area in pixels
                val area = boundingRect.width * boundingRect.height

                if (area in minCardArea..maxCardArea) {
                    // Convert OpenCV Rect to org.opencv.core.Rect
                    val cardRect = Rect(
                        boundingRect.x,
                        boundingRect.y,
                        boundingRect.width,
                        boundingRect.height
                    )
                    positions.add(cardRect)
                    Log.d(TAG, "Found potential card position: ${cardRect.x},${cardRect.y} ${cardRect.width}x${cardRect.height}")
                }
                contour.release()
            }

            // If contour method didn't work well, fall back to template matching
            if (positions.size < 20) { // Expected around 24 cards
                positions.clear()
                positions.addAll(detectCardsByTemplateMatching(result, backTemplate))
            }

            result.release()
            thresholdMat.release()
            binaryMat.release()
            hierarchy.release()
        }

        Log.d(TAG, "Detected ${positions.size} card positions")
        return positions
    }

    private fun detectCardsByTemplateMatching(result: Mat, backTemplate: Mat): List<Rect> {
        val positions = mutableListOf<Rect>()
        val mask = Mat.zeros(result.size(), CvType.CV_8UC1)

        // Find multiple matches
        for (i in 0 until 30) { // Try to find up to 30 cards
            val mmr = Core.minMaxLoc(result, mask)
            if (mmr.maxVal < CARD_BACK_THRESHOLD) break

            val cardRect = Rect(
                mmr.maxLoc.x.toInt(),
                mmr.maxLoc.y.toInt(),
                backTemplate.cols(),
                backTemplate.rows()
            )
            positions.add(cardRect)

            // Mask out the found area to prevent duplicate detections
            val maskRect = org.opencv.core.Rect(
                (mmr.maxLoc.x - backTemplate.cols() / 2).toInt().coerceAtLeast(0),
                (mmr.maxLoc.y - backTemplate.rows() / 2).toInt().coerceAtLeast(0),
                backTemplate.cols(),
                backTemplate.rows()
            )
            Imgproc.rectangle(mask, maskRect, Scalar(255.0), -1)
        }

        mask.release()
        return positions
    }

    /**
     * Analyze a specific card region to determine its state and identity
     */
    private fun analyzeCardRegion(cardRegion: Mat, position: Rect): DetectedCard? {
        // First check if it's face-down (matches card back)
        cardBackTemplate?.let { backTemplate ->
            val backResult = Mat()
            Imgproc.matchTemplate(cardRegion, backTemplate, backResult, Imgproc.TM_CCOEFF_NORMED)
            val backMatch = Core.minMaxLoc(backResult)
            backResult.release()

            if (backMatch.maxVal >= CARD_BACK_THRESHOLD) {
                return DetectedCard(
                    templateIndex = -1, // -1 indicates face-down card
                    templateName = "card_back",
                    position = position,
                    isFaceUp = false,
                    confidence = backMatch.maxVal
                )
            }
        }

        // Check against all card templates to find face-up cards
        var bestMatch: DetectedCard? = null
        var bestConfidence = 0.0

        cardTemplates.forEachIndexed { index, template ->
            val result = Mat()
            Imgproc.matchTemplate(cardRegion, template, result, Imgproc.TM_CCOEFF_NORMED)
            val match = Core.minMaxLoc(result)
            result.release()

            if (match.maxVal >= MATCH_THRESHOLD && match.maxVal > bestConfidence) {
                bestConfidence = match.maxVal
                bestMatch = DetectedCard(
                    templateIndex = index,
                    templateName = templateNames[index],
                    position = position,
                    isFaceUp = true,
                    confidence = match.maxVal
                )
            }
        }

        return bestMatch
    }

    /**
     * Perform full screen detection (slower, used for initial setup)
     */
    private fun performFullScreenDetection(imgMat: Mat): List<DetectedCard> {
        val detectedCards = mutableListOf<DetectedCard>()

        // Detect face-down cards first (most common in initial state)
        cardBackTemplate?.let { backTemplate ->
            detectCardsOfType(imgMat, backTemplate, -1, "card_back", false)
                .forEach { detectedCards.add(it) }
        }

        // Detect face-up cards
        cardTemplates.forEachIndexed { index, template ->
            detectCardsOfType(imgMat, template, index, templateNames[index], true)
                .forEach { detectedCards.add(it) }
        }

        Log.d(TAG, "Full screen detection found ${detectedCards.size} cards")
        return detectedCards
    }

    /**
     * Detect all instances of a specific card type
     */
    private fun detectCardsOfType(
        imgMat: Mat,
        template: Mat,
        templateIndex: Int,
        templateName: String,
        isFaceUp: Boolean
    ): List<DetectedCard> {
        val results = mutableListOf<DetectedCard>()
        val result = Mat()

        Imgproc.matchTemplate(imgMat, template, result, Imgproc.TM_CCOEFF_NORMED)

        val threshold = if (isFaceUp) MATCH_THRESHOLD else CARD_BACK_THRESHOLD
        val mask = Mat.zeros(result.size(), CvType.CV_8UC1)

        // For face-down cards, we want to find more matches (up to 24)
        val maxMatches = if (isFaceUp) 4 else 30 // Face-down cards can appear many times

        for (i in 0 until maxMatches) {
            val mmr = Core.minMaxLoc(result, mask)
            if (mmr.maxVal < threshold) break

            val position = Rect(
                mmr.maxLoc.x.toInt(),
                mmr.maxLoc.y.toInt(),
                template.cols(),
                template.rows()
            )

            results.add(DetectedCard(
                templateIndex = templateIndex,
                templateName = templateName,
                position = position,
                isFaceUp = isFaceUp,
                confidence = mmr.maxVal
            ))

            // Mask out this detection to find other instances
            val maskRect = org.opencv.core.Rect(
                (mmr.maxLoc.x - template.cols() / 2).toInt().coerceAtLeast(0),
                (mmr.maxLoc.y - template.rows() / 2).toInt().coerceAtLeast(0),
                template.cols(),
                template.rows()
            )
            Imgproc.rectangle(mask, maskRect, Scalar(255.0), -1)
        }

        result.release()
        mask.release()

        Log.d(TAG, "Found ${results.size} instances of $templateName (faceUp: $isFaceUp)")
        return results
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        cardTemplates.forEach { it.release() }
        cardBackTemplate?.release()
        templateCache.values.forEach { it.release() }
        cardTemplates.clear()
        templateNames.clear()
        templateCache.clear()
    }

    // Data classes
    data class DetectedCard(
        val templateIndex: Int, // -1 for face-down cards
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