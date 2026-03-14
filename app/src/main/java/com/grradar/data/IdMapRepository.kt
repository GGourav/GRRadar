package com.grradar.data

import android.content.Context
import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.grradar.model.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Param key mappings loaded from id_map.json
 */
data class ParamKeys(
    @SerializedName("objectIdKey") val objectIdKey: Int = 0,
    @SerializedName("posXKey") val posXKey: Int = 8,
    @SerializedName("posYKey") val posYKey: Int = 9
)

data class HarvestableKeys(
    @SerializedName("typeNameKey") val typeNameKey: Int = 1,
    @SerializedName("listKey") val listKey: Int = 2,
    @SerializedName("tierKey") val tierKey: Int = 7,
    @SerializedName("enchantKey") val enchantKey: Int = 11
)

data class MobKeys(
    @SerializedName("typeNameKey") val typeNameKey: Int = 1,
    @SerializedName("tierKey") val tierKey: Int = 7,
    @SerializedName("enchantKey") val enchantKey: Int = 11,
    @SerializedName("isBossKey") val isBossKey: Int = 50,
    @SerializedName("healthKey") val healthKey: Int = 12
)

data class PlayerKeys(
    @SerializedName("nameKey") val nameKey: Int = 1,
    @SerializedName("guildKey") val guildKey: Int = 3,
    @SerializedName("allianceKey") val allianceKey: Int = 4,
    @SerializedName("factionFlagKey") val factionFlagKey: Int = 23,
    @SerializedName("healthKey") val healthKey: Int = 12,
    @SerializedName("mountKey") val mountKey: Int = 26
)

data class JoinFinishedKeys(
    @SerializedName("localObjectIdKey") val localObjectIdKey: Int = 0,
    @SerializedName("posXKey") val posXKey: Int = 8,
    @SerializedName("posYKey") val posYKey: Int = 9
)

data class CoordinatePlanB(
    @SerializedName("minValid") val minValid: Double = -32768.0,
    @SerializedName("maxValid") val maxValid: Double = 32768.0
)

/**
 * Complete ID map configuration
 */
data class IdMapConfig(
    @SerializedName("version") val version: String = "",
    @SerializedName("common") val common: ParamKeys = ParamKeys(),
    @SerializedName("harvestable") val harvestable: HarvestableKeys = HarvestableKeys(),
    @SerializedName("mob") val mob: MobKeys = MobKeys(),
    @SerializedName("player") val player: PlayerKeys = PlayerKeys(),
    @SerializedName("joinFinished") val joinFinished: JoinFinishedKeys = JoinFinishedKeys(),
    @SerializedName("coordinatePlanB") val coordinatePlanB: CoordinatePlanB = CoordinatePlanB()
)

/**
 * Repository for loading and caching id_map.json
 * Contains all entity classification logic based on deep analysis of ao-bin-dumps
 */
class IdMapRepository private constructor() {
    
    companion object {
        private const val ID_MAP_FILE = "id_map.json"
        
        @Volatile
        private var instance: IdMapRepository? = null
        
        fun getInstance(): IdMapRepository {
            return instance ?: synchronized(this) {
                instance ?: IdMapRepository().also { instance = it }
            }
        }
    }
    
    private var config: IdMapConfig? = null
    private val gson = Gson()
    
    /**
     * Initialize from assets
     */
    fun initialize(context: Context): Boolean {
        return try {
            val json = loadAsset(context.assets, ID_MAP_FILE)
            parseConfig(json)
        } catch (e: Exception) {
            // Use defaults if file not found
            config = IdMapConfig()
            true
        }
    }
    
    /**
     * Initialize with JSON string directly
     */
    fun initializeFromJson(json: String): Boolean {
        return try {
            parseConfig(json)
        } catch (e: Exception) {
            config = IdMapConfig()
            false
        }
    }
    
    private fun loadAsset(assets: AssetManager, filename: String): String {
        val inputStream = assets.open(filename)
        val reader = BufferedReader(InputStreamReader(inputStream))
        return reader.use { it.readText() }
    }
    
    private fun parseConfig(json: String): Boolean {
        config = gson.fromJson(json, IdMapConfig::class.java)
        return true
    }
    
    // ===== Getters for param keys =====
    
    fun getCommonKeys(): ParamKeys = config?.common ?: ParamKeys()
    fun getHarvestableKeys(): HarvestableKeys = config?.harvestable ?: HarvestableKeys()
    fun getMobKeys(): MobKeys = config?.mob ?: MobKeys()
    fun getPlayerKeys(): PlayerKeys = config?.player ?: PlayerKeys()
    fun getJoinFinishedKeys(): JoinFinishedKeys = config?.joinFinished ?: JoinFinishedKeys()
    fun getCoordinatePlanB(): CoordinatePlanB = config?.coordinatePlanB ?: CoordinatePlanB()
    
    // ===== Tier and Enchantment Extraction =====
    
    /**
     * Extract tier from type name (e.g., "T4_FIBER" -> 4)
     */
    fun extractTier(typeName: String?): Int {
        if (typeName.isNullOrEmpty()) return 0
        
        // Match patterns like T4_, T8_, etc.
        val match = Regex("T([1-8])_").find(typeName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
    
    /**
     * Extract enchantment from type name (e.g., "T4_FIBER@2" -> RARE)
     */
    fun extractEnchantment(typeName: String?): Enchantment {
        if (typeName.isNullOrEmpty()) return Enchantment.NONE
        
        // Match patterns like @1, @2, @3, @4 at end of string
        val match = Regex("@([1-4])$").find(typeName)
        val level = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Enchantment.fromInt(level)
    }
    
    // ===== Entity Classification =====
    
    /**
     * Classify entity type from type name string
     * Based on analysis of 3,756 unique mob IDs from ao-bin-dumps
     */
    fun classifyEntity(typeName: String?): EntityType {
        if (typeName.isNullOrEmpty()) return EntityType.UNKNOWN
        
        val upper = typeName.uppercase()
        
        // === RESOURCES ===
        if (upper.contains("FIBER") || upper.contains("COTTON") || 
            upper.contains("HEMP") || upper.contains("FLAX") || 
            upper.contains("SILK") || upper.contains("SPONGE")) {
            return EntityType.RESOURCE_FIBER
        }
        
        if (upper.contains("_ORE") || upper.contains("IRON") || 
            upper.contains("STEEL") || upper.contains("TITANIUM") || 
            upper.contains("RUNITE") || upper.contains("METEORITE") ||
            upper.contains("COPPER") || upper.contains("TIN")) {
            return EntityType.RESOURCE_ORE
        }
        
        if (upper.contains("_WOOD") || upper.contains("LOG") || 
            upper.contains("BIRCH") || upper.contains("OAK") || 
            upper.contains("CEDAR") || upper.contains("PINE") || 
            upper.contains("FROSTWOOD")) {
            return EntityType.RESOURCE_LOGS
        }
        
        if (upper.contains("_ROCK") || upper.contains("_STONE") || 
            upper.contains("LIMESTONE") || upper.contains("SANDSTONE") || 
            upper.contains("TRAVERTINE") || upper.contains("GRANITE") ||
            upper.contains("SLATE") || upper.contains("BASALT")) {
            return EntityType.RESOURCE_ROCK
        }
        
        if (upper.contains("_HIDE") && !upper.contains("MOB_")) {
            return EntityType.RESOURCE_HIDE
        }
        
        // === CRYSTAL MOBS (Outlands Black Zones) ===
        if (upper.contains("CRYSTALSPIDER") || upper.contains("CRYSTAL_SPIDER")) {
            return EntityType.CRYSTAL_SPIDER
        }
        if (upper.contains("CRYSTALCOBRA") || upper.contains("CRYSTAL_COBRA")) {
            return EntityType.CRYSTAL_COBRA
        }
        if (upper.contains("CRYSTALBEETLE") || upper.contains("CRYSTAL_BEETLE")) {
            return EntityType.CRYSTAL_BEETLE
        }
        if (upper.contains("ARCANE_ELEMENTAL")) {
            return EntityType.ARCANE_ELEMENTAL
        }
        
        // === MIST BOSSES (Only in Uncommon+ Mists) ===
        if (upper.contains("MISTS_GRIFFIN") || upper.contains("GRIFFIN")) {
            return EntityType.MIST_BOSS_GRIFFIN
        }
        if (upper.contains("MISTS_FAIRYDRAGON") || upper.contains("FAIRYDRAGON") || 
            upper.contains("FEY_DRAGON")) {
            return EntityType.MIST_BOSS_FEY_DRAGON
        }
        if (upper.contains("VEILWEAVER") || upper.contains("VEIL_WEAVER")) {
            return EntityType.MIST_BOSS_VEILWEAVER
        }
        
        // === MIST ENTITIES ===
        if (upper.contains("MISTS_WISP") || upper.contains("MIST_WISP")) {
            return EntityType.MIST_WISP
        }
        if (upper.contains("CAGEDWISP") || upper.contains("CAGED_WISP")) {
            return EntityType.CAGED_WISP
        }
        if (upper.contains("TURBULENT_MIST")) {
            return EntityType.TURBULENT_WISP
        }
        if (upper.contains("_MISTS_") || upper.contains("_MIST_")) {
            return EntityType.MIST_MOB
        }
        
        // === AVALONIAN ENTITIES ===
        if (upper.contains("AVALON_DRONE") || upper.contains("AVALONIAN_DRONE") ||
            upper.contains("TREASURE_MINION")) {
            return EntityType.AVALONIAN_DRONE
        }
        if (upper.contains("_AVALON_") && !upper.contains("TREASURE")) {
            return EntityType.MOB_AVALONIAN
        }
        
        // === HIDE ANIMALS (Skinnable) ===
        if (upper.contains("CRITTER_HIDE") || 
            (upper.contains("_HIDE_") && upper.contains("MOB"))) {
            return when {
                upper.contains("MIST") -> EntityType.HIDE_ANIMAL_MIST
                upper.contains("ROADS") -> EntityType.HIDE_ANIMAL_ROADS
                else -> EntityType.HIDE_ANIMAL
            }
        }
        
        // === FACTION MOBS ===
        when {
            upper.contains("_HERETIC_") -> return EntityType.MOB_HERETIC
            upper.contains("_MORGANA_") -> return EntityType.MOB_MORGANA
            upper.contains("_KEEPER_") -> return EntityType.MOB_KEEPER
            upper.contains("_UNDEAD_") -> return EntityType.MOB_UNDEAD
            upper.contains("_DEMON_") -> return EntityType.MOB_DEMON
        }
        
        // === RESOURCE GUARDIANS ===
        if (upper.contains("GUARDIAN_") || upper.contains("CRITTER_FIBER") ||
            upper.contains("CRITTER_ORE") || upper.contains("CRITTER_ROCK") ||
            upper.contains("CRITTER_WOOD")) {
            return EntityType.RESOURCE_GUARDIAN
        }
        
        // === BOSS MOBS ===
        when {
            upper.contains("_VETERAN_BOSS") -> return EntityType.VETERAN_BOSS
            upper.contains("_MINIBOSS") -> return EntityType.MINIBOSS
            upper.contains("_BOSS") -> return EntityType.BOSS
            upper.contains("_ELITE") -> return EntityType.ELITE_MOB
        }
        
        // === SPECIAL ENTITIES ===
        if (upper.contains("TREASURE_CHEST") || upper.contains("LOOT_CHEST")) {
            return EntityType.TREASURE_CHEST
        }
        if (upper.contains("DUNGEON_PORTAL") || upper.contains("DUNGEON_EXIT")) {
            return EntityType.DUNGEON_PORTAL
        }
        if (upper.contains("HELLGATE")) return EntityType.HELLGATE
        if (upper.contains("POWERCRYSTAL")) return EntityType.POWER_CRYSTAL
        if (upper.contains("SILVER")) return EntityType.SILVER
        
        return EntityType.UNKNOWN
    }
    
    /**
     * Determine mob category from type name
     */
    fun getMobCategory(typeName: String?): MobCategory {
        if (typeName.isNullOrEmpty()) return MobCategory.OTHER
        
        val upper = typeName.uppercase()
        
        return when {
            upper.contains("GUARDIAN_") || 
            upper.contains("CRITTER_FIBER") || 
            upper.contains("CRITTER_ORE") || 
            upper.contains("CRITTER_ROCK") || 
            upper.contains("CRITTER_WOOD") -> MobCategory.HARVESTABLE
            
            upper.contains("CRITTER_HIDE") || 
            (upper.contains("_HIDE_") && upper.contains("MOB")) -> MobCategory.SKINNABLE
            
            upper.contains("CRYSTALSPIDER") ||
            upper.contains("CRYSTALCOBRA") ||
            upper.contains("CRYSTALBEETLE") -> MobCategory.CRYSTAL
            
            upper.contains("_AVALON_") -> MobCategory.AVALONIAN
            
            upper.contains("GRIFFIN") ||
            upper.contains("FAIRYDRAGON") ||
            upper.contains("VEILWEAVER") -> MobCategory.MIST_BOSS
            
            upper.contains("_BOSS") || 
            upper.contains("_MINIBOSS") ||
            upper.contains("_VETERAN_BOSS") -> MobCategory.BOSS
            
            else -> MobCategory.OTHER
        }
    }
    
    /**
     * Get version string
     */
    fun getVersion(): String = config?.version ?: "unknown"
    
    /**
     * Check if initialized
     */
    fun isInitialized(): Boolean = config != null
}
