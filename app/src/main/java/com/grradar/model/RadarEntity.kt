package com.grradar.entity

import android.graphics.Color
import android.util.Log

/**
 * Represents an entity detected on the radar.
 */
data class RadarEntity(
    val objectId: Int,
    var entityType: EntityType,
    var posX: Float,
    var posY: Float,
    val typeName: String,
    val tier: Int = 0,
    val enchantment: Int = 0
) {
    // Mutable properties that can change
    var health: Int = 0
    var maxHealth: Int = 0
    var distance: Float = 0f
    var lastUpdate: Long = System.currentTimeMillis()
    
    /**
     * Calculate distance from player.
     */
    fun calculateDistance(playerX: Float, playerY: Float): Float {
        val dx = posX - playerX
        val dy = posY - playerY
        distance = kotlin.math.sqrt(dx * dx + dy * dy)
        return distance
    }
    
    /**
     * Get display color based on entity type.
     */
    fun getColor(): Int {
        return when (entityType) {
            EntityType.PLAYER -> Color.GREEN
            EntityType.MOB -> Color.RED
            EntityType.RESOURCE -> getResourceColor()
            EntityType.CHEST -> Color.MAGENTA
            EntityType.GATE -> Color.YELLOW
            EntityType.DUNGEON -> Color.CYAN
            EntityType.CAMP -> Color.ORANGE
            EntityType.BOSS -> Color.parseColor("#FF00FF") // Bright magenta
            EntityType.UNKNOWN -> Color.GRAY
        }
    }
    
    /**
     * Get resource color based on tier.
     */
    private fun getResourceColor(): Int {
        return when (tier) {
            1 -> Color.parseColor("#FFFFFF") // White - T1
            2 -> Color.parseColor("#CCCCCC") // Light gray - T2
            3 -> Color.parseColor("#FFFF00") // Yellow - T3
            4 -> Color.parseColor("#FF9900") // Orange - T4
            5 -> Color.parseColor("#FF0000") // Red - T5
            6 -> Color.parseColor("#9900FF") // Purple - T6
            7 -> Color.parseColor("#00FFFF") // Cyan - T7
            8 -> Color.parseColor("#00FF00") // Bright green - T8
            else -> Color.parseColor("#00FF00") // Green - default
        }
    }
    
    /**
     * Get display name for the entity.
     */
    fun getDisplayName(): String {
        val tierStr = if (tier > 0) "T$tier" else ""
        val enchantStr = if (enchantment > 0) ".$enchantment" else ""
        return "$tierStr$enchantStr $typeName".trim()
    }
    
    /**
     * Get icon or symbol for the entity.
     */
    fun getSymbol(): String {
        return when (entityType) {
            EntityType.PLAYER -> "●"
            EntityType.MOB -> "▲"
            EntityType.RESOURCE -> "◆"
            EntityType.CHEST -> "▣"
            EntityType.GATE -> "◈"
            EntityType.DUNGEON -> "⬟"
            EntityType.CAMP -> "⬡"
            EntityType.BOSS -> "★"
            EntityType.UNKNOWN -> "?"
        }
    }
    
    /**
     * Check if entity is expired (not updated for too long).
     */
    fun isExpired(maxAgeMs: Long = 30000): Boolean {
        return System.currentTimeMillis() - lastUpdate > maxAgeMs
    }
    
    override fun toString(): String {
        return "RadarEntity(id=$objectId, type=$entityType, name='$typeName', pos=($posX, $posY), tier=$tier)"
    }
}

/**
 * Entity type enumeration.
 */
enum class EntityType(val displayName: String, val priority: Int) {
    PLAYER("Player", 100),
    MOB("Mob", 50),
    RESOURCE("Resource", 30),
    CHEST("Chest", 70),
    GATE("Gate", 60),
    DUNGEON("Dungeon", 65),
    CAMP("Camp", 55),
    BOSS("Boss", 90),
    UNKNOWN("Unknown", 0);
    
    /**
     * Check if this is a harvestable entity.
     */
    fun isHarvestable(): Boolean {
        return this == RESOURCE
    }
    
    /**
     * Check if this is a hostile entity.
     */
    fun isHostile(): Boolean {
        return this == MOB || this == BOSS
    }
    
    /**
     * Check if this is an interactable entity.
     */
    fun isInteractable(): Boolean {
        return this == CHEST || this == GATE || this == DUNGEON
    }
    
    /**
     * Get default visibility range for this entity type.
     */
    fun getVisibilityRange(): Float {
        return when (this) {
            PLAYER -> 50f
            MOB -> 40f
            BOSS -> 60f
            RESOURCE -> 30f
            CHEST -> 35f
            GATE -> 45f
            DUNGEON -> 45f
            CAMP -> 50f
            UNKNOWN -> 25f
        }
    }
}
