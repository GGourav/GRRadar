package com.grradar.parser

import android.util.Log
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photon Protocol 16 Parser
 *
 * UDP Payload Structure:
 *   Photon Header (12 bytes):
 *     [0-1]  PeerID      uint16 big-endian
 *     [2]    Flags       uint8
 *     [3]    CmdCount    uint8  number of commands
 *     [4-7]  Timestamp   uint32
 *     [8-11] Challenge   uint32
 *   Commands (repeat CmdCount times):
 *     [0]    CmdType     uint8  6=reliable, 7=unreliable
 *     [1]    ChannelId   uint8
 *     [2]    CmdFlags    uint8
 *     [3]    Reserved    uint8
 *     [4-7]  CmdLength   uint32 total bytes incl. this hdr
 *     [8-11] ReliableSeq uint32
 *     [12+]  Payload
 *
 * Payload (first byte = message type):
 *   0x02 = OperationRequest  (client → server, skip)
 *   0x03 = OperationResponse (server → client, skip)
 *   0x04 = Event             ← THIS IS WHAT WE PARSE
 */
object PhotonParser {

    private const val TAG = "PhotonParser"

    // Photon type codes
    private const val TYPE_NULL = 0x2A
    private const val TYPE_BOOLEAN = 0x6F  // 'o'
    private const val TYPE_BYTE = 0x62     // 'b'
    private const val TYPE_SHORT = 0x6B    // 'k'
    private const val TYPE_INTEGER = 0x69  // 'i'
    private const val TYPE_LONG = 0x6C     // 'l'
    private const val TYPE_FLOAT = 0x66    // 'f'
    private const val TYPE_DOUBLE = 0x64   // 'd'
    private const val TYPE_STRING = 0x73   // 's'
    private const val TYPE_BYTE_ARRAY = 0x78  // 'x'
    private const val TYPE_INT_ARRAY = 0x6E   // 'n'
    private const val TYPE_ARRAY = 0x61       // 'a'
    private const val TYPE_HASHTABLE = 0x68   // 'h'
    private const val TYPE_DICTIONARY = 0x44  // 'D'
    private const val TYPE_OBJECT_ARRAY = 0x7A // 'z'

    // Message types
    private const val MSG_OPERATION_REQUEST = 0x02
    private const val MSG_OPERATION_RESPONSE = 0x03
    private const val MSG_EVENT = 0x04

    // Command types
    private const val CMD_RELIABLE = 6
    private const val CMD_UNRELIABLE = 7

    private var eventDispatcher: EventDispatcher? = null

    fun init(dispatcher: EventDispatcher) {
        eventDispatcher = dispatcher
    }

    /**
     * Parse a Photon packet and return list of event params
     */
    fun parse(data: ByteArray, length: Int): List<Map<Int, Any?>> {
        val events = mutableListOf<Map<Int, Any?>>()

        if (length < 12) {
            return events
        }

        try {
            val buf = ByteBuffer.wrap(data, 0, length)
            buf.order(ByteOrder.BIG_ENDIAN)

            // Parse header (12 bytes)
            val peerId = buf.short.toInt() and 0xFFFF
            val flags = buf.get.toInt() and 0xFF
            val cmdCount = buf.get.toInt() and 0xFF
            val timestamp = buf.int // skip
            val challenge = buf.int // skip

            // Loop through commands
            repeat(cmdCount) {
                val event = parseCommand(buf)
                if (event != null) {
                    events.add(event)
                }
            }

        } catch (e: Exception) {
            DiscoveryLogger.e("Photon parse error: ${e.message}")
        }

        return events
    }

    private fun parseCommand(buf: ByteBuffer): Map<Int, Any?>? {
        if (buf.remaining() < 12) return null

        val cmdType = buf.get.toInt() and 0xFF
        val channelId = buf.get.toInt() and 0xFF
        val cmdFlags = buf.get.toInt() and 0xFF
        buf.get() // reserved
        val cmdLength = buf.int
        val reliableSeq = buf.int

        // Only process reliable (6) or unreliable (7) commands
        if (cmdType != CMD_RELIABLE && cmdType != CMD_UNRELIABLE) {
            // Skip payload
            val payloadSize = cmdLength - 12
            if (payloadSize > 0 && buf.remaining() >= payloadSize) {
                buf.position(buf.position() + payloadSize)
            }
            return null
        }

        val payloadSize = cmdLength - 12
        if (payloadSize <= 0 || buf.remaining() < payloadSize) {
            return null
        }

        val payloadStart = buf.position()

        var event: Map<Int, Any?>? = null

        try {
            val msgType = buf.get.toInt() and 0xFF

            if (msgType == MSG_EVENT) {
                event = parseEvent(buf)
            }

        } catch (e: Exception) {
            DiscoveryLogger.v("Command parse error: ${e.message}")
        }

        // Always advance to end of payload
        buf.position(payloadStart + payloadSize)

        return event
    }

    private fun parseEvent(buf: ByteBuffer): Map<Int, Any?> {
        val params = HashMap<Int, Any?>()

        if (buf.remaining() < 3) return params

        val eventCode = buf.get.toInt() and 0xFF
        val paramCount = buf.short.toInt() and 0xFFFF

        repeat(paramCount) {
            if (buf.remaining() < 2) return@repeat

            val key = buf.get.toInt() and 0xFF
            val value = readValue(buf)
            params[key] = value
        }

        // Store event code in params[252] for dispatcher
        params[252] = eventCode

        return params
    }

    private fun readValue(buf: ByteBuffer): Any? {
        if (buf.remaining() < 1) return null

        val typeCode = buf.get.toInt() and 0xFF

        return when (typeCode) {
            TYPE_NULL -> null

            TYPE_BOOLEAN -> {
                if (buf.remaining() >= 1) buf.get.toInt() != 0 else null
            }

            TYPE_BYTE -> {
                if (buf.remaining() >= 1) buf.get.toInt() and 0xFF else null
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
                    if (buf.remaining() >= len) {
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null
            }

            TYPE_BYTE_ARRAY -> {
                if (buf.remaining() >= 4) {
                    val len = buf.int
                    if (buf.remaining() >= len) {
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        bytes
                    } else null
                } else null
            }

            TYPE_INT_ARRAY -> {
                if (buf.remaining() >= 4) {
                    val len = buf.int
                    if (buf.remaining() >= len * 4) {
                        IntArray(len) { buf.int }
                    } else null
                } else null
            }

            TYPE_ARRAY -> {
                if (buf.remaining() >= 3) {
                    val len = buf.short.toInt() and 0xFFFF
                    val elemType = buf.get.toInt() and 0xFF
                    List(len) { readValueByType(buf, elemType) }
                } else null
            }

            TYPE_HASHTABLE -> {
                if (buf.remaining() >= 2) {
                    val count = buf.short.toInt() and 0xFFFF
                    val map = HashMap<Any?, Any?>()
                    repeat(count) {
                        val k = readValue(buf)
                        val v = readValue(buf)
                        map[k] = v
                    }
                    map
                } else null
            }

            TYPE_DICTIONARY -> {
                if (buf.remaining() >= 4) {
                    val keyType = buf.get.toInt() and 0xFF
                    val valType = buf.get.toInt() and 0xFF
                    val count = buf.short.toInt() and 0xFFFF
                    val map = HashMap<Any?, Any?>()
                    repeat(count) {
                        val k = readValueByType(buf, keyType)
                        val v = readValueByType(buf, valType)
                        map[k] = v
                    }
                    map
                } else null
            }

            TYPE_OBJECT_ARRAY -> {
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xFFFF
                    List(len) { readValue(buf) }
                } else null
            }

            else -> {
                DiscoveryLogger.logUnknownType(typeCode, "readValue")
                null
            }
        }
    }

    private fun readValueByType(buf: ByteBuffer, typeCode: Int): Any? {
        return when (typeCode) {
            TYPE_NULL -> null
            TYPE_BOOLEAN -> if (buf.remaining() >= 1) buf.get.toInt() != 0 else null
            TYPE_BYTE -> if (buf.remaining() >= 1) buf.get.toInt() and 0xFF else null
            TYPE_SHORT -> if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else null
            TYPE_INTEGER -> if (buf.remaining() >= 4) buf.int else null
            TYPE_LONG -> if (buf.remaining() >= 8) buf.long else null
            TYPE_FLOAT -> if (buf.remaining() >= 4) buf.float else null
            TYPE_DOUBLE -> if (buf.remaining() >= 8) buf.double else null
            TYPE_STRING -> {
                if (buf.remaining() >= 2) {
                    val len = buf.short.toInt() and 0xFFFF
                    if (buf.remaining() >= len) {
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null
            }
            else -> readValue(buf)
        }
    }

    /**
     * Dispatch parsed events
     */
    fun dispatchEvents(events: List<Map<Int, Any?>>) {
        events.forEach { params ->
            eventDispatcher?.dispatch(params)
        }
    }
}
