package com.grradar

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger

/**
 * Main Application class
 * 
 * Handles:
 * - MultiDex support
 * - IdMapRepository initialization
 * - DiscoveryLogger startup
 */
class MainApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize ID map repository (loads id_map.json)
        IdMapRepository.getInstance().initialize(this)
        
        // Start discovery logger
        DiscoveryLogger.start(this)
        
        DiscoveryLogger.i("GRRadar Application started")
    }

    override fun onTerminate() {
        DiscoveryLogger.stop()
        super.onTerminate()
    }
}
