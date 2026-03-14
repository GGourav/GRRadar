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
import com.grradar.overlay.RadarOverlayService
import com.grradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity() {

    private val REQUEST_OVERLAY_PERMISSION = 1001
    private val REQUEST_VPN_PERMISSION = 1002

    private lateinit var tvStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvVpnStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var isRadarRunning = false

    // Broadcast receiver for VPN status
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra("running", false) ?: false
            if (!running && isRadarRunning) {
                // VPN stopped unexpectedly
                isRadarRunning = false
                updatePermissionStatus()
                Toast.makeText(this@MainActivity, "VPN stopped unexpectedly", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updatePermissionStatus()

        // Register broadcast receiver
        val filter = IntentFilter("com.grradar.VPN_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStatusReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(vpnStatusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvVpnStatus = findViewById(R.id.tv_vpn_status)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        btnStart.setOnClickListener {
            if (checkAllPermissions()) {
                startRadar()
            }
        }

        btnStop.setOnClickListener {
            stopRadar()
        }

        findViewById<Button>(R.id.btn_request_overlay).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.btn_request_vpn).setOnClickListener {
            requestVpnPermission()
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasVpn = VpnService.prepare(this) == null

        tvOverlayStatus.text = if (hasOverlay) {
            "✓ Overlay Permission: GRANTED"
        } else {
            "✗ Overlay Permission: DENIED"
        }

        tvOverlayStatus.setTextColor(
            if (hasOverlay) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )

        tvVpnStatus.text = if (hasVpn) {
            "✓ VPN Permission: GRANTED"
        } else {
            "✗ VPN Permission: DENIED"
        }

        tvVpnStatus.setTextColor(
            if (hasVpn) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )

        val allGranted = hasOverlay && hasVpn
        tvStatus.text = if (isRadarRunning) {
            "Status: Radar running..."
        } else if (allGranted) {
            "Status: Ready to start radar"
        } else {
            "Status: Grant permissions to continue"
        }

        btnStart.isEnabled = allGranted && !isRadarRunning
        btnStop.isEnabled = isRadarRunning
    }

    private fun checkAllPermissions(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return false
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            Toast.makeText(this, "Please grant VPN permission", Toast.LENGTH_SHORT).show()
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
            return false
        }

        return true
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN_PERMISSION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_VPN_PERMISSION -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "VPN permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updatePermissionStatus()
    }

    private fun startRadar() {
        if (!checkAllPermissions()) return

        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_START
        }
        startService(vpnIntent)

        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_START
        }
        startService(overlayIntent)

        isRadarRunning = true
        updatePermissionStatus()

        Toast.makeText(this, "Radar started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRadar() {
        val vpnIntent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_STOP
        }
        startService(vpnIntent)

        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_STOP
        }
        startService(overlayIntent)

        isRadarRunning = false
        updatePermissionStatus()

        Toast.makeText(this, "Radar stopped", Toast.LENGTH_SHORT).show()
    }
}
