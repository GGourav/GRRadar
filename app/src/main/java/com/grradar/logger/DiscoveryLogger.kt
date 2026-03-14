package com.grradar.logger

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Discovery Logger - Async param logger for debugging
 * 
 * Logs all Photon event params to a file for reverse engineering.
 * Use this when id_map.json keys are unknown or have shifted.
 * 
 * Log file location: /sdcard/Android/data/com.grradar/files/discovery_log.txt
 *                     OR context.getExternalFilesDir(null)/discovery_log.txt
 */
class DiscoveryLogger private constructor() {
    
    companion object {
        private const val TAG = "DiscoveryLogger"
        private const val MAX_LOG_SIZE = 10 * 1024 * 1024L // 10MB
        private const val FLUSH_INTERVAL = 1000L // 1 second
        
        @Volatile
        private var instance: DiscoveryLogger? = null
        
        fun getInstance(): DiscoveryLogger {
            return instance ?: synchronized(this) {
                instance ?: DiscoveryLogger().also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<LogEntry>(capacity = Channel.UNLIMITED)
    private var logFile: File? = null
    private var writer: PrintWriter? = null
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val eventCounter = AtomicLong(0)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    @Volatile
    private var isRunning = false
    
    /**
     * Initialize the logger
     */
    fun initialize(context: Context): Boolean {
        return try {
            val filesDir = context.getExternalFilesDir(null) 
                ?: File(context.filesDir, "logs")
            
            if (!filesDir.exists()) {
                filesDir.mkdirs()
            }
            
            logFile = File(filesDir, "discovery_log.txt")
            
            // Rotate if too large
            rotateIfNeeded()
            
            // Start writer coroutine
            startWriter()
            
            Log.i(TAG, "Logger initialized: ${logFile?.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize logger: ${e.message}")
            false
        }
    }
    
    /**
     * Start the async writer coroutine
     */
    private fun startWriter() {
        if (isRunning) return
        
        isRunning = true
        
        scope.launch {
            var lastFlush = System.currentTimeMillis()
            
            while (isRunning) {
                try {
                    // Use select for timeout-based flush
                    val entry = withTimeoutOrNull(FLUSH_INTERVAL) {
                        logChannel.receive()
                    }
                    
                    if (entry != null) {
                        writeEntry(entry)
                    }
                    
                    // Periodic flush
                    if (System.currentTimeMillis() - lastFlush > FLUSH_INTERVAL) {
                        writer?.flush()
                        lastFlush = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    if (e !is TimeoutCancellationException) {
                        Log.w(TAG, "Writer error: ${e.message}")
                    }
                }
            }
            
            // Final flush on shutdown
            writer?.flush()
            writer?.close()
        }
    }
    
    /**
     * Log an event with its parameters
     */
    fun logEvent(eventName: String, params: Map<Int, Any?>) {
        if (!isRunning) return
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            eventName = eventName,
            params = params
        )
        
        logChannel.trySendBlocking(entry)
        eventCounter.incrementAndGet()
    }
    
    /**
     * Log a raw packet (for debugging)
     */
    fun logPacket(packetHex: String, description: String = "") {
        if (!isRunning) return
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            eventName = "RAW_PACKET",
            rawHex = packetHex,
            description = description,
            params = emptyMap()
        )
        
        logChannel.trySendBlocking(entry)
    }
    
    /**
     * Log a discovery note
     */
    fun logNote(note: String) {
        if (!isRunning) return
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            eventName = "NOTE",
            description = note,
            params = emptyMap()
        )
        
        logChannel.trySendBlocking(entry)
    }
    
    /**
     * Write an entry to the log file
     */
    private fun writeEntry(entry: LogEntry) {
        try {
            if (writer == null && logFile != null) {
                writer = PrintWriter(FileWriter(logFile, true))
            }
            
            val timestamp = dateFormat.format(Date(entry.timestamp))
            
            writer?.println("═".repeat(60))
            writer?.println("[$timestamp] Event #${eventCounter.get()}")
            writer?.println("═".repeat(60))
            writer?.println("Event: ${entry.eventName}")
            
            if (entry.description.isNotEmpty()) {
                writer?.println("Description: ${entry.description}")
            }
            
            if (entry.rawHex.isNotEmpty()) {
                writer?.println("Raw Hex: ${entry.rawHex}")
            }
            
            if (entry.params.isNotEmpty()) {
                writer?.println("Parameters:")
                writer?.println(formatParams(entry.params))
            }
            
            writer?.println()
            
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}")
        }
    }
    
    /**
     * Format parameters for logging
     */
    private fun formatParams(params: Map<Int, Any?>): String {
        val sb = StringBuilder()
        
        // Sort by key for easier reading
        params.toSortedMap().forEach { (key, value) ->
            sb.append("  Key $key: ")
            
            when (value) {
                null -> sb.append("null")
                is ByteArray -> sb.append("ByteArray[${value.size}] = ${value.take(16).toHexString()}...")
                is IntArray -> sb.append("IntArray[${value.size}] = ${value.take(16).toList()}")
                is FloatArray -> sb.append("FloatArray[${value.size}] = ${value.take(16).toList()}")
                is Array<*> -> {
                    sb.append("Array[${value.size}]:\n")
                    value.take(5).forEachIndexed { i, item ->
                        sb.append("    [$i] ${formatValue(item)}\n")
                    }
                    if (value.size > 5) {
                        sb.append("    ... and ${value.size - 5} more\n")
                    }
                }
                is Map<*, *> -> {
                    sb.append("Map[${value.size}]:\n")
                    value.entries.take(5).forEach { (k, v) ->
                        sb.append("    $k -> ${formatValue(v)}\n")
                    }
                    if (value.size > 5) {
                        sb.append("    ... and ${value.size - 5} more\n")
                    }
                }
                is List<*> -> {
                    sb.append("List[${value.size}]:\n")
                    value.take(5).forEachIndexed { i, item ->
                        sb.append("    [$i] ${formatValue(item)}\n")
                    }
                    if (value.size > 5) {
                        sb.append("    ... and ${value.size - 5} more\n")
                    }
                }
                else -> sb.append(formatValue(value))
            }
            
            sb.append("\n")
        }
        
        return sb.toString()
    }
    
    /**
     * Format a single value
     */
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Float -> String.format("%.2f", value)
            is Double -> String.format("%.4f", value)
            is ByteArray -> "ByteArray[${value.size}]"
            else -> value.toString()
        }
    }
    
    /**
     * Rotate log file if too large
     */
    private fun rotateIfNeeded() {
        val file = logFile ?: return
        
        if (file.exists() && file.length() > MAX_LOG_SIZE) {
            val rotated = File(file.parent, "discovery_log_old.txt")
            if (rotated.exists()) {
                rotated.delete()
            }
            file.renameTo(rotated)
            Log.i(TAG, "Rotated log file")
        }
    }
    
    /**
     * Stop the logger
     */
    fun stop() {
        isRunning = false
        scope.cancel()
        writer?.flush()
        writer?.close()
        writer = null
    }
    
    /**
     * Get log file path
     */
    fun getLogFilePath(): String? = logFile?.absolutePath
    
    /**
     * Get event count
     */
    fun getEventCount(): Long = eventCounter.get()
    
    /**
     * Clear log file
     */
    fun clearLog() {
        try {
            writer?.close()
            logFile?.delete()
            writer = if (logFile != null) PrintWriter(FileWriter(logFile, false)) else null
            eventCounter.set(0)
            Log.i(TAG, "Log cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log: ${e.message}")
        }
    }
    
    // Extension function for ByteArray to hex string
    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02X".format(it) }
    }
}

/**
 * Log entry data class
 */
private data class LogEntry(
    val timestamp: Long,
    val eventName: String,
    val params: Map<Int, Any?>,
    val rawHex: String = "",
    val description: String = ""
)
