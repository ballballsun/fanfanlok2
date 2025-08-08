package com.example.fanfanlok

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var boardLayoutManager: BoardLayoutManager
    
    // UI Elements
    private lateinit var switchPositionCache: Switch
    private lateinit var tvCacheStatus: TextView
    private lateinit var tvCacheStats: TextView
    private lateinit var btnClearCache: Button
    private lateinit var btnTestDetection: Button
    
    // Recognition settings
    private lateinit var seekMatchThreshold: SeekBar
    private lateinit var tvMatchThreshold: TextView
    private lateinit var seekBackThreshold: SeekBar
    private lateinit var tvBackThreshold: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        boardLayoutManager = BoardLayoutManager(this)
        
        initializeViews()
        setupListeners()
        updateUI()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
    }
    
    private fun initializeViews() {
        // Position cache controls
        switchPositionCache = findViewById(R.id.switch_position_cache)
        tvCacheStatus = findViewById(R.id.tv_cache_status)
        tvCacheStats = findViewById(R.id.tv_cache_stats)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        btnTestDetection = findViewById(R.id.btn_test_detection)
        
        // Recognition threshold controls
        seekMatchThreshold = findViewById(R.id.seek_match_threshold)
        tvMatchThreshold = findViewById(R.id.tv_match_threshold)
        seekBackThreshold = findViewById(R.id.seek_back_threshold)
        tvBackThreshold = findViewById(R.id.tv_back_threshold)
        
        // Initialize seekbars
        seekMatchThreshold.max = 100
        seekMatchThreshold.progress = 75 // Default 0.75
        seekBackThreshold.max = 100
        seekBackThreshold.progress = 80 // Default 0.80
    }
    
    private fun setupListeners() {
        // Position cache toggle
        switchPositionCache.setOnCheckedChangeListener { _, isChecked ->
            boardLayoutManager.setPositionCacheEnabled(isChecked)
            updateUI()
            
            val message = if (isChecked) {
                "Position caching enabled. The app will remember card positions for faster detection."
            } else {
                "Position caching disabled. The app will detect card positions fresh each time."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        
        // Clear cache button
        btnClearCache.setOnClickListener {
            showClearCacheDialog()
        }
        
        // Test detection button
        btnTestDetection.setOnClickListener {
            showTestDetectionDialog()
        }
        
        // Threshold seekbars
        seekMatchThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100.0
                tvMatchThreshold.text = "Match Threshold: ${String.format("%.2f", threshold)}"
                // TODO: Save to SharedPreferences
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekBackThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100.0
                tvBackThreshold.text = "Card Back Threshold: ${String.format("%.2f", threshold)}"
                // TODO: Save to SharedPreferences
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun updateUI() {
        val stats = boardLayoutManager.getCacheStats()
        
        // Update position cache UI
        switchPositionCache.isChecked = stats.isEnabled
        
        val statusText = when {
            !stats.isEnabled -> "❌ Position caching is disabled"
            stats.hasLayout -> "✅ Board layout cached (${stats.positionCount} positions)"
            else -> "⚠️ No board layout cached yet"
        }
        tvCacheStatus.text = statusText
        
        // Update cache statistics
        val statsText = buildString {
            appendLine("=== Cache Statistics ===")
            appendLine("Cache Enabled: ${if (stats.isEnabled) "Yes" else "No"}")
            appendLine("Layout Cached: ${if (stats.hasLayout) "Yes" else "No"}")
            
            if (stats.hasLayout) {
                appendLine("Card Positions: ${stats.positionCount}")
                appendLine("Screen Resolution: ${stats.screenResolution ?: "Unknown"}")
                
                stats.lastDetectedAt?.let { timestamp ->
                    val date = Date(timestamp)
                    val formatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    appendLine("Last Detected: ${formatter.format(date)}")
                }
            }
            
            appendLine()
            appendLine("=== Performance Tips ===")
            
            when {
                !stats.isEnabled -> {
                    appendLine("• Enable caching for faster detection")
                    appendLine("• Cached positions improve speed by 60-80%")
                }
                stats.hasLayout -> {
                    appendLine("• ✅ Optimized for fast detection")
                    appendLine("• Cache will be used for matching layouts")
                }
                else -> {
                    appendLine("• Start automation once to cache positions")
                    appendLine("• First run may be slower for detection")
                }
            }
        }
        tvCacheStats.text = statsText
        
        // Update button states
        btnClearCache.isEnabled = stats.hasLayout
        
        // Update threshold displays
        tvMatchThreshold.text = "Match Threshold: ${String.format("%.2f", seekMatchThreshold.progress / 100.0)}"
        tvBackThreshold.text = "Card Back Threshold: ${String.format("%.2f", seekBackThreshold.progress / 100.0)}"
    }
    
    private fun showClearCacheDialog() {
        val stats = boardLayoutManager.getCacheStats()
        
        AlertDialog.Builder(this)
            .setTitle("Clear Position Cache")
            .setMessage("This will remove the cached board layout with ${stats.positionCount} positions.\n\nThe next automation run will need to detect positions from scratch, which may be slower.\n\nContinue?")
            .setPositiveButton("Clear Cache") { _, _ ->
                boardLayoutManager.clearCache()
                updateUI()
                Toast.makeText(this, "Position cache cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showTestDetectionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Test Card Detection")
            .setMessage("This feature would test card detection with current settings.\n\nTo implement:\n• Take a screenshot\n• Run card recognition\n• Show detection results\n• Display performance metrics\n\nThis is useful for tuning thresholds and verifying card template quality.")
            .setPositiveButton("OK", null)
            .show()
    }
}