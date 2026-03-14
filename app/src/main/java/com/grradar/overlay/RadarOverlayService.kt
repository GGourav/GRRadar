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
import com.grradar.R
import com.grradar.data.EntityStore
import com.grradar.model.RadarEntity
import com.grradar.vpn.AlbionVpnService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Radar Overlay Service - Displays the radar as a floating overlay
 * 
 * Uses WindowManager to draw over other apps (including Albion Online).
 * Requires SYSTEM_ALERT_WINDOW permission.
 * 
 * Gets EntityStore from VPN service via AlbionVpnService.getSharedEntityStore()
 */
class RadarOverlayService : Service() {

    companion object {
        private const val TAG = "RadarOverlayService"
        private const val DEFAULT_RADAR_SIZE = 300 // pixels
        private const val DEFAULT_SCALE = 2.0f // world units per pixel
        private const val UPDATE_INTERVAL_MS = 50L // 20 FPS
        private const val CHANNEL_ID = "grradar_overlay_channel"
        private const val NOTIFICATION_ID = 1002

        // Intent actions
        const val ACTION_START = "com.grradar.overlay.ACTION_START"
        const val ACTION_STOP = "com.grradar.overlay.ACTION_STOP"

        @Volatile
        private var isRunning = false

        // Listener interface for radar events
        interface RadarListener {
            fun onEntityAdded(entity: RadarEntity)
            fun onEntityRemoved(id: Int)
            fun onEntityUpdated(entity: RadarEntity)
            fun onLocalPlayerMoved(x: Float, y: Float)
            fun onZoneChanged(zoneName: String)
        }

        private val listeners = CopyOnWriteArrayList<RadarListener>()

        fun addListener(listener: RadarListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: RadarListener) {
            listeners.remove(listener)
        }

        fun isRunning(): Boolean = isRunning
    }

    // Window manager and views
    private var windowManager: WindowManager? = null
    private var radarContainer: FrameLayout? = null
    private var radarView: RadarSurfaceView? = null

    // Entity store reference (obtained from VPN service)
    private var entityStore: EntityStore? = null

    // Radar configuration
    private var radarSize = DEFAULT_RADAR_SIZE
    private var radarScale = DEFAULT_SCALE
    private var radarX = 0
    private var radarY = 0

    // Render thread
    private var renderThread: Thread? = null
    private var shouldRender = true

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Overlay Service created")
        
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Overlay Service starting...")

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        if (radarContainer == null) {
            createOverlay()
        }

        isRunning = true
        
        return START_STICKY
    }

    /**
     * Create notification channel for overlay service
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GRRadar Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Radar overlay display service"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GRRadar Overlay Active")
            .setContentText("Radar is displayed over game")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    /**
     * Create the overlay window
     */
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createOverlay() {
        try {
            // Window layout params
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                radarSize,
                radarSize,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = radarX
                y = radarY
            }

            // Create container
            radarContainer = FrameLayout(this)

            // Create radar surface view
            radarView = RadarSurfaceView(this)
            radarView?.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            radarContainer?.addView(radarView)

            // Add touch listener for dragging
            var lastX = 0
            var lastY = 0
            var isDragging = false

            radarContainer?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX.toInt() - lastX
                        val dy = event.rawY.toInt() - lastY

                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                        }

                        if (isDragging) {
                            params.x += dx
                            params.y += dy
                            windowManager?.updateViewLayout(radarContainer, params)
                        }

                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                        true
                    }
                    else -> false
                }
            }

            // Add to window manager
            windowManager?.addView(radarContainer, params)

            // Start render thread
            startRenderThread()

            Log.i(TAG, "Overlay created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: ${e.message}")
        }
    }

    /**
     * Start the render thread
     */
    private fun startRenderThread() {
        shouldRender = true
        renderThread = Thread {
            Log.i(TAG, "Render thread started")

            while (shouldRender) {
                try {
                    // Get EntityStore from VPN service if not yet connected
                    if (entityStore == null) {
                        entityStore = AlbionVpnService.getSharedEntityStore()
                        if (entityStore != null) {
                            radarView?.setEntityStore(entityStore!!)
                            Log.i(TAG, "EntityStore connected from VPN service")
                        }
                    }

                    // Update radar view
                    radarView?.refresh()

                    // Notify listeners
                    entityStore?.let { store ->
                        val pos = store.getLocalPlayerPosition()
                        listeners.forEach { listener ->
                            listener.onLocalPlayerMoved(pos.first, pos.second)
                        }
                    }

                    Thread.sleep(UPDATE_INTERVAL_MS)

                } catch (e: Exception) {
                    if (e !is InterruptedException) {
                        Log.w(TAG, "Render error: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "Render thread stopped")
        }
        renderThread?.start()
    }

    /**
     * Set the entity store for rendering (alternative method)
     */
    fun setEntityStore(store: EntityStore) {
        entityStore = store
        radarView?.setEntityStore(store)
    }

    /**
     * Update radar configuration
     */
    fun updateConfig(size: Int, scale: Float) {
        radarSize = size
        radarScale = scale

        radarView?.setScale(scale)

        // Update window size
        radarContainer?.let { container ->
            val params = container.layoutParams as WindowManager.LayoutParams
            params.width = size
            params.height = size
            windowManager?.updateViewLayout(container, params)
        }
    }

    /**
     * Remove the overlay
     */
    private fun removeOverlay() {
        Log.i(TAG, "Removing overlay...")

        shouldRender = false
        renderThread?.interrupt()
        renderThread?.join(1000)
        renderThread = null

        try {
            radarContainer?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }

        radarContainer = null
        radarView = null
        isRunning = false
    }

    override fun onDestroy() {
        Log.i(TAG, "Overlay Service destroying...")
        removeOverlay()
        listeners.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Show or hide the radar
     */
    fun setVisible(visible: Boolean) {
        radarContainer?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Check if overlay is visible
     */
    fun isVisible(): Boolean {
        return radarContainer?.visibility == View.VISIBLE
    }
}
