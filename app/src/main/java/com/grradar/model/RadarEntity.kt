package com.grradar.model

enum class EntityType {
    RESOURCE_FIBER,
    RESOURCE_ORE,
    RESOURCE_LOGS,
    RESOURCE_ROCK,
    RESOURCE_HIDE,
    RESOURCE_CROP,
    NORMAL_MOB,
    ENCHANTED_MOB,
    BOSS_MOB,
    PLAYER,
    HOSTILE_PLAYER,
    SILVER,
    MIST_WISP,
    CHEST,
    DUNGEON_PORTAL,
    UNKNOWN
}

data class RadarEntity(
    val id: Int,
    val type: EntityType,
    var worldX: Float,
    var worldY: Float,
    val typeName: String,
    val tier: Int,
    val enchant: Int,
    val name: String
)
