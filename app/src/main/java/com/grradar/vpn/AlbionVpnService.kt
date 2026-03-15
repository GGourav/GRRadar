package com.grradar.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.grradar.MainActivity
import com.grradar.R
import com.grradar.data.EntityStore
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import com.grradar.parser.EventDispatcher
import com.grradar.parser.PhotonParser
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AlbionVpnService — VPN with full packet relay and Photon parsing
 *
 * ARCHITECTURE:
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │                        Albion Online App                        │
 *   └─────────────────────────────────────────────────────────────────┘
 *                                    │
 *                                    ▼ UDP/TCP packets
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │                      TUN Interface (10.8.0.2)                   │
 *   │                     addAllowedApplication("com.albiononline")   │
 *   └─────────────────────────────────────────────────────────────────┘
 *                                    │
 *                                    ▼ Raw IP packets
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │                     TUN Read Loop (coroutine)                   │
 *   │                          ↓ Parse IP header                      │
 *   │              ┌────────────────┴────────────────┐                │
 *   │              ▼                                 ▼                │
 *   │         UDP Handler                       TCP Handler           │
 *   │     (port 5056 = Photon)              (login, HTTPS)            │
 *   │              │                                 │                │
 *   │              ▼                                 ▼                │
 *   │      Photon Parser                      NIO SocketChannel       │
 *   │              │                                 │                │
 *   │              ▼                                 │                │
 *   │     EventDispatcher                          │                 │
 *   │              │                                 │                │
 *   │              ▼                                 │                │
 *   │       EntityStore                             │                │
 *   └─────────────────────────────────────────────────────────────────┘
 *                                    │
 *                                    ▼ NIO Selector Loop
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │                    Protected NIO Channels                       │
 *   │                   (protect() prevents VPN loop)                 │
 *   └─────────────────────────────────────────────────────────────────┘
 *                                    │
 *                                    ▼
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │                     Real Network (Internet)                     │
 *   │                    Albion Game Servers                          │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * VPN CONFIGURATION:
 *   - TUN IP: 10.8.0.2/32
 *   - MTU: 32767 (large for game packets)
 *   - Allowed app: com.albiononline only
 *   - Target port: UDP 5056 (Photon)
 *
 * FEATURES:
 *   - Full packet relay (game traffic passes through unchanged)
 *   - Photon Protocol 16 parsing for port 5056
 *   - NIO channels with Selector for efficient I/O
 *   - Coroutine-based async operation
 *   - Foreground service with notification
 *   - Statistics tracking
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG = "AlbionVpnService"

        // Intent actions
        const val ACTION_START = "com.grradar.vpn.START"
        const val ACTION_STOP = "com.grradar.vpn.STOP"
        const val BROADCAST_VPN_STATUS = "com.grradar.VPN_STATUS"

        // Notification
        private const val CHANNEL_ID = "grradar_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // VPN Configuration
        private const val MTU = 32767
        private const val TUN_IP = "10.8.0.2"
        private const val TUN_PREFIX = 32
        private const val ALBION_PACKAGE = "com.albiononline"
        private const val ALBION_PORT = 5056

        // Statistics (accessible from outside for UI)
        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)

        @Volatile
        var entityCount: Int = 0
            private set

        /**
         * Check if VPN is currently running
         */
        @Volatile
        var isRunning = false
            private set
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var selector: Selector? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Channel maps keyed by source port
    private val udpMap = ConcurrentHashMap<Int, UdpEntry>()
    private val tcpMap = ConcurrentHashMap<Int, TcpEntry>()

    // Broadcast receiver for stop command
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                Log.i(TAG, "Received stop broadcast")
                stopSelf()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        DiscoveryLogger.start(this)
        DiscoveryLogger.i("AlbionVpnService created")

        // Load param key mappings
        IdMapRepository.load(this)

        // Register stop receiver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action received")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "Start action received")
                startForeground(NOTIFICATION_ID, createNotification("Starting VPN..."))
                scope.launch { runCapture() }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        isRunning = false

        // Cancel all coroutines
        scope.cancel()

        // Close all channels
        runCatching { selector?.close() }
        udpMap.values.forEach { runCatching { it.channel.close() } }
        udpMap.clear()
        tcpMap.values.forEach { it.close() }
        tcpMap.clear()

        // Close TUN
        runCatching { tunPfd?.close() }
        tunPfd = null
        tunOut = null

        // Reset stats
        packetCount.set(0)
        albionCount.set(0)
        entityCount = 0

        // Unregister receiver
        runCatching { unregisterReceiver(stopReceiver) }

        DiscoveryLogger.i("AlbionVpnService destroyed")
        DiscoveryLogger.stop()

        super.onDestroy()
    }

    override fun onRevoke() {
        DiscoveryLogger.w("VPN permission revoked by system")
        Log.w(TAG, "VPN permission revoked")
        stopSelf()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GRRadar VPN Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN service for capturing Albion Online game traffic"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GRRadar VPN Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VPN SETUP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Main VPN capture loop
     * Sets up TUN interface and starts packet processing
     */
    private suspend fun runCapture() {
        DiscoveryLogger.i("Starting VPN capture...")

        // Build VPN interface
        val pfd = withContext(Dispatchers.IO) {
            runCatching {
                Builder()
                    .setSession("GRRadar")
                    .addAddress(TUN_IP, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAllowedApplication(ALBION_PACKAGE)
                    .establish()
            }.getOrNull()
        }

        if (pfd == null) {
            DiscoveryLogger.e("Failed to establish VPN interface")
            Log.e(TAG, "VPN establish failed")
            broadcastStatus(false)
            stopSelf()
            return
        }

        tunPfd = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        selector = Selector.open()
        isRunning = true

        broadcastStatus(true)

        DiscoveryLogger.i("═══════════════════════════════════════════════════════")
        DiscoveryLogger.i("VPN ESTABLISHED SUCCESSFULLY")
        DiscoveryLogger.i("TUN IP: $TUN_IP/$TUN_PREFIX")
        DiscoveryLogger.i("MTU: $MTU")
        DiscoveryLogger.i("Allowed App: $ALBION_PACKAGE")
        DiscoveryLogger.i("Target Port: $ALBION_PORT (Photon)")
        DiscoveryLogger.i("═══════════════════════════════════════════════════════")

        Log.i(TAG, "VPN established: $TUN_IP/$TUN_PREFIX, MTU=$MTU")
        updateNotification("Capturing port $ALBION_PORT")

        // Start selector loop in separate coroutine
        scope.launch(Dispatchers.IO) { runSelectorLoop() }

        // Run TUN read loop (blocks until cancelled)
        runTunReadLoop(pfd)
    }

    private fun broadcastStatus(running: Boolean) {
        val intent = Intent(BROADCAST_VPN_STATUS).apply {
            putExtra("running", running)
        }
        sendBroadcast(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TUN READ LOOP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Read packets from TUN interface and dispatch to handlers
     */
    private suspend fun runTunReadLoop(pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(MTU)

        DiscoveryLogger.i("TUN read loop started")

        try {
            while (isActive) {
                val len = input.read(buf)
                if (len <= 20) continue // Minimum IP header size

                packetCount.incrementAndGet()

                // Check IPv4
                val version = (buf[0].toInt() and 0xF0) shr 4
                if (version != 4) continue // Skip non-IPv4

                // Parse IP header
                val ihl = (buf[0].toInt() and 0x0F) * 4
                val proto = buf[9].toInt() and 0xFF

                if (len < ihl + 8) continue // Not enough for transport header

                val srcIp = buf.copyOfRange(12, 16)
                val dstIp = buf.copyOfRange(16, 20)

                when (proto) {
                    17 -> handleUdp(buf, len, ihl, srcIp, dstIp)  // UDP
                    6 -> handleTcp(buf, len, ihl, srcIp, dstIp)   // TCP
                }
            }
        } catch (e: CancellationException) {
            DiscoveryLogger.d("TUN read loop cancelled")
        } catch (e: Exception) {
            DiscoveryLogger.e("TUN read loop error: ${e.message}", e)
        }

        DiscoveryLogger.i("TUN read loop exited")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UDP HANDLER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle UDP packet from TUN
     */
    private fun handleUdp(
        buf: ByteArray, len: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ) {
        val srcPort = buf.u16(ihl)
        val dstPort = buf.u16(ihl + 2)
        val payOff = ihl + 8
        val payLen = len - payOff

        if (payLen <= 0) return

        // ═══════════════════════════════════════════════════════════════════════
        // PARSE PHOTON ON ALBION UDP TRAFFIC
        // ═══════════════════════════════════════════════════════════════════════
        if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
            albionCount.incrementAndGet()
            parsePhoton(buf, payOff, payLen)
            entityCount = EntityStore.entityCount
            updateNotification("PKT ${packetCount.get()} | ALB ${albionCount.get()} | ENT $entityCount")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // CREATE OR REUSE PROTECTED DATAGRAM CHANNEL
        // ═══════════════════════════════════════════════════════════════════════
        val entry = udpMap.getOrPut(srcPort) {
            runCatching {
                val ch = DatagramChannel.open()

                // CRITICAL: Protect socket BEFORE connecting
                // This prevents packets from going through VPN again
                if (!protect(ch.socket())) {
                    DiscoveryLogger.w("Failed to protect UDP socket")
                    ch.close()
                    return@getOrPut null
                }

                ch.configureBlocking(false)
                ch.connect(InetSocketAddress(
                    java.net.InetAddress.getByAddress(dstIp), dstPort
                ))

                val e = UdpEntry(ch, srcIp.copyOf(), srcPort, dstPort)

                // Register with selector for incoming packets
                selector?.wakeup()
                ch.register(selector, SelectionKey.OP_READ, e)

                DiscoveryLogger.d("UDP channel created: $srcPort -> $dstPort")
                e
            }.getOrNull()
        } ?: return

        // Forward payload to real server
        runCatching {
            entry.channel.write(ByteBuffer.wrap(buf, payOff, payLen))
        }.onFailure {
            DiscoveryLogger.w("UDP write failed: ${it.message}")
            udpMap.remove(srcPort)?.channel?.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TCP HANDLER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle TCP packet from TUN
     */
    private fun handleTcp(
        buf: ByteArray, len: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ) {
        if (len < ihl + 20) return

        val srcPort = buf.u16(ihl)
        val dstPort = buf.u16(ihl + 2)

        // TCP header offset
        val tcpOff = ((buf[ihl + 12].toInt() and 0xF0) shr 4) * 4

        // TCP flags
        val flags = buf[ihl + 13].toInt() and 0xFF
        val isSyn = flags and 0x02 != 0
        val isFin = flags and 0x01 != 0
        val isRst = flags and 0x04 != 0

        val payOff = ihl + tcpOff
        val payLen = (len - payOff).coerceAtLeast(0)

        when {
            // Connection close
            isRst || isFin -> {
                tcpMap.remove(srcPort)?.close()
            }

            // New connection
            isSyn -> {
                scope.launch(Dispatchers.IO) {
                    openTcpChannel(srcIp, srcPort, dstIp, dstPort)
                }
            }

            // Data packet
            payLen > 0 -> {
                val entry = tcpMap[srcPort] ?: return
                runCatching {
                    val d = ByteBuffer.wrap(buf, payOff, payLen)
                    while (d.hasRemaining()) {
                        entry.channel.write(d)
                    }
                }.onFailure {
                    tcpMap.remove(srcPort)?.close()
                }
            }
        }
    }

    /**
     * Open a new TCP channel for connection
     */
    private fun openTcpChannel(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int
    ) {
        runCatching {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)

            // CRITICAL: Protect before connecting
            if (!protect(ch.socket())) {
                DiscoveryLogger.w("Failed to protect TCP socket")
                ch.close()
                return
            }

            val entry = TcpEntry(ch, srcIp.copyOf(), srcPort, dstPort)
            tcpMap[srcPort] = entry

            ch.connect(InetSocketAddress(
                java.net.InetAddress.getByAddress(dstIp), dstPort
            ))

            selector?.wakeup()
            ch.register(selector, SelectionKey.OP_CONNECT, entry)

            DiscoveryLogger.d("TCP channel created: $srcPort -> $dstPort")
        }.onFailure {
            DiscoveryLogger.w("TCP open failed: ${it.message}")
            tcpMap.remove(srcPort)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NIO SELECTOR LOOP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Selector loop for incoming packets from server
     */
    private fun runSelectorLoop() {
        val sel = selector ?: return
        val buf = ByteBuffer.allocate(MTU)

        DiscoveryLogger.d("Selector loop started")

        try {
            while (scope.isActive) {
                if (sel.select(500L) == 0) continue

                val keys = sel.selectedKeys().toSet()
                sel.selectedKeys().clear()

                for (key in keys) {
                    if (!key.isValid) continue

                    when {
                        key.isReadable -> onReadable(key, buf)
                        key.isConnectable -> onConnectable(key)
                    }
                }
            }
        } catch (e: ClosedSelectorException) {
            DiscoveryLogger.d("Selector closed")
        } catch (e: Exception) {
            DiscoveryLogger.e("Selector error: ${e.message}", e)
        }

        DiscoveryLogger.d("Selector loop exited")
    }

    private fun onReadable(key: SelectionKey, buf: ByteBuffer) {
        when (val att = key.attachment()) {
            is UdpEntry -> readUdp(att, buf)
            is TcpEntry -> readTcp(att, buf)
        }
    }

    private fun onConnectable(key: SelectionKey) {
        val entry = key.attachment() as? TcpEntry ?: return
        try {
            if (entry.channel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ)
                DiscoveryLogger.d("TCP connected: ${entry.srcPort}")
            } else {
                tcpMap.remove(entry.srcPort)?.close()
            }
        } catch (e: Exception) {
            DiscoveryLogger.w("TCP connect failed: ${e.message}")
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    /**
     * Read UDP response from server and write to TUN
     */
    private fun readUdp(entry: UdpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)
            if (n <= 0) return

            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }

            // Parse Photon on server->client packets
            if (entry.dstPort == ALBION_PORT && n >= 12) {
                albionCount.incrementAndGet()
                parsePhoton(payload, 0, n)
                entityCount = EntityStore.entityCount
                updateNotification("PKT ${packetCount.get()} | ALB ${albionCount.get()} | ENT $entityCount")
            }

            // Build response packet
            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return

            val pkt = buildUdpPacket(
                serverIp, entry.srcIp,
                entry.dstPort, entry.srcPort, payload
            )

            // Write to TUN
            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            DiscoveryLogger.v("UDP read error: ${e.message}")
        }
    }

    /**
     * Read TCP response from server and write to TUN
     */
    private fun readTcp(entry: TcpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)

            if (n < 0) {
                tcpMap.remove(entry.srcPort)?.close()
                return
            }
            if (n == 0) return

            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }

            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return

            val pkt = buildTcpPacket(
                serverIp, entry.srcIp,
                entry.dstPort, entry.srcPort, payload
            )

            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            DiscoveryLogger.v("TCP read error: ${e.message}")
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PACKET BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build UDP packet with IP header
     */
    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        b.put(0x45.toByte()) // Version 4, IHL 5
        b.put(0) // DSCP, ECN
        b.putShort(ipLen.toShort())
        b.putShort(0) // Identification
        b.putShort(0x4000.toShort()) // Flags, Fragment Offset
        b.put(64) // TTL
        b.put(17) // Protocol: UDP
        val csumPos = b.position()
        b.putShort(0) // Checksum placeholder
        b.put(srcIp)
        b.put(dstIp)

        // Calculate IP checksum
        val arr = b.array()
        val cs = ipChecksum(arr, 0, 20)
        arr[csumPos] = (cs shr 8).toByte()
        arr[csumPos + 1] = cs.toByte()

        // UDP header
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort())
        b.putShort(0) // UDP checksum (disabled)
        b.put(payload)

        return arr
    }

    /**
     * Build TCP packet with IP header
     */
    private fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val ipLen = 20 + tcpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        b.put(0x45.toByte())
        b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0)
        b.putShort(0x4000.toShort())
        b.put(64)
        b.put(6) // Protocol: TCP
        val ipCsumPos = b.position()
        b.putShort(0)
        b.put(srcIp)
        b.put(dstIp)

        val arr = b.array()
        val ipCs = ipChecksum(arr, 0, 20)
        arr[ipCsumPos] = (ipCs shr 8).toByte()
        arr[ipCsumPos + 1] = ipCs.toByte()

        // TCP header (simplified - PSH+ACK)
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putInt(1) // Sequence number
        b.putInt(1) // Acknowledgment number
        b.put(0x50.toByte()) // Data offset: 5
        b.put(0x18.toByte()) // Flags: PSH+ACK
        b.putShort(65535.toShort()) // Window
        b.putShort(0) // Checksum (disabled)
        b.putShort(0) // Urgent pointer
        b.put(payload)

        return arr
    }

    /**
     * Calculate IP header checksum
     */
    private fun ipChecksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len % 2 != 0) {
            sum += (data[off + len - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHOTON PARSER
    // ═══════════════════════════════════════════════════════════════════════════

    private var lastLogTime = 0L

    /**
     * Parse Photon packet and dispatch events
     * This is the main entry point for entity detection
     */
    private fun parsePhoton(buf: ByteArray, off: Int, len: Int) {
        try {
            val result = PhotonParser.parse(buf, off, len)

            // Dispatch all parsed events to handlers
            result.events.forEach { event ->
                EventDispatcher.dispatch(event)
            }

            // Log periodically
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 5000) {
                lastLogTime = now
                if (result.events.isNotEmpty()) {
                    DiscoveryLogger.d("Photon: ${result.events.size} events, entities=${EntityStore.entityCount}")
                }
            }

        } catch (e: Exception) {
            DiscoveryLogger.w("Photon parse error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    private data class UdpEntry(
        val channel: DatagramChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpEntry) return false
            return srcPort == other.srcPort
        }

        override fun hashCode(): Int = srcPort
    }

    private inner class TcpEntry(
        val channel: SocketChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int
    ) {
        fun close() = runCatching { channel.close() }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EXTENSION FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Read unsigned 16-bit big-endian from byte array
 */
private fun ByteArray.u16(off: Int): Int =
    ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)
