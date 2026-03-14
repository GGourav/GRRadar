package com.grradar.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.grradar.data.EntityStore
import com.grradar.data.IdMapRepository
import com.grradar.parser.EventDispatcher
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Albion VPN Service - Captures UDP traffic from Albion Online
 * 
 * Uses Android's VpnService API to intercept packets without root.
 * Only routes traffic from com.albiononline package.
 * 
 * Configuration:
 * - TUN address: 10.0.0.2/32
 * - TUN route: 0.0.0.0/0
 * - MTU: 32767
 * - Target port: 5056 (Photon UDP)
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG = "AlbionVpnService"
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_ROUTE = "0.0.0.0"
        private const val MTU = 32767
        private const val TARGET_PACKAGE = "com.albiononline"
        private const val PHOTON_PORT = 5056

        @Volatile
        private var isRunning = false

        fun isRunning(): Boolean = isRunning
    }

    // VPN interface
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInput: FileInputStream? = null
    private var vpnOutput: FileOutputStream? = null

    // Parser components
    private lateinit var entityStore: EntityStore
    private lateinit var idMapRepo: IdMapRepository
    private lateinit var eventDispatcher: EventDispatcher

    // Control flags
    private val shouldStop = AtomicBoolean(false)
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
        
        // Initialize repositories
        entityStore = EntityStore()
        idMapRepo = IdMapRepository.getInstance()
        eventDispatcher = EventDispatcher(entityStore, idMapRepo)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN Service starting...")
        
        if (!shouldStop.get() && vpnInterface == null) {
            startVpn()
        }
        
        return START_STICKY
    }

    /**
     * Start the VPN and packet capture
     */
    private fun startVpn() {
        try {
            // Build VPN interface
            val builder = Builder()
                .setSession("GRRadar VPN")
                .addAddress(TUN_ADDRESS, 32)
                .addRoute(TUN_ROUTE, 0)
                .setMtu(MTU)
                .setBlocking(true)
                .addAllowedApplication(TARGET_PACKAGE)

            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

            isRunning = true
            shouldStop.set(false)

            // Start packet processing thread
            workerThread = Thread { processPackets() }
            workerThread?.start()

            Log.i(TAG, "VPN started successfully - capturing Albion traffic")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}")
            stopVpn()
        }
    }

    /**
     * Main packet processing loop
     */
    private fun processPackets() {
        val buffer = ByteBuffer.allocate(MTU)
        buffer.order(ByteOrder.BIG_ENDIAN)

        Log.i(TAG, "Packet processing started")

        while (!shouldStop.get() && vpnInterface != null) {
            try {
                // Read packet from TUN interface
                val length = vpnInput?.read(buffer.array()) ?: -1
                
                if (length <= 0) {
                    continue
                }

                // Parse IP packet
                buffer.limit(length)
                buffer.position(0)

                val version = (buffer.get().toInt() shr 4) and 0x0F
                
                if (version != 4) {
                    buffer.clear()
                    continue
                }

                // IPv4 header parsing
                val ihl = buffer.get().toInt() and 0x0F
                buffer.position(12) // Skip to src/dst addresses
                
                val protocol = buffer.get(9).toInt() and 0xFF

                // Only process UDP packets
                if (protocol != 17) {
                    buffer.clear()
                    continue
                }

                // Skip to UDP header (IP header is ihl * 4 bytes)
                val ipHeaderLength = ihl * 4
                buffer.position(ipHeaderLength)

                // Read UDP header
                val srcPort = buffer.short.toInt() and 0xFFFF
                val dstPort = buffer.short.toInt() and 0xFFFF
                val udpLength = buffer.short.toInt() and 0xFFFF
                buffer.short // checksum

                // Check if this is Photon traffic (port 5056)
                if (srcPort == PHOTON_PORT || dstPort == PHOTON_PORT) {
                    // Extract UDP payload
                    val payloadLength = udpLength - 8 // minus UDP header
                    if (payloadLength > 0 && buffer.remaining() >= payloadLength) {
                        val payload = ByteArray(payloadLength)
                        buffer.get(payload)
                        
                        // Parse Photon packet
                        eventDispatcher.parsePayload(payload)
                    }
                }

                buffer.clear()

            } catch (e: Exception) {
                if (!shouldStop.get()) {
                    Log.w(TAG, "Packet processing error: ${e.message}")
                }
            }
        }

        Log.i(TAG, "Packet processing stopped")
    }

    /**
     * Stop the VPN and cleanup
     */
    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN...")
        
        shouldStop.set(true)
        isRunning = false

        try {
            workerThread?.interrupt()
            workerThread?.join(1000)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping worker thread: ${e.message}")
        }

        try {
            vpnInput?.close()
            vpnOutput?.close()
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }

        vpnInput = null
        vpnOutput = null
        vpnInterface = null
        workerThread = null

        Log.i(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN Service destroying...")
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopVpn()
        super.onRevoke()
    }

    /**
     * Get entity store for radar display
     */
    fun getEntityStore(): EntityStore = entityStore

    /**
     * Get current statistics
     */
    fun getStats(): VpnStats {
        return VpnStats(
            isRunning = isRunning,
            entityCount = entityStore.getEntityCount(),
            localPlayerId = entityStore.getLocalPlayerId(),
            currentZone = entityStore.getCurrentZone()
        )
    }
}

/**
 * VPN statistics data class
 */
data class VpnStats(
    val isRunning: Boolean,
    val entityCount: Int,
    val localPlayerId: Int?,
    val currentZone: String
)
