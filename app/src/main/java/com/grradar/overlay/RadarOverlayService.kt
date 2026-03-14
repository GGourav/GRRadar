package com.grradar.overlay

import android.annotation.SuppressLint
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
import com.grradar.data.EntityStore
import com.grradar.model.RadarEntity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Radar Overlay Service - Displays the radar as a floating overlay
 * 
 * Uses WindowManager to draw over other apps (including Albion Online).
 * Requires SYSTEM_ALERT_WINDOW permission.
 */
class RadarOverlayService : Service() {

    companion object {
        private const val TAG = "RadarOverlayService"
        private const val DEFAULT_RADAR_SIZE = 300 // pixels
        private const val DEFAULT_SCALE = 2.0f // world units per pixel
        private const val UPDATE_INTERVAL_MS = 50L // 20 FPS

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
            Log.i(TAG, "Listener added, count: ${listeners.size}")
        }

        fun removeListener(listener: RadarListener) {
            listeners.remove(listener)
            Log.i(TAG, "Listener removed, count: ${listeners.size}")
        }

        fun isRunning(): Boolean = isRunning
    }

    // Window manager and views
    private var windowManager: WindowManager? = null
    private var radarContainer: FrameLayout? = null
    private var radarView: RadarSurfaceView? = null

    // Entity store reference
    private var entityStore: EntityStore? = null

    // Radar configuration
    private var radarSize = DEFAULT_RADAR_SIZE
    private var radarScale = DEFAULT_SCALE
    private var radarX = 0
    private var radarY = 0

    // Render thread
    private var renderThread: Thread? = null
    private val shouldRender = AtomicBoolean(true)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Overlay Service created")
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Overlay Service starting...")

        when (intent?.action) {
            ACTION_START -> {
                if (radarContainer == null) {
                    createOverlay()
                }
                isRunning = true
            }
            ACTION_STOP -> {
                removeOverlay()
                stopSelf()
            }
            else -> {
                if (radarContainer == null) {
                    createOverlay()
                }
                isRunning = true
            }
        }
        
        return START_STICKY
    }

    /**
     * Create the overlay window
     */
    @SuppressLint("ClickableViewAccessibility")
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
            radarView = RadarSurfaceView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
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

                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
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
        shouldRender.set(true)
        
        renderThread = Thread {
            Log.i(TAG, "Render thread started")

            while (shouldRender.get()) {
                try {
                    // Update radar view
                    radarView?.refresh()
                    radarView?.postInvalidate()

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
     * Set the entity store for rendering
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

        shouldRender.set(false)
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
