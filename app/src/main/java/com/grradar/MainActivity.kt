package com.grradar

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.grradar.overlay.RadarOverlayService
import com.grradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity() {

    // Request codes
    private val REQUEST_OVERLAY_PERMISSION = 1001
    private val REQUEST_VPN_PERMISSION = 1002

    // UI elements
    private lateinit var tvStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvVpnStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Service state
    private var isRadarRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
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

        // Request overlay permission button
        findViewById<Button>(R.id.btn_request_overlay).setOnClickListener {
            requestOverlayPermission()
        }

        // Request VPN permission button
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
        tvStatus.text = if (allGranted) {
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
        tvStatus.text = "Status: Radar running..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        Toast.makeText(this, "Radar started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRadar() {
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

        isRadarRunning = false
        tvStatus.text = "Status: Radar stopped"
        btnStart.isEnabled = true
        btnStop.isEnabled = false

        Toast.makeText(this, "Radar stopped", Toast.LENGTH_SHORT).show()
    }
}
