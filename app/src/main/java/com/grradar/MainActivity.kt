package com.grradar

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Main Activity - Entry point for GRRadar.
 * Permission flow and VPN/Overlay control will be implemented in Step 2.
 */
class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Permission flow will be implemented in Step 2
    }
}
