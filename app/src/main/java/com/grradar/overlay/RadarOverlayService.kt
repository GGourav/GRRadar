package com.grradar.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.grradar.R

class RadarOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.grradar.overlay.START"
        const val ACTION_STOP = "com.grradar.overlay.STOP"
        private const val TAG = "RadarOverlayService"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    showOverlay()
                }
            }
            ACTION_STOP -> {
                hideOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            300,
            300,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        overlayView = FrameLayout(this).apply {
            setBackgroundColor(0x80000000.toInt()) // Semi-transparent black
        }

        windowManager?.addView(overlayView, params)
        isRunning = true
        Log.d(TAG, "Overlay shown")
    }

    private fun hideOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
        isRunning = false
        Log.d(TAG, "Overlay hidden")
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
