package com.grradar.parser

import android.util.Log
import com.grradar.logger.DiscoveryLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhotonParser(private val callback: PhotonCallback) {
    
    companion object {
        private const val TAG = "PhotonParser"
        
        private const val MSG_EVENT: Byte = 0x04
        private const val CMD_RELIABLE: Byte = 0x06
        private const val CMD_UNRELIABLE: Byte = 0x07
        
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
    
    fun parse(payload: ByteArray): Boolean {
        if (payload.size < 12) {
            Log.d(TAG, "Payload too small: ${payload.size} bytes")
            return false
        }
        
        return try {
            val buffer = ByteBuffer.wrap(payload)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            val peerId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.get().toInt() and 0xFF
            val cmdCount = buffer.get().toInt() and 0xFF
            val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
            val challenge = buffer.int.toLong() and 0xFFFFFFFFL
            
            Log.d(TAG, "Photon header: peer=$peerId flags=$flags cmds=$cmdCount ts=$timestamp")
            DiscoveryLogger.d("Photon header: peer=$peerId flags=$flags cmds=$cmdCount ts=$timestamp")
            
            if (cmdCount == 0) {
                Log.d(TAG, "No commands in packet")
                return true
            }
            
            var parsedAny = false
            for (i in 0 until cmdCount) {
                if (buffer.remaining() < 12) {
                    Log.w(TAG, "Buffer exhausted at command $i")
                    break
                }
                if (parseCommand(buffer)) {
                    parsedAny = true
                }
            }
            
            parsedAny
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            DiscoveryLogger.e("Photon parse error: ${e.message}")
            callback.onError("Parse error: ${e.message}")
            false
        }
    }
    
    private fun parseCommand(buffer: ByteBuffer): Boolean {
        val cmdType = buffer.get().toInt() and 0xFF
        val channelId = buffer.get().toInt() and 0xFF
        val cmdFlags = buffer.get().toInt() and 0xFF
        buffer.get()
        val cmdLength = buffer.int
        buffer.int()
        
        val startPos = buffer.position()
        val payloadLength = cmdLength - 12
        
        Log.d(TAG, "Command: type=$cmdType ch=$channelId len=$cmdLength payLen=$payloadLength")
        
        if (payloadLength <= 0 || buffer.remaining() < payloadLength) {
            Log.w(TAG, "Invalid payload length: $payloadLength, remaining: ${buffer.remaining()}")
            return false
        }
        
        val payloadBytes = ByteArray(payloadLength)
        buffer.get(payloadBytes)
        
        val isReliable = cmdType.toByte() == CMD_RELIABLE
        val isUnreliable = cmdType.toByte() == CMD_UNRELIABLE
        
        var result = false
        if (isReliable || isUnreliable) {
            result = parsePayload(payloadBytes)
        }
        
        buffer.position(startPos + payloadLength)
        return result
    }
    
    private fun parsePayload(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        
        val msgType = payload[0]
        Log.d(TAG, "Payload msgType: 0x%02X".format(msgType))
        
        return when (msgType) {
            MSG_EVENT -> parseEvent(payload)
            else -> {
                Log.d(TAG, "Not an event (type=0x%02X), skipping".format(msgType))
                false
            }
        }
    }
    
    private fun parseEvent(payload: ByteArray): Boolean {
        if (payload.size < 3) {
            Log.w(TAG, "Event payload too small: ${payload.size}")
            return false
        }
        
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        buffer.get()
        val eventCode = buffer.get().toInt() and 0xFF
        val paramCount = buffer.short.toInt() and 0xFFFF
        
        Log.d(TAG, "Event: code=$eventCode params=$paramCount")
        DiscoveryLogger.d("Photon event: code=$eventCode params=$paramCount")
        
        val params = HashMap<Int, Any?>()
        
        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) {
                Log.w(TAG, "Buffer exhausted reading param $i")
                break
            }
            try {
                val key = buffer.get().toInt() and 0xFF
                val value = readValue(buffer)
                params[key] = value
                Log.v(TAG, "Param[$key] = $value")
            } catch (e: Exception) {
                Log.w(TAG, "Param read error at $i: ${e.message}")
                break
            }
        }
        
        val eventName = getEventName(eventCode, params)
        Log.d(TAG, "Dispatching event: $eventName")
        DiscoveryLogger.d("Dispatching event: $eventName")
        
        callback.onEvent(eventName, params)
        return true
    }
    
    private fun getEventName(eventCode: Int, params: Map<Int, Any?>): String {
        val nameParam = params[252]
        if (nameParam is String && nameParam.isNotEmpty()) {
            return nameParam
        }
        
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
            13 -> "NewLootChest"
            14 -> "NewTreasureChest"
            16 -> "NewRandomDungeonExit"
            18 -> "NewHellgateExitPortal"
            19 -> "NewMistsDungeonExit"
            253 -> "Leave"
            254 -> "Move"
            255 -> "ForcedMovement"
            else -> "Event_$eventCode"
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
                Log.w(TAG, "Unknown type: 0x%02X".format(typeCode))
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
        return IntArray(length) { buffer.int }
    }
    
    private fun readArray(buffer: ByteBuffer): Array<Any?> {
        if (buffer.remaining() < 3) return arrayOfNulls(0)
        val length = buffer.short.toInt() and 0xFFFF
        buffer.get()
        if (length <= 0) return arrayOfNulls(0)
        return Array(length) { readValue(buffer) }
    }
    
    private fun readHashtable(buffer: ByteBuffer): Map<Any?, Any?> {
        if (buffer.remaining() < 2) return emptyMap()
        val count = buffer.short.toInt() and 0xFFFF
        if (count == 0) return emptyMap()
        val map = HashMap<Any?, Any?>(count)
        repeat(count) { map[readValue(buffer)] = readValue(buffer) }
        return map
    }
    
    private fun readDictionary(buffer: ByteBuffer): Map<Any?, Any?> {
        if (buffer.remaining() < 4) return emptyMap()
        buffer.get()
        buffer.get()
        val count = buffer.short.toInt() and 0xFFFF
        if (count == 0) return emptyMap()
        val map = HashMap<Any?, Any?>(count)
        repeat(count) { map[readValue(buffer)] = readValue(buffer) }
        return map
    }
    
    private fun readObjectArray(buffer: ByteBuffer): Array<Any?> {
        if (buffer.remaining() < 2) return arrayOfNulls(0)
        val length = buffer.short.toInt() and 0xFFFF
        if (length <= 0) return arrayOfNulls(0)
        return Array(length) { readValue(buffer) }
    }
    
    fun getInt(params: Map<Int, Any?>, key: Int, default: Int = 0): Int {
        return when (val v = params[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            else -> default
        }
    }
    
    fun getFloat(params: Map<Int, Any?>, key: Int, default: Float = 0f): Float {
        return when (val v = params[key]) {
            is Float -> v
            is Double -> v.toFloat()
            is Number -> v.toFloat()
            else -> default
        }
    }
    
    fun getString(params: Map<Int, Any?>, key: Int, default: String = ""): String {
        return when (val v = params[key]) {
            is String -> v
            else -> default
        }
    }
    
    fun getBoolean(params: Map<Int, Any?>, key: Int, default: Boolean = false): Boolean {
        return when (val v = params[key]) {
            is Boolean -> v
            is Int -> v != 0
            is Number -> v.toInt() != 0
            else -> default
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    fun getList(params: Map<Int, Any?>, key: Int): List<*>? {
        return when (val v = params[key]) {
            is List<*> -> v
            is Array<*> -> v.toList()
            else -> null
        }
    }
    
    fun scanForTierPrefix(params: Map<Int, Any?>): String? {
        for (v in params.values) {
            if (v is String) {
                val upper = v.uppercase()
                for (t in 1..8) {
                    if (upper.startsWith("T${t}_")) return v
                }
            }
        }
        return null
    }
    
    fun scanForCoordinates(params: Map<Int, Any?>, minValid: Float, maxValid: Float): Pair<Float, Float>? {
        val floats = mutableListOf<Float>()
        for (v in params.values) {
            when (v) {
                is Float -> if (v in minValid..maxValid && v != 0f) floats.add(v)
                is Double -> { 
                    val f = v.toFloat()
                    if (f in minValid..maxValid && f != 0f) floats.add(f) 
                }
            }
        }
        return if (floats.size >= 2) Pair(floats[0], floats[1]) else null
    }
}
