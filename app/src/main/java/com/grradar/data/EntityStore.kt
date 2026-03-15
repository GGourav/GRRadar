package com.grradar.data

import android.util.Log
import com.grradar.logger.DiscoveryLogger
import com.grradar.model.EntityType
import com.grradar.model.RadarEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * EntityStore — Thread-safe storage for radar entities
 *
 * PURPOSE:
 *   Maintains all detected entities in memory for the radar overlay to render.
 *   Supports concurrent access from VPN packet parsing thread and UI render thread.
 *
 * FEATURES:
 *   - Thread-safe ConcurrentHashMap for entity storage
 *   - Local player tracking (from JoinFinished event)
 *   - Automatic filtering of local player from entity list
 *   - Zone change handling (clear on ChangeCluster)
 *   - Statistics tracking
 *
 * USAGE:
 *   EntityStore.setLocalPlayer(id, x, y)  // From JoinFinished
 *   EntityStore.putEntity(entity)          // From New* events
 *   EntityStore.removeEntity(id)           // From Leave event
 *   EntityStore.clearAll()                 // From ChangeCluster
 *   EntityStore.getAllEntities()           // For rendering
 */
object EntityStore {

    private const val TAG = "EntityStore"

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY STORAGE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * All entities keyed by ObjectId
     * Using ConcurrentHashMap for thread-safe concurrent access
     */
    private val entities = ConcurrentHashMap<Int, RadarEntity>()

    // ═══════════════════════════════════════════════════════════════════════════
    // LOCAL PLAYER STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Local player's network ObjectId
     * Set from JoinFinished event, -1 means not set
     */
    @Volatile
    var localPlayerId: Int = -1
        private set

    /**
     * Local player's world X coordinate
     * Used as radar center
     */
    @Volatile
    var localPlayerX: Float = 0.0f
        private set

    /**
     * Local player's world Y coordinate
     * Used as radar center
     */
    @Volatile
    var localPlayerY: Float = 0.0f
        private set

    /**
     * Zone name the local player is in
     */
    @Volatile
    var currentZone: String = ""
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Total entity count
     */
    @Volatile
    var entityCount: Int = 0
        private set

    /**
     * Resource entity count
     */
    @Volatile
    var resourceCount: Int = 0
        private set

    /**
     * Mob entity count
     */
    @Volatile
    var mobCount: Int = 0
        private set

    /**
     * Player entity count (excluding local player)
     */
    @Volatile
    var playerCount: Int = 0
        private set

    /**
     * Special entity count (chests, dungeons, etc.)
     */
    @Volatile
    var specialCount: Int = 0
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // LOCAL PLAYER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Set local player info from JoinFinished event
     * This should be called BEFORE any NewCharacter events are processed
     *
     * @param objectId Local player's network ObjectId
     * @param x World X coordinate
     * @param y World Y coordinate
     */
    fun setLocalPlayer(objectId: Int, x: Float, y: Float) {
        localPlayerId = objectId
        localPlayerX = x
        localPlayerY = y

        DiscoveryLogger.i("Local player set: id=$objectId, pos=($x, $y)")
        Log.i(TAG, "Local player: id=$objectId, pos=($x, $y)")
    }

    /**
     * Update local player position from Move event
     *
     * @param x New world X coordinate
     * @param y New world Y coordinate
     */
    fun updateLocalPlayerPosition(x: Float, y: Float) {
        localPlayerX = x
        localPlayerY = y
    }

    /**
     * Set current zone name
     *
     * @param zone Zone/cluster name
     */
    fun setCurrentZone(zone: String) {
        currentZone = zone
        DiscoveryLogger.i("Zone changed to: $zone")
    }

    /**
     * Check if local player has been set
     *
     * @return True if JoinFinished has been processed
     */
    fun hasLocalPlayer(): Boolean = localPlayerId >= 0

    /**
     * Get local player position as a Pair
     *
     * @return (x, y) world coordinates
     */
    fun getLocalPlayerPosition(): Pair<Float, Float> = Pair(localPlayerX, localPlayerY)

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Clear all entities (called on zone change / ChangeCluster)
     */
    fun clearAll() {
        val previousCount = entities.size
        entities.clear()
        localPlayerId = -1
        localPlayerX = 0.0f
        localPlayerY = 0.0f
        currentZone = ""
        updateCounts()

        DiscoveryLogger.i("EntityStore cleared (zone change). Previous: $previousCount entities")
        Log.d(TAG, "Cleared $previousCount entities")
    }

    /**
     * Add or update an entity
     * If entity is the local player, update position instead of storing
     *
     * @param entity The RadarEntity to store
     */
    fun putEntity(entity: RadarEntity) {
        // Check if this is the local player
        if (entity.id == localPlayerId && entity.isPlayer()) {
            // Update local player position instead of storing as entity
            localPlayerX = entity.worldX
            localPlayerY = entity.worldY
            return
        }

        // Store/update the entity
        entities[entity.id] = entity
        updateCounts()
    }

    /**
     * Remove an entity by ObjectId
     *
     * @param objectId The network ObjectId to remove
     */
    fun removeEntity(objectId: Int) {
        entities.remove(objectId)
        updateCounts()
    }

    /**
     * Get entity by ObjectId
     *
     * @param objectId The network ObjectId
     * @return RadarEntity or null if not found
     */
    fun getEntity(objectId: Int): RadarEntity? = entities[objectId]

    /**
     * Check if an entity exists
     *
     * @param objectId The network ObjectId
     * @return True if entity exists
     */
    fun hasEntity(objectId: Int): Boolean = entities.containsKey(objectId)

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all entities as a list
     * Safe for iteration even during concurrent modifications
     *
     * @return List of all entities (excluding local player)
     */
    fun getAllEntities(): List<RadarEntity> = entities.values.toList()

    /**
     * Get entities filtered by type
     *
     * @param types vararg of EntityTypes to include
     * @return Filtered list of entities
     */
    fun getEntitiesByType(vararg types: EntityType): List<RadarEntity> {
        val typeSet = types.toSet()
        return entities.values.filter { it.type in typeSet }
    }

    /**
     * Get entities within range of a position
     *
     * @param centerX Center X coordinate
     * @param centerY Center Y coordinate
     * @param range Maximum distance from center
     * @return Entities within range
     */
    fun getEntitiesInRange(centerX: Float, centerY: Float, range: Float): List<RadarEntity> {
        val rangeSquared = range * range
        return entities.values.filter { entity ->
            val dx = entity.worldX - centerX
            val dy = entity.worldY - centerY
            (dx * dx + dy * dy) <= rangeSquared
        }
    }

    /**
     * Get entities within range of local player
     *
     * @param range Maximum distance from local player
     * @return Entities within range
     */
    fun getEntitiesNearLocalPlayer(range: Float): List<RadarEntity> {
        return getEntitiesInRange(localPlayerX, localPlayerY, range)
    }

    /**
     * Get all resource entities
     */
    fun getResources(): List<RadarEntity> = entities.values.filter { it.isResource() }

    /**
     * Get all mob entities
     */
    fun getMobs(): List<RadarEntity> = entities.values.filter { it.isMob() }

    /**
     * Get all player entities
     */
    fun getPlayers(): List<RadarEntity> = entities.values.filter { it.isPlayer() }

    /**
     * Get hostile players only
     */
    fun getHostilePlayers(): List<RadarEntity> = entities.values.filter {
        it.type == EntityType.HOSTILE_PLAYER
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update internal statistics counters
     */
    private fun updateCounts() {
        entityCount = entities.size
        resourceCount = 0
        mobCount = 0
        playerCount = 0
        specialCount = 0

        for (entity in entities.values) {
            when {
                entity.isResource() -> resourceCount++
                entity.isMob() -> mobCount++
                entity.isPlayer() -> playerCount++
                else -> specialCount++
            }
        }
    }

    /**
     * Get statistics string for display
     *
     * @return Formatted statistics string
     */
    fun getStatsString(): String {
        return buildString {
            append("Total: $entityCount")
            append(" | Resources: $resourceCount")
            append(" | Mobs: $mobCount")
            append(" | Players: $playerCount")
        }
    }

    /**
     * Get detailed statistics for logging
     */
    fun getDetailedStats(): String {
        return """
            EntityStore Statistics:
              Total Entities: $entityCount
              Resources: $resourceCount
              Mobs: $mobCount
              Players: $playerCount
              Special: $specialCount
              Local Player ID: $localPlayerId
              Local Position: ($localPlayerX, $localPlayerY)
              Current Zone: $currentZone
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEBUG HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Dump all entity IDs and types for debugging
     */
    fun dumpEntities(): String {
        return entities.entries.take(50).joinToString("\n") { (id, entity) ->
            "  [$id] ${entity.type}: ${entity.typeName} at (${entity.worldX}, ${entity.worldY})"
        }
    }
}
