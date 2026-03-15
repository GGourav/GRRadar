package com.grradar.parser

import android.util.Log
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PhotonParser — Photon Protocol 16 parser for Albion Online
 *
 * PHOTON PROTOCOL 16 STRUCTURE:
 *
 * UDP PAYLOAD HEADER (12 bytes):
 *   [0-1]  PeerID      uint16 big-endian
 *   [2]    Flags       uint8
 *   [3]    CmdCount    uint8   Number of commands in this packet
 *   [4-7]  Timestamp   uint32
 *   [8-11] Challenge   uint32
 *
 * COMMAND HEADER (12 bytes per command):
 *   [0]    CmdType     uint8   6=reliable, 7=unreliable
 *   [1]    ChannelId   uint8
 *   [2]    CmdFlags    uint8
 *   [3]    Reserved    uint8
 *   [4-7]  CmdLength   uint32  Total bytes including this header
 *   [8-11] ReliableSeq uint32
 *   [12+]  Payload
 *
 * PAYLOAD MESSAGE TYPES:
 *   0x02 = OperationRequest   (client → server, skip)
 *   0x03 = OperationResponse  (server → client, skip)
 *   0x04 = Event              ← THIS IS WHAT WE PARSE
 *
 * EVENT STRUCTURE:
 *   [0]     EventCode     uint8
 *   [1-2]   ParamCount    uint16 big-endian
 *   Then ParamCount times:
 *     [0]   Key           uint8
 *     [1]   TypeCode      uint8
 *     [2+]  Value         (length depends on type)
 *
 * PHOTON TYPE CODES:
 *   0x2A = Null
 *   0x6F = Boolean ('o')   1 byte
 *   0x62 = Byte    ('b')   1 byte
 *   0x6B = Short   ('k')   2 bytes big-endian
 *   0x69 = Integer ('i')   4 bytes big-endian
 *   0x6C = Long    ('l')   8 bytes big-endian
 *   0x66 = Float   ('f')   4 bytes big-endian  ← POSITIONS
 *   0x64 = Double  ('d')   8 bytes
 *   0x73 = String  ('s')   2-byte length + UTF-8 ← TYPE NAMES
 *   0x78 = ByteArray ('x') 4-byte length + bytes
 *   0x6E = IntArray ('n')  4-byte length + int32s
 *   0x61 = Array   ('a')   2-byte length + elements
 *   0x68 = Hashtable ('h') 2-byte count + pairs
 *   0x44 = Dictionary ('D') types + count + pairs
 *   0x7A = ObjectArray ('z') 2-byte length + values
 *
 * USAGE:
 *   val result = PhotonParser.parse(data, offset, length)
 *   result.events.forEach { event ->
 *       EventDispatcher.dispatch(event)
 *   }
 */
object PhotonParser {

    private const val TAG = "PhotonParser"

    // ═══════════════════════════════════════════════════════════════════════════
    // PHOTON TYPE CODES
    // ═══════════════════════════════════════════════════════════════════════════

    private const val TYPE_NULL: Byte = 0x2A        // '*'
    private const val TYPE_BOOLEAN: Byte = 0x6F     // 'o'
    private const val TYPE_BYTE: Byte = 0x62        // 'b'
    private const val TYPE_SHORT: Byte = 0x6B       // 'k'
    private const val TYPE_INTEGER: Byte = 0x69     // 'i'
    private const val TYPE_LONG: Byte = 0x6C        // 'l'
    private const val TYPE_FLOAT: Byte = 0x66       // 'f'
    private const val TYPE_DOUBLE: Byte = 0x64      // 'd'
    private const val TYPE_STRING: Byte = 0x73      // 's'
    private const val TYPE_BYTE_ARRAY: Byte = 0x78  // 'x'
    private const val TYPE_INT_ARRAY: Byte = 0x6E   // 'n'
    private const val TYPE_ARRAY: Byte = 0x61       // 'a'
    private const val TYPE_HASHTABLE: Byte = 0x68   // 'h'
    private const val TYPE_DICTIONARY: Byte = 0x44  // 'D'
    private const val TYPE_OBJECT_ARRAY: Byte = 0x7A // 'z'

    // ═══════════════════════════════════════════════════════════════════════════
    // PHOTON MESSAGE TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    private const val MSG_OPERATION_REQUEST: Byte = 0x02
    private const val MSG_OPERATION_RESPONSE: Byte = 0x03
    private const val MSG_EVENT: Byte = 0x04

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    private var photonPacketCount = 0L
    private var eventCount = 0L
    private var parseErrorCount = 0L

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN PARSE METHOD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse a Photon packet from raw UDP payload
     *
     * @param data Raw UDP payload bytes
     * @param offset Start offset in data array
     * @param length Length of payload
     * @return ParseResult containing success status and parsed events
     */
    fun parse(data: ByteArray, offset: Int, length: Int): ParseResult {
        photonPacketCount++

        // Minimum header size check
        if (length < 12) {
            return ParseResult(
                success = false,
                error = "Packet too short: $length bytes (minimum 12)",
                packetCount = photonPacketCount
            )
        }

        // Wrap in ByteBuffer with big-endian order (Photon uses big-endian)
        val buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)

        // Parse Photon header
        val peerId = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        val cmdCount = buf.get().toInt() and 0xFF
        val timestamp = buf.int
        val challenge = buf.int

        // Log header occasionally for debugging
        if (photonPacketCount % 100 == 1L) {
            DiscoveryLogger.d("Photon header: peer=$peerId flags=$flags cmds=$cmdCount ts=$timestamp")
        }

        val events = mutableListOf<ParsedEvent>()

        // Parse each command
        for (cmdIndex in 0 until cmdCount) {
            // Check remaining bytes for minimum command header
            if (buf.remaining() < 12) {
                DiscoveryLogger.w("Command $cmdIndex: insufficient bytes for header (${buf.remaining()})")
                break
            }

            val cmdStart = buf.position()

            // Parse command header
            val cmdType = buf.get().toInt() and 0xFF
            val channelId = buf.get().toInt() and 0xFF
            val cmdFlags = buf.get().toInt() and 0xFF
            buf.get() // reserved byte
            val cmdLength = buf.int
            buf.int // reliableSeq - we don't need this

            // Validate command length
            if (cmdLength < 12) {
                DiscoveryLogger.w("Command $cmdIndex: invalid cmdLength=$cmdLength")
                break
            }

            if (cmdLength > buf.remaining() + 12) {
                DiscoveryLogger.w("Command $cmdIndex: cmdLength=$cmdLength exceeds remaining=${buf.remaining()}")
                break
            }

            val payloadLen = cmdLength - 12
            val payloadStart = buf.position()

            // Only parse Events (type 0x04)
            if (payloadLen > 0 && buf.hasRemaining()) {
                val msgType = buf.get()

                if (msgType == MSG_EVENT) {
                    try {
                        val event = parseEvent(buf, payloadLen - 1)
                        if (event != null) {
                            events.add(event)
                            eventCount++
                        }
                    } catch (e: Exception) {
                        parseErrorCount++
                        DiscoveryLogger.w("Event parse error in command $cmdIndex: ${e.message}")
                    }
                }
                // Skip OperationRequest (0x02) and OperationResponse (0x03)
            }

            // Move to next command
            buf.position(cmdStart + cmdLength)
        }

        return ParseResult(
            success = true,
            events = events,
            packetCount = photonPacketCount,
            eventCount = eventCount
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVENT PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse a Photon Event from the buffer
     *
     * @param buf ByteBuffer positioned at event data
     * @param remainingLen Remaining bytes in this event
     * @return ParsedEvent or null if parsing failed
     */
    private fun parseEvent(buf: ByteBuffer, remainingLen: Int): ParsedEvent? {
        if (remainingLen < 3) {
            return null
        }

        try {
            // Event code (1 byte)
            val eventCode = buf.get().toInt() and 0xFF

            // Parameter count (2 bytes big-endian)
            val paramCount = buf.short.toInt() and 0xFFFF

            // Sanity check
            if (paramCount > 500) {
                DiscoveryLogger.w("Suspicious paramCount: $paramCount for event $eventCode")
                return null
            }

            // Parse parameters
            val params = HashMap<Int, Any?>()

            for (i in 0 until paramCount) {
                if (buf.remaining() < 2) {
                    DiscoveryLogger.w("Event $eventCode: insufficient bytes for param $i")
                    break
                }

                // Parameter key (1 byte)
                val key = buf.get().toInt() and 0xFF

                // Parameter value
                val value = readValue(buf)
                if (value != null || key == 0) { // Always store key 0 (objectId)
                    params[key] = value
                }
            }

            // Get event name from params[252] (0xFC) if available
            // This is the string name of the event, which is more stable than integer codes
            val eventName = (params[252] as? String) ?: "Event_$eventCode"

            // Log new events for discovery (limit to first 200 to avoid spam)
            if (eventCount < 200 || eventName.startsWith("New") || eventName == "JoinFinished") {
                DiscoveryLogger.logPhotonEvent(eventName, params)
            }

            return ParsedEvent(
                eventCode = eventCode,
                eventName = eventName,
                params = params
            )

        } catch (e: Exception) {
            DiscoveryLogger.w("Event parse exception: ${e.message}")
            return null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALUE READING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Read a typed value from the buffer
     *
     * @param buf ByteBuffer positioned at type code
     * @return Parsed value or null
     */
    private fun readValue(buf: ByteBuffer): Any? {
        if (!buf.hasRemaining()) {
            return null
        }

        val typeCode = buf.get()

        return when (typeCode) {
            TYPE_NULL -> null

            TYPE_BOOLEAN -> {
                if (buf.hasRemaining()) buf.get() != 0.toByte() else null
            }

            TYPE_BYTE -> {
                if (buf.hasRemaining()) buf.get().toInt() and 0xFF else null
            }

            TYPE_SHORT -> {
                if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else null
            }

            TYPE_INTEGER -> {
                if (buf.remaining() >= 4) buf.int else null
            }

            TYPE_LONG -> {
                if (buf.remaining() >= 8) buf.long else null
            }

            TYPE_FLOAT -> {
                if (buf.remaining() >= 4) buf.float else null
            }

            TYPE_DOUBLE -> {
                if (buf.remaining() >= 8) buf.double else null
            }

            TYPE_STRING -> {
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xFFFF
                    if (len <= buf.remaining() && len < 65536) {
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        try {
                            String(bytes, Charsets.UTF_8)
                        } catch (e: Exception) {
                            String(bytes, Charsets.ISO_8859_1)
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            TYPE_BYTE_ARRAY -> {
                if (buf.remaining() >= 4) {
                    val len = buf.int
                    if (len >= 0 && len <= buf.remaining() && len < 1048576) { // Max 1MB
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        bytes
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            TYPE_INT_ARRAY -> {
                if (buf.remaining() >= 4) {
                    val len = buf.int
                    if (len >= 0 && len <= buf.remaining() / 4 && len < 262144) {
                        IntArray(len) { buf.int }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            TYPE_ARRAY -> {
                if (buf.remaining() >= 3) {
                    val len = buf.short.toInt() and 0xFFFF
                    buf.get() // element type
                    // Skip array elements - we don't need nested arrays
                    for (i in 0 until len) {
                        skipValue(buf)
                    }
                    null // Return null for arrays - too complex for radar
                } else {
                    null
                }
            }

            TYPE_HASHTABLE -> {
                if (buf.remaining() >= 2) {
                    val count = buf.short.toInt() and 0xFFFF
                    // Skip hashtable pairs
                    for (i in 0 until count) {
                        skipValue(buf) // key
                        skipValue(buf) // value
                    }
                    null
                } else {
                    null
                }
            }

            TYPE_DICTIONARY -> {
                if (buf.remaining() >= 4) {
                    buf.get() // keyType
                    buf.get() // valType
                    val count = buf.short.toInt() and 0xFFFF
                    for (i in 0 until count) {
                        skipValue(buf)
                        skipValue(buf)
                    }
                    null
                } else {
                    null
                }
            }

            TYPE_OBJECT_ARRAY -> {
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xFFFF
                    for (i in 0 until len) {
                        skipValue(buf)
                    }
                    null
                } else {
                    null
                }
            }

            else -> {
                // Unknown type code - log and return null
                if (parseErrorCount < 10) {
                    DiscoveryLogger.w("Unknown Photon type code: 0x%02X".format(typeCode))
                }
                null
            }
        }
    }

    /**
     * Skip a value in the buffer without parsing it
     * Used for nested types we don't need to fully parse
     */
    private fun skipValue(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return

        val typeCode = buf.get()

        when (typeCode) {
            TYPE_NULL -> { /* nothing */ }
            TYPE_BOOLEAN, TYPE_BYTE -> { if (buf.hasRemaining()) buf.get() }
            TYPE_SHORT -> { if (buf.remaining() >= 2) buf.short }
            TYPE_INTEGER -> { if (buf.remaining() >= 4) buf.int }
            TYPE_LONG -> { if (buf.remaining() >= 8) buf.long }
            TYPE_FLOAT -> { if (buf.remaining() >= 4) buf.float }
            TYPE_DOUBLE -> { if (buf.remaining() >= 8) buf.double }
            TYPE_STRING -> {
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xFFFF
                    if (len <= buf.remaining()) {
                        buf.position(buf.position() + len)
                    }
                }
            }
            TYPE_BYTE_ARRAY -> {
                if (buf.remaining() >= 4) {
                    val len = buf.int
                    if (len >= 0 && len <= buf.remaining()) {
                        buf.position(buf.position() + len)
                    }
                }
            }
            TYPE_INT_ARRAY -> {
                if (buf.remaining() >= 4) {
                    val len = buf.int
                    if (len >= 0 && len * 4 <= buf.remaining()) {
                        buf.position(buf.position() + len * 4)
                    }
                }
            }
            TYPE_ARRAY -> {
                if (buf.remaining() >= 3) {
                    val len = buf.short.toInt() and 0xFFFF
                    buf.get()
                    for (i in 0 until len) skipValue(buf)
                }
            }
            TYPE_HASHTABLE -> {
                if (buf.remaining() >= 2) {
                    val count = buf.short.toInt() and 0xFFFF
                    for (i in 0 until count) {
                        skipValue(); skipValue()
                    }
                }
            }
            TYPE_DICTIONARY -> {
                if (buf.remaining() >= 4) {
                    buf.get(); buf.get()
                    val count = buf.short.toInt() and 0xFFFF
                    for (i in 0 until count) {
                        skipValue(); skipValue()
                    }
                }
            }
            TYPE_OBJECT_ARRAY -> {
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xFFFF
                    for (i in 0 until len) skipValue(buf)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS FOR EXTRACTING DATA FROM PARAMS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extract ObjectId from parameters using configured key
     *
     * @param params Parameter map from parsed event
     * @return ObjectId as Int, or null if not found
     */
    fun getObjectId(params: Map<Int, Any?>): Int? {
        val key = IdMapRepository.objectIdKey
        if (key < 0) return null

        return when (val value = params[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> null
        }
    }

    /**
     * Extract position coordinates from parameters
     * Uses Plan A (configured keys) or Plan B (float range scanner)
     *
     * @param params Parameter map from parsed event
     * @return Pair of (x, y) coordinates, or null if not found
     */
    fun getPosition(params: Map<Int, Any?>): Pair<Float, Float>? {
        val posXKey = IdMapRepository.posXKey
        val posYKey = IdMapRepository.posYKey

        // ═══════════════════════════════════════════════════════════════════════
        // PLAN A: Use configured keys
        // ═══════════════════════════════════════════════════════════════════════
        if (posXKey >= 0 && posYKey >= 0) {
            val x = params[posXKey] as? Float
            val y = params[posYKey] as? Float

            if (x != null && y != null) {
                return Pair(x, y)
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PLAN B: Scan all float params for valid coordinate range
        // ═══════════════════════════════════════════════════════════════════════
        val minValid = IdMapRepository.coordinateMinValid
        val maxValid = IdMapRepository.coordinateMaxValid

        val validFloats = params.values
            .filterIsInstance<Float>()
            .filter { it in minValid..maxValid }
            .toList()

        // Need at least 2 floats for a position
        if (validFloats.size >= 2) {
            return Pair(validFloats[0], validFloats[1])
        }

        // Try doubles converted to floats
        val validDoubles = params.values
            .filterIsInstance<Double>()
            .filter { it.toFloat() in minValid..maxValid }
            .map { it.toFloat() }
            .toList()

        if (validDoubles.size >= 2) {
            return Pair(validDoubles[0], validDoubles[1])
        }

        return null
    }

    /**
     * Extract type name string from parameters
     * Uses multiple key locations and fallback scanning
     *
     * @param params Parameter map from parsed event
     * @return Type name string, or null if not found
     */
    fun getTypeName(params: Map<Int, Any?>): String? {
        // ═══════════════════════════════════════════════════════════════════════
        // PLAN A: Check common key locations
        // ═══════════════════════════════════════════════════════════════════════
        for (key in listOf(1, 2, 5, 6, 253, 254)) {
            val value = params[key]
            if (value is String) {
                // Check if it looks like a type name
                if (value.startsWith("T", ignoreCase = true) || 
                    value.contains("_", ignoreCase = true)) {
                    return value
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PLAN B: Scan all strings for T{n}_ prefix pattern
        // ═══════════════════════════════════════════════════════════════════════
        for (value in params.values) {
            if (value is String && value.contains(Regex("T[1-8]_"))) {
                return value
            }
        }

        return null
    }

    /**
     * Extract integer parameter by key with fallback
     *
     * @param params Parameter map
     * @param key Parameter key
     * @param default Default value if not found
     * @return Integer value
     */
    fun getInt(params: Map<Int, Any?>, key: Int, default: Int = 0): Int {
        return when (val value = params[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> default
        }
    }

    /**
     * Extract float parameter by key with fallback
     *
     * @param params Parameter map
     * @param key Parameter key
     * @param default Default value if not found
     * @return Float value
     */
    fun getFloat(params: Map<Int, Any?>, key: Int, default: Float = 0f): Float {
        return when (val value = params[key]) {
            is Float -> value
            is Double -> value.toFloat()
            is Number -> value.toFloat()
            else -> default
        }
    }

    /**
     * Extract string parameter by key
     *
     * @param params Parameter map
     * @param key Parameter key
     * @return String value or empty string
     */
    fun getString(params: Map<Int, Any?>, key: Int): String {
        return (params[key] as? String) ?: ""
    }

    /**
     * Extract boolean parameter by key
     *
     * @param params Parameter map
     * @param key Parameter key
     * @param default Default value if not found
     * @return Boolean value
     */
    fun getBoolean(params: Map<Int, Any?>, key: Int, default: Boolean = false): Boolean {
        return when (val value = params[key]) {
            is Boolean -> value
            is Int -> value != 0
            is Long -> value != 0L
            is Number -> value.toInt() != 0
            else -> default
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get parser statistics
     */
    fun getStats(): String {
        return "PhotonParser: packets=$photonPacketCount, events=$eventCount, errors=$parseErrorCount"
    }

    /**
     * Reset statistics
     */
    fun resetStats() {
        photonPacketCount = 0
        eventCount = 0
        parseErrorCount = 0
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Result of parsing a Photon packet
 */
data class ParseResult(
    val success: Boolean,
    val events: List<ParsedEvent> = emptyList(),
    val error: String? = null,
    val packetCount: Long = 0,
    val eventCount: Long = 0
) {
    /**
     * Check if any events were parsed
     */
    fun hasEvents(): Boolean = events.isNotEmpty()

    /**
     * Get event count
     */
    fun eventCount(): Int = events.size
}

/**
 * A parsed Photon event
 */
data class ParsedEvent(
    val eventCode: Int,
    val eventName: String,
    val params: Map<Int, Any?>
) {
    /**
     * Get a parameter by key
     */
    fun <T> getParam(key: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return params[key] as? T
    }

    /**
     * Check if event is a New* entity spawn event
     */
    fun isSpawnEvent(): Boolean = eventName.startsWith("New")

    /**
     * Check if event is JoinFinished
     */
    fun isJoinFinished(): Boolean = eventName == "JoinFinished"

    /**
     * Check if event is Leave
     */
    fun isLeave(): Boolean = eventName == "Leave"

    /**
     * Check if event is Move
     */
    fun isMove(): Boolean = eventName == "Move"

    override fun toString(): String {
        return "ParsedEvent(name='$eventName', code=$eventCode, params=${params.size})"
    }
}
