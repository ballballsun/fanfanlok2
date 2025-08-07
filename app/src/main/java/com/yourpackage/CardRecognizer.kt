package com.yourpackage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED

class CardRecognizer(private val templates: List<Mat>) {

    private val matchThreshold = 0.8  // Adjust threshold for match confidence

    /**
     * Recognize cards on the given screenshot bitmap.
     * Returns a list of detected card positions and their template index.
     */
    fun recognizeCards(screenshot: Bitmap): List<DetectedCard> {
        val results = mutableListOf<DetectedCard>()

        // Convert screenshot to OpenCV Mat
        val imgMat = Mat()
        Utils.bitmapToMat(screenshot, imgMat)
        Imgproc.cvtColor(imgMat, imgMat, Imgproc.COLOR_BGR2GRAY)

        for ((index, template) in templates.withIndex()) {
            val resultCols = imgMat.cols() - template.cols() + 1
            val resultRows = imgMat.rows() - template.rows() + 1
            val result = Mat(resultRows, resultCols, org.opencv.core.CvType.CV_32FC1)

            // Template Matching
            Imgproc.matchTemplate(imgMat, template, result, TM_CCOEFF_NORMED)
            Core.MinMaxLocResult().also { mmr ->
                Core.minMaxLoc(result, mmr)
                if (mmr.maxVal >= matchThreshold) {
                    val matchLoc = mmr.maxLoc
                    results.add(
                        DetectedCard(
                            templateIndex = index,
                            location = Rect(
                                matchLoc.x.toInt(),
                                matchLoc.y.toInt(),
                                template.cols(),
                                template.rows()
                            )
                        )
                    )
                    // Optional: To avoid multiple detections of the same card, you could mask matched region here
                }
            }
            result.release()
        }

        imgMat.release()
        return results
    }

    data class DetectedCard(val templateIndex: Int, val location: Rect)
}
