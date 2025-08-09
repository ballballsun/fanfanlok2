package com.example.fanfanlok

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fanfanlok.AccessibilityTapService
import com.example.fanfanlok.OverlayService
import com.example.fanfanlok.R
import com.example.fanfanlok.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_SCREEN_CAPTURE = 1000
        private const val REQUEST_CAPTURE_VIDEO_OUTPUT = 2001
    }

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnRequestAccessibility: Button
    private lateinit var btnRequestScreenCapture: Button
    private lateinit var btnStartAutomation: Button
    private lateinit var btnStopAutomation: Button
    private lateinit var btnSetting: Button

    // Services and managers
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var screenCaptureResultCode: Int = 0
    private var screenCaptureResultData: Intent? = null

    // Permission request launchers
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUI()
        if (Settings.canDrawOverlays(this)) {
            showToast("Overlay permission granted")
        } else {
            showToast("Overlay permission denied")
        }
    }

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUI()
        if (isAccessibilityServiceEnabled()) {
            showToast("Accessibility service enabled")
        } else {
            showToast("Please enable the accessibility service manually")
        }
    }

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            screenCaptureResultCode = result.resultCode
            screenCaptureResultData = result.data
            showToast("Screen capture permission granted")
            Log.d(TAG, "Screen capture permission granted - ResultCode: $screenCaptureResultCode")
        } else {
            screenCaptureResultCode = 0
            screenCaptureResultData = null
            showToast("Screen capture permission denied")
            Log.w(TAG, "Screen capture permission denied - ResultCode: ${result.resultCode}, Data: ${result.data}")
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeServices()
        setupClickListeners()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.tv_status)
        debugText = findViewById(R.id.tv_debug)
        btnRequestOverlay = findViewById(R.id.btn_request_overlay)
        btnRequestAccessibility = findViewById(R.id.btn_request_accessibility)
        btnRequestScreenCapture = findViewById(R.id.btn_request_screen_capture)
        btnStartAutomation = findViewById(R.id.btn_start_automation)
        btnStopAutomation = findViewById(R.id.btn_stop_automation)
        btnSetting = findViewById(R.id.btn_setting)
    }

    private fun initializeServices() {
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun setupClickListeners() {
        btnRequestOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        btnRequestAccessibility.setOnClickListener {
            requestAccessibilityPermission()
        }

        btnRequestScreenCapture.setOnClickListener {
            requestScreenCapturePermission()
        }

        btnStartAutomation.setOnClickListener {
            startAutomation()
        }

        btnStopAutomation.setOnClickListener {
            stopAutomation()
        }

        btnSetting.setOnClickListener {
            openSettings()
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            showToast("Overlay permission already granted")
        }
    }

    private fun requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDialog()
        } else {
            showToast("Accessibility service already enabled")
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service Required")
            .setMessage("This app needs accessibility service to simulate touch input. Please enable '${getString(R.string.app_name)}' in the accessibility settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                accessibilitySettingsLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestScreenCapturePermission() {
        try {
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            Log.d(TAG, "Requesting screen capture permission")
            screenCapturePermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting screen capture permission", e)
            showToast("Error requesting screen capture permission")
        }
    }

    private fun startAutomation() {
        Log.d(TAG, "startAutomation() called")

        // Validate all basic permissions
        if (!Settings.canDrawOverlays(this)) {
            showToast("Overlay permission required")
            Log.w(TAG, "Missing overlay permission")
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            showToast("Accessibility service required")
            Log.w(TAG, "Missing accessibility service")
            return
        }

        // Validate screen capture permission data
        if (screenCaptureResultCode != RESULT_OK) {
            showToast("Screen capture permission not granted - invalid result code")
            Log.w(TAG, "Invalid screen capture result code: $screenCaptureResultCode (expected: $RESULT_OK)")
            return
        }

        if (screenCaptureResultData == null) {
            showToast("Screen capture permission not granted - missing intent data")
            Log.w(TAG, "Screen capture result data is null")
            return
        }

        Log.d(TAG, "All permissions validated, starting services...")

        try {
            // Start ScreenCaptureService with fresh projection data
            val screenCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, screenCaptureResultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_INTENT, screenCaptureResultData)
            }

            Log.d(TAG, "Starting ScreenCaptureService with resultCode: $screenCaptureResultCode")
            ContextCompat.startForegroundService(this, screenCaptureIntent)

            // Start OverlayService
            val overlayIntent = Intent(this, OverlayService::class.java)
            startService(overlayIntent)
            Log.d(TAG, "Started OverlayService")

            showToast("Automation services started successfully")
            Log.d(TAG, "Automation services started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting automation services", e)
            showToast("Error starting automation: ${e.message}")
        }

        updateUI()
    }

    private fun stopAutomation() {
        // Stop services
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, OverlayService::class.java))

        showToast("Automation services stopped")
        Log.d(TAG, "Automation services stopped")
        updateUI()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun areAllPermissionsGranted(): Boolean {
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityGranted = isAccessibilityServiceEnabled()
        val screenCaptureGranted = screenCaptureResultCode == RESULT_OK && screenCaptureResultData != null

        Log.d(TAG, "Permission check - Overlay: $overlayGranted, Accessibility: $accessibilityGranted, ScreenCapture: $screenCaptureGranted")
        Log.d(TAG, "Permission details - ResultCode: $screenCaptureResultCode (RESULT_OK=$RESULT_OK), Data null: ${screenCaptureResultData == null}")

        return overlayGranted && accessibilityGranted && screenCaptureGranted
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        val expectedComponentName = ComponentName(this, AccessibilityTapService::class.java)
        return enabledServices.any { service ->
            service.resolveInfo.serviceInfo.let { serviceInfo ->
                serviceInfo.packageName == expectedComponentName.packageName &&
                        serviceInfo.name == expectedComponentName.className
            }
        }
    }

    private fun updateUI() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityGranted = isAccessibilityServiceEnabled()
        val screenCaptureGranted = screenCaptureResultCode == RESULT_OK && screenCaptureResultData != null
        val allPermissionsGranted = overlayGranted && accessibilityGranted && screenCaptureGranted

        // Update button states
        btnRequestOverlay.isEnabled = !overlayGranted
        btnRequestOverlay.text = if (overlayGranted) "✓ Overlay Permission" else "Request Overlay Permission"

        btnRequestAccessibility.isEnabled = !accessibilityGranted
        btnRequestAccessibility.text = if (accessibilityGranted) "✓ Accessibility Service" else "Enable Accessibility Service"

        btnRequestScreenCapture.isEnabled = !screenCaptureGranted
        btnRequestScreenCapture.text = if (screenCaptureGranted) "✓ Screen Capture" else "Grant Screen Capture"

        btnStartAutomation.isEnabled = allPermissionsGranted
        btnStopAutomation.isEnabled = true

        // Update status text
        val status = when {
            !overlayGranted -> "❌ Missing overlay permission"
            !accessibilityGranted -> "❌ Missing accessibility service"
            !screenCaptureGranted -> "❌ Missing screen capture permission"
            else -> "✅ All permissions granted - Ready to start"
        }
        statusText.text = status

        // Update debug information
        val debugInfo = buildString {
            appendLine("=== Permission Status ===")
            appendLine("Overlay: ${if (overlayGranted) "✓" else "✗"}")
            appendLine("Accessibility: ${if (accessibilityGranted) "✓" else "✗"}")
            appendLine("Screen Capture: ${if (screenCaptureGranted) "✓" else "✗"}")
            appendLine()
            appendLine("=== Service Status ===")
            appendLine("Screen Capture Code: $screenCaptureResultCode")
            appendLine("Screen Capture Data: ${screenCaptureResultData != null}")
            appendLine()
            appendLine("=== Debug Info ===")
            appendLine("Package: $packageName")
            appendLine("SDK Version: ${android.os.Build.VERSION.SDK_INT}")
        }
        debugText.text = debugInfo

        Log.d(TAG, "UI updated - All permissions: $allPermissionsGranted")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}