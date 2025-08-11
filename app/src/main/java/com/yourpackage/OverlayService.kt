package com.example.fanfanlok

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.opencv.core.Rect

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_START_AUTOMATION = "com.example.fanfanlok.START_AUTOMATION"
        const val ACTION_STOP_AUTOMATION = "com.example.fanfanlok.STOP_AUTOMATION"
        const val ACTION_CARD_DETECTION_UPDATE = "com.example.fanfanlok.CARD_DETECTION_UPDATE"
        const val EXTRA_DETECTED_CARDS = "detected_cards"

        // Debug mode flag - set to true for real-time detection, false for cached positions
        private const val DEBUG_USE_REALTIME_DETECTION = true
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isAutomationRunning = false
    private var isShowingDetection = false

    // UI Elements
    private lateinit var btnToggle: Button
    private lateinit var btnShow: Button
    private lateinit var btnHide: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView

    // Detection visualization
    private var detectionOverlay: DetectionOverlayView? = null
    private var currentDetectedCards = listOf<CardRecognizer.DetectedCard>()

    // Broadcast receiver for automation state updates
    private val automationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received automation state broadcast: ${intent?.action}")
            when (intent?.action) {
                ScreenCaptureService.ACTION_AUTOMATION_STATE -> {
                    val isRunning = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_RUNNING, false)
                    val stats = intent.getParcelableExtra<MatchLogic.GameStats>(ScreenCaptureService.EXTRA_GAME_STATS)

                    Log.d(TAG, "Automation state updated: $isRunning")
                    updateAutomationState(isRunning)
                    stats?.let { updateGameStats(it) }
                }
                ACTION_CARD_DETECTION_UPDATE -> {
                    @Suppress("DEPRECATION")
                    val detectedCards = intent.getParcelableArrayListExtra<DetectedCardParcelable>(EXTRA_DETECTED_CARDS)
                    Log.d(TAG, "Received detection update: ${detectedCards?.size ?: 0} cards")
                    detectedCards?.let { cards ->
                        currentDetectedCards = cards.map { it.toDetectedCard() }
                        Log.d(TAG, "Converted to ${currentDetectedCards.size} DetectedCard objects")
                        currentDetectedCards.forEachIndexed { index, card ->
                            Log.d(TAG, "Card $index: ${card.templateName} at (${card.position.x}, ${card.position.y}) ${card.position.width}x${card.position.height}")
                        }
                        if (isShowingDetection) {
                            Log.d(TAG, "Updating detection overlay...")
                            updateDetectionOverlay()
                        }
                    } ?: run {
                        Log.w(TAG, "Received null detection cards")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayView()
        registerAutomationReceiver()
    }

    private fun setupOverlayView() {
        // Inflate the overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        // Initialize UI elements
        btnToggle = overlayView.findViewById(R.id.btn_toggle)
        tvStatus = overlayView.findViewById(R.id.tv_status)
        tvStats = overlayView.findViewById(R.id.tv_stats)

        // Create show/hide buttons programmatically
        val buttonContainer = overlayView.findViewById<LinearLayout>(R.id.button_container)

        btnShow = Button(this).apply {
            text = "👁️ SHOW"
            setBackgroundColor(Color.parseColor("#2196F3")) // Blue
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(4, 4, 4, 4)
            }
        }

        btnHide = Button(this).apply {
            text = "🙈 HIDE"
            setBackgroundColor(Color.parseColor("#FF9800")) // Orange
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            visibility = View.GONE // Initially hidden
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(4, 4, 4, 4)
            }
        }

        buttonContainer.addView(btnShow)
        buttonContainer.addView(btnHide)

        // Layout parameters for the floating window
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Use TYPE_APPLICATION_OVERLAY for Android O and above
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // Position the overlay initially at the top-right corner
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 100

        // Add the overlay view to the window
        windowManager.addView(overlayView, params)

        // Set up button actions
        btnToggle.setOnClickListener {
            Log.d(TAG, "Toggle button clicked, current state: $isAutomationRunning")
            toggleAutomation()
        }

        btnShow.setOnClickListener {
            Log.d(TAG, "Show button clicked")
            showDetection()
        }

        btnHide.setOnClickListener {
            Log.d(TAG, "Hide button clicked")
            hideDetection()
        }

        // Enable drag & move for the overlay
        setupDragAndDrop(params)

        // Initialize UI
        updateUI()
        Log.d(TAG, "Overlay view setup complete")
    }

    private fun setupDragAndDrop(params: WindowManager.LayoutParams) {
        overlayView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        // Only start dragging if moved significantly
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true
                            params.x = initialX - deltaX
                            params.y = initialY + deltaY
                            windowManager.updateViewLayout(overlayView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // If not dragging, let the click through to buttons
                        return isDragging
                    }
                }
                return false
            }
        })
    }

    private fun registerAutomationReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_AUTOMATION_STATE)
            addAction(ACTION_CARD_DETECTION_UPDATE)
        }
        registerReceiver(automationStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Automation state receiver registered")
    }

    private fun showDetection() {
        if (isShowingDetection) {
            Log.d(TAG, "Detection already showing")
            return
        }

        isShowingDetection = true
        Log.d(TAG, "showDetection() called - DEBUG_USE_REALTIME_DETECTION = $DEBUG_USE_REALTIME_DETECTION")

        if (DEBUG_USE_REALTIME_DETECTION) {
            // Request current detection from ScreenCaptureService
            val intent = Intent("com.example.fanfanlok.REQUEST_DETECTION")
            sendBroadcast(intent)
            Log.d(TAG, "Sent REQUEST_DETECTION broadcast")
            Toast.makeText(this, "🔍 Requesting current card detection...", Toast.LENGTH_SHORT).show()

            // Set a timeout to show status after 3 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (currentDetectedCards.isEmpty()) {
                    Log.w(TAG, "No detection results received after 3 seconds")
                    Toast.makeText(this, "⚠️ No detection results received", Toast.LENGTH_LONG).show()
                } else {
                    Log.d(TAG, "Detection results received: ${currentDetectedCards.size} cards")
                }
            }, 3000)
        } else {
            // Use cached positions (simpler for debug)
            val boardLayoutManager = BoardLayoutManager(this)
            val cachedPositions = boardLayoutManager.getCachedPositions()

            Log.d(TAG, "Cached positions: ${cachedPositions?.size ?: 0}")

            if (cachedPositions != null && cachedPositions.isNotEmpty()) {
                // Convert cached positions to DetectedCard format
                currentDetectedCards = cachedPositions.mapIndexed { index, rect ->
                    CardRecognizer.DetectedCard(
                        templateIndex = -1,
                        templateName = "cached_position_$index",
                        position = rect,
                        isFaceUp = false,
                        confidence = 1.0
                    )
                }
                Log.d(TAG, "Created ${currentDetectedCards.size} cached cards for display")
                updateDetectionOverlay()
                Toast.makeText(this, "📍 Showing ${cachedPositions.size} cached positions", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "No cached positions available")
                val cacheStats = boardLayoutManager.getCacheStats()
                Log.d(TAG, "Cache stats: enabled=${cacheStats.isEnabled}, hasLayout=${cacheStats.hasLayout}, count=${cacheStats.positionCount}")
                Toast.makeText(this, "❌ No cached positions available", Toast.LENGTH_SHORT).show()
                isShowingDetection = false
            }
        }

        updateUI()
    }

    private fun hideDetection() {
        Log.d(TAG, "Hiding detection overlay")
        isShowingDetection = false
        removeDetectionOverlay()
        updateUI()
        Toast.makeText(this, "🙈 Detection overlay hidden", Toast.LENGTH_SHORT).show()
    }

    private fun updateDetectionOverlay() {
        if (!isShowingDetection) {
            Log.d(TAG, "updateDetectionOverlay called but isShowingDetection = false")
            return
        }

        Log.d(TAG, "Updating detection overlay with ${currentDetectedCards.size} cards")

        // Remove existing overlay
        removeDetectionOverlay()

        if (currentDetectedCards.isNotEmpty()) {
            Log.d(TAG, "Creating detection overlay view...")
            // Create new detection overlay
            detectionOverlay = DetectionOverlayView(this, currentDetectedCards)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(detectionOverlay, params)
                Log.d(TAG, "Detection overlay added successfully with ${currentDetectedCards.size} cards")
                Toast.makeText(this, "✅ Showing ${currentDetectedCards.size} rectangles", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add detection overlay", e)
                Toast.makeText(this, "❌ Failed to show overlay: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "No cards to display in overlay")
            Toast.makeText(this, "⚠️ No cards detected to display", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeDetectionOverlay() {
        detectionOverlay?.let { overlay ->
            try {
                windowManager.removeView(overlay)
                Log.d(TAG, "Detection overlay removed")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing detection overlay", e)
            }
            detectionOverlay = null
        }
    }

    private fun toggleAutomation() {
        Log.d(TAG, "toggleAutomation called, current state: $isAutomationRunning")
        // FIXED: Corrected the logic - when NOT running, START it
        if (!isAutomationRunning) {
            startAutomation()
        } else {
            stopAutomation()
        }
    }

    private fun startAutomation() {
        Log.d(TAG, "startAutomation called - sending broadcast")

        // Update local state immediately for UI responsiveness
        isAutomationRunning = true
        updateUI()

        // Send broadcast with explicit targeting to ensure delivery
        val intent = Intent(ACTION_START_AUTOMATION).apply {
            // Add explicit package targeting to ensure broadcast delivery
            setPackage(packageName)
            // Add flags for better broadcast delivery
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Sent START_AUTOMATION broadcast with package: $packageName")

        Toast.makeText(this, "🚀 Starting Automation...", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutomation() {
        Log.d(TAG, "stopAutomation called - sending broadcast")

        // Update local state immediately for UI responsiveness
        isAutomationRunning = false
        updateUI()

        // Send broadcast with explicit targeting to ensure delivery
        val intent = Intent(ACTION_STOP_AUTOMATION).apply {
            // Add explicit package targeting to ensure broadcast delivery
            setPackage(packageName)
            // Add flags for better broadcast delivery
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Sent STOP_AUTOMATION broadcast with package: $packageName")

        Toast.makeText(this, "⏹️ Stopping Automation...", Toast.LENGTH_SHORT).show()
    }

    private fun updateAutomationState(isRunning: Boolean) {
        Log.d(TAG, "updateAutomationState: $isRunning")
        isAutomationRunning = isRunning
        updateUI()
    }

    private fun updateGameStats(stats: MatchLogic.GameStats) {
        val statsText = """
            Moves: ${stats.totalMoves}
            Matches: ${stats.matchesMade}
            Cards Left: ${stats.cardsRemaining}
            Revealed: ${stats.revealedCount}
        """.trimIndent()

        tvStats.text = statsText
        Log.d(TAG, "Updated game stats: moves=${stats.totalMoves}, matches=${stats.matchesMade}")
    }

    private fun updateUI() {
        Log.d(TAG, "updateUI called, automation running: $isAutomationRunning, showing detection: $isShowingDetection")

        // Update toggle button
        btnToggle.text = if (isAutomationRunning) "⏹️ STOP" else "▶️ START"
        btnToggle.setBackgroundColor(
            if (isAutomationRunning)
                android.graphics.Color.parseColor("#FF6B6B") // Red for stop
            else
                android.graphics.Color.parseColor("#4ECDC4") // Green for start
        )

        // Update show/hide buttons
        btnShow.visibility = if (isShowingDetection) View.GONE else View.VISIBLE
        btnHide.visibility = if (isShowingDetection) View.VISIBLE else View.GONE

        // Update status text
        tvStatus.text = when {
            isAutomationRunning && isShowingDetection -> "🤖🔍 PLAYING + SHOWING"
            isAutomationRunning -> "🤖 PLAYING..."
            isShowingDetection -> "🔍 SHOWING DETECTION"
            else -> "⏸️ Ready"
        }

        // Set status text color
        tvStatus.setTextColor(
            when {
                isAutomationRunning -> android.graphics.Color.parseColor("#4CAF50") // Green
                isShowingDetection -> android.graphics.Color.parseColor("#2196F3") // Blue
                else -> android.graphics.Color.parseColor("#9E9E9E") // Gray
            }
        )

        Log.d(TAG, "UI updated - button text: ${btnToggle.text}, status: ${tvStatus.text}")
    }

    override fun onDestroy() {
        Log.d(TAG, "OverlayService onDestroy")

        hideDetection() // Clean up detection overlay

        try {
            unregisterReceiver(automationStateReceiver)
            Log.d(TAG, "Automation receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }

        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
            Log.d(TAG, "Overlay view removed")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Custom view for drawing detection rectangles
    private class DetectionOverlayView(
        context: Context,
        private val detectedCards: List<CardRecognizer.DetectedCard>
    ) : View(context) {

        private val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            alpha = 200 // Semi-transparent
        }

        private val fillPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            alpha = 50 // Very transparent fill
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            Log.d(TAG, "Drawing ${detectedCards.size} detection rectangles")

            detectedCards.forEach { card ->
                val rect = card.position
                val left = rect.x.toFloat()
                val top = rect.y.toFloat()
                val right = (rect.x + rect.width).toFloat()
                val bottom = (rect.y + rect.height).toFloat()

                // Draw semi-transparent fill
                canvas.drawRect(left, top, right, bottom, fillPaint)

                // Draw border
                canvas.drawRect(left, top, right, bottom, paint)

                Log.v(TAG, "Drew rectangle for ${card.templateName} at (${rect.x}, ${rect.y}) ${rect.width}x${rect.height}")
            }
        }
    }
}