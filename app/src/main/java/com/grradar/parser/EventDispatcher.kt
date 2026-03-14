package com.grradar.parser

import com.grradar.data.EntityStore
import com.grradar.data.IdMapRepository
import com.grradar.logger.DiscoveryLogger
import com.grradar.model.EntityType
import com.grradar.model.RadarEntity
import com.grradar.vpn.AlbionVpnService

/**
 * EventDispatcher - Routes Photon events by name string
 *
 * CRITICAL: Dispatch by event NAME, not integer code.
 * Integer codes shift with patches, but names are stable.
 */
class EventDispatcher {

    private val handlerMap = HashMap<String, (Map<Int, Any?>) -> Unit>()
    private val idMap = IdMapRepository.get()

    init {
        // Register event handlers
        handlerMap["JoinFinished"] = ::handleJoinFinished
        handlerMap["NewCharacter"] = ::handleNewCharacter
        handlerMap["Leave"] = ::handleLeave
        handlerMap["Move"] = ::handleMove
        handlerMap["NewSimpleHarvestableObject"] = ::handleHarvestable
        handlerMap["NewSimpleHarvestableObjectList"] = ::handleHarvestableList
        handlerMap["NewHarvestableObject"] = ::handleHarvestable
        handlerMap["NewMob"] = ::handleMob
        handlerMap["NewSilverObject"] = ::handleSilver
        handlerMap["NewLootChest"] = ::handleChest
        handlerMap["NewTreasureChest"] = ::handleChest
        handlerMap["NewMistsCagedWisp"] = ::handleMistWisp
        handlerMap["NewMistsWispSpawn"] = ::handleMistWisp
        handlerMap["NewRandomDungeonExit"] = ::handleDungeon
        handlerMap["ChangeCluster"] = ::handleChangeCluster
        handlerMap["HarvestFinished"] = ::handleHarvestFinished
        handlerMap["InventoryMoveItem"] = { /* discard */ }
    }

    fun dispatch(params: Map<Int, Any?>) {
        // Get event code from params[252]
        val codeInt = (params[252] as? Number)?.toInt() ?: return

        // Resolve event name from code
        val eventName = IdMapRepository.resolveEventName(codeInt)

        if (eventName == null) {
            DiscoveryLogger.logUnknownEvent(codeInt, params)
            return
        }

        // Log target events for discovery
        when (eventName) {
            "JoinFinished", "NewCharacter" -> {
                val objectId = getInt(params, idMap.common.objectIdKey) ?: -1
                DiscoveryLogger.logAllParams(eventName, objectId, params)
            }
            "Move" -> {
                // Only log first 5 Move events
                val count = moveCount.incrementAndGet()
                if (count <= 5) {
                    val objectId = getInt(params, idMap.common.objectIdKey) ?: -1
                    DiscoveryLogger.logAllParams(eventName, objectId, params)
                }
            }
        }

        // Dispatch to handler
        val handler = handlerMap[eventName]
        if (handler != null) {
            handler.invoke(params)
        } else {
            DiscoveryLogger.logDiscovery(eventName, -1, params)
        }
    }

    // ─── Event Handlers ───────────────────────────────────────────────────────

    private fun handleJoinFinished(params: Map<Int, Any?>) {
        val localObjectId = getInt(params, idMap.joinFinished.localObjectIdKey)
        val posX = getFloatWithPlanB(params, idMap.joinFinished.posXKey)
        val posY = getFloatWithPlanB(params, idMap.joinFinished.posYKey)

        if (localObjectId == null || posX == null || posY == null) {
            DiscoveryLogger.logDiscovery("JoinFinished", localObjectId ?: -1, params)
            return
        }

        DiscoveryLogger.i("JoinFinished: localPlayerId=$localObjectId, pos=($posX, $posY)")

        // Clear entities on zone entry
        EntityStore.clear()
        EntityStore.setLocalPlayer(localObjectId, posX, posY)

        AlbionVpnService.resetEntityCount()
    }

    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return

        // Check if this is the local player
        if (objectId == EntityStore.localPlayerId) {
            // Update local player position
            val posX = getFloatWithPlanB(params, idMap.common.posXKey)
            val posY = getFloatWithPlanB(params, idMap.common.posYKey)
            if (posX != null && posY != null) {
                EntityStore.setLocalPlayer(objectId, posX, posY)
            }
            return
        }

        val posX = getFloatWithPlanB(params, idMap.common.posXKey)
        val posY = getFloatWithPlanB(params, idMap.common.posYKey)

        if (posX == null || posY == null) {
            DiscoveryLogger.logDiscovery("NewCharacter", objectId, params)
            return
        }

        val name = getString(params, idMap.player.nameKey) ?: ""
        val isHostile = getBool(params, idMap.player.factionFlagKey) ?: false

        val entityType = if (isHostile) EntityType.HOSTILE_PLAYER else EntityType.PLAYER

        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = "",
            tier = 0,
            enchant = 0,
            name = name
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()

        DiscoveryLogger.d("NewCharacter: $name at ($posX, $posY), hostile=$isHostile")
    }

    private fun handleLeave(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        EntityStore.removeEntity(objectId)
    }

    private fun handleMove(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        if (objectId == EntityStore.localPlayerId) {
            EntityStore.setLocalPlayer(objectId, posX, posY)
            return
        }

        EntityStore.updatePosition(objectId, posX, posY)
    }

    private fun handleHarvestable(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return

        val posX = getFloatWithPlanB(params, idMap.common.posXKey)
        val posY = getFloatWithPlanB(params, idMap.common.posYKey)

        if (posX == null || posY == null) {
            DiscoveryLogger.logDiscovery("NewSimpleHarvestableObject", objectId, params)
            return
        }

        // Plan A: Get typeName from key
        var typeName = getString(params, idMap.harvestable.typeNameKey)

        // Plan B: Scan for T1_ through T8_ prefix
        if (typeName == null) {
            typeName = planBScanTypeName(params)
        }

        // Plan C: Log and skip
        if (typeName == null) {
            DiscoveryLogger.logDiscovery("NewSimpleHarvestableObject", objectId, params)
            return
        }

        val (tier, enchant) = parseTierEnchant(typeName)
        val entityType = classifyResource(typeName)

        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchant = enchant,
            name = ""
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()
    }

    private fun handleHarvestableList(params: Map<Int, Any?>) {
        // Batch harvestable objects
        val list = params[idMap.harvestable.listKey] as? List<*> ?: return

        list.forEach { item ->
            if (item is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val itemParams = item.mapKeys { it.key as Int } as Map<Int, Any?>
                handleHarvestable(itemParams)
            }
        }
    }

    private fun handleMob(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return

        val posX = getFloatWithPlanB(params, idMap.common.posXKey)
        val posY = getFloatWithPlanB(params, idMap.common.posYKey)

        if (posX == null || posY == null) {
            DiscoveryLogger.logDiscovery("NewMob", objectId, params)
            return
        }

        var typeName = getString(params, idMap.mob.typeNameKey)

        if (typeName == null) {
            typeName = planBScanTypeName(params)
        }

        if (typeName == null) {
            DiscoveryLogger.logDiscovery("NewMob", objectId, params)
            return
        }

        val isBoss = getBool(params, idMap.mob.isBossKey) ?: false
        val (tier, enchant) = parseTierEnchant(typeName)
        val entityType = classifyMob(typeName, isBoss)

        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchant = enchant,
            name = ""
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()
    }

    private fun handleSilver(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.SILVER,
            worldX = posX,
            worldY = posY,
            typeName = "SILVER",
            tier = 0,
            enchant = 0,
            name = ""
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()
    }

    private fun handleChest(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.CHEST,
            worldX = posX,
            worldY = posY,
            typeName = "CHEST",
            tier = 0,
            enchant = 0,
            name = ""
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()
    }

    private fun handleMistWisp(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.MIST_WISP,
            worldX = posX,
            worldY = posY,
            typeName = "MIST_WISP",
            tier = 0,
            enchant = 0,
            name = ""
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()
    }

    private fun handleDungeon(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        val posX = getFloatWithPlanB(params, idMap.common.posXKey) ?: return
        val posY = getFloatWithPlanB(params, idMap.common.posYKey) ?: return

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = posX,
            worldY = posY,
            typeName = "DUNGEON",
            tier = 0,
            enchant = 0,
            name = ""
        )

        EntityStore.addEntity(entity)
        AlbionVpnService.incrementEntityCount()
    }

    private fun handleChangeCluster(params: Map<Int, Any?>) {
        DiscoveryLogger.i("ChangeCluster: clearing all entities")
        EntityStore.clear()
        EntityStore.localPlayerId = -1
    }

    private fun handleHarvestFinished(params: Map<Int, Any?>) {
        val objectId = getInt(params, idMap.common.objectIdKey) ?: return
        EntityStore.removeEntity(objectId)
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────

    private fun getInt(params: Map<Int, Any?>, key: Int): Int? {
        val value = params[key] ?: return null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun getFloat(params: Map<Int, Any?>, key: Int): Float? {
        val value = params[key] ?: return null
        return when (value) {
            is Float -> value
            is Number -> value.toFloat()
            else -> null
        }
    }

    private fun getString(params: Map<Int, Any?>, key: Int): String? {
        return params[key] as? String
    }

    private fun getBool(params: Map<Int, Any?>, key: Int): Boolean? {
        val value = params[key] ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    private fun getFloatWithPlanB(params: Map<Int, Any?>, key: Int): Float? {
        // Plan A: Direct lookup
        val direct = getFloat(params, key)
        if (direct != null) return direct

        // Plan B: Scan all float params for valid coordinate range
        val (minValid, maxValid) = IdMapRepository.getCoordinateRange()

        return params.values
            .filterIsInstance<Float>()
            .firstOrNull { it >= minValid && it <= maxValid }
    }

    private fun planBScanTypeName(params: Map<Int, Any?>): String? {
        val prefixes = listOf("T1_", "T2_", "T3_", "T4_", "T5_", "T6_", "T7_", "T8_")

        return params.values
            .filterIsInstance<String>()
            .firstOrNull { v -> prefixes.any { p -> v.startsWith(p, ignoreCase = true) } }
    }

    private fun parseTierEnchant(name: String): Pair<Int, Int> {
        val tier = Regex("^T(\\d)_").find(name)
            ?.groupValues?.get(1)
            ?.toIntOrNull() ?: 0

        val enchant = when {
            name.contains("@4") || name.endsWith(".4") -> 4
            name.contains("@3") || name.endsWith(".3") -> 3
            name.contains("@2") || name.endsWith(".2") -> 2
            name.contains("@1") || name.endsWith(".1") -> 1
            else -> 0
        }

        return Pair(tier, enchant)
    }

    private fun classifyResource(name: String): EntityType {
        val upper = name.uppercase()

        return when {
            upper.contains("FIBER") || upper.contains("COTTON") ||
            upper.contains("HEMP") || upper.contains("FLAX") ||
            upper.contains("SILK") -> EntityType.RESOURCE_FIBER

            upper.contains("ORE") || upper.contains("IRON") ||
            upper.contains("STEEL") || upper.contains("TITANIUM") ||
            upper.contains("RUNITE") -> EntityType.RESOURCE_ORE

            upper.contains("WOOD") || upper.contains("LOG") ||
            upper.contains("BIRCH") || upper.contains("OAK") ||
            upper.contains("CEDAR") || upper.contains("PINE") -> EntityType.RESOURCE_LOGS

            upper.contains("ROCK") || upper.contains("STONE") ||
            upper.contains("LIMESTONE") || upper.contains("SANDSTONE") ||
            upper.contains("GRANITE") -> EntityType.RESOURCE_ROCK

            upper.contains("HIDE") || upper.contains("LEATHER") ||
            upper.contains("REPTILE") || upper.contains("BEAR") -> EntityType.RESOURCE_HIDE

            upper.contains("WHEAT") || upper.contains("CROP") ||
            upper.contains("CARROT") || upper.contains("TURNIP") -> EntityType.RESOURCE_CROP

            else -> EntityType.RESOURCE_FIBER // Default
        }
    }

    private fun classifyMob(name: String, isBoss: Boolean): EntityType {
        val upper = name.uppercase()

        return when {
            isBoss -> EntityType.BOSS_MOB
            upper.contains("BOSS") || upper.contains("ELDER") ||
            upper.contains("ANCIENT") || upper.contains("GUARDIAN") ||
            upper.contains("KEEPER") -> EntityType.BOSS_MOB

            upper.contains("ENCHANTED") || upper.contains("CORRUPT") -> EntityType.ENCHANTED_MOB
            else -> EntityType.NORMAL_MOB
        }
    }

    companion object {
        private val moveCount = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
