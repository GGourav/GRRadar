package com.grradar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grradar.data.EntityStore
import com.grradar.logger.DiscoveryLogger
import com.grradar.overlay.RadarOverlayService
import com.grradar.vpn.AlbionVpnService

/**
 * MainActivity — Main entry point and permission flow
 *
 * PERMISSION SEQUENCE:
 *   1. Check SYSTEM_ALERT_WINDOW (overlay) permission
 *      - If not granted, launch Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 *   2. Check VPN permission via VpnService.prepare()
 *      - If returns non-null intent, launch VPN permission request
 *   3. Both granted → Enable START button
 *
 * FEATURES:
 *   - Start/Stop radar controls
 *   - Status display (VPN, overlay, entity counts)
 *   - Log file location display
 *   - Statistics updates
 *
 * BROADCAST RECEIVERS:
 *   - VPN_STATUS: Updates when VPN starts/stops
 *   - OVERLAY_STATUS: Updates when overlay starts/stops
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_VPN_PERMISSION = 1002
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VIEWS
    // ═══════════════════════════════════════════════════════════════════════════

    private lateinit var scrollRoot: ScrollView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var logPathText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearLogButton: Button

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private var vpnGranted = false
    private var overlayGranted = false
    private var isRadarRunning = false

    // ═══════════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVER
    // ═══════════════════════════════════════════════════════════════════════════

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlbionVpnService.BROADCAST_VPN_STATUS -> {
                    val running = intent.getBooleanExtra("running", false)
                    runOnUiThread {
                        updateStatus()
                    }
                }
                RadarOverlayService.BROADCAST_OVERLAY_STATUS -> {
                    val running = intent.getBooleanExtra("running", false)
                    runOnUiThread {
                        updateStatus()
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DiscoveryLogger.i("GRRadar Application started")
        Log.i(TAG, "MainActivity created")

        // Find views
        findViews()

        // Setup button listeners
        setupButtons()

        // Initial state
        stopButton.isEnabled = false
        updateStatus()

        // Check permissions
        checkPermissions()
    }

    private fun findViews() {
        scrollRoot = findViewById(R.id.scrollRoot)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        logPathText = findViewById(R.id.logPathText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        clearLogButton = findViewById(R.id.clearLogButton)

        // Show log file path
        logPathText.text = "Log: /sdcard/Android/data/com.grradar/files/discovery_log.txt"
    }

    private fun setupButtons() {
        startButton.setOnClickListener {
            startRadar()
        }

        stopButton.setOnClickListener {
            stopRadar()
        }

        clearLogButton.setOnClickListener {
            clearEntities()
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-check permissions when returning to activity
        checkPermissions()
        updateStatus()

        // Register broadcast receivers
        val filter = IntentFilter().apply {
            addAction(AlbionVpnService.BROADCAST_VPN_STATUS)
            addAction(RadarOverlayService.BROADCAST_OVERLAY_STATUS)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(statusReceiver, filter, flags)

        // Start periodic status updates
        startStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
        stopStatusUpdates()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun checkPermissions() {
        // Check overlay permission
        overlayGranted = Settings.canDrawOverlays(this)

        DiscoveryLogger.d("Permissions check: overlay=$overlayGranted, vpn=$vpnGranted")
        Log.d(TAG, "Permissions: overlay=$overlayGranted, vpn=$vpnGranted")

        if (!overlayGranted) {
            DiscoveryLogger.i("Requesting overlay permission")
            requestOverlayPermission()
            return
        }

        // Check VPN permission
        val vpnIntent = VpnService.prepare(this)
        vpnGranted = vpnIntent == null

        if (!vpnGranted && vpnIntent != null) {
            DiscoveryLogger.i("Requesting VPN permission")
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
            return
        }

        // All permissions granted
        updateButtonStates()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                overlayGranted = Settings.canDrawOverlays(this)
                if (overlayGranted) {
                    DiscoveryLogger.i("Overlay permission granted")
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                    checkPermissions() // Continue to VPN check
                } else {
                    DiscoveryLogger.w("Overlay permission denied")
                    Toast.makeText(this, "Overlay permission required for radar display", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_VPN_PERMISSION -> {
                vpnGranted = resultCode == Activity.RESULT_OK
                if (vpnGranted) {
                    DiscoveryLogger.i("VPN permission granted")
                    Toast.makeText(this, "VPN permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    DiscoveryLogger.w("VPN permission denied")
                    Toast.makeText(this, "VPN permission required for traffic capture", Toast.LENGTH_LONG).show()
                }
                updateButtonStates()
            }
        }
    }

    private fun updateButtonStates() {
        startButton.isEnabled = overlayGranted && vpnGranted && !isRadarRunning
        stopButton.isEnabled = isRadarRunning
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RADAR CONTROL
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startRadar() {
        if (!overlayGranted || !vpnGranted) {
            Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show()
            return
        }

        DiscoveryLogger.i("Starting radar...")
        Log.i(TAG, "Starting radar")

        // Start VPN service
        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_START
        }
        startService(vpnIntent)

        // Start overlay service
        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_START
        }
        startService(overlayIntent)

        isRadarRunning = true
        updateButtonStates()

        Toast.makeText(this, "Radar started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRadar() {
        DiscoveryLogger.i("Stopping radar...")
        Log.i(TAG, "Stopping radar")

        // Stop overlay
        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_STOP
        }
        startService(overlayIntent)

        // Stop VPN
        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_STOP
        }
        startService(vpnIntent)

        isRadarRunning = false
        updateButtonStates()

        Toast.makeText(this, "Radar stopped", Toast.LENGTH_SHORT).show()
    }

    private fun clearEntities() {
        EntityStore.clearAll()
        DiscoveryLogger.i("Entities cleared by user")
        Toast.makeText(this, "Entities cleared", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS UPDATES
    // ═══════════════════════════════════════════════════════════════════════════

    private var statusUpdateRunnable: Runnable? = null
    private val statusUpdateHandler = android.os.Handler(mainLooper)

    private fun startStatusUpdates() {
        statusUpdateRunnable = object : Runnable {
            override fun run() {
                updateStatus()
                statusUpdateHandler.postDelayed(this, 1000) // Update every second
            }
        }
        statusUpdateHandler.post(statusUpdateRunnable!!)
    }

    private fun stopStatusUpdates() {
        statusUpdateRunnable?.let {
            statusUpdateHandler.removeCallbacks(it)
        }
    }

    private fun updateStatus() {
        val vpnRunning = AlbionVpnService.isRunning
        val overlayRunning = RadarOverlayService.isRunning

        isRadarRunning = vpnRunning && overlayRunning
        updateButtonStates()

        // Build status text
        val status = buildString {
            append("═══════════════════════════════════\n")
            append("STATUS\n")
            append("═══════════════════════════════════\n")
            append("VPN: ${if (vpnRunning) "● Running" else "○ Stopped"}\n")
            append("Overlay: ${if (overlayRunning) "● Running" else "○ Stopped"}\n")
            append("Permissions:\n")
            append("  Overlay: ${if (overlayGranted) "✓" else "✗"}\n")
            append("  VPN: ${if (vpnGranted) "✓" else "✗"}\n")
        }
        statusText.text = status

        // Build stats text
        val stats = buildString {
            append("═══════════════════════════════════\n")
            append("STATISTICS\n")
            append("═══════════════════════════════════\n")
            append("Packets: ${AlbionVpnService.packetCount.get()}\n")
            append("Albion: ${AlbionVpnService.albionCount.get()}\n")
            append("Entities: ${EntityStore.entityCount}\n")
            append("  Resources: ${EntityStore.resourceCount}\n")
            append("  Mobs: ${EntityStore.mobCount}\n")
            append("  Players: ${EntityStore.playerCount}\n")
            append("Local Player ID: ${EntityStore.localPlayerId}\n")

            val (localX, localY) = EntityStore.getLocalPlayerPosition()
            append("Position: (${localX.toInt()}, ${localY.toInt()})\n")

            if (EntityStore.currentZone.isNotEmpty()) {
                append("Zone: ${EntityStore.currentZone}\n")
            }
        }
        statsText.text = stats
    }
}
