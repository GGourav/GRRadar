package com.grradar.model

enum class EntityType(val displayName: String, val colorHex: String, val priority: Int) {
    // Resources - Tier 1-8, Enchantment 0-4
    RESOURCE_FIBER("Fiber", "#C4B454", 10),
    RESOURCE_ORE("Ore", "#8B4513", 10),
    RESOURCE_LOGS("Logs", "#228B22", 10),
    RESOURCE_ROCK("Rock", "#808080", 10),
    RESOURCE_HIDE("Hide", "#8B0000", 10),
    
    // Crystal Mobs - Found in Outlands (Black Zones)
    CRYSTAL_SPIDER("Crystal Spider", "#00CED1", 30),
    CRYSTAL_COBRA("Crystal Cobra", "#9400D3", 35),
    CRYSTAL_BEETLE("Crystal Beetle", "#FFD700", 35),
    ARCANE_ELEMENTAL("Arcane Elemental", "#9932CC", 25),
    
    // Mist Entities
    MIST_WISP("Mist Wisp", "#E6E6FA", 15),
    TURBULENT_WISP("Turbulent Wisp", "#87CEEB", 16),
    CAGED_WISP("Caged Wisp", "#FF69B4", 17),
    MIST_MOB("Mist Mob", "#483D8B", 20),
    
    // Mist Bosses
    MIST_BOSS_GRIFFIN("Griffin (Mist Boss)", "#FF6600", 50),
    MIST_BOSS_FEY_DRAGON("Fey Dragon (Mist Boss)", "#FF00FF", 50),
    MIST_BOSS_VEILWEAVER("Veilweaver (Mist Boss)", "#4B0082", 50),
    
    // Avalonian Entities
    AVALONIAN_DRONE("Avalonian Drone", "#DAA520", 25),
    TREASURE_DRONE("Treasure Drone", "#FFD700", 28),
    AVALONIAN_ELITE("Avalonian Elite", "#B8860B", 26),
    MOB_AVALONIAN("Avalonian Mob", "#DAA520", 24),
    
    // Hide Animals (Skinnable)
    HIDE_ANIMAL("Hide Animal", "#CD853F", 12),
    HIDE_ANIMAL_MIST("Mist Hide Animal", "#CD853F", 13),
    HIDE_ANIMAL_ROADS("Roads Hide Animal", "#CD853F", 13),
    
    // Resource Guardians
    RESOURCE_GUARDIAN("Resource Guardian", "#32CD32", 14),
    
    // Faction Mobs
    MOB_HERETIC("Heretic", "#8B4513", 18),
    MOB_MORGANA("Morgana", "#4B0082", 18),
    MOB_KEEPER("Keeper", "#228B22", 18),
    MOB_UNDEAD("Undead", "#708090", 18),
    MOB_DEMON("Demon", "#DC143C", 19),
    
    // Boss Mobs
    MINIBOSS("Miniboss", "#FF8C00", 40),
    BOSS("Boss", "#FF4500", 45),
    VETERAN_BOSS("Veteran Boss", "#DC143C", 48),
    ELITE_MOB("Elite", "#9932CC", 35),
    WORLD_BOSS("World Boss", "#FF0000", 55),
    
    // Special Entities
    TREASURE_CHEST("Treasure Chest", "#FFD700", 22),
    DUNGEON_PORTAL("Dungeon Portal", "#9370DB", 21),
    HELLGATE("Hellgate", "#FF4500", 23),
    POWER_CRYSTAL("Power Crystal", "#00FFFF", 24),
    SILVER("Silver", "#FFEB3B", 8),
    
    // Players
    PLAYER("Player", "#FFFFFF", 5),
    HOSTILE_PLAYER("Hostile Player", "#FF0000", 6),
    
    // Unknown
    UNKNOWN("Unknown", "#888888", 1)
}

enum class EntityTypeClass {
    RESOURCE, CRYSTAL, MIST, MIST_BOSS, AVALONIAN, HIDE, 
    FACTION, BOSS, SPECIAL, PLAYER, UNKNOWN
}

enum class Enchantment(val level: Int, val displayName: String, val ringColorHex: String?) {
    NONE(0, "Flat", null),
    UNCOMMON(1, "Uncommon (.1)", "#00FF00"),
    RARE(2, "Rare (.2)", "#0000FF"),
    EXCEPTIONAL(3, "Exceptional (.3)", "#800080"),
    PRISTINE(4, "Pristine (.4)", "#FFD700");

    companion object {
        fun fromInt(value: Int): Enchantment {
            return entries.find { it.level == value } ?: NONE
        }
    }
}

enum class MistRarity(val displayName: String, val colorHex: String) {
    STANDARD("Standard", "#FFFFFF"),
    UNCOMMON("Uncommon", "#00FF00"),
    RARE("Rare", "#0000FF"),
    LEGENDARY("Legendary", "#FFD700");

    companion object {
        fun fromString(value: String): MistRarity {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: STANDARD
        }
    }
}

enum class MobCategory {
    HARVESTABLE, SKINNABLE, ENEMY, BOSS, MIST_BOSS, CRYSTAL, AVALONIAN, OTHER
}

enum class FactionFlag(val value: Int, val displayName: String) {
    NONE(0, "Neutral"),
    ALLIED(1, "Allied"),
    HOSTILE(2, "Hostile"),
    FRIENDLY(3, "Friendly");

    companion object {
        fun fromInt(value: Int): FactionFlag {
            return entries.find { it.value == value } ?: NONE
        }
    }
}

data class RadarEntity(
    val id: Int,
    val type: EntityType,
    val worldX: Float,
    val worldY: Float,
    val typeName: String = "",
    val tier: Int = 0,
    val enchantment: Enchantment = Enchantment.NONE,
    val name: String = "",
    val guildName: String = "",
    val allianceName: String = "",
    val factionFlag: FactionFlag = FactionFlag.NONE,
    val health: Float = 1.0f,
    val isBoss: Boolean = false,
    val isVeteran: Boolean = false,
    val isElite: Boolean = false,
    val mobCategory: MobCategory = MobCategory.OTHER,
    val mistRarity: MistRarity = MistRarity.STANDARD,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isResource(): Boolean {
        return type in listOf(
            EntityType.RESOURCE_FIBER,
            EntityType.RESOURCE_ORE,
            EntityType.RESOURCE_LOGS,
            EntityType.RESOURCE_ROCK,
            EntityType.RESOURCE_HIDE
        )
    }

    fun isCrystalMob(): Boolean {
        return type in listOf(
            EntityType.CRYSTAL_SPIDER,
            EntityType.CRYSTAL_COBRA,
            EntityType.CRYSTAL_BEETLE,
            EntityType.ARCANE_ELEMENTAL
        )
    }

    fun isMistBoss(): Boolean {
        return type in listOf(
            EntityType.MIST_BOSS_GRIFFIN,
            EntityType.MIST_BOSS_FEY_DRAGON,
            EntityType.MIST_BOSS_VEILWEAVER
        )
    }

    fun isSkinnable(): Boolean {
        return type in listOf(
            EntityType.HIDE_ANIMAL,
            EntityType.HIDE_ANIMAL_MIST,
            EntityType.HIDE_ANIMAL_ROADS
        ) || mobCategory == MobCategory.SKINNABLE
    }

    fun getDisplayName(): String {
        return when {
            name.isNotEmpty() -> name
            typeName.isNotEmpty() -> typeName
            else -> type.displayName
        }
    }

    fun getTierDisplay(): String {
        if (tier == 0) return ""
        val tierStr = "T$tier"
        val enchantStr = if (enchantment != Enchantment.NONE) ".${enchantment.level}" else ""
        return "$tierStr$enchantStr"
    }

    fun distanceFrom(x: Float, y: Float): Float {
        val dx = worldX - x
        val dy = worldY - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
