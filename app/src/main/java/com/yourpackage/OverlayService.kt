private fun initializeHelperComponents() {
    helperSettings = HelperSettings(this)
    manualCardHelper = ManualCardHelper(this)

    // Set up helper mode listener
    manualCardHelper.setOnHelperModeChangedListener { isActive ->
        isHelperModeActive = isActive
        updateUI()

        if (isActive) {
            showCardPositionOverlay()
        } else {
            hideCardPositionOverlay()
        }
    }

    // Set up screenshot request listener
    manualCardHelper.setOnScreenshotRequestListener {
        requestScreenshotForEdgeDetection()
    }

    Log.d(TAG, "Helper components initialized")
}

private fun requestScreenshotForEdgeDetection() {
    Log.d(TAG, "Requesting screenshot for edge detection")

    // Request screenshot from ScreenCaptureService
    val intent = Intent("com.example.fanfanlok.REQUEST_SCREENSHOT_FOR_HELPER")
    sendBroadcast(intent)

    // Show user feedback
    Toast.makeText(this, "📸 Capturing screen for card detection...", Toast.LENGTH_SHORT).show()
}package com.example.fanfanlok

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
import com.example.fanfanlok.helper.ManualCardHelper
import com.example.fanfanlok.helper.CardPositionOverlay
import com.example.fanfanlok.helper.HelperSettings

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_START_AUTOMATION = "com.example.fanfanlok.START_AUTOMATION"
        const val ACTION_STOP_AUTOMATION = "com.example.fanfanlok.STOP_AUTOMATION"
        const val ACTION_CARD_DETECTION_UPDATE = "com.example.fanfanlok.CARD_DETECTION_UPDATE"
        const val EXTRA_DETECTED_CARDS = "detected_cards"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isDetectionRunning = false
    private var isShowingDetection = false
    private var isHelperModeActive = false

    // UI Elements
    private lateinit var btnToggle: Button
    private lateinit var btnShow: Button
    private lateinit var btnHide: Button
    private lateinit var btnHelper: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView

    // Detection visualization
    private var detectionOverlay: DetectionOverlayView? = null
    private var currentDetectedCards = listOf<CardRecognizer.DetectedCard>()

    // Manual card helper components
    private lateinit var manualCardHelper: ManualCardHelper
    private lateinit var helperSettings: HelperSettings
    private var cardPositionOverlay: CardPositionOverlay? = null

    // Broadcast receiver for detection state updates
    private val detectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received detection state broadcast: ${intent?.action}")
            when (intent?.action) {
                ScreenCaptureService.ACTION_AUTOMATION_STATE -> {
                    val isRunning = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_RUNNING, false)
                    val detectionCount = intent.getIntExtra(ScreenCaptureService.EXTRA_DETECTION_COUNT, 0)

                    Log.d(TAG, "Detection state updated: running=$isRunning, count=$detectionCount")
                    updateDetectionState(isRunning)
                    updateDetectionStats(detectionCount)
                }
                ACTION_CARD_DETECTION_UPDATE -> {
                    @Suppress("DEPRECATION")
                    val detectedCards = intent.getParcelableArrayListExtra<DetectedCardParcelable>(EXTRA_DETECTED_CARDS)
                    Log.d(TAG, "Received detection update: ${detectedCards?.size ?: 0} cards")
                    detectedCards?.let { cards ->
                        currentDetectedCards = cards.map { it.toDetectedCard() }
                        Log.d(TAG, "Converted to ${currentDetectedCards.size} DetectedCard objects")
                        if (isShowingDetection) {
                            Log.d(TAG, "Updating detection overlay...")
                            updateDetectionOverlay()
                        }
                    } ?: run {
                        Log.w(TAG, "Received null detection cards")
                    }
                }
                "com.example.fanfanlok.SCREENSHOT_FOR_HELPER" -> {
                    // Handle screenshot response for helper
                    val screenshotData = intent.getByteArrayExtra("screenshot_data")
                    if (screenshotData != null) {
                        Log.d(TAG, "Received screenshot for helper edge detection")
                        handleScreenshotForHelper(screenshotData)
                    }
                }
            }
        }
    }

    private fun handleScreenshotForHelper(screenshotData: ByteArray) {
        try {
            // Convert byte array back to bitmap
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(screenshotData, 0, screenshotData.size)
            if (bitmap != null) {
                // Pass screenshot to helper for edge detection
                manualCardHelper.setCurrentScreenshot(bitmap)
                Log.d(TAG, "Screenshot provided to helper: ${bitmap.width}x${bitmap.height}")
                Toast.makeText(this, "✅ Ready for card detection - tap on cards", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Failed to decode screenshot bitmap")
                Toast.makeText(this, "❌ Screenshot capture failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling screenshot for helper", e)
            Toast.makeText(this, "❌ Screenshot processing failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Initialize helper components
        initializeHelperComponents()

        setupOverlayView()
        registerDetectionReceiver()
    }

    private fun initializeHelperComponents() {
        helperSettings = HelperSettings(this)
        manualCardHelper = ManualCardHelper(this)

        // Set up helper mode listener
        manualCardHelper.setOnHelperModeChangedListener { isActive ->
            isHelperModeActive = isActive
            updateUI()

            if (isActive) {
                showCardPositionOverlay()
            } else {
                hideCardPositionOverlay()
            }
        }

        Log.d(TAG, "Helper components initialized")
    }

    private fun setupOverlayView() {
        // Inflate the overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        // Initialize UI elements
        btnToggle = overlayView.findViewById(R.id.btn_toggle)
        tvStatus = overlayView.findViewById(R.id.tv_status)
        tvStats = overlayView.findViewById(R.id.tv_stats)

        // Create show/hide/helper buttons programmatically
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

        btnHelper = Button(this).apply {
            text = "🎯 HELPER"
            setBackgroundColor(Color.parseColor("#9C27B0")) // Purple
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(4, 4, 4, 4)
            }
        }

        buttonContainer.addView(btnShow)
        buttonContainer.addView(btnHide)
        buttonContainer.addView(btnHelper)

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
            Log.d(TAG, "Toggle button clicked, current state: $isDetectionRunning")
            toggleDetection()
        }

        btnShow.setOnClickListener {
            Log.d(TAG, "Show button clicked")
            showDetection()
        }

        btnHide.setOnClickListener {
            Log.d(TAG, "Hide button clicked")
            hideDetection()
        }

        btnHelper.setOnClickListener {
            Log.d(TAG, "Helper button clicked")
            toggleHelperMode()
        }

        // Enable drag & move for the overlay
        setupDragAndDrop(params)

        // Initialize UI
        updateUI()
        Log.d(TAG, "Overlay view setup complete")
    }

    private fun toggleHelperMode() {
        Log.d(TAG, "Toggling helper mode, current state: $isHelperModeActive")

        if (isHelperModeActive) {
            stopHelperMode()
        } else {
            startHelperMode()
        }
    }

    private fun startHelperMode() {
        Log.d(TAG, "Starting helper mode")

        // Stop detection and hide detection overlay if active
        if (isDetectionRunning) {
            stopDetection()
        }
        if (isShowingDetection) {
            hideDetection()
        }

        // Start helper mode
        manualCardHelper.startHelperMode()

        Toast.makeText(this, "🎯 Helper Mode Active - Tap to add cards", Toast.LENGTH_LONG).show()
        Log.d(TAG, "Helper mode started")
    }

    private fun stopHelperMode() {
        Log.d(TAG, "Stopping helper mode")

        // Show save dialog or auto-save
        val cardCount = manualCardHelper.getCardCount()
        if (cardCount > 0) {
            manualCardHelper.stopHelperMode(savePositions = true)
            Toast.makeText(this, "✅ Helper Mode Stopped - $cardCount cards saved", Toast.LENGTH_LONG).show()
        } else {
            manualCardHelper.stopHelperMode(savePositions = false)
            Toast.makeText(this, "🎯 Helper Mode Stopped", Toast.LENGTH_SHORT).show()
        }

        Log.d(TAG, "Helper mode stopped with $cardCount cards")
    }

    private fun showCardPositionOverlay() {
        if (cardPositionOverlay != null) {
            Log.d(TAG, "Card position overlay already showing")
            return
        }

        Log.d(TAG, "Showing card position overlay")

        try {
            cardPositionOverlay = CardPositionOverlay(this, manualCardHelper, helperSettings)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            // Position to cover entire screen
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0

            windowManager.addView(cardPositionOverlay, params)
            Log.d(TAG, "Card position overlay added successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add card position overlay", e)
            Toast.makeText(this, "❌ Failed to show helper overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideCardPositionOverlay() {
        cardPositionOverlay?.let { overlay ->
            try {
                windowManager.removeView(overlay)
                Log.d(TAG, "Card position overlay removed")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing card position overlay", e)
            }
            cardPositionOverlay = null
        }
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

    private fun registerDetectionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_AUTOMATION_STATE)
            addAction(ACTION_CARD_DETECTION_UPDATE)
            addAction("com.example.fanfanlok.SCREENSHOT_FOR_HELPER")
        }
        registerReceiver(detectionStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Detection state receiver registered")
    }

    private fun showDetection() {
        if (isShowingDetection) {
            Log.d(TAG, "Detection already showing")
            return
        }

        // Don't allow detection show during helper mode
        if (isHelperModeActive) {
            Toast.makeText(this, "❌ Exit helper mode first", Toast.LENGTH_SHORT).show()
            return
        }

        isShowingDetection = true
        Log.d(TAG, "showDetection() called - current cards: ${currentDetectedCards.size}")

        // If we already have detected cards, show them immediately
        if (currentDetectedCards.isNotEmpty()) {
            Log.d(TAG, "Using existing detection data: ${currentDetectedCards.size} cards")
            updateDetectionOverlay()
            Toast.makeText(this, "✅ Showing ${currentDetectedCards.size} detected cards", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "No existing data, requesting current detection")
            // Request current detection from ScreenCaptureService
            val intent = Intent("com.example.fanfanlok.REQUEST_DETECTION")
            sendBroadcast(intent)
            Log.d(TAG, "Sent REQUEST_DETECTION broadcast")
            Toast.makeText(this, "🔍 Requesting current card detection...", Toast.LENGTH_SHORT).show()

            // Set a timeout to check status after 2 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (currentDetectedCards.isEmpty()) {
                    Log.w(TAG, "No detection results received after 2 seconds")
                    Toast.makeText(this, "⚠️ No detection results received - try starting detection first", Toast.LENGTH_LONG).show()
                    isShowingDetection = false // Reset state if no results
                } else {
                    Log.d(TAG, "Detection results received: ${currentDetectedCards.size} cards")
                    updateDetectionOverlay()
                    Toast.makeText(this, "✅ Showing ${currentDetectedCards.size} detected cards", Toast.LENGTH_SHORT).show()
                }
                updateUI()
            }, 2000)
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

        // Debug: Log each card position
        currentDetectedCards.forEachIndexed { index, card ->
            Log.d(TAG, "Card $index: (${card.position.x}, ${card.position.y}) ${card.position.width}x${card.position.height}")
        }

        // Remove existing overlay first
        removeDetectionOverlay()

        if (currentDetectedCards.isNotEmpty()) {
            Log.d(TAG, "Creating detection overlay view...")

            try {
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

                // Set position to cover entire screen
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0
                params.y = 0

                windowManager.addView(detectionOverlay, params)
                Log.d(TAG, "Detection overlay added successfully with ${currentDetectedCards.size} cards")

                // Force a redraw
                detectionOverlay?.invalidate()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to add detection overlay", e)
                Toast.makeText(this, "❌ Failed to show overlay: ${e.message}", Toast.LENGTH_LONG).show()
                isShowingDetection = false
                updateUI()
            }
        } else {
            Log.w(TAG, "No cards to display in overlay")
            Toast.makeText(this, "⚠️ No cards detected to display", Toast.LENGTH_SHORT).show()
            isShowingDetection = false
            updateUI()
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

    private fun toggleDetection() {
        // Don't allow detection during helper mode
        if (isHelperModeActive) {
            Toast.makeText(this, "❌ Exit helper mode first", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "toggleDetection called, current state: $isDetectionRunning")
        if (!isDetectionRunning) {
            startDetection()
        } else {
            stopDetection()
        }
    }

    private fun startDetection() {
        Log.d(TAG, "startDetection called - sending broadcast")

        // Update local state immediately for UI responsiveness
        isDetectionRunning = true
        updateUI()

        // Send broadcast with explicit targeting to ensure delivery
        val intent = Intent(ACTION_START_AUTOMATION).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Sent START_AUTOMATION broadcast")

        Toast.makeText(this, "🚀 Starting Card Detection...", Toast.LENGTH_SHORT).show()
    }

    private fun stopDetection() {
        Log.d(TAG, "stopDetection called - sending broadcast")

        // Update local state immediately for UI responsiveness
        isDetectionRunning = false
        updateUI()

        // Send broadcast with explicit targeting to ensure delivery
        val intent = Intent(ACTION_STOP_AUTOMATION).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Sent STOP_AUTOMATION broadcast")

        Toast.makeText(this, "⏹️ Stopping Detection...", Toast.LENGTH_SHORT).show()
    }

    private fun updateDetectionState(isRunning: Boolean) {
        Log.d(TAG, "updateDetectionState: $isRunning")
        isDetectionRunning = isRunning
        updateUI()
    }

    private fun updateDetectionStats(detectionCount: Int) {
        val helperStats = manualCardHelper.getHelperStats()

        val statsText = """
            Detections: $detectionCount
            Cards Found: ${currentDetectedCards.size}
            Showing: ${if (isShowingDetection) "Yes" else "No"}
            Helper Cards: ${helperStats.totalCards}
        """.trimIndent()

        tvStats.text = statsText
        Log.d(TAG, "Updated detection stats: count=$detectionCount, cards=${currentDetectedCards.size}, helper=${helperStats.totalCards}")
    }

    private fun updateUI() {
        Log.d(TAG, "updateUI called, detection: $isDetectionRunning, showing: $isShowingDetection, helper: $isHelperModeActive")

        // Update toggle button
        btnToggle.text = if (isDetectionRunning) "⏹️ STOP" else "▶️ START"
        btnToggle.setBackgroundColor(
            if (isDetectionRunning)
                android.graphics.Color.parseColor("#FF6B6B") // Red for stop
            else
                android.graphics.Color.parseColor("#4ECDC4") // Green for start
        )
        btnToggle.isEnabled = !isHelperModeActive // Disable during helper mode

        // Update show/hide buttons
        btnShow.visibility = if (isShowingDetection || isHelperModeActive) View.GONE else View.VISIBLE
        btnHide.visibility = if (isShowingDetection && !isHelperModeActive) View.VISIBLE else View.GONE
        btnShow.isEnabled = !isHelperModeActive
        btnHide.isEnabled = !isHelperModeActive

        // Update helper button
        btnHelper.text = if (isHelperModeActive) "✅ SAVE" else "🎯 HELPER"
        btnHelper.setBackgroundColor(
            if (isHelperModeActive)
                android.graphics.Color.parseColor("#4CAF50") // Green for save
            else
                android.graphics.Color.parseColor("#9C27B0") // Purple for helper
        )

        // Update status text
        tvStatus.text = when {
            isHelperModeActive -> "🎯 HELPER MODE - ${manualCardHelper.getCardCount()} cards"
            isDetectionRunning && isShowingDetection -> "🔍👁️ DETECTING + SHOWING"
            isDetectionRunning -> "🔍 DETECTING..."
            isShowingDetection -> "👁️ SHOWING OVERLAY"
            else -> "⏸️ Ready"
        }

        // Set status text color
        tvStatus.setTextColor(
            when {
                isHelperModeActive -> android.graphics.Color.parseColor("#9C27B0") // Purple
                isDetectionRunning -> android.graphics.Color.parseColor("#4CAF50") // Green
                isShowingDetection -> android.graphics.Color.parseColor("#2196F3") // Blue
                else -> android.graphics.Color.parseColor("#9E9E9E") // Gray
            }
        )

        Log.d(TAG, "UI updated - toggle: ${btnToggle.text}, helper: ${btnHelper.text}, status: ${tvStatus.text}")
    }

    override fun onDestroy() {
        Log.d(TAG, "OverlayService onDestroy")

        // Stop helper mode if active
        if (isHelperModeActive) {
            manualCardHelper.stopHelperMode(savePositions = true)
        }

        hideDetection() // Clean up detection overlay
        hideCardPositionOverlay() // Clean up helper overlay

        try {
            unregisterReceiver(detectionStateReceiver)
            Log.d(TAG, "Detection receiver unregistered")
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
            strokeWidth = 8f
            isAntiAlias = true
        }

        private val fillPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            alpha = 60 // More visible fill
        }

        init {
            // Make sure the view is visible and will draw
            setWillNotDraw(false)
            Log.d(TAG, "DetectionOverlayView created with ${detectedCards.size} cards")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            Log.d(TAG, "onDraw called - drawing ${detectedCards.size} rectangles")

            // Draw a test rectangle to verify drawing works
            paint.color = Color.GREEN
            canvas.drawRect(100f, 100f, 300f, 200f, paint)
            Log.d(TAG, "Drew test rectangle")

            // Reset paint color
            paint.color = Color.RED

            detectedCards.forEachIndexed { index, card ->
                val rect = card.position
                val left = rect.x.toFloat()
                val top = rect.y.toFloat()
                val right = (rect.x + rect.width).toFloat()
                val bottom = (rect.y + rect.height).toFloat()

                Log.d(TAG, "Drawing card $index: rect($left, $top, $right, $bottom)")

                try {
                    // Draw semi-transparent fill
                    canvas.drawRect(left, top, right, bottom, fillPaint)

                    // Draw border
                    canvas.drawRect(left, top, right, bottom, paint)

                    Log.d(TAG, "Successfully drew card $index rectangle")
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing card $index rectangle", e)
                }
            }

            Log.d(TAG, "onDraw completed - drew ${detectedCards.size} card rectangles")
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            Log.d(TAG, "onLayout: changed=$changed, bounds=($left, $top, $right, $bottom)")
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            Log.d(TAG, "onMeasure: width=${MeasureSpec.getSize(widthMeasureSpec)}, height=${MeasureSpec.getSize(heightMeasureSpec)}")
        }
    }
}