package com.grradar.parser

import android.util.Log
import com.grradar.data.EntityStore
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import com.grradar.model.*

/**
 * Event Dispatcher - Routes Photon events to entity handlers
 */
class EventDispatcher(
    private val entityStore: EntityStore,
    private val idMapRepo: IdMapRepository
) {
    companion object {
        private const val TAG = "EventDispatcher"
    }
    
    private val parser = PhotonParser(object : PhotonParser.PhotonCallback {
        override fun onEvent(eventName: String, params: Map<Int, Any?>) {
            dispatchEvent(eventName, params)
        }
        
        override fun onError(error: String) {
            Log.e(TAG, "Photon error: $error")
            DiscoveryLogger.e("Photon error: $error")
        }
    })
    
    object Events {
        const val JOIN_FINISHED = "JoinFinished"
        const val NEW_CHARACTER = "NewCharacter"
        const val NEW_MOB = "NewMob"
        const val NEW_SIMPLE_HARVESTABLE_OBJECT = "NewSimpleHarvestableObject"
        const val NEW_SIMPLE_HARVESTABLE_OBJECT_LIST = "NewSimpleHarvestableObjectList"
        const val NEW_HARVESTABLE_OBJECT = "NewHarvestableObject"
        const val NEW_SILVER_OBJECT = "NewSilverObject"
        const val LEAVE = "Leave"
        const val MOVE = "Move"
        const val CHANGE_CLUSTER = "ChangeCluster"
    }
    
    fun parsePayload(payload: ByteArray): Boolean {
        return parser.parse(payload)
    }
    
    private fun dispatchEvent(eventName: String, params: Map<Int, Any?>) {
        Log.d(TAG, "Event: $eventName with ${params.size} params")
        
        // LOG ALL EVENTS TO DISCOVERY LOGGER
        DiscoveryLogger.logEvent(eventName, params)
        
        when (eventName) {
            Events.JOIN_FINISHED -> handleJoinFinished(params)
            Events.NEW_CHARACTER -> handleNewCharacter(params)
            Events.NEW_MOB -> handleNewMob(params)
            Events.NEW_SIMPLE_HARVESTABLE_OBJECT -> handleNewHarvestable(params)
            Events.NEW_SIMPLE_HARVESTABLE_OBJECT_LIST -> handleHarvestableList(params)
            Events.NEW_HARVESTABLE_OBJECT -> handleNewHarvestable(params)
            Events.NEW_SILVER_OBJECT -> handleSilver(params)
            Events.LEAVE -> handleLeave(params)
            Events.MOVE -> handleMove(params)
            Events.CHANGE_CLUSTER -> handleChangeCluster(params)
            else -> Log.v(TAG, "Unhandled: $eventName")
        }
    }
    
    private fun handleJoinFinished(params: Map<Int, Any?>) {
        val keys = idMapRepo.getJoinFinishedKeys()
        val objectId = parser.getInt(params, keys.localObjectIdKey)
        var posX = parser.getFloat(params, keys.posXKey)
        var posY = parser.getFloat(params, keys.posYKey)
        
        Log.i(TAG, "JoinFinished: id=$objectId pos=($posX,$posY)")
        
        // Plan B: Scan coordinates
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(params, planB.minValid.toFloat(), planB.maxValid.toFloat())
            if (coords != null) {
                posX = coords.first
                posY = coords.second
                Log.i(TAG, "JoinFinished PlanB: ($posX,$posY)")
            }
        }
        
        entityStore.setLocalPlayerId(objectId)
        entityStore.setLocalPlayerPosition(posX, posY)
    }
    
    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val playerKeys = idMapRepo.getPlayerKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        var posX = parser.getFloat(params, commonKeys.posXKey)
        var posY = parser.getFloat(params, commonKeys.posYKey)
        val name = parser.getString(params, playerKeys.nameKey)
        
        // Plan B
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(params, planB.minValid.toFloat(), planB.maxValid.toFloat())
            if (coords != null) { posX = coords.first; posY = coords.second }
        }
        
        if (objectId == entityStore.getLocalPlayerId()) {
            entityStore.setLocalPlayerPosition(posX, posY)
            return
        }
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.PLAYER,
            worldX = posX,
            worldY = posY,
            typeName = "PLAYER",
            name = name
        )
        entityStore.putEntity(entity)
        Log.d(TAG, "Player: $name at ($posX,$posY)")
    }
    
    private fun handleNewMob(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val mobKeys = idMapRepo.getMobKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        var posX = parser.getFloat(params, commonKeys.posXKey)
        var posY = parser.getFloat(params, commonKeys.posYKey)
        var typeName = parser.getString(params, mobKeys.typeNameKey)
        var tier = parser.getInt(params, mobKeys.tierKey)
        
        // Plan B
        typeName = if (typeName.isEmpty()) parser.scanForTierPrefix(params) ?: "" else typeName
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(params, planB.minValid.toFloat(), planB.maxValid.toFloat())
            if (coords != null) { posX = coords.first; posY = coords.second }
        }
        
        tier = if (tier > 0) tier else idMapRepo.extractTier(typeName)
        val enchant = idMapRepo.extractEnchantment(typeName)
        val entityType = idMapRepo.classifyEntity(typeName)
        
        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchantment = enchant
        )
        entityStore.putEntity(entity)
        Log.d(TAG, "Mob: $typeName at ($posX,$posY)")
    }
    
    private fun handleNewHarvestable(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val harvestKeys = idMapRepo.getHarvestableKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        var posX = parser.getFloat(params, commonKeys.posXKey)
        var posY = parser.getFloat(params, commonKeys.posYKey)
        var typeName = parser.getString(params, harvestKeys.typeNameKey)
        var tier = parser.getInt(params, harvestKeys.tierKey)
        
        // Plan B
        typeName = if (typeName.isEmpty()) parser.scanForTierPrefix(params) ?: "" else typeName
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(params, planB.minValid.toFloat(), planB.maxValid.toFloat())
            if (coords != null) { posX = coords.first; posY = coords.second }
        }
        
        tier = if (tier > 0) tier else idMapRepo.extractTier(typeName)
        val enchant = idMapRepo.extractEnchantment(typeName)
        val entityType = idMapRepo.classifyEntity(typeName)
        
        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchantment = enchant
        )
        entityStore.putEntity(entity)
        Log.d(TAG, "Resource: $typeName at ($posX,$posY)")
    }
    
    private fun handleHarvestableList(params: Map<Int, Any?>) {
        val list = parser.getList(params, 2)
        list?.forEach { if (it is Map<*, *>) @Suppress("UNCHECKED_CAST") handleNewHarvestable(it as Map<Int, Any?>) }
    }
    
    private fun handleSilver(params: Map<Int, Any?>) {
        val objectId = parser.getInt(params, 0)
        val posX = parser.getFloat(params, 8)
        val posY = parser.getFloat(params, 9)
        entityStore.putEntity(RadarEntity(id = objectId, type = EntityType.SILVER, worldX = posX, worldY = posY, typeName = "SILVER"))
    }
    
    private fun handleLeave(params: Map<Int, Any?>) {
        entityStore.removeEntity(parser.getInt(params, 0))
    }
    
    private fun handleMove(params: Map<Int, Any?>) {
        val objectId = parser.getInt(params, 0)
        val posX = parser.getFloat(params, 8)
        val posY = parser.getFloat(params, 9)
        
        if (objectId == entityStore.getLocalPlayerId()) {
            entityStore.setLocalPlayerPosition(posX, posY)
        } else {
            entityStore.getEntity(objectId)?.let {
                entityStore.putEntity(it.copy(worldX = posX, worldY = posY))
            }
        }
    }
    
    private fun handleChangeCluster(params: Map<Int, Any?>) {
        Log.i(TAG, "Zone change - clearing entities")
        entityStore.clearAll()
    }
}
