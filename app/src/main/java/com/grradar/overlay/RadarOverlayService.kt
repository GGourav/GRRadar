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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

class RadarOverlayService : Service() {

    companion object {
        private const val TAG = "RadarOverlayService"
        private const val DEFAULT_RADAR_SIZE = 300
        private const val DEFAULT_SCALE = 2.0f
        private const val UPDATE_INTERVAL_MS = 50L
        private const val CHANNEL_ID = "grradar_overlay_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.grradar.overlay.ACTION_START"
        const val ACTION_STOP = "com.grradar.overlay.ACTION_STOP"

        @Volatile
        private var isRunning = false

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

    private var windowManager: WindowManager? = null
    private var radarContainer: FrameLayout? = null
    private var radarView: RadarSurfaceView? = null
    private var entityStore: EntityStore? = null

    private var radarSize = DEFAULT_RADAR_SIZE
    private var radarScale = DEFAULT_SCALE

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Overlay Service created")
        
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Overlay Service starting... action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, createNotification())

        if (radarContainer == null) {
            createOverlay()
        }

        isRunning = true
        
        return START_STICKY
    }

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

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GRRadar Overlay Active")
            .setContentText("Radar is displayed over game")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        try {
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
                x = 50
                y = 100
            }

            radarContainer = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            radarView = RadarSurfaceView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            radarContainer?.addView(radarView)

            var lastX = 0
            var lastY = 0

            radarContainer?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX.toInt() - lastX
                        val dy = event.rawY.toInt() - lastY

                        params.x += dx
                        params.y += dy
                        windowManager?.updateViewLayout(radarContainer, params)

                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(radarContainer, params)

            startUpdateLoop()

            Log.i(TAG, "Overlay created successfully - size=${radarSize}x${radarSize}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: ${e.message}", e)
        }
    }

    private fun startUpdateLoop() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (entityStore == null) {
                    entityStore = AlbionVpnService.getSharedEntityStore()
                    if (entityStore != null) {
                        radarView?.setEntityStore(entityStore!!)
                        Log.i(TAG, "EntityStore connected from VPN service")
                    }
                }

                radarView?.refresh()

                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.post(updateRunnable!!)
    }

    fun setEntityStore(store: EntityStore) {
        entityStore = store
        radarView?.setEntityStore(store)
    }

    fun updateConfig(size: Int, scale: Float) {
        radarSize = size
        radarScale = scale

        radarView?.setScale(scale)

        radarContainer?.let { container ->
            val params = container.layoutParams as WindowManager.LayoutParams
            params.width = size
            params.height = size
            windowManager?.updateViewLayout(container, params)
        }
    }

    private fun removeOverlay() {
        Log.i(TAG, "Removing overlay...")

        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null

        try {
            radarContainer?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }

        radarContainer = null
        radarView = null
        entityStore = null
        isRunning = false
    }

    override fun onDestroy() {
        Log.i(TAG, "Overlay Service destroying...")
        removeOverlay()
        listeners.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setVisible(visible: Boolean) {
        radarContainer?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun isVisible(): Boolean {
        return radarContainer?.visibility == View.VISIBLE
    }
}
