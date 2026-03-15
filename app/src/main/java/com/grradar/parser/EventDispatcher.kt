package com.grradar.parser

import android.util.Log
import com.grradar.data.EntityStore
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import com.grradar.model.EntityType
import com.grradar.model.RadarEntity

/**
 * EventDispatcher — Routes Photon events to entity handlers
 *
 * IMPORTANT DESIGN DECISION:
 *   The APK dispatches events by NAME (string), not integer code.
 *   Integer codes shift with every Albion patch, but event names are stable.
 *   We use a when() block on eventName string for resilience.
 *
 * RADAR-RELEVANT EVENTS:
 *   JoinFinished                    - Local player zone entry (CRITICAL)
 *   NewCharacter                    - Player entities
 *   NewMob                          - Mob entities
 *   NewSimpleHarvestableObject      - Single resource node
 *   NewSimpleHarvestableObjectList  - Batch resource nodes
 *   NewHarvestableObject            - Alternate harvestable form
 *   NewSilverObject                 - Silver drops
 *   NewLootChest / NewTreasureChest - Chests
 *   NewRandomDungeonExit            - Solo dungeon portals
 *   NewMistsCagedWisp               - Mist wisps
 *   Leave                           - Entity left zone
 *   Move                            - Position update
 *   ChangeCluster                   - Zone transition (clear all)
 *   HarvestFinished                 - Resource depleted
 *
 * USAGE:
 *   EventDispatcher.dispatch(event)
 */
object EventDispatcher {

    private const val TAG = "EventDispatcher"

    // ═══════════════════════════════════════════════════════════════════════════
    // EVENT NAME CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════

    // Critical events
    private const val EVENT_JOIN_FINISHED = "JoinFinished"
    private const val EVENT_CHANGE_CLUSTER = "ChangeCluster"

    // Entity spawn events
    private const val EVENT_NEW_CHARACTER = "NewCharacter"
    private const val EVENT_NEW_MOB = "NewMob"
    private const val EVENT_NEW_SIMPLE_HARVESTABLE = "NewSimpleHarvestableObject"
    private const val EVENT_NEW_HARVESTABLE_LIST = "NewSimpleHarvestableObjectList"
    private const val EVENT_NEW_HARVESTABLE = "NewHarvestableObject"
    private const val EVENT_NEW_SILVER = "NewSilverObject"
    private const val EVENT_NEW_LOOT_CHEST = "NewLootChest"
    private const val EVENT_NEW_TREASURE_CHEST = "NewTreasureChest"
    private const val EVENT_NEW_RANDOM_DUNGEON_EXIT = "NewRandomDungeonExit"
    private const val EVENT_NEW_EXPEDITION_EXIT = "NewExpeditionExit"
    private const val EVENT_NEW_HELLGATE_EXIT = "NewHellgateExitPortal"
    private const val EVENT_NEW_MISTS_DUNGEON_EXIT = "NewMistsDungeonExit"
    private const val EVENT_NEW_MISTS_CAGED_WISP = "NewMistsCagedWisp"
    private const val EVENT_NEW_MISTS_WISP_SPAWN = "NewMistsWispSpawn"
    private const val EVENT_NEW_PORTAL_ENTRANCE = "NewPortalEntrance"
    private const val EVENT_NEW_PORTAL_EXIT = "NewPortalExit"

    // Entity update/remove events
    private const val EVENT_LEAVE = "Leave"
    private const val EVENT_MOVE = "Move"
    private const val EVENT_FORCED_MOVEMENT = "ForcedMovement"
    private const val EVENT_HARVEST_FINISHED = "HarvestFinished"

    // Health events
    private const val EVENT_HEALTH_UPDATE = "HealthUpdate"

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    private var dispatchCount = 0L
    private var entityCreatedCount = 0L
    private var joinFinishedCount = 0L
    private var errorCount = 0L

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN DISPATCH METHOD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Dispatch a parsed event to the appropriate handler
     *
     * @param event The parsed Photon event
     */
    fun dispatch(event: ParsedEvent) {
        dispatchCount++

        try {
            when (event.eventName) {
                // ═══════════════════════════════════════════════════════════════
                // CRITICAL EVENTS
                // ═══════════════════════════════════════════════════════════════
                EVENT_JOIN_FINISHED -> handleJoinFinished(event.params)
                EVENT_CHANGE_CLUSTER -> handleChangeCluster(event.params)

                // ═══════════════════════════════════════════════════════════════
                // ENTITY SPAWN EVENTS
                // ═══════════════════════════════════════════════════════════════
                EVENT_NEW_CHARACTER -> handleNewCharacter(event.params)
                EVENT_NEW_MOB -> handleNewMob(event.params)
                EVENT_NEW_SIMPLE_HARVESTABLE -> handleNewHarvestable(event.params)
                EVENT_NEW_HARVESTABLE_LIST -> handleNewHarvestableList(event.params)
                EVENT_NEW_HARVESTABLE -> handleNewHarvestable(event.params)
                EVENT_NEW_SILVER -> handleNewSilver(event.params)
                EVENT_NEW_LOOT_CHEST, EVENT_NEW_TREASURE_CHEST -> handleNewChest(event.params)
                EVENT_NEW_RANDOM_DUNGEON_EXIT,
                EVENT_NEW_EXPEDITION_EXIT,
                EVENT_NEW_HELLGATE_EXIT,
                EVENT_NEW_MISTS_DUNGEON_EXIT -> handleNewDungeon(event.params)
                EVENT_NEW_MISTS_CAGED_WISP,
                EVENT_NEW_MISTS_WISP_SPAWN -> handleNewMist(event.params)
                EVENT_NEW_PORTAL_ENTRANCE,
                EVENT_NEW_PORTAL_EXIT -> handleNewPortal(event.params)

                // ═══════════════════════════════════════════════════════════════
                // ENTITY UPDATE/REMOVE EVENTS
                // ═══════════════════════════════════════════════════════════════
                EVENT_LEAVE -> handleLeave(event.params)
                EVENT_MOVE -> handleMove(event.params)
                EVENT_FORCED_MOVEMENT -> handleMove(event.params)
                EVENT_HARVEST_FINISHED -> handleHarvestFinished(event.params)
                EVENT_HEALTH_UPDATE -> handleHealthUpdate(event.params)

                // ═══════════════════════════════════════════════════════════════
                // UNHANDLED EVENTS
                // ═══════════════════════════════════════════════════════════════
                else -> {
                    // Log unhandled New* events for future support
                    if (event.eventName.startsWith("New") && dispatchCount < 500) {
                        DiscoveryLogger.d("Unhandled event: ${event.eventName}")
                    }
                }
            }
        } catch (e: Exception) {
            errorCount++
            DiscoveryLogger.e("Error dispatching ${event.eventName}: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CRITICAL EVENT HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle JoinFinished event - local player zone entry
     *
     * CRITICAL: This fires BEFORE any NewCharacter events!
     * Without handling this:
     *   - localPlayerId is never set
     *   - Cannot filter self from other players
     *   - Radar center is wrong (0,0)
     *
     * This also indicates entering a new zone, so we clear all existing entities.
     */
    private fun handleJoinFinished(params: Map<Int, Any?>) {
        joinFinishedCount++

        // Extract local player ID
        val objectId = PhotonParser.getObjectId(params)
        if (objectId == null) {
            DiscoveryLogger.w("JoinFinished: Missing objectId")
            return
        }

        // Extract position
        val position = PhotonParser.getPosition(params)
        if (position == null) {
            DiscoveryLogger.w("JoinFinished: Missing position for objectId=$objectId")
            return
        }

        // Clear existing entities (entering new zone)
        EntityStore.clearAll()

        // Set local player
        EntityStore.setLocalPlayer(objectId, position.first, position.second)

        DiscoveryLogger.i("JoinFinished #$joinFinishedCount: localPlayer=$objectId pos=(${position.first}, ${position.second})")
        Log.i(TAG, "JoinFinished: id=$objectId pos=(${position.first}, ${position.second})")
    }

    /**
     * Handle ChangeCluster event - zone transition
     * Clear all entities when changing zones
     */
    private fun handleChangeCluster(params: Map<Int, Any?>) {
        DiscoveryLogger.i("ChangeCluster: Clearing all entities (zone transition)")
        EntityStore.clearAll()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY SPAWN HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle NewCharacter event - player entity spawn
     */
    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params)

        // Check if this is the local player
        if (objectId == EntityStore.localPlayerId) {
            // Update local player position
            if (position != null) {
                EntityStore.updateLocalPlayerPosition(position.first, position.second)
            }
            return
        }

        // Need position for other players
        if (position == null) {
            DiscoveryLogger.w("NewCharacter $objectId: Missing position")
            return
        }

        // Extract player info
        val name = PhotonParser.getString(params, IdMapRepository.playerNameKey)
        val guild = PhotonParser.getString(params, IdMapRepository.playerGuildKey)
        val alliance = PhotonParser.getString(params, IdMapRepository.playerAllianceKey)
        val factionFlag = params[IdMapRepository.playerFactionFlagKey]
        val health = PhotonParser.getFloat(params, IdMapRepository.playerHealthKey, 1.0f)

        // Determine hostility
        val isHostile = when (factionFlag) {
            is Boolean -> factionFlag
            is Int -> factionFlag != 0
            is Long -> factionFlag != 0L
            is Byte -> factionFlag.toInt() != 0
            else -> false
        }

        // Create entity
        val entity = RadarEntity(
            id = objectId,
            type = if (isHostile) EntityType.HOSTILE_PLAYER else EntityType.PLAYER,
            worldX = position.first,
            worldY = position.second,
            name = name,
            guild = guild,
            alliance = alliance,
            isHostile = isHostile,
            healthPercent = health.coerceIn(0f, 1f)
        )

        EntityStore.putEntity(entity)
        entityCreatedCount++

        // Log first few players
        if (entityCreatedCount <= 10 || name.isNotEmpty()) {
            DiscoveryLogger.d("NewCharacter: '$name' guild='$guild' hostile=$isHostile at (${position.first}, ${position.second})")
        }
    }

    /**
     * Handle NewMob event - mob entity spawn
     */
    private fun handleNewMob(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params)
        val typeName = PhotonParser.getTypeName(params)

        if (position == null) {
            DiscoveryLogger.w("NewMob $objectId: Missing position")
            return
        }

        // Extract mob info
        val tier = PhotonParser.getInt(params, IdMapRepository.mobTierKey)
            .takeIf { it > 0 } ?: IdMapRepository.parseTier(typeName ?: "")
        val enchant = PhotonParser.getInt(params, IdMapRepository.mobEnchantKey)
            .takeIf { it >= 0 } ?: IdMapRepository.parseEnchant(typeName ?: "")
        val isBoss = PhotonParser.getBoolean(params, IdMapRepository.mobIsBossKey) ||
            IdMapRepository.isBossType(typeName ?: "")
        val health = PhotonParser.getFloat(params, IdMapRepository.mobHealthKey, 1.0f)

        // Determine mob type
        val entityType = when {
            isBoss -> EntityType.BOSS_MOB
            enchant > 0 -> EntityType.ENCHANTED_MOB
            else -> EntityType.NORMAL_MOB
        }

        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = position.first,
            worldY = position.second,
            typeName = typeName ?: "",
            tier = tier,
            enchant = enchant,
            healthPercent = health.coerceIn(0f, 1f)
        )

        EntityStore.putEntity(entity)
        entityCreatedCount++

        if (entityCreatedCount <= 5 || isBoss) {
            DiscoveryLogger.d("NewMob: '$typeName' tier=$tier enchant=$enchant boss=$isBoss")
        }
    }

    /**
     * Handle NewSimpleHarvestableObject - single resource node spawn
     */
    private fun handleNewHarvestable(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params)
        val typeName = PhotonParser.getTypeName(params)

        if (typeName == null) {
            // Log missing type name for debugging
            if (entityCreatedCount < 50) {
                DiscoveryLogger.d("NewHarvestable $objectId: Missing typeName, params keys: ${params.keys.sorted()}")
            }
            return
        }

        if (position == null) {
            DiscoveryLogger.w("NewHarvestable $objectId: Missing position for '$typeName'")
            return
        }

        // Extract resource info
        val tier = PhotonParser.getInt(params, IdMapRepository.harvestTierKey)
            .takeIf { it > 0 } ?: IdMapRepository.parseTier(typeName)
        val enchant = PhotonParser.getInt(params, IdMapRepository.harvestEnchantKey)
            .takeIf { it >= 0 } ?: IdMapRepository.parseEnchant(typeName)

        // Determine resource type from type name
        val resourceType = IdMapRepository.matchResourcePrefix(typeName)

        // Additional matching for edge cases
        val entityType = when {
            resourceType == "fiber" -> EntityType.RESOURCE_FIBER
            resourceType == "ore" -> EntityType.RESOURCE_ORE
            resourceType == "logs" -> EntityType.RESOURCE_LOGS
            resourceType == "rock" -> EntityType.RESOURCE_ROCK
            resourceType == "hide" -> EntityType.RESOURCE_HIDE
            resourceType == "crop" -> EntityType.RESOURCE_CROP

            // Fallback: Check type name patterns
            typeName.contains("FIBER", ignoreCase = true) -> EntityType.RESOURCE_FIBER
            typeName.contains("ORE", ignoreCase = true) -> EntityType.RESOURCE_ORE
            typeName.contains("WOOD", ignoreCase = true) ||
                typeName.contains("LOG", ignoreCase = true) -> EntityType.RESOURCE_LOGS
            typeName.contains("ROCK", ignoreCase = true) ||
                typeName.contains("STONE", ignoreCase = true) -> EntityType.RESOURCE_ROCK
            typeName.contains("HIDE", ignoreCase = true) ||
                typeName.contains("LEATHER", ignoreCase = true) -> EntityType.RESOURCE_HIDE
            typeName.contains("WHEAT", ignoreCase = true) ||
                typeName.contains("CROP", ignoreCase = true) -> EntityType.RESOURCE_CROP

            else -> {
                // Unknown resource - log for discovery
                if (entityCreatedCount < 20) {
                    DiscoveryLogger.d("Unknown resource type: '$typeName'")
                }
                EntityType.UNKNOWN
            }
        }

        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = position.first,
            worldY = position.second,
            typeName = typeName,
            tier = tier,
            enchant = enchant
        )

        EntityStore.putEntity(entity)
        entityCreatedCount++

        // Log first few resources
        if (entityCreatedCount <= 3) {
            DiscoveryLogger.d("NewHarvestable: '$typeName' tier=$tier enchant=$enchant type=$entityType")
        }
    }

    /**
     * Handle NewSimpleHarvestableObjectList - batch resource nodes
     * This is often sent when entering a zone with many resources
     */
    private fun handleNewHarvestableList(params: Map<Int, Any?>) {
        DiscoveryLogger.d("NewHarvestableList: Batch event received")

        // The list is typically in params[listKey]
        // For full implementation, we would iterate through the list
        // and call handleNewHarvestable for each item
        // This is left as a future optimization
    }

    /**
     * Handle NewSilverObject - silver drops
     */
    private fun handleNewSilver(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params) ?: return

        val typeName = PhotonParser.getString(params, IdMapRepository.silverTypeNameKey)
        val amount = PhotonParser.getInt(params, IdMapRepository.silverAmountKey, 0)

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.SILVER,
            worldX = position.first,
            worldY = position.second,
            typeName = typeName,
            name = if (amount > 0) "$amount silver" else "Silver"
        )

        EntityStore.putEntity(entity)
    }

    /**
     * Handle NewLootChest / NewTreasureChest
     */
    private fun handleNewChest(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params) ?: return

        val typeName = PhotonParser.getString(params, IdMapRepository.chestTypeNameKey)

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.CHEST,
            worldX = position.first,
            worldY = position.second,
            typeName = typeName
        )

        EntityStore.putEntity(entity)
        DiscoveryLogger.d("NewChest: '$typeName' at (${position.first}, ${position.second})")
    }

    /**
     * Handle dungeon portal events
     */
    private fun handleNewDungeon(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params) ?: return

        val typeName = PhotonParser.getString(params, IdMapRepository.dungeonTypeNameKey)

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = position.first,
            worldY = position.second,
            typeName = typeName
        )

        EntityStore.putEntity(entity)
    }

    /**
     * Handle mist wisp events
     */
    private fun handleNewMist(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params) ?: return

        val typeName = PhotonParser.getString(params, IdMapRepository.mistTypeNameKey)

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.MIST_WISP,
            worldX = position.first,
            worldY = position.second,
            typeName = typeName
        )

        EntityStore.putEntity(entity)
    }

    /**
     * Handle portal events
     */
    private fun handleNewPortal(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params) ?: return

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = position.first,
            worldY = position.second
        )

        EntityStore.putEntity(entity)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY UPDATE/REMOVE HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle Leave event - entity left zone
     */
    private fun handleLeave(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        EntityStore.removeEntity(objectId)
    }

    /**
     * Handle Move event - entity position update
     */
    private fun handleMove(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val position = PhotonParser.getPosition(params) ?: return

        // Check if this is the local player
        if (objectId == EntityStore.localPlayerId) {
            EntityStore.updateLocalPlayerPosition(position.first, position.second)
            return
        }

        // Update existing entity position
        val existing = EntityStore.getEntity(objectId)
        if (existing != null) {
            val updated = existing.copy(
                worldX = position.first,
                worldY = position.second
            )
            EntityStore.putEntity(updated)
        }
    }

    /**
     * Handle HarvestFinished - resource node depleted
     */
    private fun handleHarvestFinished(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        EntityStore.removeEntity(objectId)
    }

    /**
     * Handle HealthUpdate - entity health changed
     */
    private fun handleHealthUpdate(params: Map<Int, Any?>) {
        val objectId = PhotonParser.getObjectId(params) ?: return
        val health = PhotonParser.getFloat(params, IdMapRepository.playerHealthKey, -1f)

        if (health < 0) return

        val existing = EntityStore.getEntity(objectId)
        if (existing != null) {
            val updated = existing.copy(healthPercent = health.coerceIn(0f, 1f))
            EntityStore.putEntity(updated)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get dispatcher statistics
     */
    fun getStats(): String {
        return buildString {
            append("EventDispatcher:\n")
            append("  Dispatched: $dispatchCount\n")
            append("  Entities created: $entityCreatedCount\n")
            append("  JoinFinished count: $joinFinishedCount\n")
            append("  Errors: $errorCount\n")
            append("  ${EntityStore.getStatsString()}")
        }
    }

    /**
     * Reset statistics
     */
    fun resetStats() {
        dispatchCount = 0
        entityCreatedCount = 0
        joinFinishedCount = 0
        errorCount = 0
    }
}
