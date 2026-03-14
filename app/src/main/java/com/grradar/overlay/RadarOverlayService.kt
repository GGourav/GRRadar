package com.grradar.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.grradar.MainActivity
import com.grradar.R
import com.grradar.vpn.AlbionVpnService

class RadarOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.grradar.overlay.START"
        const val ACTION_STOP = "com.grradar.overlay.STOP"
        private const val TAG = "RadarOverlayService"
        private const val CHANNEL_ID = "grradar_overlay_channel"
        private const val NOTIFICATION_ID = 1002
        private const val UPDATE_INTERVAL_MS = 500L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvPc: TextView? = null
    private var tvPca: TextView? = null
    private var tvEntity: TextView? = null
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            if (isRunning) {
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    showOverlay()
                    startStatsUpdate()
                }
            }
            ACTION_STOP -> {
                stopStatsUpdate()
                hideOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GRRadar Overlay Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Radar overlay display service"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GRRadar Overlay Active")
            .setContentText("Radar overlay is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            400,
            200,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        // Create overlay with statistics display
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt()) // Dark semi-transparent background

            // Add padding
            setPadding(16, 16, 16, 16)

            // Create stats text views
            val textLayout = FrameLayout(this@RadarOverlayService).apply {
                
                tvPc = TextView(this@RadarOverlayService).apply {
                    text = "PC: 0"
                    textSize = 14f
                    setTextColor(Color.CYAN)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 0 }
                }
                addView(tvPc)

                tvPca = TextView(this@RadarOverlayService).apply {
                    text = "PCA: 0"
                    textSize = 14f
                    setTextColor(Color.GREEN)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 40 }
                }
                addView(tvPca)

                tvEntity = TextView(this@RadarOverlayService).apply {
                    text = "Entity: 0"
                    textSize = 14f
                    setTextColor(Color.YELLOW)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 80 }
                }
                addView(tvEntity)
            }
            addView(textLayout)
        }

        windowManager?.addView(overlayView, params)
        isRunning = true
        Log.d(TAG, "Overlay shown with stats display")
    }

    private fun startStatsUpdate() {
        handler.post(updateRunnable)
    }

    private fun stopStatsUpdate() {
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateStats() {
        tvPc?.text = "PC: ${AlbionVpnService.totalPacketCount}"
        tvPca?.text = "PCA: ${AlbionVpnService.albionPacketCount}"
        tvEntity?.text = "Entity: ${AlbionVpnService.entityCount}"
    }

    private fun hideOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
        tvPc = null
        tvPca = null
        tvEntity = null
        isRunning = false
        Log.d(TAG, "Overlay hidden")
    }

    override fun onDestroy() {
        stopStatsUpdate()
        hideOverlay()
        super.onDestroy()
    }
}
