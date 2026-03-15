package com.grradar.parser

import android.util.Log
import com.grradar.entity.RadarEntity
import com.grradar.entity.EntityType
import com.grradar.overlay.RadarOverlayManager
import com.grradar.parser.IdMapRepository
import com.grradar.discovery.DiscoveryLogger

/**
 * Dispatches parsed Photon events to the radar overlay.
 * Handles entity spawning, position updates, and removal.
 */
object EventDispatcher {
    
    private const val TAG = "EventDispatcher"
    
    // Entity tracking: objectId -> RadarEntity
    private val entities = mutableMapOf<Int, RadarEntity>()
    
    // Player reference (objectId = 0 is typically local player)
    private var playerX: Float = 0f
    private var playerY: Float = 0f
    
    // Statistics
    private var totalEventsProcessed = 0
    private var totalEntitiesCreated = 0
    private var totalEntitiesUpdated = 0
    
    /**
     * Process a parsed Photon event.
     * Returns true if the event was handled.
     */
    fun dispatchEvent(eventName: String, params: Map<Int, Any>): Boolean {
        totalEventsProcessed++
        
        DiscoveryLogger.log("EVENT", "Dispatching: $eventName with ${params.size} params")
        Log.d(TAG, "=== DISPATCH EVENT: $eventName ===")
        Log.d(TAG, "Params: ${params.keys.joinToString()}")
        
        // Log all parameter keys and values for debugging
        params.forEach { (key, value) ->
            val valueStr = when (value) {
                is ByteArray -> "ByteArray[${value.size}]"
                is String -> "\"$value\""
                else -> value.toString()
            }
            DiscoveryLogger.log("PARAM", "  key=$key value=$valueStr")
            Log.d(TAG, "  param[$key] = $valueStr")
        }
        
        val handled = when (eventName) {
            // Entity spawn events
            "spawn", "Spawn", "SPAWN" -> handleSpawn(params)
            "AddItem", "addItem" -> handleSpawn(params)
            "NewObject", "newObject" -> handleSpawn(params)
            
            // Position update events
            "move", "Move", "MOVE" -> handleMove(params)
            "PositionUpdate", "positionUpdate" -> handleMove(params)
            "SetPosition", "setPosition" -> handleMove(params)
            
            // Character events
            "CharacterInfo", "characterInfo" -> handleCharacterInfo(params)
            "PlayerJoined", "playerJoined" -> handleSpawn(params)
            
            // Resource/harvestable events
            "HarvestableObject", "harvestable" -> handleSpawn(params)
            "ResourceSpawn", "resourceSpawn" -> handleSpawn(params)
            
            // Mob/monster events  
            "MobSpawn", "mobSpawn" -> handleSpawn(params)
            "MonsterSpawn", "monsterSpawn" -> handleSpawn(params)
            
            // Remove events
            "despawn", "Despawn", "DESPAWN" -> handleDespawn(params)
            "RemoveItem", "removeItem" -> handleDespawn(params)
            "DeleteObject", "deleteObject" -> handleDespawn(params)
            
            // Health/damage events
            "HealthUpdate", "healthUpdate" -> handleHealthUpdate(params)
            
            // Unknown event - try to parse anyway
            else -> handleUnknownEvent(eventName, params)
        }
        
        if (handled) {
            DiscoveryLogger.log("EVENT", "✅ Successfully handled: $eventName")
        } else {
            DiscoveryLogger.log("EVENT", "⚠️ Could not handle: $eventName")
        }
        
        return handled
    }
    
    /**
     * Handle entity spawn event.
     */
    private fun handleSpawn(params: Map<Int, Any>): Boolean {
        DiscoveryLogger.log("SPAWN", "Processing spawn event")
        
        // Get object ID (key 0)
        val objectId = when (val id = params[0]) {
            is Int -> id
            is Long -> id.toInt()
            is Number -> id.toInt()
            else -> {
                DiscoveryLogger.log("SPAWN", "No valid objectId in params[0]: $id")
                Log.w(TAG, "No objectId in spawn event")
                return false
            }
        }
        
        // Get position (keys 8, 9 for x, y)
        val posX = extractFloat(params[8])
        val posY = extractFloat(params[9])
        
        // Get type information
        val typeName = extractString(params[1])
        val typeId = extractInt(params[2])
        val tier = extractInt(params[7])
        val enchant = extractInt(params[11])
        
        DiscoveryLogger.log("SPAWN", "objectId=$objectId type='$typeName' pos=($posX, $posY) tier=$tier enchant=$enchant")
        
        // Determine entity type
        val entityType = determineEntityType(typeName, typeId, params)
        
        // Create entity
        val entity = RadarEntity(
            objectId = objectId,
            entityType = entityType,
            posX = posX,
            posY = posY,
            typeName = typeName ?: "unknown",
            tier = tier,
            enchantment = enchant
        )
        
        // Store entity
        entities[objectId] = entity
        totalEntitiesCreated++
        
        DiscoveryLogger.log("SPAWN", "✅ Created entity: $entity")
        
        // Update overlay
        updateOverlay()
        
        return true
    }
    
    /**
     * Handle position update event.
     */
    private fun handleMove(params: Map<Int, Any>): Boolean {
        val objectId = extractInt(params[0]) ?: return false
        val posX = extractFloat(params[8])
        val posY = extractFloat(params[9])
        
        val entity = entities[objectId]
        if (entity != null) {
            entity.posX = posX
            entity.posY = posY
            totalEntitiesUpdated++
            
            DiscoveryLogger.log("MOVE", "Updated entity $objectId to ($posX, $posY)")
            
            // Check if this is player movement
            if (objectId == 0 || entity.entityType == EntityType.PLAYER) {
                playerX = posX
                playerY = posY
                DiscoveryLogger.log("PLAYER", "Player position: ($playerX, $playerY)")
            }
            
            updateOverlay()
            return true
        }
        
        // Might be a new entity we haven't seen
        DiscoveryLogger.log("MOVE", "Move for unknown entity $objectId, treating as spawn")
        return handleSpawn(params)
    }
    
    /**
     * Handle character info (usually local player).
     */
    private fun handleCharacterInfo(params: Map<Int, Any>): Boolean {
        DiscoveryLogger.log("CHAR", "CharacterInfo params: ${params.keys.joinToString()}")
        
        // Try to get position
        val posX = extractFloat(params[8])
        val posY = extractFloat(params[9])
        
        if (posX != 0f || posY != 0f) {
            playerX = posX
            playerY = posY
            DiscoveryLogger.log("PLAYER", "Player position from CharacterInfo: ($playerX, $playerY)")
        }
        
        return true
    }
    
    /**
     * Handle entity despawn event.
     */
    private fun handleDespawn(params: Map<Int, Any>): Boolean {
        val objectId = extractInt(params[0]) ?: return false
        
        val removed = entities.remove(objectId)
        if (removed != null) {
            DiscoveryLogger.log("DESPAWN", "Removed entity $objectId (${removed.typeName})")
            updateOverlay()
            return true
        }
        
        return false
    }
    
    /**
     * Handle health update event.
     */
    private fun handleHealthUpdate(params: Map<Int, Any>): Boolean {
        val objectId = extractInt(params[0]) ?: return false
        val health = extractInt(params[1]) ?: return false
        val maxHealth = extractInt(params[2]) ?: health
        
        val entity = entities[objectId]
        if (entity != null) {
            entity.health = health
            entity.maxHealth = maxHealth
            DiscoveryLogger.log("HEALTH", "Entity $objectId health: $health/$maxHealth")
            updateOverlay()
            return true
        }
        
        return false
    }
    
    /**
     * Handle unknown events by trying to extract useful data.
     */
    private fun handleUnknownEvent(eventName: String, params: Map<Int, Any>): Boolean {
        DiscoveryLogger.log("UNKNOWN", "Attempting to parse unknown event: $eventName")
        
        // Check if this looks like an entity event
        val hasObjectId = params.containsKey(0)
        val hasPosition = params.containsKey(8) && params.containsKey(9)
        
        if (hasObjectId && hasPosition) {
            DiscoveryLogger.log("UNKNOWN", "Event looks like entity spawn, attempting parse")
            return handleSpawn(params)
        }
        
        // Check for type name patterns
        for ((key, value) in params) {
            if (value is String) {
                when {
                    value.contains("T1_") || value.contains("T2_") || 
                    value.contains("T3_") || value.contains("T4_") ||
                    value.contains("T5_") || value.contains("T6_") ||
                    value.contains("T7_") || value.contains("T8_") -> {
                        DiscoveryLogger.log("UNKNOWN", "Found resource type in param[$key]: $value")
                        return handleSpawn(params)
                    }
                    value.contains("KEEPER") || value.contains("MIST") -> {
                        DiscoveryLogger.log("UNKNOWN", "Found mob type in param[$key]: $value")
                        return handleSpawn(params)
                    }
                }
            }
        }
        
        return false
    }
    
    /**
     * Determine entity type from various parameters.
     */
    private fun determineEntityType(typeName: String?, typeId: Int?, params: Map<Int, Any>): EntityType {
        // Check type name first
        if (typeName != null) {
            val upper = typeName.uppercase()
            when {
                upper.contains("PLAYER") -> return EntityType.PLAYER
                upper.contains("MOB") || upper.contains("MONSTER") -> return EntityType.MOB
                upper.contains("KEEPER") -> return EntityType.MOB
                upper.contains("MIST") -> return EntityType.MOB
                upper.contains("RESOURCE") || upper.contains("HARVESTABLE") -> return EntityType.RESOURCE
                upper.contains("TREE") || upper.contains("ROCK") || upper.contains("ORE") -> return EntityType.RESOURCE
                upper.contains("FIBER") || upper.contains("HIDE") || upper.contains("FISH") -> return EntityType.RESOURCE
                upper.contains("T1_") || upper.contains("T2_") || upper.contains("T3_") -> return EntityType.RESOURCE
                upper.contains("T4_") || upper.contains("T5_") || upper.contains("T6_") -> return EntityType.RESOURCE
                upper.contains("T7_") || upper.contains("T8_") -> return EntityType.RESOURCE
                upper.contains("CHEST") || upper.contains("TREASURE") -> return EntityType.CHEST
                upper.contains("GATE") || upper.contains("PORTAL") -> return EntityType.GATE
                upper.contains("DUNGEON") -> return EntityType.DUNGEON
                upper.contains("CAMP") || upper.contains("MOUNTAIN") -> return EntityType.CAMP
                upper.contains("BOSS") -> return EntityType.BOSS
                upper.contains("AVALONIAN") -> return EntityType.BOSS
            }
        }
        
        // Check type ID from IdMapRepository
        if (typeId != null) {
            val mappedType = IdMapRepository.getEntityType(typeId)
            if (mappedType != EntityType.UNKNOWN) {
                return mappedType
            }
        }
        
        // Check params for type hints
        for ((key, value) in params) {
            if (value is String) {
                val upper = value.uppercase()
                if (upper.contains("T") && upper.contains("_") && 
                    (upper.contains("ORE") || upper.contains("WOOD") || 
                     upper.contains("ROCK") || upper.contains("FIBER") || 
                     upper.contains("HIDE"))) {
                    return EntityType.RESOURCE
                }
            }
        }
        
        return EntityType.UNKNOWN
    }
    
    // Helper extraction functions
    private fun extractFloat(value: Any?): Float {
        return when (value) {
            is Float -> value
            is Double -> value.toFloat()
            is Int -> value.toFloat()
            is Long -> value.toFloat()
            is Number -> value.toFloat()
            else -> 0f
        }
    }
    
    private fun extractInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> null
        }
    }
    
    private fun extractString(value: Any?): String? {
        return when (value) {
            is String -> value
            is ByteArray -> try { String(value, Charsets.UTF_8) } catch (e: Exception) { null }
            else -> null
        }
    }
    
    /**
     * Update the radar overlay with current entities.
     */
    private fun updateOverlay() {
        val entityList = entities.values.toList()
        DiscoveryLogger.log("OVERLAY", "Updating overlay with ${entityList.size} entities")
        RadarOverlayManager.updateEntities(entityList, playerX, playerY)
    }
    
    /**
     * Get current entity count.
     */
    fun getEntityCount(): Int = entities.size
    
    /**
     * Get all entities.
     */
    fun getEntities(): List<RadarEntity> = entities.values.toList()
    
    /**
     * Get player position.
     */
    fun getPlayerPosition(): Pair<Float, Float> = Pair(playerX, playerY)
    
    /**
     * Get statistics.
     */
    fun getStats(): String {
        return "Events: $totalEventsProcessed, Created: $totalEntitiesCreated, Updated: $totalEntitiesUpdated, Active: ${entities.size}"
    }
    
    /**
     * Clear all tracked entities.
     */
    fun clear() {
        entities.clear()
        playerX = 0f
        playerY = 0f
        totalEventsProcessed = 0
        totalEntitiesCreated = 0
        totalEntitiesUpdated = 0
        DiscoveryLogger.log("DISPATCHER", "Cleared all entities")
        updateOverlay()
    }
    
    /**
     * Process raw photon event data directly.
     * Called when event name is embedded in params.
     */
    fun processRawEvent(params: Map<Int, Any>): Boolean {
        // Try to extract event name from param 252 (0xFC)
        val eventName = extractString(params[252]) ?: "unknown"
        return dispatchEvent(eventName, params)
    }
}
