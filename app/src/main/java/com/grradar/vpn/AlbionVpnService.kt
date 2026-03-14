package com.grradar.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.grradar.MainActivity
import com.grradar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer

class AlbionVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.grradar.vpn.START"
        const val ACTION_STOP = "com.grradar.vpn.STOP"
        const val ACTION_CONNECT = "com.grradar.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.grradar.vpn.DISCONNECT"

        private const val TAG = "AlbionVpnService"
        private const val CHANNEL_ID = "grradar_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // VPN Configuration
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_PREFIX = 32
        private const val TUN_ROUTE = "0.0.0.0"
        private const val TUN_ROUTE_PREFIX = 0
        private const val MTU = 32767
        private const val ALBION_PACKAGE = "com.albiononline"
        private const val PHOTON_PORT = 5056

        // Packet constants
        private const val IP_HEADER_MIN_LENGTH = 20
        private const val UDP_HEADER_LENGTH = 8
        private const val PROTOCOL_UDP = 17

        // Statistics (static for access from overlay)
        @Volatile
        var totalPacketCount: Long = 0
            private set

        @Volatile
        var albionPacketCount: Long = 0
            private set

        @Volatile
        var entityCount: Int = 0
            private set

        fun incrementEntityCount() {
            entityCount++
        }

        fun resetEntityCount() {
            entityCount = 0
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private var isRunning = false

    // CRITICAL: WakeLock to prevent Android from throttling TUN thread
    private var wakeLock: PowerManager.WakeLock? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_CONNECT -> {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    acquireWakeLock()
                    startVpn()
                }
            }
            ACTION_STOP, ACTION_DISCONNECT -> {
                stopVpn()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GRRadar VPN Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN service for capturing game traffic"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GRRadar VPN Active")
            .setContentText("Capturing Albion Online traffic...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GRRadar::TunWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max, will re-acquire
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
        Log.i(TAG, "WakeLock released")
    }

    private fun startVpn() {
        try {
            // Reset statistics
            totalPacketCount = 0
            albionPacketCount = 0
            entityCount = 0

            // Configure VPN interface - only allow Albion Online
            val builder = Builder()
                .setSession("GRRadar")
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute(TUN_ROUTE, TUN_ROUTE_PREFIX)
                .setMtu(MTU)
                .addAllowedApplication(ALBION_PACKAGE)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface - user may have denied permission")
                return
            }

            isRunning = true
            Log.i(TAG, "VPN interface established successfully")
            Log.i(TAG, "TUN Address: $TUN_ADDRESS/$TUN_PREFIX")
            Log.i(TAG, "MTU: $MTU")
            Log.i(TAG, "Allowed app: $ALBION_PACKAGE")

            // Start packet capture loop
            startPacketCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun startPacketCapture() {
        vpnJob = coroutineScope.launch {
            val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(MTU)

            Log.i(TAG, "Packet capture loop started - waiting for Albion traffic...")
            Log.i(TAG, "Make sure Albion Online is installed and running")

            while (isActive && isRunning) {
                try {
                    // Read packet from TUN interface
                    val length = vpnInput.read(buffer.array())
                    if (length > 0) {
                        totalPacketCount++
                        buffer.limit(length)
                        processPacket(buffer)
                        buffer.clear()

                        // Re-acquire wake lock periodically
                        wakeLock?.let {
                            if (!it.isHeld) {
                                it.acquire(10 * 60 * 1000L)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error reading packet: ${e.message}")
                    }
                    break
                }
            }

            Log.i(TAG, "Packet capture loop stopped")
        }
    }

    private fun processPacket(buffer: ByteBuffer) {
        try {
            // Parse IP header
            if (buffer.remaining() < IP_HEADER_MIN_LENGTH) {
                return
            }

            val versionAndHeaderLength = buffer.get(0).toInt() and 0xFF
            val version = (versionAndHeaderLength shr 4) and 0x0F

            // Only process IPv4
            if (version != 4) {
                return
            }

            val headerLength = (versionAndHeaderLength and 0x0F) * 4
            val protocol = buffer.get(9).toInt() and 0xFF

            // Only process UDP packets
            if (protocol != PROTOCOL_UDP) {
                return
            }

            // Parse UDP header
            val udpOffset = headerLength
            if (buffer.remaining() < udpOffset + UDP_HEADER_LENGTH) {
                return
            }

            val sourcePort = ((buffer.get(udpOffset).toInt() and 0xFF) shl 8) or
                    (buffer.get(udpOffset + 1).toInt() and 0xFF)
            val destPort = ((buffer.get(udpOffset + 2).toInt() and 0xFF) shl 8) or
                    (buffer.get(udpOffset + 3).toInt() and 0xFF)

            // Check if this is Photon traffic (port 5056)
            if (sourcePort == PHOTON_PORT || destPort == PHOTON_PORT) {
                albionPacketCount++
                
                val payloadOffset = udpOffset + UDP_HEADER_LENGTH
                val payloadLength = buffer.remaining() - payloadOffset

                if (payloadLength > 0) {
                    val payload = ByteArray(payloadLength)
                    buffer.position(payloadOffset)
                    buffer.get(payload)

                    // Log every 10 Albion packets
                    if (albionPacketCount % 10 == 0L) {
                        Log.d(TAG, "PC: $totalPacketCount | PCA: $albionPacketCount | Entity: $entityCount")
                        Log.d(TAG, "Photon payload size: $payloadLength bytes, srcPort: $sourcePort, dstPort: $destPort")
                    }

                    // TODO: Pass to PhotonParser (Step 4)
                    // PhotonParser.parse(payload)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet: ${e.message}")
        }
    }

    private fun stopVpn() {
        isRunning = false

        vpnJob?.cancel()
        vpnJob = null

        vpnInterface?.close()
        vpnInterface = null

        releaseWakeLock()

        Log.i(TAG, "VPN stopped")
        Log.i(TAG, "Final stats - PC: $totalPacketCount | PCA: $albionPacketCount | Entity: $entityCount")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by user")
        stopVpn()
        super.onRevoke()
    }
}
