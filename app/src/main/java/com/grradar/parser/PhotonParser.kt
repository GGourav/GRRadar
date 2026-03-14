package com.grradar.parser

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photon Protocol 16 Parser for Albion Online
 * 
 * Photon traffic is NOT encrypted - plain binary protocol over UDP port 5056
 * 
 * Packet Structure:
 * - Header (12 bytes):
 *   [0-1]  PeerID      uint16 big-endian
 *   [2]    Flags       uint8
 *   [3]    CmdCount    uint8
 *   [4-7]  Timestamp   uint32
 *   [8-11] Challenge   uint32
 * 
 * - Commands (repeat CmdCount times):
 *   [0]    CmdType     uint8   (6=reliable, 7=unreliable)
 *   [1]    ChannelId   uint8
 *   [2]    CmdFlags    uint8
 *   [3]    Reserved    uint8
 *   [4-7]  CmdLength   uint32  (total bytes including header)
 *   [8-11] ReliableSeq uint32
 *   [12+]  Payload
 * 
 * - Payload (first byte = message type):
 *   0x02 = OperationRequest   (client → server, skip)
 *   0x03 = OperationResponse  (server → client, skip)
 *   0x04 = Event              ← THIS IS WHAT YOU PARSE
 */
class PhotonParser(private val callback: PhotonCallback) {
    
    companion object {
        private const val TAG = "PhotonParser"
        
        // Message types
        private const val MSG_OPERATION_REQUEST: Byte = 0x02
        private const val MSG_OPERATION_RESPONSE: Byte = 0x03
        private const val MSG_EVENT: Byte = 0x04
        
        // Command types
        private const val CMD_RELIABLE: Byte = 0x06
        private const val CMD_UNRELIABLE: Byte = 0x07
        
        // Photon type codes
        private const val TYPE_NULL: Byte = 0x2A
        private const val TYPE_BOOLEAN: Byte = 0x6F
        private const val TYPE_BYTE: Byte = 0x62
        private const val TYPE_SHORT: Byte = 0x6B
        private const val TYPE_INTEGER: Byte = 0x69
        private const val TYPE_LONG: Byte = 0x6C
        private const val TYPE_FLOAT: Byte = 0x66
        private const val TYPE_DOUBLE: Byte = 0x64
        private const val TYPE_STRING: Byte = 0x73
        private const val TYPE_BYTE_ARRAY: Byte = 0x78
        private const val TYPE_INT_ARRAY: Byte = 0x6E
        private const val TYPE_ARRAY: Byte = 0x61
        private const val TYPE_HASHTABLE: Byte = 0x68
        private const val TYPE_DICTIONARY: Byte = 0x44
        private const val TYPE_OBJECT_ARRAY: Byte = 0x7A
    }
    
    interface PhotonCallback {
        fun onEvent(eventName: String, params: Map<Int, Any?>)
        fun onError(error: String)
    }
    
    /**
     * Parse a UDP payload (after IP/UDP headers stripped)
     */
    fun parse(payload: ByteArray): Boolean {
        if (payload.size < 12) {
            return false
        }
        
        return try {
            val buffer = ByteBuffer.wrap(payload)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            // Parse header
            val peerId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.get().toInt() and 0xFF
            val cmdCount = buffer.get().toInt() and 0xFF
            val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
            val challenge = buffer.int.toLong() and 0xFFFFFFFFL
            
            Log.d(TAG, "Packet: peerId=$peerId, flags=$flags, cmdCount=$cmdCount")
            
            // Parse each command
            for (i in 0 until cmdCount) {
                if (buffer.remaining() < 12) break
                parseCommand(buffer)
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            false
        }
    }
    
    private fun parseCommand(buffer: ByteBuffer) {
        val cmdType = buffer.get().toInt() and 0xFF
        val channelId = buffer.get().toInt() and 0xFF
        val cmdFlags = buffer.get().toInt() and 0xFF
        buffer.get() // reserved
        val cmdLength = buffer.int
        buffer.int // reliableSeq
        
        val startPos = buffer.position()
        val payloadLength = cmdLength - 12
        
        if (payloadLength <= 0 || buffer.remaining() < payloadLength) {
            return
        }
        
        val payloadBytes = ByteArray(payloadLength)
        buffer.get(payloadBytes)
        
        when (cmdType.toByte()) {
            CMD_RELIABLE, CMD_UNRELIABLE -> parsePayload(payloadBytes)
        }
        
        buffer.position(startPos + payloadLength)
    }
    
    private fun parsePayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        
        when (payload[0]) {
            MSG_EVENT -> parseEvent(payload)
            MSG_OPERATION_REQUEST -> { /* Skip */ }
            MSG_OPERATION_RESPONSE -> { /* Skip */ }
        }
    }
    
    private fun parseEvent(payload: ByteArray) {
        if (payload.size < 3) return
        
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        buffer.get() // Skip message type byte
        val eventCode = buffer.get().toInt() and 0xFF
        val paramCount = buffer.short.toInt() and 0xFFFF
        
        Log.d(TAG, "Event: code=$eventCode, paramCount=$paramCount")
        
        val params = HashMap<Int, Any?>()
        
        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) break
            
            try {
                val key = buffer.get().toInt() and 0xFF
                val value = readValue(buffer)
                params[key] = value
                
                Log.v(TAG, "Param[$key] = $value")
            } catch (e: Exception) {
                Log.w(TAG, "Error reading param $i: ${e.message}")
                break
            }
        }
        
        val eventName = getEventName(eventCode, params)
        Log.d(TAG, "Dispatching event: $eventName with ${params.size} params")
        
        callback.onEvent(eventName, params)
    }
    
    private fun getEventName(eventCode: Int, params: Map<Int, Any?>): String {
        // Known event code mappings
        return when (eventCode) {
            1 -> "JoinFinished"
            2 -> "NewCharacter"
            3 -> "NewMob"
            4 -> "NewSimpleHarvestableObject"
            5 -> "NewSimpleHarvestableObjectList"
            6 -> "NewHarvestableObject"
            7 -> "NewSilverObject"
            8 -> "NewSimpleItem"
            9 -> "NewFishingZoneObject"
            10 -> "NewMistsCagedWisp"
            11 -> "NewMistsWispSpawn"
            12 -> "NewMistDungeonRoomMobSoul"
            13 -> "NewLootChest"
            14 -> "NewTreasureChest"
            15 -> "NewCarriableObject"
            16 -> "NewRandomDungeonExit"
            17 -> "NewExpeditionExit"
            18 -> "NewHellgateExitPortal"
            19 -> "NewMistsDungeonExit"
            20 -> "NewPortalEntrance"
            21 -> "NewPortalExit"
            253 -> "Leave"
            254 -> "Move"
            255 -> "ForcedMovement"
            else -> {
                val nameParam = params[252]
                if (nameParam is String) nameParam else "Event_$eventCode"
            }
        }
    }
    
    private fun readValue(buffer: ByteBuffer): Any? {
        if (buffer.remaining() < 1) return null
        
        val typeCode = buffer.get()
        
        return when (typeCode) {
            TYPE_NULL -> null
            TYPE_BOOLEAN -> buffer.get() != 0.toByte()
            TYPE_BYTE -> buffer.get().toInt() and 0xFF
            TYPE_SHORT -> buffer.short.toInt() and 0xFFFF
            TYPE_INTEGER -> buffer.int
            TYPE_LONG -> buffer.long
            TYPE_FLOAT -> buffer.float
            TYPE_DOUBLE -> buffer.double
            TYPE_STRING -> readString(buffer)
            TYPE_BYTE_ARRAY -> readByteArray(buffer)
            TYPE_INT_ARRAY -> readIntArray(buffer)
            TYPE_ARRAY -> readArray(buffer)
            TYPE_HASHTABLE -> readHashtable(buffer)
            TYPE_DICTIONARY -> readDictionary(buffer)
            TYPE_OBJECT_ARRAY -> readObjectArray(buffer)
            else -> {
                Log.w(TAG, "Unknown type code: 0x${"%02X".format(typeCode)}")
                null
            }
        }
    }
    
    private fun readString(buffer: ByteBuffer): String {
        if (buffer.remaining() < 2) return ""
        
        val length = buffer.short.toInt() and 0xFFFF
        if (length == 0 || length > buffer.remaining()) return ""
        
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
    
    private fun readByteArray(buffer: ByteBuffer): ByteArray {
        if (buffer.remaining() < 4) return ByteArray(0)
        
        val length = buffer.int
        if (length <= 0 || length > buffer.remaining()) return ByteArray(0)
        
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }
    
    private fun readIntArray(buffer: ByteBuffer): IntArray {
        if (buffer.remaining() < 4) return IntArray(0)
        
        val length = buffer.int
        if (length <= 0 || length * 4 > buffer.remaining()) return IntArray(0)
        
        val array = IntArray(length)
        for (i in 0 until length) {
            array[i] = buffer.int
        }
        return array
    }
    
    private fun readArray(buffer: ByteBuffer): Array<Any?> {
        if (buffer.remaining() < 3) return arrayOfNulls(0)
        
        val length = buffer.short.toInt() and 0xFFFF
        buffer.get() // elementType
        
        if (length <= 0) return arrayOfNulls(0)
        
        val array = arrayOfNulls<Any>(length)
        for (i in 0 until length) {
            array[i] = readValue(buffer)
        }
        return array
    }
    
    private fun readHashtable(buffer: ByteBuffer): Map<Any?, Any?> {
        if (buffer.remaining() < 2) return emptyMap()
        
        val count = buffer.short.toInt() and 0xFFFF
        if (count == 0) return emptyMap()
        
        val map = HashMap<Any?, Any?>(count)
        for (i in 0 until count) {
            val key = readValue(buffer)
            val value = readValue(buffer)
            map[key] = value
        }
        return map
    }
    
    private fun readDictionary(buffer: ByteBuffer): Map<Any?, Any?> {
        if (buffer.remaining() < 4) return emptyMap()
        
        buffer.get() // keyType
        buffer.get() // valueType
        val count = buffer.short.toInt() and 0xFFFF
        
        if (count == 0) return emptyMap()
        
        val map = HashMap<Any?, Any?>(count)
        for (i in 0 until count) {
            val key = readValue(buffer)
            val value = readValue(buffer)
            map[key] = value
        }
        return map
    }
    
    private fun readObjectArray(buffer: ByteBuffer): Array<Any?> {
        if (buffer.remaining() < 2) return arrayOfNulls(0)
        
        val length = buffer.short.toInt() and 0xFFFF
        if (length <= 0) return arrayOfNulls(0)
        
        val array = arrayOfNulls<Any>(length)
        for (i in 0 until length) {
            array[i] = readValue(buffer)
        }
        return array
    }
    
    // ===== Value extraction helpers =====
    
    fun getInt(params: Map<Int, Any?>, key: Int, default: Int = 0): Int {
        val value = params[key] ?: return default
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Number -> value.toInt()
            else -> default
        }
    }
    
    fun getFloat(params: Map<Int, Any?>, key: Int, default: Float = 0f): Float {
        val value = params[key] ?: return default
        return when (value) {
            is Float -> value
            is Double -> value.toFloat()
            is Int -> value.toFloat()
            is Long -> value.toFloat()
            is Number -> value.toFloat()
            else -> default
        }
    }
    
    fun getString(params: Map<Int, Any?>, key: Int, default: String = ""): String {
        val value = params[key] ?: return default
        return when (value) {
            is String -> value
            else -> value.toString()
        }
    }
    
    fun getBoolean(params: Map<Int, Any?>, key: Int, default: Boolean = false): Boolean {
        val value = params[key] ?: return default
        return when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Byte -> value != 0.toByte()
            is Number -> value.toInt() != 0
            else -> default
        }
    }
    
    fun getByteArray(params: Map<Int, Any?>, key: Int): ByteArray? {
        val value = params[key] ?: return null
        return when (value) {
            is ByteArray -> value
            else -> null
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    fun getMap(params: Map<Int, Any?>, key: Int): Map<*, *>? {
        val value = params[key] ?: return null
        return when (value) {
            is Map<*, *> -> value
            else -> null
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    fun getList(params: Map<Int, Any?>, key: Int): List<*>? {
        val value = params[key] ?: return null
        return when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> null
        }
    }
    
    // ===== Plan B scanners =====
    
    /**
     * Plan B: Scan all string params for tier prefix (T1_ through T8_)
     * Used when typeNameKey is unknown
     */
    fun scanForTierPrefix(params: Map<Int, Any?>): String? {
        for ((_, value) in params) {
            if (value is String) {
                val upper = value.uppercase()
                for (tier in 1..8) {
                    if (upper.startsWith("T${tier}_")) {
                        return value
                    }
                }
            }
        }
        return null
    }
    
    /**
     * Plan B: Scan all float params for world coordinates
     * Albion world coordinates are in range [-32768, +32768]
     */
    fun scanForCoordinates(params: Map<Int, Any?>, minValid: Float, maxValid: Float): Pair<Float, Float>? {
        val floats = mutableListOf<Float>()
        
        for ((_, value) in params) {
            when (value) {
                is Float -> {
                    if (value in minValid..maxValid && value != 0f) {
                        floats.add(value)
                    }
                }
                is Double -> {
                    val f = value.toFloat()
                    if (f in minValid..maxValid && f != 0f) {
                        floats.add(f)
                    }
                }
            }
        }
        
        // First two valid floats are usually posX and posY
        if (floats.size >= 2) {
            return Pair(floats[0], floats[1])
        }
        
        return null
    }
    
    /**
     * Scan for coordinate values with specific min/max from config
     */
    fun scanForCoordinates(params: Map<Int, Any?>, minValid: Double, maxValid: Double): Pair<Float, Float>? {
        return scanForCoordinates(params, minValid.toFloat(), maxValid.toFloat())
    }
}
