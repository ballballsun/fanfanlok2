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
        const val CAPTURE_INTERVAL_MS = 500L // Capture every 500ms for performance

        // Service state broadcast actions
        const val ACTION_AUTOMATION_STATE = "com.example.fanfanlok.AUTOMATION_STATE"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_GAME_STATS = "game_stats"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Recognition components
    private var cardRecognizer: CardRecognizer? = null
    private var boardLayoutManager: BoardLayoutManager? = null
    private var matchLogic: MatchLogic? = null

    // Automation state
    private var isAutomationRunning = false
    private var captureHandler = Handler(Looper.getMainLooper())
    private var lastCaptureTime = 0L

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // OpenCV initialization state
    private var isOpenCVInitialized = false

    // Broadcast receiver for automation control
    private val automationControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_START_AUTOMATION -> {
                    startAutomation()
                }
                OverlayService.ACTION_STOP_AUTOMATION -> {
                    stopAutomation()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize OpenCV synchronously
        initializeOpenCV()

        // Register automation control receiver
        val filter = IntentFilter().apply {
            addAction("com.example.fanfanlok.START_AUTOMATION")
            addAction("com.example.fanfanlok.STOP_AUTOMATION")
        }

        registerReceiver(automationControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)


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

    private var isProjectionRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If projection is already active, ignore duplicate start
        if (isProjectionRunning) {
            Log.w(TAG, "MediaProjection already running; ignoring duplicate start request")
            return START_NOT_STICKY
        }

        // Read the fresh permission data from Activity
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_INTENT)

        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "Invalid result code or missing resultData — must request fresh projection permission")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start the foreground service before using MediaProjection
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

        // Get the MediaProjection instance from fresh data
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

        Log.d(TAG, "MediaProjection started successfully")
        return START_NOT_STICKY // Don't restart automatically with stale Intent
    }



    private fun initializeRecognition() {
        if (!isOpenCVInitialized) {
            Log.w(TAG, "Cannot initialize recognition - OpenCV not ready")
            return
        }

        try {
            cardRecognizer = CardRecognizer(this)
            boardLayoutManager = BoardLayoutManager(this)
            matchLogic = MatchLogic()

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

        // ✅ Register MediaProjection.Callback (required on Android 14+)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection stopped")

                // Release virtual display and image reader
                virtualDisplay?.release()
                imageReader?.close()

                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // Prepare ImageReader
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        // ✅ Only now it's safe to create the virtual display
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
            if (image != null && isAutomationRunning && isOpenCVInitialized) {
                processScreenCapture(image)
                image.close()
            } else {
                image?.close()
            }
        }, Handler(Looper.getMainLooper()))

        Log.d(TAG, "Virtual display setup complete: ${screenWidth}x${screenHeight}")
    }

    private fun processScreenCapture(image: Image) {
        try {
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                performCardRecognition(bitmap)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen capture", e)
        }
    }

    private fun performCardRecognition(screenshot: Bitmap) {
        val cardRecognizer = this.cardRecognizer ?: return
        val boardLayoutManager = this.boardLayoutManager ?: return
        val matchLogic = this.matchLogic ?: return

        try {
            // Use cached positions if available
            val cachedPositions = boardLayoutManager.getCachedPositions()

            // Detect cards
            val detectionResult = cardRecognizer.detectAllCards(screenshot, cachedPositions)

            // Validate and update cached positions if needed
            if (cachedPositions != null) {
                val validation = boardLayoutManager.validatePositions(detectionResult.boardPositions)
                when (validation) {
                    BoardLayoutManager.ValidationResult.MISMATCH -> {
                        Log.d(TAG, "Layout mismatch, updating cached positions")
                        boardLayoutManager.updateCachedPositions(
                            detectionResult.boardPositions,
                            screenWidth,
                            screenHeight
                        )
                    }
                    BoardLayoutManager.ValidationResult.PARTIAL_MATCH -> {
                        Log.d(TAG, "Partial layout match, minor adjustment")
                        // Could implement minor position adjustments here
                    }
                    else -> {
                        // MATCH or NO_CACHE - no action needed
                    }
                }
            } else if (detectionResult.boardPositions.isNotEmpty()) {
                // Save newly detected positions
                boardLayoutManager.saveBoardLayout(
                    detectionResult.boardPositions,
                    screenWidth,
                    screenHeight
                )
                Log.d(TAG, "Saved new board layout with ${detectionResult.boardPositions.size} positions")
            }

            // Update game logic
            val gameUpdate = matchLogic.updateGameState(detectionResult)

            // Calculate next move
            val nextMove = matchLogic.calculateNextMove()

            // Execute move if available
            nextMove?.let { move ->
                executeMove(move)
            }

            // Broadcast game state
            broadcastGameState(matchLogic.getGameStats())

            // Check if game is complete
            if (matchLogic.isGameComplete()) {
                Log.d(TAG, "Game completed!")
                stopAutomation()
            }

            Log.d(TAG, "Recognition cycle completed: ${detectionResult.cards.size} cards detected")

        } catch (e: Exception) {
            Log.e(TAG, "Error in card recognition", e)
        }
    }

    private fun executeMove(move: MatchLogic.NextMove) {
        when (move) {
            is MatchLogic.NextMove.FLIP_CARD -> {
                simulateTap(move.position.x + move.position.width / 2,
                    move.position.y + move.position.height / 2)
                Log.d(TAG, "Executing flip card: ${move.reason}")
            }
            is MatchLogic.NextMove.MAKE_MATCH -> {
                // Tap first card
                simulateTap(move.card1.x + move.card1.width / 2,
                    move.card1.y + move.card1.height / 2)

                // Wait briefly and tap second card
                captureHandler.postDelayed({
                    simulateTap(move.card2.x + move.card2.width / 2,
                        move.card2.y + move.card2.height / 2)
                    matchLogic?.recordMatch(move.card1, move.card2)
                }, 300)

                Log.d(TAG, "Executing match for template ${move.templateIndex}")
            }
        }

        matchLogic?.recordMove()
    }

    private fun simulateTap(x: Int, y: Int) {
        // Send broadcast to accessibility service to perform tap
        val tapIntent = Intent("com.example.fanfanlok.PERFORM_TAP").apply {
            putExtra("x", x)
            putExtra("y", y)
        }
        sendBroadcast(tapIntent)
    }

    fun startAutomation() {
        if (!isOpenCVInitialized) {
            Log.e(TAG, "Cannot start automation - OpenCV not initialized")
            return
        }

        isAutomationRunning = true
        matchLogic?.reset()
        broadcastAutomationState(true)
        Log.d(TAG, "Automation started")
    }

    fun stopAutomation() {
        isAutomationRunning = false
        broadcastAutomationState(false)
        Log.d(TAG, "Automation stopped")
    }

    private fun broadcastAutomationState(isRunning: Boolean) {
        val intent = Intent(ACTION_AUTOMATION_STATE).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
        }
        sendBroadcast(intent)
    }

    private fun broadcastGameState(stats: MatchLogic.GameStats) {
        val intent = Intent(ACTION_AUTOMATION_STATE).apply {
            putExtra(EXTRA_IS_RUNNING, isAutomationRunning)
            putExtra(EXTRA_GAME_STATS, stats)
        }
        sendBroadcast(intent)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.RGB_565
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
            .setContentTitle("Card Game Automation Running")
            .setContentText(if (isAutomationRunning) "Actively playing game" else "Ready to play")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Card Game Automation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of card game automation"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAutomation()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        isProjectionRunning = false
        cardRecognizer?.cleanup()
        captureHandler.removeCallbacksAndMessages(null)

        try {
            unregisterReceiver(automationControlReceiver)
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