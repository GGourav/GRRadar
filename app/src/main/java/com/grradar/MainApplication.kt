package com.grradar

import androidx.multidex.MultiDexApplication
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger

/**
 * Main Application class
 * 
 * Handles:
 * - MultiDex support (via MultiDexApplication base class)
 * - IdMapRepository initialization
 * - DiscoveryLogger startup
 */
class MainApplication : MultiDexApplication() {

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
