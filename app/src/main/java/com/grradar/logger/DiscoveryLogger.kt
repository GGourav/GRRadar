package com.grradar.discovery

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Logger for discovery and debugging purposes.
 * Writes to both logcat and a file for analysis.
 */
object DiscoveryLogger {
    
    private const val TAG = "DiscoveryLogger"
    private const val LOG_FILE_NAME = "discovery_log.txt"
    
    private var logFile: File? = null
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val logQueue = ConcurrentLinkedQueue<String>()
    private var initialized = false
    private var loggingEnabled = true
    private var maxFileSize = 5 * 1024 * 1024L // 5MB
    
    /**
     * Initialize the logger with application context.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        
        try {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            logFile = File(logDir, LOG_FILE_NAME)
            
            // Check file size and rotate if needed
            if (logFile!!.exists() && logFile!!.length() > maxFileSize) {
                rotateLog()
            }
            
            writer = PrintWriter(FileWriter(logFile, true), true)
            initialized = true
            
            Log.i(TAG, "DiscoveryLogger initialized: ${logFile!!.absolutePath}")
            log("LOGGER", "=== Discovery Logger Initialized ===")
            log("LOGGER", "Log file: ${logFile!!.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DiscoveryLogger", e)
            initialized = false
        }
    }
    
    /**
     * Log a message with a category/tag.
     */
    fun log(category: String, message: String) {
        if (!loggingEnabled) return
        
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$category] $message"
        
        // Always log to logcat
        Log.d("GRRadar_$category", message)
        
        // Queue for file writing
        logQueue.offer(logLine)
        
        // Write to file
        writeToFile(logLine)
    }
    
    /**
     * Log a hex dump of a byte array.
     */
    fun logHexDump(category: String, prefix: String, data: ByteArray, length: Int = data.size) {
        if (!loggingEnabled) return
        
        log(category, "$prefix (${minOf(length, data.size)} bytes)")
        
        val hex = StringBuilder()
        val ascii = StringBuilder()
        var offset = 0
        
        for (i in 0 until minOf(length, data.size)) {
            val b = data[i].toInt() and 0xFF
            hex.append(String.format("%02X ", b))
            ascii.append(if (b in 32..126) b.toChar() else '.')
            
            if ((i + 1) % 16 == 0) {
                log(category, String.format("%04X: %-48s |%s|", offset, hex.toString(), ascii.toString()))
                hex.clear()
                ascii.clear()
                offset += 16
            }
        }
        
        // Remaining bytes
        if (hex.isNotEmpty()) {
            log(category, String.format("%04X: %-48s |%s|", offset, hex.toString(), ascii.toString()))
        }
    }
    
    /**
     * Log packet information.
     */
    fun logPacket(direction: String, protocol: String, length: Int, data: ByteArray? = null) {
        log("PACKET", "$direction $protocol length=$length")
        if (data != null && data.isNotEmpty()) {
            logHexDump("PACKET", "Data:", data, minOf(64, data.size))
        }
    }
    
    /**
     * Log an error with stack trace.
     */
    fun logError(category: String, message: String, error: Throwable? = null) {
        log("ERROR", "[$category] $message")
        if (error != null) {
            log("ERROR", "Exception: ${error.javaClass.simpleName}: ${error.message}")
            error.stackTrace.take(5).forEach { frame ->
                log("ERROR", "  at $frame")
            }
        }
    }
    
    /**
     * Log entity detection.
     */
    fun logEntity(action: String, objectId: Int, typeName: String?, x: Float, y: Float) {
        log("ENTITY", "$action: id=$objectId type='$typeName' pos=($x, $y)")
    }
    
    /**
     * Log parsing event.
     */
    fun logParse(stage: String, details: String) {
        log("PARSE", "[$stage] $details")
    }
    
    /**
     * Write directly to file.
     */
    private fun writeToFile(line: String) {
        try {
            writer?.println(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
    
    /**
     * Rotate log file.
     */
    private fun rotateLog() {
        try {
            val backupFile = File(logFile!!.parent, "${LOG_FILE_NAME}.old")
            if (backupFile.exists()) {
                backupFile.delete()
            }
            logFile!!.renameTo(backupFile)
            Log.i(TAG, "Rotated log file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log", e)
        }
    }
    
    /**
     * Flush pending logs.
     */
    fun flush() {
        try {
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush log", e)
        }
    }
    
    /**
     * Close the logger.
     */
    @Synchronized
    fun close() {
        try {
            writer?.flush()
            writer?.close()
            writer = null
            initialized = false
            Log.i(TAG, "DiscoveryLogger closed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close logger", e)
        }
    }
    
    /**
     * Enable or disable logging.
     */
    fun setEnabled(enabled: Boolean) {
        loggingEnabled = enabled
        if (enabled) {
            log("LOGGER", "Logging enabled")
        }
    }
    
    /**
     * Get log file path.
     */
    fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }
    
    /**
     * Get recent log entries.
     */
    fun getRecentLogs(count: Int = 100): List<String> {
        val result = mutableListOf<String>()
        try {
            logFile?.readLines()?.takeLast(count)?.let {
                result.addAll(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
        }
        return result
    }
    
    /**
     * Clear log file.
     */
    fun clear() {
        try {
            writer?.close()
            logFile?.delete()
            writer = PrintWriter(FileWriter(logFile, true), true)
            log("LOGGER", "Log cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log", e)
        }
    }
    
    /**
     * Check if logger is initialized.
     */
    fun isInitialized(): Boolean = initialized
    
    /**
     * Get log file size in bytes.
     */
    fun getLogSize(): Long {
        return logFile?.length() ?: 0L
    }
}
