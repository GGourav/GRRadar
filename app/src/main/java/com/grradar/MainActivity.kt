package com.grradar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grradar.logger.DiscoveryLogger
import com.grradar.overlay.RadarOverlayService
import com.grradar.vpn.AlbionVpnService

/**
 * Main Activity
 * 
 * Handles:
 * - Permission flow (overlay + VPN)
 * - VPN service start/stop
 * - Overlay service start/stop
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_VPN_PERMISSION = 1002
    }

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var vpnPermissionButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // State
    private var hasOverlayPermission = false
    private var hasVpnPermission = false
    private var isRunning = false

    // Broadcast receiver for VPN status
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlbionVpnService.BROADCAST_VPN_STATUS) {
                val running = intent.getBooleanExtra("running", false)
                isRunning = running
                updateUI()
                
                if (running) {
                    DiscoveryLogger.i("VPN started successfully")
                    startOverlayService()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        overlayPermissionButton = findViewById(R.id.overlayPermissionButton)
        vpnPermissionButton = findViewById(R.id.vpnPermissionButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Set button listeners
        overlayPermissionButton.setOnClickListener {
            requestOverlayPermission()
        }
        
        vpnPermissionButton.setOnClickListener {
            requestVpnPermission()
        }
        
        startButton.setOnClickListener {
            startRadar()
        }
        
        stopButton.setOnClickListener {
            stopRadar()
        }

        // Register VPN status receiver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(vpnStatusReceiver, IntentFilter(AlbionVpnService.BROADCAST_VPN_STATUS), flags)

        DiscoveryLogger.i("MainActivity created")
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUI()
        updateStats()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(vpnStatusReceiver) }
        super.onDestroy()
    }

    /**
     * Check required permissions
     */
    private fun checkPermissions() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        val vpnIntent = VpnService.prepare(this)
        hasVpnPermission = vpnIntent == null
        
        DiscoveryLogger.d("Permissions: overlay=$hasOverlayPermission, vpn=$hasVpnPermission")
    }

    /**
     * Request overlay permission
     */
    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            return
        }
        
        DiscoveryLogger.i("Requesting overlay permission")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    /**
     * Request VPN permission
     */
    private fun requestVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent == null) {
            Toast.makeText(this, "VPN permission already granted", Toast.LENGTH_SHORT).show()
            hasVpnPermission = true
            updateUI()
            return
        }
        
        DiscoveryLogger.i("Requesting VPN permission")
        startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                hasOverlayPermission = Settings.canDrawOverlays(this)
                if (hasOverlayPermission) {
                    DiscoveryLogger.i("Overlay permission granted")
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    DiscoveryLogger.w("Overlay permission denied")
                    Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_LONG).show()
                }
                updateUI()
            }
            REQUEST_VPN_PERMISSION -> {
                hasVpnPermission = resultCode == Activity.RESULT_OK
                if (hasVpnPermission) {
                    DiscoveryLogger.i("VPN permission granted")
                    Toast.makeText(this, "VPN permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    DiscoveryLogger.w("VPN permission denied")
                    Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show()
                }
                updateUI()
            }
        }
    }

    /**
     * Start the radar
     */
    private fun startRadar() {
        if (!hasOverlayPermission) {
            Toast.makeText(this, "Please grant Overlay permission first", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!hasVpnPermission) {
            Toast.makeText(this, "Please grant VPN permission first", Toast.LENGTH_SHORT).show()
            return
        }
        
        DiscoveryLogger.i("Starting radar...")
        
        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_START
        }
        startService(vpnIntent)
        
        isRunning = true
        updateUI()
        
        Toast.makeText(this, "Radar starting...", Toast.LENGTH_SHORT).show()
    }

    /**
     * Start overlay service
     */
    private fun startOverlayService() {
        DiscoveryLogger.i("Starting overlay service")
        
        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_START
        }
        startService(overlayIntent)
    }

    /**
     * Stop the radar
     */
    private fun stopRadar() {
        DiscoveryLogger.i("Stopping radar...")
        
        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_STOP
        }
        startService(vpnIntent)
        
        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_STOP
        }
        startService(overlayIntent)
        
        isRunning = false
        updateUI()
        
        Toast.makeText(this, "Radar stopped", Toast.LENGTH_SHORT).show()
    }

    /**
     * Update UI based on current state
     */
    private fun updateUI() {
        // Update status text
        val status = when {
            !hasOverlayPermission -> "⚠️ Overlay permission required"
            !hasVpnPermission -> "⚠️ VPN permission required"
            isRunning -> "✅ Radar Active"
            else -> "✅ Ready to start"
        }
        statusText.text = status
        
        // Update permission buttons
        if (hasOverlayPermission) {
            overlayPermissionButton.text = "✓ Overlay Granted"
            overlayPermissionButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            overlayPermissionButton.isEnabled = false
        } else {
            overlayPermissionButton.text = "Grant Overlay Permission"
            overlayPermissionButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
            overlayPermissionButton.isEnabled = true
        }
        
        if (hasVpnPermission) {
            vpnPermissionButton.text = "✓ VPN Granted"
            vpnPermissionButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            vpnPermissionButton.isEnabled = false
        } else {
            vpnPermissionButton.text = "Grant VPN Permission"
            vpnPermissionButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
            vpnPermissionButton.isEnabled = true
        }
        
        // Update radar buttons
        startButton.isEnabled = !isRunning && hasOverlayPermission && hasVpnPermission
        stopButton.isEnabled = isRunning
        
        startButton.alpha = if (startButton.isEnabled) 1.0f else 0.5f
        stopButton.alpha = if (stopButton.isEnabled) 1.0f else 0.5f
    }

    /**
     * Update stats display
     */
    private fun updateStats() {
        if (!isRunning) {
            statsText.text = ""
            return
        }
        
        val entityCount = AlbionVpnService.entityCount
        val packetCount = AlbionVpnService.packetCount.get()
        val albionCount = AlbionVpnService.albionCount.get()
        
        statsText.text = "Packets: $packetCount | Albion: $albionCount | Entities: $entityCount"
        
        android.os.Handler(mainLooper).postDelayed({ updateStats() }, 1000)
    }
}
