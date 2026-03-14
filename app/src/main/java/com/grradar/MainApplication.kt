package com.grradar

import android.app.Application
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger

/**
 * Main Application class
 * 
 * Handles:
 * - IdMapRepository initialization
 * - DiscoveryLogger startup
 * 
 * Note: MultiDex not needed for minSdk 26+ (native support since API 21)
 */
class MainApplication : Application() {

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
