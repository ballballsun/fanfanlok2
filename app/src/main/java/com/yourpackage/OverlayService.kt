package com.example.fanfanlok

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
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
            when (intent?.action) {
                ScreenCaptureService.ACTION_AUTOMATION_STATE -> {
                    val isRunning = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_RUNNING, false)
                    val stats = intent.getParcelableExtra<MatchLogic.GameStats>(ScreenCaptureService.EXTRA_GAME_STATS)
                    
                    updateAutomationState(isRunning)
                    stats?.let { updateGameStats(it) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

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
            toggleAutomation()
        }

        // Enable drag & move for the overlay
        setupDragAndDrop(params)

        // Initialize UI
        updateUI()
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
    }

    private fun toggleAutomation() {
        if (isAutomationRunning) {
            stopAutomation()
        } else {
            startAutomation()
        }
    }

    private fun startAutomation() {
        // Send broadcast to start automation
        val intent = Intent(ACTION_START_AUTOMATION)
        sendBroadcast(intent)
        
        Toast.makeText(this, "🚀 Automation Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutomation() {
        // Send broadcast to stop automation
        val intent = Intent(ACTION_STOP_AUTOMATION)
        sendBroadcast(intent)
        
        Toast.makeText(this, "⏹️ Automation Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateAutomationState(isRunning: Boolean) {
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
    }

    private fun updateUI() {
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
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(automationStateReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}