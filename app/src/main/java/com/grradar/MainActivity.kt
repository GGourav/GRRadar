package com.grradar

import android.app.AlertDialog
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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import com.grradar.overlay.RadarOverlayService
import com.grradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var vpnSwitch: Switch
    private lateinit var overlaySwitch: Switch
    private lateinit var clearLogButton: Button

    private var vpnRunning = false
    private var overlayRunning = false

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra("running", false) ?: false
            vpnRunning = running
            updateUI()
            Log.i(TAG, "VPN status changed: $running")
            DiscoveryLogger.i("VPN status changed: $running")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        vpnSwitch = findViewById(R.id.vpnSwitch)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        clearLogButton = findViewById(R.id.clearLogButton)

        // Initialize logger
        DiscoveryLogger.start(this)
        DiscoveryLogger.i("MainActivity created")

        // Initialize IdMapRepository
        IdMapRepository.getInstance().initialize(this)

        setupListeners()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(vpnStatusReceiver, IntentFilter(AlbionVpnService.BROADCAST_VPN_STATUS), flags)
        
        vpnRunning = AlbionVpnService.isRunning()
        overlayRunning = RadarOverlayService.isRunning()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(vpnStatusReceiver) }
    }

    private fun setupListeners() {
        vpnSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !vpnRunning) {
                startVpn()
            } else if (!isChecked && vpnRunning) {
                stopVpn()
            }
        }

        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !overlayRunning) {
                startOverlay()
            } else if (!isChecked && overlayRunning) {
                stopOverlay()
            }
        }

        clearLogButton.setOnClickListener {
            DiscoveryLogger.clearLog()
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_STOP
        }
        startService(intent)
        vpnRunning = false
        updateUI()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("GRRadar needs overlay permission to display the radar over the game.")
                .setPositiveButton("Grant") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            overlaySwitch.isChecked = false
            return
        }

        val intent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_START
        }
        startService(intent)
        overlayRunning = true
        updateUI()
        
        DiscoveryLogger.i("Overlay started")
    }

    private fun stopOverlay() {
        val intent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_STOP
        }
        startService(intent)
        overlayRunning = false
        updateUI()
        
        DiscoveryLogger.i("Overlay stopped")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                val intent = Intent(this, AlbionVpnService::class.java).apply {
                    action = AlbionVpnService.ACTION_START
                }
                startService(intent)
                vpnRunning = true
                updateUI()
                DiscoveryLogger.i("VPN started")
            } else {
                vpnSwitch.isChecked = false
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
                DiscoveryLogger.w("VPN permission denied")
            }
        }
    }

    private fun updateUI() {
        vpnSwitch.isChecked = vpnRunning
        overlaySwitch.isChecked = overlayRunning

        val status = buildString {
            append("VPN: ${if (vpnRunning) "Running" else "Stopped"}\n")
            append("Overlay: ${if (overlayRunning) "Running" else "Stopped"}\n")
            append("Packets: ${AlbionVpnService.packetCount.get()}\n")
            append("Albion: ${AlbionVpnService.albionCount.get()}\n")
            append("Entities: ${AlbionVpnService.entityCount}")
        }
        statusText.text = status

        val logPath = DiscoveryLogger.getLogFilePath()
        val stats = buildString {
            append("Log: $logPath\n")
            append("Events logged: ${DiscoveryLogger.getEventCount()}")
        }
        statsText.text = stats
    }
}
