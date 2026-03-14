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
 * AlbionVpnService — VPN with full packet relay
 *
 * Architecture:
 *   TUN read loop → parse raw IP packets from Albion only
 *     UDP port 5056  → NIO DatagramChannel (protected) + Photon parser
 *     TCP (any port) → NIO SocketChannel (protected) relay (login/HTTPS)
 *   Selector loop    → incoming responses → write back to TUN
 */
class AlbionVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.grradar.vpn.START"
        const val ACTION_STOP = "com.grradar.vpn.STOP"
        const val BROADCAST_VPN_STATUS = "com.grradar.VPN_STATUS"

        private const val TAG = "AlbionVpnService"
        private const val CHANNEL_ID = "grradar_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // VPN Configuration
        private const val MTU = 32767
        private const val TUN_IP = "10.8.0.2"
        private const val TUN_PREFIX = 32
        private const val ALBION_PACKAGE = "com.albiononline"
        private const val ALBION_PORT = 5056

        // Statistics
        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)

        @Volatile
        var entityCount: Int = 0
            private set

        // Shared entity store for overlay access
        @Volatile
        private var sharedEntityStore: EntityStore? = null
        
        fun getSharedEntityStore(): EntityStore? = sharedEntityStore

        fun incrementEntityCount() {
            entityCount++
        }

        fun resetEntityCount() {
            entityCount = 0
        }

        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var selector: Selector? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udpMap = ConcurrentHashMap<Int, UdpEntry>()
    private val tcpMap = ConcurrentHashMap<Int, TcpEntry>()

    // Parser components
    private lateinit var entityStore: EntityStore
    private lateinit var idMapRepo: IdMapRepository
    private lateinit var eventDispatcher: EventDispatcher

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopSelf()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize parser components
        entityStore = EntityStore()
        idMapRepo = IdMapRepository.getInstance()
        eventDispatcher = EventDispatcher(entityStore, idMapRepo)
        
        // Share entity store with overlay
        sharedEntityStore = entityStore

        DiscoveryLogger.i("AlbionVpnService created")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                scope.launch { runCapture() }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()

        runCatching { selector?.close() }
        udpMap.values.forEach { runCatching { it.channel.close() } }
        udpMap.clear()
        tcpMap.values.forEach { it.close() }
        tcpMap.clear()
        runCatching { tunPfd?.close() }
        tunPfd = null

        packetCount.set(0)
        albionCount.set(0)
        entityCount = 0
        isRunning = false
        sharedEntityStore = null

        runCatching { unregisterReceiver(stopReceiver) }

        DiscoveryLogger.i("AlbionVpnService destroyed")

        super.onDestroy()
    }

    override fun onRevoke() {
        DiscoveryLogger.w("VPN permission revoked")
        stopSelf()
    }

    // ─── Notification ─────────────────────────────────────────────────────────

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
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ─── TUN Setup ────────────────────────────────────────────────────────────

    private suspend fun runCapture() {
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
            DiscoveryLogger.e("TUN establish failed")
            broadcastStatus(false)
            stopSelf()
            return
        }

        tunPfd = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        selector = Selector.open()
        isRunning = true

        broadcastStatus(true)
        DiscoveryLogger.i("VPN established successfully")
        DiscoveryLogger.i("TUN IP: $TUN_IP/$TUN_PREFIX")
        DiscoveryLogger.i("MTU: $MTU")
        DiscoveryLogger.i("Allowed app: $ALBION_PACKAGE")
        DiscoveryLogger.i("Capturing Albion port $ALBION_PORT")

        updateNotification("Capturing port $ALBION_PORT")

        scope.launch(Dispatchers.IO) { runSelectorLoop() }
        runTunReadLoop(pfd)
    }

    private fun broadcastStatus(running: Boolean) {
        val intent = Intent(BROADCAST_VPN_STATUS).apply {
            putExtra("running", running)
        }
        sendBroadcast(intent)
    }

    // ─── TUN Read Loop ─────────────────────────────────────────────────────────

    private suspend fun runTunReadLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(MTU)

            try {
                while (isActive) {
                    val len = input.read(buf).takeIf { it > 20 } ?: continue
                    packetCount.incrementAndGet()

                    // IPv4 only
                    if ((buf[0].toInt() and 0xF0) != 0x40) continue

                    val ihl = (buf[0].toInt() and 0x0F) * 4
                    val proto = buf[9].toInt() and 0xFF

                    if (len < ihl + 8) continue

                    val srcIp = buf.copyOfRange(12, 16)
                    val dstIp = buf.copyOfRange(16, 20)

                    when (proto) {
                        17 -> handleUdp(buf, len, ihl, srcIp, dstIp)
                        6 -> handleTcp(buf, len, ihl, srcIp, dstIp)
                    }
                }
            } catch (e: CancellationException) {
                DiscoveryLogger.d("TUN read cancelled")
            } catch (e: Exception) {
                DiscoveryLogger.e("TUN read error: ${e.message}")
            }
        }

    // ─── UDP Handler (NIO DatagramChannel) ────────────────────────────────────

    private fun handleUdp(
        buf: ByteArray, len: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ) {
        val srcPort = buf.u16(ihl)
        val dstPort = buf.u16(ihl + 2)
        val payOff = ihl + 8
        val payLen = len - payOff

        if (payLen <= 0) return

        // Parse Photon on Albion UDP traffic
        if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
            albionCount.incrementAndGet()
            parsePhoton(buf, payOff, payLen)
            entityCount = entityStore.getEntityCount()
            updateNotification("PKT ${packetCount.get()} | ALB ${albionCount.get()} | ENT $entityCount")
        }

        // Create or reuse protected DatagramChannel keyed by source port
        val entry = udpMap.getOrPut(srcPort) {
            runCatching {
                val ch = DatagramChannel.open()
                protect(ch.socket()) // protect BEFORE connect
                ch.configureBlocking(false)
                ch.connect(InetSocketAddress(
                    java.net.InetAddress.getByAddress(dstIp), dstPort
                ))
                val e = UdpEntry(ch, srcIp.copyOf(), srcPort, dstPort)
                selector?.wakeup()
                ch.register(selector, SelectionKey.OP_READ, e)
                DiscoveryLogger.d("UDP channel created: $srcPort -> $dstPort")
                e
            }.getOrElse { return }
        }

        // Forward payload to real server
        runCatching {
            entry.channel.write(ByteBuffer.wrap(buf, payOff, payLen))
        }.onFailure {
            DiscoveryLogger.w("UDP write failed: ${it.message}")
            udpMap.remove(srcPort)?.channel?.runCatching { close() }
        }
    }

    // ─── TCP Handler (NIO SocketChannel) ──────────────────────────────────────

    private fun handleTcp(
        buf: ByteArray, len: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ) {
        if (len < ihl + 20) return

        val srcPort = buf.u16(ihl)
        val dstPort = buf.u16(ihl + 2)
        val tcpOff = ((buf[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val flags = buf[ihl + 13].toInt() and 0xFF
        val isSyn = flags and 0x02 != 0
        val isFin = flags and 0x01 != 0
        val isRst = flags and 0x04 != 0
        val payOff = ihl + tcpOff
        val payLen = (len - payOff).coerceAtLeast(0)

        when {
            isRst || isFin -> {
                tcpMap.remove(srcPort)?.close()
            }
            isSyn -> {
                scope.launch(Dispatchers.IO) {
                    openTcpChannel(srcIp, srcPort, dstIp, dstPort)
                }
            }
            payLen > 0 -> {
                val entry = tcpMap[srcPort] ?: return
                runCatching {
                    val d = ByteBuffer.wrap(buf, payOff, payLen)
                    while (d.hasRemaining()) entry.channel.write(d)
                }.onFailure {
                    tcpMap.remove(srcPort)?.close()
                }
            }
        }
    }

    private fun openTcpChannel(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int
    ) {
        runCatching {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            protect(ch.socket()) // protect BEFORE connect

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

    // ─── NIO Selector Loop ────────────────────────────────────────────────────

    private fun runSelectorLoop() {
        val sel = selector ?: return
        val buf = ByteBuffer.allocate(MTU)

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
            DiscoveryLogger.e("Selector error: ${e.message}")
        }
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

    private fun readUdp(entry: UdpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)
            if (n <= 0) return

            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }

            // Server→client Albion packets: parse Photon here
            if (entry.dstPort == ALBION_PORT && n >= 12) {
                albionCount.incrementAndGet()
                parsePhoton(payload, 0, n)
                entityCount = entityStore.getEntityCount()
                updateNotification("PKT ${packetCount.get()} | ALB ${albionCount.get()} | ENT $entityCount")
            }

            // Write response back to TUN
            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return

            val pkt = buildUdpPacket(
                serverIp, entry.srcIp,
                entry.dstPort, entry.srcPort, payload
            )

            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            DiscoveryLogger.v("UDP read: ${e.message}")
        }
    }

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
            DiscoveryLogger.v("TCP read: ${e.message}")
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    // ─── Packet Builders ──────────────────────────────────────────────────────

    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        b.put(0x45.toByte())
        b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0)
        b.putShort(0x4000.toShort())
        b.put(64)
        b.put(17) // UDP
        val csumPos = b.position()
        b.putShort(0)
        b.put(srcIp)
        b.put(dstIp)

        val arr = b.array()
        val cs = ipChecksum(arr, 0, 20)
        arr[csumPos] = (cs shr 8).toByte()
        arr[csumPos + 1] = cs.toByte()

        // UDP header
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort())
        b.putShort(0) // checksum disabled
        b.put(payload)

        return arr
    }

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
        b.put(6) // TCP
        val ipCsumPos = b.position()
        b.putShort(0)
        b.put(srcIp)
        b.put(dstIp)

        val arr = b.array()
        val ipCs = ipChecksum(arr, 0, 20)
        arr[ipCsumPos] = (ipCs shr 8).toByte()
        arr[ipCsumPos + 1] = ipCs.toByte()

        // TCP header (PSH+ACK)
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putInt(1) // seq
        b.putInt(1) // ack
        b.put(0x50.toByte()) // data offset = 5
        b.put(0x18.toByte()) // PSH+ACK
        b.putShort(65535.toShort()) // window
        b.putShort(0) // checksum
        b.putShort(0) // urgent
        b.put(payload)

        return arr
    }

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

    // ─── Photon Parser Integration ─────────────────────────────────────────────

    private fun parsePhoton(buf: ByteArray, off: Int, len: Int) {
        try {
            // Extract payload from buffer at offset
            val payload = if (off == 0 && len == buf.size) {
                buf
            } else {
                buf.copyOfRange(off, off + len)
            }
            
            // Use EventDispatcher to parse
            eventDispatcher.parsePayload(payload)
            
        } catch (e: Exception) {
            DiscoveryLogger.e("Photon parse error: ${e.message}")
        }
    }

    // ─── Data Classes ─────────────────────────────────────────────────────────

    private data class UdpEntry(
        val channel: DatagramChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int
    )

    private inner class TcpEntry(
        val channel: SocketChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int
    ) {
        fun close() = runCatching { channel.close() }
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

// Extension function for reading unsigned 16-bit
private fun ByteArray.u16(off: Int): Int =
    ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

/**
 * VPN statistics data class
 */
data class VpnStats(
    val isRunning: Boolean,
    val entityCount: Int,
    val localPlayerId: Int?,
    val currentZone: String
)
