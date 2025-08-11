package com.example.fanfanlok

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "ScreenCaptureServiceChannel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_INTENT = "result_intent"
        const val TAG = "ScreenCapture"
        const val VIRTUAL_DISPLAY_NAME = "ScreenCapture"
        const val CAPTURE_INTERVAL_MS = 1000L // Slower capture for static board detection

        // Service state broadcast actions
        const val ACTION_AUTOMATION_STATE = "com.example.fanfanlok.AUTOMATION_STATE"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_DETECTION_COUNT = "detection_count"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Recognition components
    private var cardRecognizer: CardRecognizer? = null
    private var boardLayoutManager: BoardLayoutManager? = null

    // Detection state
    private var isDetectionRunning = false
    private var captureHandler = Handler(Looper.getMainLooper())
    private var lastCaptureTime = 0L
    private var detectionCount = 0

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // OpenCV initialization state
    private var isOpenCVInitialized = false

    // Static board detection state
    private var staticLayoutDetected = false
    private var detectionAttempts = 0
    private val maxDetectionAttempts = 5

    // Projection state
    private var isProjectionRunning = false

    // Store last detected cards for showing overlay when detection is stopped
    private var lastDetectedCards = listOf<CardRecognizer.DetectedCard>()

    private fun requestScreenshotForHelper() {
        Log.d(TAG, "Capturing screenshot for helper edge detection")

        imageReader?.let { reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        // Convert bitmap to byte array for transmission
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        val byteArray = stream.toByteArray()

                        // Send screenshot data to overlay service
                        val intent = Intent("com.example.fanfanlok.SCREENSHOT_FOR_HELPER").apply {
                            putExtra("screenshot_data", byteArray)
                        }
                        sendBroadcast(intent)

                        Log.d(TAG, "Screenshot sent to helper: ${bitmap.width}x${bitmap.height}")
                        bitmap.recycle()
                        stream.close()
                    } else {
                        Log.w(TAG, "Failed to convert image to bitmap for helper")
                    }
                    image.close()
                } else {
                    Log.w(TAG, "No image available for helper screenshot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screenshot for helper", e)
            }
        } ?: run {
            Log.w(TAG, "ImageReader not available for helper screenshot")
        }
    }

    // Broadcast receiver for detection control
    private val detectionControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received detection control: ${intent?.action}")
            when (intent?.action) {
                OverlayService.ACTION_START_AUTOMATION -> {
                    Log.d(TAG, "Processing START_DETECTION broadcast")
                    startDetection()
                }
                OverlayService.ACTION_STOP_AUTOMATION -> {
                    Log.d(TAG, "Processing STOP_DETECTION broadcast")
                    stopDetection()
                }
                "com.example.fanfanlok.REQUEST_DETECTION" -> {
                    Log.d(TAG, "Processing REQUEST_DETECTION broadcast")
                    requestCurrentDetection()
                }
                "com.example.fanfanlok.REQUEST_SCREENSHOT_FOR_HELPER" -> {
                    Log.d(TAG, "Processing REQUEST_SCREENSHOT_FOR_HELPER broadcast")
                    requestScreenshotForHelper()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        createNotificationChannel()

        // Initialize OpenCV synchronously
        initializeOpenCV()

        // Register detection control receiver
        registerDetectionControlReceiver()
    }

    private fun registerDetectionControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_START_AUTOMATION)
            addAction(OverlayService.ACTION_STOP_AUTOMATION)
            addAction("com.example.fanfanlok.REQUEST_DETECTION")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(detectionControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(detectionControlReceiver, filter)
            }
            Log.d(TAG, "Detection control receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register detection control receiver", e)
        }
    }

    private fun initializeOpenCV() {
        try {
            if (OpenCVLoader.initDebug()) {
                Log.d(TAG, "OpenCV loaded successfully")
                isOpenCVInitialized = true
                initializeRecognition()
            } else {
                Log.e(TAG, "OpenCV initialization failed")
                isOpenCVInitialized = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OpenCV initialization", e)
            isOpenCVInitialized = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")

        // If projection is already active, ignore duplicate start
        if (isProjectionRunning) {
            Log.w(TAG, "MediaProjection already running; ignoring duplicate start request")
            return START_NOT_STICKY
        }

        // Read the permission data from Activity
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_INTENT)

        Log.d(TAG, "Result code: $resultCode, Result data: $resultData")

        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "Invalid result code or missing resultData")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start the foreground service
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Get the MediaProjection instance
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection")
            stopSelf()
            return START_NOT_STICKY
        }

        // Set up virtual display for capture
        setupVirtualDisplay()
        isProjectionRunning = true

        Log.d(TAG, "MediaProjection started successfully - ready for detection")
        return START_NOT_STICKY
    }

    private fun initializeRecognition() {
        if (!isOpenCVInitialized) {
            Log.w(TAG, "Cannot initialize recognition - OpenCV not ready")
            return
        }

        try {
            cardRecognizer = CardRecognizer(this)
            boardLayoutManager = BoardLayoutManager(this)

            Log.d(TAG, "Recognition components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognition components", e)
        }
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        val screenDensity = displayMetrics.densityDpi
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density: $screenDensity")

        // Register MediaProjection.Callback
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection stopped")
                virtualDisplay?.release()
                imageReader?.close()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // Prepare ImageReader
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        // Create virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        // Set image available listener
        imageReader?.setOnImageAvailableListener({ reader ->
            val currentTime = System.currentTimeMillis()

            // Rate limit captures
            if (currentTime - lastCaptureTime < CAPTURE_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            lastCaptureTime = currentTime

            val image = reader.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Image captured: ${image.width}x${image.height}")
                if (isDetectionRunning && isOpenCVInitialized) {
                    processScreenCapture(image)
                }
                image.close()
            }
        }, Handler(Looper.getMainLooper()))

        Log.d(TAG, "Virtual display setup complete: ${screenWidth}x${screenHeight}")
    }

    private fun processScreenCapture(image: Image) {
        try {
            Log.d(TAG, "Processing screen capture for card detection...")
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                performCardDetection(bitmap)
                bitmap.recycle()
            } else {
                Log.w(TAG, "Failed to convert image to bitmap")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen capture", e)
        }
    }

    private fun performCardDetection(screenshot: Bitmap) {
        val cardRecognizer = this.cardRecognizer ?: return
        val boardLayoutManager = this.boardLayoutManager ?: return

        try {
            Log.d(TAG, "Performing card detection on ${screenshot.width}x${screenshot.height} bitmap")

            // Detect cards using edge detection and contour analysis
            val detectionResult = cardRecognizer.detectAllCards(screenshot)
            Log.d(TAG, "Cards detected: ${detectionResult.cards.size}")

            detectionResult.cards.forEachIndexed { index, card ->
                Log.d(TAG, "Card $index: at (${card.position.x}, ${card.position.y}) ${card.position.width}x${card.position.height}")
            }

            // Store last detected cards for overlay use
            lastDetectedCards = detectionResult.cards

            // Check if we have enough cards for a static layout
            if (!staticLayoutDetected && detectionResult.cards.size >= 10) { // Expect at least 10 cards
                staticLayoutDetected = true
                Log.d(TAG, "Static board layout detected! Found ${detectionResult.cards.size} cards")

                // Save the static board layout
                boardLayoutManager.saveBoardLayout(
                    detectionResult.boardPositions,
                    screenWidth,
                    screenHeight
                )
            }

            // Update detection count
            detectionCount++

            // Broadcast detection results to overlay
            broadcastDetectionResults(detectionResult.cards)

            // Broadcast detection state
            broadcastDetectionState()

            Log.d(TAG, "Detection cycle completed successfully - attempt ${detectionAttempts + 1}")
            detectionAttempts++

            // Stop detection after successful layout detection or max attempts
            if (staticLayoutDetected || detectionAttempts >= maxDetectionAttempts) {
                if (staticLayoutDetected) {
                    Log.d(TAG, "Static layout saved, continuing periodic detection for overlay")
                } else {
                    Log.w(TAG, "Max detection attempts reached without finding sufficient cards")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in card detection", e)
        }
    }

    private fun requestCurrentDetection() {
        Log.d(TAG, "Requesting current detection...")

        // Check if we have stored cards from previous detection
        if (lastDetectedCards.isNotEmpty()) {
            Log.d(TAG, "Broadcasting last detected cards: ${lastDetectedCards.size} cards")
            broadcastDetectionResults(lastDetectedCards)
            return
        }

        // If no stored cards and detection is running, try to capture current state
        if (isDetectionRunning) {
            imageReader?.let { reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        Log.d(TAG, "Processing on-demand detection request")
                        val bitmap = imageToBitmap(image)
                        if (bitmap != null) {
                            performCardDetection(bitmap)
                            bitmap.recycle()
                        }
                        image.close()
                    } else {
                        Log.w(TAG, "No image available for on-demand detection")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing on-demand detection", e)
                }
            }
        } else {
            Log.w(TAG, "No stored cards and detection not running - cannot provide detection results")
            // Still try to broadcast empty results to reset overlay state
            broadcastDetectionResults(emptyList())
        }
    }

    private fun broadcastDetectionResults(cards: List<CardRecognizer.DetectedCard>) {
        try {
            val parcelableCards = cards.map { DetectedCardParcelable.fromDetectedCard(it) }
            val intent = Intent(OverlayService.ACTION_CARD_DETECTION_UPDATE).apply {
                putParcelableArrayListExtra(OverlayService.EXTRA_DETECTED_CARDS, ArrayList(parcelableCards))
                setPackage(packageName) // Ensure it stays within our app
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Broadcasted detection results: ${cards.size} cards")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting detection results", e)
        }
    }

    fun startDetection() {
        if (!isOpenCVInitialized) {
            Log.e(TAG, "Cannot start detection - OpenCV not initialized")
            broadcastDetectionState()
            return
        }

        Log.d(TAG, "Starting card detection - OpenCV initialized: $isOpenCVInitialized")
        isDetectionRunning = true
        staticLayoutDetected = false // Reset detection state
        detectionAttempts = 0
        detectionCount = 0
        boardLayoutManager?.clearCache() // Clear cache for fresh detection
        broadcastDetectionState()
        Log.d(TAG, "Detection started - now processing captures")
    }

    fun stopDetection() {
        Log.d(TAG, "Stopping card detection")
        isDetectionRunning = false
        detectionAttempts = 0
        broadcastDetectionState()
        Log.d(TAG, "Detection stopped")
    }

    private fun broadcastDetectionState() {
        try {
            val intent = Intent(ACTION_AUTOMATION_STATE).apply {
                putExtra(EXTRA_IS_RUNNING, isDetectionRunning)
                putExtra(EXTRA_DETECTION_COUNT, detectionCount)
                setPackage(packageName) // Ensure it stays within our app
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Broadcasted detection state: running=$isDetectionRunning, count=$detectionCount")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting detection state", e)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            return if (rowPadding == 0) {
                bitmap
            } else {
                // Crop bitmap if there's padding
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                croppedBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap", e)
            return null
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Card Detection Running")
            .setContentText(if (isDetectionRunning) "Detecting card positions" else "Ready to detect")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Card Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of card position detection"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy()")
        stopDetection()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        isProjectionRunning = false
        cardRecognizer?.cleanup()
        captureHandler.removeCallbacksAndMessages(null)

        try {
            unregisterReceiver(detectionControlReceiver)
            Log.d(TAG, "Detection control receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }

        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}