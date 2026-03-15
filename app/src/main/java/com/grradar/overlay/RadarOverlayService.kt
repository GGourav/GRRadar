package com.grradar.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import com.grradar.R
import com.grradar.data.EntityStore
import com.grradar.logger.DiscoveryLogger
import java.util.Timer
import java.util.TimerTask

/**
 * RadarOverlayService — Floating radar overlay window
 *
 * ARCHITECTURE:
 *   - Uses WindowManager to create a floating overlay
 *   - TYPE_APPLICATION_OVERLAY for Android O+
 *   - Transparent background with radar drawn on top
 *   - Periodic refresh via Timer
 *
 * FEATURES:
 *   - Draggable overlay position
 *   - Configurable size
 *   - Entity type toggles
 *   - Statistics display
 *   - Foreground service with notification
 *
 * PERMISSIONS REQUIRED:
 *   - SYSTEM_ALERT_WINDOW (overlay permission)
 */
class RadarOverlayService : Service() {

    companion object {
        private const val TAG = "RadarOverlayService"

        // Intent actions
        const val ACTION_START = "com.grradar.overlay.START"
        const val ACTION_STOP = "com.grradar.overlay.STOP"
        const val BROADCAST_OVERLAY_STATUS = "com.grradar.OVERLAY_STATUS"

        // Notification
        private const val CHANNEL_ID = "grradar_overlay_channel"
        private const val NOTIFICATION_ID = 1002

        // Refresh interval (10 FPS)
        private const val REFRESH_INTERVAL_MS = 100L

        /**
         * Check if overlay is currently running
         */
        @Volatile
        var isRunning = false
            private set
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var radarView: RadarSurfaceView? = null
    private var refreshTimer: Timer? = null

    // Overlay position (for dragging)
    private var lastX = 0
    private var lastY = 0
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DiscoveryLogger.i("RadarOverlayService created")
        Log.i(TAG, "Overlay service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    createOverlay()
                    startRefreshTimer()
                    isRunning = true
                    broadcastStatus(true)
                    DiscoveryLogger.i("Radar overlay started")
                    Log.i(TAG, "Overlay started")
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Overlay service destroying")
        stopRefreshTimer()
        removeOverlay()
        isRunning = false
        broadcastStatus(false)
        DiscoveryLogger.i("RadarOverlayService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GRRadar Overlay Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Radar overlay display service"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GRRadar Overlay Active")
            .setContentText("Radar is displayed over the game")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun broadcastStatus(running: Boolean) {
        val intent = Intent(BROADCAST_OVERLAY_STATUS).apply {
            putExtra("running", running)
        }
        sendBroadcast(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OVERLAY CREATION
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density

        // Window layout parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        // Create container
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0, 0, 0, 0)) // Transparent
        }

        // Create radar view
        val radarSize = (200 * density).toInt()
        radarView = RadarSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(radarSize, radarSize)
            worldRange = 50f

            // Enable all entity types by default
            showResources = true
            showMobs = true
            showPlayers = true
            showChests = true
            showDungeons = true
            showSilver = true
            showMist = true
        }

        overlayView?.addView(radarView)

        // Touch listener for dragging
        overlayView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        // View might be detached
                    }
                    true
                }
                else -> false
            }
        }

        // Add to window
        try {
            windowManager?.addView(overlayView, params)
            DiscoveryLogger.i("Overlay view added to window")
        } catch (e: Exception) {
            DiscoveryLogger.e("Failed to add overlay view: ${e.message}", e)
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            DiscoveryLogger.w("Error removing overlay: ${e.message}")
        }
        overlayView = null
        radarView = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFRESH TIMER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startRefreshTimer() {
        refreshTimer = Timer("RadarRefresh", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    radarView?.post {
                        radarView?.refresh()
                    }
                }
            }, 0, REFRESH_INTERVAL_MS)
        }
        DiscoveryLogger.d("Refresh timer started (${REFRESH_INTERVAL_MS}ms)")
    }

    private fun stopRefreshTimer() {
        refreshTimer?.cancel()
        refreshTimer = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update overlay position
     */
    fun updatePosition(x: Int, y: Int) {
        windowManager?.let { wm ->
            overlayView?.let { view ->
                val params = view.layoutParams as WindowManager.LayoutParams
                params.x = x
                params.y = y
                try {
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    DiscoveryLogger.w("Failed to update overlay position: ${e.message}")
                }
            }
        }
    }

    /**
     * Update overlay size
     */
    fun updateSize(sizeDp: Int) {
        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt().coerceIn(100, 500)

        radarView?.let { rv ->
            rv.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            rv.worldRange = 50f // Reset range
        }

        // Force layout update
        overlayView?.requestLayout()

        try {
            windowManager?.let { wm ->
                overlayView?.let { view ->
                    wm.updateViewLayout(view, view.layoutParams)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Set world range (zoom level)
     */
    fun setWorldRange(range: Float) {
        radarView?.worldRange = range.coerceIn(20f, 200f)
    }

    /**
     * Toggle entity type visibility
     */
    fun setEntityVisibility(type: String, visible: Boolean) {
        radarView?.apply {
            when (type) {
                "resources" -> showResources = visible
                "mobs" -> showMobs = visible
                "players" -> showPlayers = visible
                "chests" -> showChests = visible
                "dungeons" -> showDungeons = visible
                "silver" -> showSilver = visible
                "mist" -> showMist = visible
            }
        }
    }

    /**
     * Get current statistics
     */
    fun getStats(): String {
        return EntityStore.getStatsString()
    }
}
