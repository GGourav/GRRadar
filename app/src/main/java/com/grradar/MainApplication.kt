package com.grradar

import androidx.multidex.MultiDexApplication

/**
 * Main Application class for GRRadar.
 * Extends MultiDexApplication to support multiple DEX files
 * for the large number of method references in the Photon parser.
 */
class MainApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        // Application initialization will be added in later steps
    }
}
