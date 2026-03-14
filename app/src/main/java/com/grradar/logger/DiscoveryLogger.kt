package com.grradar.logger

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object DiscoveryLogger {

    private const val TAG = "GRRadar"
    private const val MAX_LOG_ENTRIES = 500
    private const val MAX_QUEUE_SIZE = 10000
    private const val MAX_OCCURRENCES_PER_EVENT = 20

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val inMemoryBuffer = ConcurrentLinkedQueue<String>()
    private val eventOccurrenceCount = ConcurrentHashMap<String, AtomicInteger>()

    private var logWriter: PrintWriter? = null
    private var writerThread: Thread? = null
    private var isRunning = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logDir = File("/sdcard/gxradar")
    private val logFile = File(logDir, "discovery_log.txt")

    // Listeners for UI updates
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun start(context: android.content.Context) {
        if (isRunning) return

        // Try external storage first, fallback to internal
        try {
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            if (logDir.exists() && logDir.canWrite()) {
                logWriter = PrintWriter(FileWriter(logFile, true), true)
                Log.i(TAG, "DiscoveryLogger: Using external storage: ${logFile.absolutePath}")
            } else {
                throw Exception("External storage not available")
            }
        } catch (e: Exception) {
            // Fallback to internal storage
            val internalFile = File(context.filesDir, "discovery_log.txt")
            logWriter = PrintWriter(FileWriter(internalFile, true), true)
            Log.i(TAG, "DiscoveryLogger: Using internal storage: ${internalFile.absolutePath}")
        }

        // Start writer thread
        writerThread = Thread {
            while (isRunning || logQueue.isNotEmpty()) {
                try {
                    val entry = logQueue.poll()
                    if (entry != null) {
                        logWriter?.println(entry)

                        // Add to in-memory buffer
                        inMemoryBuffer.add(entry)
                        while (inMemoryBuffer.size > MAX_LOG_ENTRIES) {
                            inMemoryBuffer.poll()
                        }

                        // Notify listeners
                        notifyListeners()
                    } else {
                        Thread.sleep(50)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "DiscoveryLogger writer error: ${e.message}")
                }
            }
        }.apply {
            name = "DiscoveryLogger-Writer"
            isDaemon = true
            start()
        }

        isRunning = true
        logInternal("I", "=== DiscoveryLogger started ===")
    }

    @Synchronized
    fun stop() {
        isRunning = false
        writerThread?.interrupt()
        writerThread = null
        logWriter?.flush()
        logWriter?.close()
        logWriter = null
        eventOccurrenceCount.clear()
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val recentLogs = getRecentLogs(50)
        listeners.forEach { listener ->
            try {
                listener(recentLogs)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun d(message: String) {
        Log.d(TAG, message)
        logInternal("D", message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        logInternal("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        logInternal("W", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message - ${throwable.message}"
        } else {
            message
        }
        logInternal("E", fullMessage)
    }

    private fun logInternal(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $level: $message"

        if (logQueue.size < MAX_QUEUE_SIZE) {
            logQueue.add(entry)
        }
    }

    /**
     * Log all params for target events (JoinFinished, NewCharacter, Move)
     */
    fun logAllParams(eventName: String, objectId: Int, params: Map<Int, Any?>) {
        // Check occurrence limit
        val count = eventOccurrenceCount.getOrPut(eventName) { AtomicInteger(0) }
        val occurrence = count.incrementAndGet()
        
        if (occurrence > MAX_OCCURRENCES_PER_EVENT) {
            return
        }

        val timestamp = dateFormat.format(Date())
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] PARAMS $eventName objectId=$objectId (occurrence $occurrence/$MAX_OCCURRENCES_PER_EVENT)")
        sb.appendLine("  Param count: ${params.size}")

        // Sort by key for readability
        params.entries.sortedBy { it.key }.forEach { (key, value) ->
            val typeName = when (value) {
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is Double -> "Double"
                is Boolean -> "Boolean"
                is Byte -> "Byte"
                is Short -> "Short"
                is String -> "String"
                is ByteArray -> "ByteArray[${(value as ByteArray).size}]"
                is IntArray -> "IntArray[${(value as IntArray).size}]"
                is List<*> -> "Array[${(value as List<*>).size}]"
                is Map<*, *> -> "Map[${(value as Map<*, *>).size}]"
                null -> "Null"
                else -> value::class.simpleName ?: "Unknown"
            }

            val displayValue = when (value) {
                is String -> "\"$value\""
                is Float -> String.format("%.3f", value)
                is Double -> String.format("%.3f", value)
                is ByteArray -> "[${value.take(8).joinToString(",") { "%02X".format(it) }}${if (value.size > 8) "..." else ""}]"
                null -> "null"
                else -> value.toString()
            }

            sb.appendLine("  key=$key\ttype=$typeName\tvalue=$displayValue")
        }

        if (logQueue.size < MAX_QUEUE_SIZE) {
            logQueue.add(sb.toString())
        }
    }

    /**
     * Log when Plan A and Plan B both fail
     */
    fun logDiscovery(eventName: String, objectId: Int, params: Map<Int, Any?>) {
        val timestamp = dateFormat.format(Date())
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] DISCOVERY FAILED $eventName objectId=$objectId")
        sb.appendLine("  Plan A and Plan B failed. Dumping all params:")

        params.entries.sortedBy { it.key }.forEach { (key, value) ->
            val typeName = when (value) {
                is Int -> "Int"
                is Float -> "Float"
                is String -> "String"
                is Boolean -> "Boolean"
                is Byte -> "Byte"
                null -> "Null"
                else -> value::class.simpleName ?: "Unknown"
            }
            val displayValue = when (value) {
                is String -> "\"$value\""
                is Float -> String.format("%.3f", value)
                null -> "null"
                else -> value.toString()
            }
            sb.appendLine("  key=$key\ttype=$typeName\tvalue=$displayValue")
        }

        if (logQueue.size < MAX_QUEUE_SIZE) {
            logQueue.add(sb.toString())
        }
    }

    /**
     * Log unknown event code
     */
    fun logUnknownEvent(eventCode: Int, params: Map<Int, Any?>) {
        val timestamp = dateFormat.format(Date())
        val sb = StringBuilder()
        sb.appendLine("[$timestamp] UNKNOWN EVENT code=$eventCode")
        sb.appendLine("  Params: ${params.entries.joinToString(", ") { "${it.key}=${it.value}" }}")

        if (logQueue.size < MAX_QUEUE_SIZE) {
            logQueue.add(sb.toString())
        }
    }

    /**
     * Log unknown Photon type code
     */
    fun logUnknownType(typeCode: Int, context: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] UNKNOWN TYPE code=0x%02X (%d) context=$context".format(typeCode, typeCode)

        if (logQueue.size < MAX_QUEUE_SIZE) {
            logQueue.add(entry)
        }
    }

    fun getAllLogs(): String {
        return inMemoryBuffer.joinToString("\n")
    }

    fun getRecentLogs(count: Int = 50): String {
        val list = inMemoryBuffer.toList()
        val start = maxOf(0, list.size - count)
        return list.subList(start, list.size).joinToString("\n")
    }

    fun clear() {
        inMemoryBuffer.clear()
        logQueue.clear()
        eventOccurrenceCount.clear()
        notifyListeners()
    }

    fun getLogFile(): File? {
        return if (logFile.exists()) logFile else null
    }
                      }
