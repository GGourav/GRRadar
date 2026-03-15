package com.grradar.model

/**
 * EntityType — All entity types the radar can display
 *
 * Categories:
 *   - RESOURCES: Fiber, Ore, Logs, Rock, Hide, Crop
 *   - MOBS: Normal, Enchanted, Boss
 *   - PLAYERS: Friendly, Hostile
 *   - SPECIAL: Silver, Chest, Dungeon, Mist
 */
enum class EntityType {
    // Resources (harvestable nodes)
    RESOURCE_FIBER,
    RESOURCE_ORE,
    RESOURCE_LOGS,
    RESOURCE_ROCK,
    RESOURCE_HIDE,
    RESOURCE_CROP,

    // Mobs (NPC enemies)
    NORMAL_MOB,      // Standard mob
    ENCHANTED_MOB,   // Enchanted mob (.1, .2, .3, .4)
    BOSS_MOB,        // Boss-class mob

    // Players
    PLAYER,          // Friendly/neutral player
    HOSTILE_PLAYER,  // Hostile player (red)

    // Special
    SILVER,          // Silver drops
    MIST_WISP,       // Mist zone wisps
    CHEST,           // Loot/treasure chests
    DUNGEON_PORTAL,  // Dungeon entrances/exits

    // Unknown
    UNKNOWN          // Unrecognized entity type
}

/**
 * RadarEntity — Represents a game entity on the radar
 *
 * This data class holds all information about an entity that the radar
 * needs to display it correctly, including position, type, tier, enchant
 * level, and player-specific information.
 *
 * COORDINATE SYSTEM:
 *   - worldX increases East
 *   - worldY increases North
 *   - Origin depends on zone
 *   - Typical range: -32768 to +32768
 *
 * @property id Network ObjectId (unique per entity per zone)
 * @property type EntityType classification
 * @property worldX World X coordinate
 * @property worldY World Y coordinate
 * @property typeName Raw type string from server (e.g., "T4_FIBER@2")
 * @property tier Tier level (1-8 for resources/mobs, 0 if not applicable)
 * @property enchant Enchantment level (0-4, where 0=none, 4=legendary)
 * @property name Player character name (empty for non-players)
 * @property guild Guild name for players
 * @property alliance Alliance name for players
 * @property isHostile Hostility flag for players
 * @property healthPercent Current health as percentage (0.0-1.0)
 */
data class RadarEntity(
    val id: Int,
    val type: EntityType,
    val worldX: Float,
    val worldY: Float,
    val typeName: String = "",
    val tier: Int = 0,
    val enchant: Int = 0,
    val name: String = "",
    val guild: String = "",
    val alliance: String = "",
    val isHostile: Boolean = false,
    val healthPercent: Float = 1.0f
) {
    /**
     * Calculate Euclidean distance from a given world position
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @return Distance in world units
     */
    fun distanceFrom(x: Float, y: Float): Float {
        val dx = worldX - x
        val dy = worldY - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Calculate distance from another entity
     *
     * @param other Another RadarEntity
     * @return Distance in world units
     */
    fun distanceFrom(other: RadarEntity): Float {
        return distanceFrom(other.worldX, other.worldY)
    }

    /**
     * Get resource category string for filtering
     *
     * @return Category string ("fiber", "ore", etc.) or null if not a resource
     */
    fun getResourceCategory(): String? = when (type) {
        EntityType.RESOURCE_FIBER -> "fiber"
        EntityType.RESOURCE_ORE -> "ore"
        EntityType.RESOURCE_LOGS -> "logs"
        EntityType.RESOURCE_ROCK -> "rock"
        EntityType.RESOURCE_HIDE -> "hide"
        EntityType.RESOURCE_CROP -> "crop"
        else -> null
    }

    /**
     * Get tier+enchant key for filtering
     * Format: "T{tier}{enchant}" (e.g., "T42" for T4.2)
     *
     * @return Tier-enchant key string, or empty if tier is 0
     */
    fun getTierEnchantKey(): String {
        if (tier == 0) return ""
        return "T$tier$enchant"
    }

    /**
     * Get display name for the entity
     *
     * @return Name to display on radar
     */
    fun getDisplayName(): String {
        return when {
            name.isNotEmpty() -> name
            typeName.isNotEmpty() -> typeName
            else -> type.name
        }
    }

    /**
     * Check if this is a resource type
     */
    fun isResource(): Boolean = type in listOf(
        EntityType.RESOURCE_FIBER,
        EntityType.RESOURCE_ORE,
        EntityType.RESOURCE_LOGS,
        EntityType.RESOURCE_ROCK,
        EntityType.RESOURCE_HIDE,
        EntityType.RESOURCE_CROP
    )

    /**
     * Check if this is a mob type
     */
    fun isMob(): Boolean = type in listOf(
        EntityType.NORMAL_MOB,
        EntityType.ENCHANTED_MOB,
        EntityType.BOSS_MOB
    )

    /**
     * Check if this is a player type
     */
    fun isPlayer(): Boolean = type in listOf(
        EntityType.PLAYER,
        EntityType.HOSTILE_PLAYER
    )

    /**
     * Check if this is a special type (chest, dungeon, etc.)
     */
    fun isSpecial(): Boolean = type in listOf(
        EntityType.SILVER,
        EntityType.CHEST,
        EntityType.DUNGEON_PORTAL,
        EntityType.MIST_WISP
    )

    /**
     * Get icon size for rendering based on type and tier
     *
     * @return Size in pixels
     */
    fun getIconSize(): Float {
        return when (type) {
            // Bosses are largest
            EntityType.BOSS_MOB -> 14f

            // Hostile players and enchanted mobs
            EntityType.HOSTILE_PLAYER,
            EntityType.ENCHANTED_MOB -> 10f

            // Regular players and chests
            EntityType.PLAYER,
            EntityType.CHEST -> 8f

            // Dungeons and normal mobs
            EntityType.DUNGEON_PORTAL,
            EntityType.NORMAL_MOB -> 7f

            // Resources scale with tier
            EntityType.RESOURCE_FIBER,
            EntityType.RESOURCE_ORE,
            EntityType.RESOURCE_LOGS,
            EntityType.RESOURCE_ROCK,
            EntityType.RESOURCE_HIDE,
            EntityType.RESOURCE_CROP -> {
                // Base size + tier bonus
                val baseSize = 4f
                val tierBonus = (tier - 1) * 0.5f
                val enchantBonus = enchant * 1f
                baseSize + tierBonus + enchantBonus
            }

            // Everything else
            else -> 5f
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadarEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id

    override fun toString(): String {
        return "RadarEntity(id=$id, type=$type, pos=($worldX, $worldY), typeName='$typeName', tier=$tier, enchant=$enchant)"
    }
}
