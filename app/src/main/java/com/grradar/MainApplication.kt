package com.grradar

import android.app.Application

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initDefaultPreferences()
    }

    private fun initDefaultPreferences() {
        val prefs = getSharedPreferences("grradar_prefs", MODE_PRIVATE)
        val editor = prefs.edit()

        if (!prefs.contains("playerDot")) {
            editor.putBoolean("playerDot", true)
        }
        if (!prefs.contains("harvestingFiber")) {
            editor.putBoolean("harvestingFiber", true)
        }
        if (!prefs.contains("harvestingOre")) {
            editor.putBoolean("harvestingOre", true)
        }
        if (!prefs.contains("harvestingRock")) {
            editor.putBoolean("harvestingRock", true)
        }
        if (!prefs.contains("harvestingHide")) {
            editor.putBoolean("harvestingHide", true)
        }
        if (!prefs.contains("harvestingWood")) {
            editor.putBoolean("harvestingWood", true)
        }
        if (!prefs.contains("mobEnemy")) {
            editor.putBoolean("mobEnemy", true)
        }
        if (!prefs.contains("mobBoss")) {
            editor.putBoolean("mobBoss", true)
        }

        editor.apply()
    }
}
