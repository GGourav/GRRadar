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
import com.grradar.vpn.AlbionVpnService
import java.util.concurrent.atomic.AtomicBoolean

class RadarOverlayService : Service() {

    companion object {
        private const val TAG = "RadarOverlayService"
        private const val DEFAULT_RADAR_SIZE = 300
        private const val UPDATE_INTERVAL_MS = 50L

        const val ACTION_START = "com.grradar.overlay.ACTION_START"
        const val ACTION_STOP = "com.grradar.overlay.ACTION_STOP"

        @Volatile
        private var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var radarContainer: FrameLayout? = null
    private var radarView: RadarSurfaceView? = null

    private var radarSize = DEFAULT_RADAR_SIZE
    private var radarX = 100
    private var radarY = 100

    private var renderThread: Thread? = null
    private val shouldRender = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Overlay Service created")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Overlay action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> createOverlay()
            ACTION_STOP -> removeOverlay()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        if (radarContainer != null) {
            Log.w(TAG, "Overlay already exists")
            return
        }

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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = radarX
                y = radarY
            }

            radarContainer = FrameLayout(this)

            radarView = RadarSurfaceView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            radarContainer?.addView(radarView)

            // Connect to VPN's entity store
            AlbionVpnService.entityStore?.let { store ->
                radarView?.setEntityStore(store)
                Log.i(TAG, "Connected to EntityStore")
            }

            // Drag handling
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
                    else -> false
                }
            }

            windowManager?.addView(radarContainer, params)
            startRenderThread()
            isRunning = true

            Log.i(TAG, "Overlay created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: ${e.message}")
        }
    }

    private fun startRenderThread() {
        shouldRender.set(true)

        renderThread = Thread {
            while (shouldRender.get()) {
                try {
                    radarView?.postInvalidate()
                    Thread.sleep(UPDATE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        renderThread?.start()
    }

    private fun removeOverlay() {
        shouldRender.set(false)
        renderThread?.interrupt()
        renderThread = null

        try {
            radarContainer?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }

        radarContainer = null
        radarView = null
        isRunning = false
        Log.i(TAG, "Overlay removed")
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
