package com.grradar.parser

import android.util.Log
import com.grradar.data.EntityStore
import com.grradar.data.IdMapRepository
import com.grradar.model.*

/**
 * Event Dispatcher - Routes Photon events to entity handlers
 * 
 * Uses STRING-BASED dispatch (not integer codes) for patch resilience.
 * Event name strings are stable across Albion patches.
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
        }
    })
    
    // Event name constants (stable across patches)
    object Events {
        const val JOIN_FINISHED = "JoinFinished"
        const val NEW_CHARACTER = "NewCharacter"
        const val NEW_MOB = "NewMob"
        const val NEW_SIMPLE_HARVESTABLE_OBJECT = "NewSimpleHarvestableObject"
        const val NEW_SIMPLE_HARVESTABLE_OBJECT_LIST = "NewSimpleHarvestableObjectList"
        const val NEW_HARVESTABLE_OBJECT = "NewHarvestableObject"
        const val NEW_SILVER_OBJECT = "NewSilverObject"
        const val NEW_SIMPLE_ITEM = "NewSimpleItem"
        const val NEW_FISHING_ZONE_OBJECT = "NewFishingZoneObject"
        const val NEW_MISTS_CAGED_WISP = "NewMistsCagedWisp"
        const val NEW_MISTS_WISP_SPAWN = "NewMistsWispSpawn"
        const val NEW_MIST_DUNGEON_ROOM_MOB_SOUL = "NewMistDungeonRoomMobSoul"
        const val NEW_LOOT_CHEST = "NewLootChest"
        const val NEW_TREASURE_CHEST = "NewTreasureChest"
        const val NEW_CARRIABLE_OBJECT = "NewCarriableObject"
        const val NEW_RANDOM_DUNGEON_EXIT = "NewRandomDungeonExit"
        const val NEW_EXPEDITION_EXIT = "NewExpeditionExit"
        const val NEW_HELLGATE_EXIT_PORTAL = "NewHellgateExitPortal"
        const val NEW_MISTS_DUNGEON_EXIT = "NewMistsDungeonExit"
        const val NEW_PORTAL_ENTRANCE = "NewPortalEntrance"
        const val NEW_PORTAL_EXIT = "NewPortalExit"
        const val LEAVE = "Leave"
        const val MOVE = "Move"
        const val FORCED_MOVEMENT = "ForcedMovement"
        const val HARVEST_FINISHED = "HarvestFinished"
        const val CHANGE_CLUSTER = "ChangeCluster"
        const val HEALTH_UPDATE = "HealthUpdate"
        const val MOUNT_HEALTH_UPDATE = "MountHealthUpdate"
    }
    
    /**
     * Parse raw UDP payload
     */
    fun parsePayload(payload: ByteArray) {
        parser.parse(payload)
    }
    
    /**
     * Dispatch event by name to appropriate handler
     */
    private fun dispatchEvent(eventName: String, params: Map<Int, Any?>) {
        Log.d(TAG, "Dispatching: $eventName with ${params.size} params")
        
        when (eventName) {
            Events.JOIN_FINISHED -> handleJoinFinished(params)
            Events.NEW_CHARACTER -> handleNewCharacter(params)
            Events.NEW_MOB -> handleNewMob(params)
            Events.NEW_SIMPLE_HARVESTABLE_OBJECT -> handleNewHarvestable(params)
            Events.NEW_SIMPLE_HARVESTABLE_OBJECT_LIST -> handleHarvestableList(params)
            Events.NEW_HARVESTABLE_OBJECT -> handleNewHarvestable(params)
            Events.NEW_SILVER_OBJECT -> handleSilver(params)
            Events.NEW_MISTS_CAGED_WISP -> handleMistsCagedWisp(params)
            Events.NEW_MISTS_WISP_SPAWN -> handleMistsWispSpawn(params)
            Events.NEW_LOOT_CHEST -> handleLootChest(params)
            Events.NEW_TREASURE_CHEST -> handleTreasureChest(params)
            Events.NEW_RANDOM_DUNGEON_EXIT -> handleDungeonPortal(params)
            Events.NEW_MISTS_DUNGEON_EXIT -> handleMistDungeonExit(params)
            Events.NEW_HELLGATE_EXIT_PORTAL -> handleHellgatePortal(params)
            Events.LEAVE -> handleLeave(params)
            Events.MOVE -> handleMove(params)
            Events.HARVEST_FINISHED -> handleHarvestFinished(params)
            Events.CHANGE_CLUSTER -> handleChangeCluster(params)
            Events.HEALTH_UPDATE -> handleHealthUpdate(params)
            else -> Log.v(TAG, "Unhandled event: $eventName")
        }
    }
    
    /**
     * Handle JoinFinished - CRITICAL for local player position
     * Fires BEFORE NewCharacter when entering a zone
     */
    private fun handleJoinFinished(params: Map<Int, Any?>) {
        val keys = idMapRepo.getJoinFinishedKeys()
        
        val objectId = parser.getInt(params, keys.localObjectIdKey)
        val posX = parser.getFloat(params, keys.posXKey)
        val posY = parser.getFloat(params, keys.posYKey)
        
        // Fallback: Scan for coordinates if key lookup fails
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(
                params, 
                planB.minValid.toFloat(), 
                planB.maxValid.toFloat()
            )
            if (coords != null) {
                entityStore.setLocalPlayerPosition(coords.first, coords.second)
            }
        } else {
            entityStore.setLocalPlayerPosition(posX, posY)
        }
        
        entityStore.setLocalPlayerId(objectId)
        
        Log.i(TAG, "JoinFinished: localPlayerId=$objectId, pos=($posX, $posY)")
    }
    
    /**
     * Handle NewCharacter - Player spawn event
     */
    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val playerKeys = idMapRepo.getPlayerKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        val name = parser.getString(params, playerKeys.nameKey)
        val guild = parser.getString(params, playerKeys.guildKey)
        val alliance = parser.getString(params, playerKeys.allianceKey)
        val factionFlagValue = parser.getInt(params, playerKeys.factionFlagKey)
        val health = parser.getFloat(params, playerKeys.healthKey, 1.0f)
        
        // Check if this is the local player
        val localPlayerId = entityStore.getLocalPlayerId()
        if (objectId == localPlayerId) {
            entityStore.setLocalPlayerPosition(posX, posY)
            return
        }
        
        // Determine if hostile
        val factionFlag = FactionFlag.fromInt(factionFlagValue)
        val isHostile = factionFlag == FactionFlag.HOSTILE
        
        val entity = RadarEntity(
            id = objectId,
            type = if (isHostile) EntityType.HOSTILE_PLAYER else EntityType.PLAYER,
            worldX = posX,
            worldY = posY,
            typeName = "PLAYER",
            tier = 0,
            enchantment = Enchantment.NONE,
            name = name,
            guildName = guild,
            allianceName = alliance,
            factionFlag = factionFlag,
            health = health
        )
        
        entityStore.putEntity(entity)
        Log.d(TAG, "NewCharacter: $name (id=$objectId, hostile=$isHostile)")
    }
    
    /**
     * Handle NewMob - Mob spawn event
     */
    private fun handleNewMob(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val mobKeys = idMapRepo.getMobKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        val typeName = parser.getString(params, mobKeys.typeNameKey)
        val tier = parser.getInt(params, mobKeys.tierKey)
        val enchantValue = parser.getInt(params, mobKeys.enchantKey)
        val isBoss = parser.getBoolean(params, mobKeys.isBossKey)
        val health = parser.getFloat(params, mobKeys.healthKey, 1.0f)
        
        // Plan B: Scan for tier prefix if typeName not found
        val actualTypeName = if (typeName.isEmpty()) {
            parser.scanForTierPrefix(params) ?: ""
        } else {
            typeName
        }
        
        // Plan B: Scan for coordinates if pos not found
        var actualX = posX
        var actualY = posY
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(
                params,
                planB.minValid.toFloat(),
                planB.maxValid.toFloat()
            )
            if (coords != null) {
                actualX = coords.first
                actualY = coords.second
            }
        }
        
        // Extract tier and enchantment from typeName
        val actualTier = if (tier > 0) tier else idMapRepo.extractTier(actualTypeName)
        val actualEnchant = if (enchantValue > 0) {
            Enchantment.fromInt(enchantValue)
        } else {
            idMapRepo.extractEnchantment(actualTypeName)
        }
        
        // Classify entity type
        val entityType = idMapRepo.classifyEntity(actualTypeName)
        val mobCategory = idMapRepo.getMobCategory(actualTypeName)
        
        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = actualX,
            worldY = actualY,
            typeName = actualTypeName,
            tier = actualTier,
            enchantment = actualEnchant,
            health = health,
            isBoss = isBoss || entityType == EntityType.BOSS || 
                     entityType == EntityType.VETERAN_BOSS || 
                     entityType == EntityType.MINIBOSS,
            isVeteran = entityType == EntityType.VETERAN_BOSS,
            isElite = entityType == EntityType.ELITE_MOB,
            mobCategory = mobCategory
        )
        
        entityStore.putEntity(entity)
        Log.d(TAG, "NewMob: $actualTypeName (id=$objectId, type=$entityType, tier=$actualTier)")
    }
    
    /**
     * Handle NewSimpleHarvestableObject - Resource spawn event
     */
    private fun handleNewHarvestable(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val harvestKeys = idMapRepo.getHarvestableKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        val typeName = parser.getString(params, harvestKeys.typeNameKey)
        val tier = parser.getInt(params, harvestKeys.tierKey)
        val enchantValue = parser.getInt(params, harvestKeys.enchantKey)
        
        // Plan B: Scan for tier prefix
        val actualTypeName = if (typeName.isEmpty()) {
            parser.scanForTierPrefix(params) ?: ""
        } else {
            typeName
        }
        
        // Plan B: Scan for coordinates
        var actualX = posX
        var actualY = posY
        if (posX == 0f && posY == 0f) {
            val planB = idMapRepo.getCoordinatePlanB()
            val coords = parser.scanForCoordinates(
                params,
                planB.minValid.toFloat(),
                planB.maxValid.toFloat()
            )
            if (coords != null) {
                actualX = coords.first
                actualY = coords.second
            }
        }
        
        // Extract tier and enchantment
        val actualTier = if (tier > 0) tier else idMapRepo.extractTier(actualTypeName)
        val actualEnchant = if (enchantValue > 0) {
            Enchantment.fromInt(enchantValue)
        } else {
            idMapRepo.extractEnchantment(actualTypeName)
        }
        
        // Classify resource type
        val entityType = idMapRepo.classifyEntity(actualTypeName)
        
        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = actualX,
            worldY = actualY,
            typeName = actualTypeName,
            tier = actualTier,
            enchantment = actualEnchant,
            mobCategory = MobCategory.HARVESTABLE
        )
        
        entityStore.putEntity(entity)
        Log.d(TAG, "Harvestable: $actualTypeName (id=$objectId, tier=$actualTier, enchant=$actualEnchant)")
    }
    
    /**
     * Handle batch harvestable list
     */
    private fun handleHarvestableList(params: Map<Int, Any?>) {
        val harvestKeys = idMapRepo.getHarvestableKeys()
        val list = parser.getList(params, harvestKeys.listKey)
        
        if (list != null) {
            @Suppress("UNCHECKED_CAST")
            for (item in list) {
                if (item is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val itemParams = item as Map<Int, Any?>
                    handleNewHarvestable(itemParams)
                }
            }
        }
    }
    
    /**
     * Handle Silver spawn
     */
    private fun handleSilver(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.SILVER,
            worldX = posX,
            worldY = posY,
            typeName = "SILVER"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Mists Caged Wisp
     */
    private fun handleMistsCagedWisp(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.CAGED_WISP,
            worldX = posX,
            worldY = posY,
            typeName = "MISTS_CAGED_WISP"
        )
        
        entityStore.putEntity(entity)
        Log.d(TAG, "Caged Wisp at ($posX, $posY)")
    }
    
    /**
     * Handle Mists Wisp Spawn
     */
    private fun handleMistsWispSpawn(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.MIST_WISP,
            worldX = posX,
            worldY = posY,
            typeName = "MISTS_WISP_SPAWN"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Loot Chest
     */
    private fun handleLootChest(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.TREASURE_CHEST,
            worldX = posX,
            worldY = posY,
            typeName = "LOOT_CHEST"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Treasure Chest
     */
    private fun handleTreasureChest(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.TREASURE_CHEST,
            worldX = posX,
            worldY = posY,
            typeName = "TREASURE_CHEST"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Dungeon Portal
     */
    private fun handleDungeonPortal(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = posX,
            worldY = posY,
            typeName = "DUNGEON_PORTAL"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Mist Dungeon Exit
     */
    private fun handleMistDungeonExit(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = posX,
            worldY = posY,
            typeName = "MISTS_DUNGEON_EXIT"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Hellgate Portal
     */
    private fun handleHellgatePortal(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.HELLGATE,
            worldX = posX,
            worldY = posY,
            typeName = "HELLGATE_PORTAL"
        )
        
        entityStore.putEntity(entity)
    }
    
    /**
     * Handle Leave - Entity despawn
     */
    private fun handleLeave(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        
        entityStore.removeEntity(objectId)
        Log.d(TAG, "Entity left: $objectId")
    }
    
    /**
     * Handle Move - Entity position update
     */
    private fun handleMove(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val posX = parser.getFloat(params, commonKeys.posXKey)
        val posY = parser.getFloat(params, commonKeys.posYKey)
        
        // Check if this is the local player
        val localPlayerId = entityStore.getLocalPlayerId()
        if (objectId == localPlayerId) {
            entityStore.setLocalPlayerPosition(posX, posY)
            return
        }
        
        // Update existing entity
        val existing = entityStore.getEntity(objectId)
        if (existing != null) {
            val updated = existing.copy(
                worldX = posX,
                worldY = posY
            )
            entityStore.putEntity(updated)
        }
    }
    
    /**
     * Handle HarvestFinished - Resource depleted
     */
    private fun handleHarvestFinished(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        
        entityStore.removeEntity(objectId)
        Log.d(TAG, "Resource depleted: $objectId")
    }
    
    /**
     * Handle ChangeCluster - Zone transition
     * CRITICAL: Clear all entities on zone change
     */
    private fun handleChangeCluster(params: Map<Int, Any?>) {
        Log.i(TAG, "Zone change detected - clearing entities")
        
        // Get zone info if available
        val zoneName = parser.getString(params, 1, "")
        
        entityStore.clearAll()
        entityStore.setCurrentZone(zoneName)
    }
    
    /**
     * Handle HealthUpdate - Entity health changed
     */
    private fun handleHealthUpdate(params: Map<Int, Any?>) {
        val commonKeys = idMapRepo.getCommonKeys()
        val mobKeys = idMapRepo.getMobKeys()
        
        val objectId = parser.getInt(params, commonKeys.objectIdKey)
        val health = parser.getFloat(params, mobKeys.healthKey, 1.0f)
        
        // Update existing entity health
        val existing = entityStore.getEntity(objectId)
        if (existing != null) {
            val updated = existing.copy(health = health)
            entityStore.putEntity(updated)
        }
    }
}
