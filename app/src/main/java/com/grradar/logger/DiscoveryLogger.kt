package com.grradar.logger

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * DiscoveryLogger — Async param logger for Photon events
 *
 * PURPOSE:
 *   Logs all Photon event parameters to a file for analysis.
 *   Use this to verify param keys after each Albion patch.
 *
 * OUTPUT:
 *   /sdcard/Android/data/com.grradar/files/discovery_log.txt
 *
 * USAGE:
 *   DiscoveryLogger.start(context)  // Call once in VPN service onCreate
 *   DiscoveryLogger.i("Message")    // Info log
 *   DiscoveryLogger.d("Message")    // Debug log
 *   DiscoveryLogger.logPhotonEvent("NewCharacter", params)  // Log full event
 *   DiscoveryLogger.stop()          // Call in VPN service onDestroy
 *
 * FEATURES:
 *   - Async writing (doesn't block packet processing)
 *   - Auto-rotates log when size exceeds MAX_LOG_SIZE
 *   - Thread-safe queue-based implementation
 *   - Detailed type information for all parameters
 */
object DiscoveryLogger {

    private const val TAG = "DiscoveryLogger"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024L // 5MB max log size

    // File handles
    private var logFile: File? = null
    private var writer: PrintWriter? = null

    // Async queue for log messages
    private val queue = ConcurrentLinkedQueue<String>()

    // Counters
    private val eventCounter = AtomicLong(0)

    // Threading
    private var logThread: Thread? = null
    private var running = false

    // Date formatter for timestamps
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Start the logger - call once in VPN service onCreate
     *
     * @param context Application context for accessing external files dir
     */
    fun start(context: Context) {
        if (running) {
            Log.w(TAG, "DiscoveryLogger already running")
            return
        }

        try {
            // Create log directory if needed
            val dir = File(context.getExternalFilesDir(null), "").apply { mkdirs() }

            // Create or rotate log file
            logFile = File(dir, "discovery_log.txt").apply {
                if (exists() && length() > MAX_LOG_SIZE) {
                    Log.i(TAG, "Rotating log file (size: ${length()})")
                    delete()
                }
            }

            // Open writer in append mode
            writer = PrintWriter(FileWriter(logFile, true), true)
            running = true

            // Start async writer thread
            logThread = Thread({
                while (running || queue.isNotEmpty()) {
                    val line = queue.poll()
                    if (line != null) {
                        try {
                            writer?.println(line)
                        } catch (e: Exception) {
                            Log.e(TAG, "Write error: ${e.message}")
                        }
                    } else {
                        // Sleep briefly when queue is empty
                        Thread.sleep(50)
                    }
                }
                Log.d(TAG, "Logger thread exiting")
            }, "DiscoveryLogger-Writer").apply {
                priority = Thread.MIN_PRIORITY
                start()
            }

            i("DiscoveryLogger started")
            Log.i(TAG, "Log file: ${logFile?.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logger: ${e.message}", e)
        }
    }

    /**
     * Stop the logger - call in VPN service onDestroy
     */
    fun stop() {
        Log.i(TAG, "Stopping DiscoveryLogger")
        running = false

        // Wait for writer thread to finish
        logThread?.join(2000)

        // Flush and close writer
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing writer: ${e.message}")
        }
        writer = null
        logFile = null

        Log.i(TAG, "DiscoveryLogger stopped")
    }

    /**
     * Internal log method
     */
    private fun log(level: String, msg: String, eventNum: Long = eventCounter.get()) {
        val ts = dateFormat.format(Date())
        val line = """
════════════════════════════════════════════════════════════
[$ts] Event #$eventNum
════════════════════════════════════════════════════════════
Event: $level
Description: $msg
""".trimIndent()
        queue.offer(line)
    }

    /**
     * Info level log
     */
    fun i(msg: String) {
        eventCounter.incrementAndGet()
        log("INFO", msg, eventCounter.get())
    }

    /**
     * Debug level log
     */
    fun d(msg: String) {
        eventCounter.incrementAndGet()
        log("DEBUG", msg, eventCounter.get())
    }

    /**
     * Warning level log
     */
    fun w(msg: String) {
        eventCounter.incrementAndGet()
        log("WARN", msg, eventCounter.get())
    }

    /**
     * Error level log with optional throwable
     */
    fun e(msg: String, t: Throwable? = null) {
        eventCounter.incrementAndGet()
        val fullMsg = if (t != null) {
            "$msg\n${Log.getStackTraceString(t)}"
        } else {
            msg
        }
        log("ERROR", fullMsg, eventCounter.get())
    }

    /**
     * Verbose level - skip for performance in production
     */
    fun v(msg: String) {
        // Uncomment for debug builds:
        // eventCounter.incrementAndGet()
        // log("VERBOSE", msg, eventCounter.get())
    }

    /**
     * Log a complete Photon event with all parameters
     * This is the primary method for discovering param keys
     *
     * @param eventName The event name string (e.g., "NewCharacter", "NewMob")
     * @param params Map of parameter keys to values
     */
    fun logPhotonEvent(eventName: String, params: Map<Int, Any?>) {
        val sb = StringBuilder()
        sb.append("PHOTON EVENT: $eventName\n")
        sb.append("PARAMETER COUNT: ${params.size}\n")
        sb.append("PARAMETERS:\n")

        // Sort by key for easier reading
        params.entries.sortedBy { it.key }.forEach { (key, value) ->
            val typeStr = formatValue(value)
            sb.append("  Key[$key] = $typeStr\n")
        }

        d(sb.toString())
    }

    /**
     * Format a parameter value with type information
     */
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"

            is String -> {
                val escaped = value.take(100) + if (value.length > 100) "..." else ""
                "String(len=${value.length}): \"$escaped\""
            }

            is Int -> "Int: $value (0x${value.toString(16)})"

            is Long -> "Long: $value"

            is Float -> {
                val formatted = String.format("%.2f", value)
                "Float: $formatted"
            }

            is Double -> {
                val formatted = String.format("%.4f", value)
                "Double: $formatted"
            }

            is Boolean -> "Boolean: $value"

            is Byte -> "Byte: $value (0x${value.toString(16)})"

            is Short -> "Short: $value"

            is ByteArray -> {
                val hex = value.take(32).joinToString("") {
                    "%02X".format(it)
                }
                "ByteArray(len=${value.size}): $hex${if (value.size > 32) "..." else ""}"
            }

            is IntArray -> {
                val preview = value.take(8).toList()
                "IntArray(len=${value.size}): $preview${if (value.size > 8) "..." else ""}"
            }

            is FloatArray -> {
                val preview = value.take(4).map { String.format("%.1f", it) }
                "FloatArray(len=${value.size}): $preview${if (value.size > 4) "..." else ""}"
            }

            is List<*> -> {
                "List(len=${value.size}): ${value.take(3)}${if (value.size > 3) "..." else ""}"
            }

            is Map<*, *> -> {
                "Map(len=${value.size}): keys=${value.keys.take(5)}"
            }

            else -> "${value::class.simpleName ?: "Unknown"}: $value"
        }
    }

    /**
     * Log raw packet hex dump for debugging
     */
    fun logPacketHex(data: ByteArray, offset: Int, length: Int, note: String = "") {
        if (length <= 0) return

        val sb = StringBuilder()
        sb.append("PACKET HEX DUMP ($length bytes)$note\n")

        // Show first 256 bytes as hex
        val bytesToShow = minOf(length, 256)
        val hex = StringBuilder()
        val ascii = StringBuilder()

        for (i in 0 until bytesToShow) {
            val b = data[offset + i]
            hex.append("%02X ".format(b))

            // Show printable ASCII
            val c = (b.toInt() and 0xFF).toChar()
            ascii.append(if (c.isLetterOrDigit() || c in "!@#\$%^&*()_+-=[]{}|;':\",./<>? ") c else '.')

            // New line every 16 bytes
            if ((i + 1) % 16 == 0) {
                sb.append(hex.toString().padEnd(48))
                sb.append(" | ")
                sb.append(ascii)
                sb.append("\n")
                hex.clear()
                ascii.clear()
            }
        }

        // Remaining bytes
        if (hex.isNotEmpty()) {
            sb.append(hex.toString().padEnd(48))
            sb.append(" | ")
            sb.append(ascii)
            sb.append("\n")
        }

        if (length > 256) {
            sb.append("... (${length - 256} more bytes)\n")
        }

        d(sb.toString())
    }

    /**
     * Get current log file path
     */
    fun getLogPath(): String? = logFile?.absolutePath

    /**
     * Get current event count
     */
    fun getEventCount(): Long = eventCounter.get()
}
