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
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grradar.data.EntityStore
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
 * - EntityStore connection between VPN and Overlay
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
                    // Start overlay after VPN is running
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
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Set button listeners
        startButton.setOnClickListener {
            checkAndStartRadar()
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
        
        // Check permissions on resume
        checkPermissions()
        updateUI()
        
        // Update stats periodically
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
        // Check overlay permission
        hasOverlayPermission = Settings.canDrawOverlays(this)
        
        // VPN permission is checked via VpnService.prepare()
        // If it returns null, we have permission
        val vpnIntent = VpnService.prepare(this)
        hasVpnPermission = vpnIntent == null
        
        DiscoveryLogger.d("Permissions: overlay=$hasOverlayPermission, vpn=$hasVpnPermission")
    }

    /**
     * Check permissions and start radar if all granted
     */
    private fun checkAndStartRadar() {
        // Step 1: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            DiscoveryLogger.i("Requesting overlay permission")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }
        
        // Step 2: Check VPN permission
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            DiscoveryLogger.i("Requesting VPN permission")
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
            return
        }
        
        // All permissions granted, start radar
        startRadar()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                hasOverlayPermission = Settings.canDrawOverlays(this)
                if (hasOverlayPermission) {
                    DiscoveryLogger.i("Overlay permission granted")
                    // Continue to check VPN permission
                    checkAndStartRadar()
                } else {
                    DiscoveryLogger.w("Overlay permission denied")
                    Toast.makeText(this, "Overlay permission required for radar display", Toast.LENGTH_LONG).show()
                }
                updateUI()
            }
            REQUEST_VPN_PERMISSION -> {
                hasVpnPermission = resultCode == Activity.RESULT_OK
                if (hasVpnPermission) {
                    DiscoveryLogger.i("VPN permission granted")
                    // Start radar
                    startRadar()
                } else {
                    DiscoveryLogger.w("VPN permission denied")
                    Toast.makeText(this, "VPN permission required for packet capture", Toast.LENGTH_LONG).show()
                }
                updateUI()
            }
        }
    }

    /**
     * Start the radar (VPN + Overlay)
     */
    private fun startRadar() {
        if (!hasOverlayPermission || !hasVpnPermission) {
            Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        
        DiscoveryLogger.i("Starting radar...")
        
        // Start VPN service
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
        
        // Connect EntityStore to overlay after a short delay
        // (VPN service needs time to create the store)
        android.os.Handler(mainLooper).postDelayed({
            connectEntityStore()
        }, 500)
    }

    /**
     * Connect VPN's EntityStore to Overlay
     */
    private fun connectEntityStore() {
        val entityStore = AlbionVpnService.getSharedEntityStore()
        if (entityStore != null) {
            DiscoveryLogger.i("EntityStore connected to overlay")
            // The overlay service will get the store from the VPN service
        } else {
            DiscoveryLogger.w("EntityStore not available yet")
        }
    }

    /**
     * Stop the radar
     */
    private fun stopRadar() {
        DiscoveryLogger.i("Stopping radar...")
        
        // Stop VPN service
        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_STOP
        }
        startService(vpnIntent)
        
        // Stop overlay service
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
        val status = when {
            !hasOverlayPermission -> "Overlay permission required"
            !hasVpnPermission -> "VPN permission required"
            isRunning -> "Radar Active"
            else -> "Ready to start"
        }
        
        statusText.text = status
        
        startButton.isEnabled = !isRunning && hasOverlayPermission && hasVpnPermission
        stopButton.isEnabled = isRunning
        
        // Update button appearance
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
        
        // Schedule next update
        android.os.Handler(mainLooper).postDelayed({ updateStats() }, 1000)
    }
}
