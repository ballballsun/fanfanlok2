package com.example.fanfanlok

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_START_AUTOMATION = "com.example.fanfanlok.START_AUTOMATION"
        const val ACTION_STOP_AUTOMATION = "com.example.fanfanlok.STOP_AUTOMATION"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isAutomationRunning = false

    // UI Elements
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView

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

        // Set up toggle button action
        btnToggle.setOnClickListener {
            Log.d(TAG, "Toggle button clicked, current state: $isAutomationRunning")
            toggleAutomation()
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
        val filter = IntentFilter(ScreenCaptureService.ACTION_AUTOMATION_STATE)
        registerReceiver(automationStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Automation state receiver registered")
    }

    private fun toggleAutomation() {
        Log.d(TAG, "toggleAutomation called, current state: $isAutomationRunning")
        if (isAutomationRunning) {
            stopAutomation()
        } else {
            startAutomation()
        }
    }

    private fun startAutomation() {
        Log.d(TAG, "startAutomation called - sending broadcast")

        // Update local state immediately for UI responsiveness
        isAutomationRunning = true
        updateUI()

        // Send broadcast to start automation
        val intent = Intent(ACTION_START_AUTOMATION)
        sendBroadcast(intent)

        Log.d(TAG, "Sent START_AUTOMATION broadcast")
        Toast.makeText(this, "🚀 Starting Automation...", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutomation() {
        Log.d(TAG, "stopAutomation called - sending broadcast")

        // Update local state immediately for UI responsiveness
        isAutomationRunning = false
        updateUI()

        // Send broadcast to stop automation
        val intent = Intent(ACTION_STOP_AUTOMATION)
        sendBroadcast(intent)

        Log.d(TAG, "Sent STOP_AUTOMATION broadcast")
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
        Log.d(TAG, "updateUI called, automation running: $isAutomationRunning")

        // Update toggle button
        btnToggle.text = if (isAutomationRunning) "⏹️ STOP" else "▶️ START"
        btnToggle.setBackgroundColor(
            if (isAutomationRunning)
                android.graphics.Color.parseColor("#FF6B6B") // Red for stop
            else
                android.graphics.Color.parseColor("#4ECDC4") // Green for start
        )

        // Update status text
        tvStatus.text = if (isAutomationRunning) {
            "🤖 PLAYING..."
        } else {
            "⏸️ Ready"
        }

        // Set status text color
        tvStatus.setTextColor(
            if (isAutomationRunning)
                android.graphics.Color.parseColor("#4CAF50") // Green
            else
                android.graphics.Color.parseColor("#9E9E9E") // Gray
        )

        Log.d(TAG, "UI updated - button text: ${btnToggle.text}, status: ${tvStatus.text}")
    }

    override fun onDestroy() {
        Log.d(TAG, "OverlayService onDestroy")

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
}