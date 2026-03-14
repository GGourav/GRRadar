package com.grradar.vpn

import android.net.VpnService

/**
 * VPN Service for capturing Albion Online UDP traffic on port 5056.
 * 
 * Creates a TUN interface that routes only com.albiononline traffic.
 * Photon Protocol 16 parsing will be implemented in Step 5.
 * 
 * Configuration (from reference APK):
 *   - Target app: com.albiononline
 *   - Target port: 5056 (Photon UDP)
 *   - TUN address: 10.0.0.2 / 32
 *   - TUN route: 0.0.0.0 / 0
 *   - MTU: 32767
 */
class AlbionVpnService : VpnService() {

    // Implementation will be added in Step 5
    
    override fun onCreate() {
        super.onCreate()
        // VPN initialization
    }

    override fun onDestroy() {
        // Cleanup
        super.onDestroy()
    }
}
