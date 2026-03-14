package com.grradar.overlay

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service for the floating radar overlay.
 * 
 * Uses WindowManager to render a transparent overlay with colored dots
 * representing game entities (resources, mobs, players, etc.)
 * positioned over the Albion Online game.
 * 
 * Requires SYSTEM_ALERT_WINDOW permission.
 * Overlay rendering will be implemented in Step 7.
 */
class RadarOverlayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Overlay initialization will be added in Step 7
    }

    override fun onDestroy() {
        // Cleanup
        super.onDestroy()
    }
}
