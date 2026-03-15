package com.grradar.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.grradar.model.*

data class CommonKeys(
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

data class IdMapConfig(
    @SerializedName("version") val version: String = "default",
    @SerializedName("common") val common: CommonKeys = CommonKeys(),
    @SerializedName("harvestable") val harvestable: HarvestableKeys = HarvestableKeys(),
    @SerializedName("mob") val mob: MobKeys = MobKeys(),
    @SerializedName("player") val player: PlayerKeys = PlayerKeys(),
    @SerializedName("joinFinished") val joinFinished: JoinFinishedKeys = JoinFinishedKeys(),
    @SerializedName("coordinatePlanB") val coordinatePlanB: CoordinatePlanB = CoordinatePlanB()
)

class IdMapRepository private constructor() {
    
    companion object {
        private const val TAG = "IdMapRepository"
        private const val ID_MAP_FILE = "id_map.json"
        
        @Volatile
        private var instance: IdMapRepository? = null
        
        fun getInstance(): IdMapRepository {
            return instance ?: synchronized(this) {
                instance ?: IdMapRepository().also { 
                    instance = it
                    if (!it.isInitialized()) {
                        it.config = IdMapConfig()
                        Log.i(TAG, "Auto-initialized with defaults")
                    }
                }
            }
        }
    }
    
    private var config: IdMapConfig? = null
    private val gson = Gson()
    
    fun initialize(context: Context): Boolean {
        return try {
            val json = context.assets.open(ID_MAP_FILE).bufferedReader().use { it.readText() }
            config = gson.fromJson(json, IdMapConfig::class.java)
            Log.i(TAG, "Loaded config: version=${config?.version}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Using defaults: ${e.message}")
            config = IdMapConfig()
            true
        }
    }
    
    fun getCommonKeys(): CommonKeys = config?.common ?: CommonKeys()
    fun getHarvestableKeys(): HarvestableKeys = config?.harvestable ?: HarvestableKeys()
    fun getMobKeys(): MobKeys = config?.mob ?: MobKeys()
    fun getPlayerKeys(): PlayerKeys = config?.player ?: PlayerKeys()
    fun getJoinFinishedKeys(): JoinFinishedKeys = config?.joinFinished ?: JoinFinishedKeys()
    fun getCoordinatePlanB(): CoordinatePlanB = config?.coordinatePlanB ?: CoordinatePlanB()
    
    fun getVersion(): String = config?.version ?: "unknown"
    fun isInitialized(): Boolean = config != null
    
    fun extractTier(typeName: String?): Int {
        if (typeName.isNullOrEmpty()) return 0
        val match = Regex("T([1-8])_").find(typeName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
    
    fun extractEnchantment(typeName: String?): Enchantment {
        if (typeName.isNullOrEmpty()) return Enchantment.NONE
        val match = Regex("@([1-4])$").find(typeName)
        val level = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Enchantment.fromInt(level)
    }
    
    fun classifyEntity(typeName: String?): EntityType {
        if (typeName.isNullOrEmpty()) return EntityType.UNKNOWN
        val upper = typeName.uppercase()
        
        // Resources
        if (upper.contains("FIBER") || upper.contains("COTTON") || upper.contains("HEMP") || 
            upper.contains("FLAX") || upper.contains("SILK") || upper.contains("SPONGE")) 
            return EntityType.RESOURCE_FIBER
            
        if (upper.contains("_ORE") || upper.contains("IRONNODE") || upper.contains("STEELNODE") || 
            upper.contains("TITANIUMNODE") || upper.contains("RUNITENODE") || 
            upper.contains("METEORITENODE") || upper.contains("ORE_MOUNTAIN")) 
            return EntityType.RESOURCE_ORE
            
        if (upper.contains("_WOOD") || upper.contains("LOG") || upper.contains("BIRCH") || 
            upper.contains("OAK") || upper.contains("CEDAR") || upper.contains("PINE") ||
            upper.contains("FROSTWOOD") || upper.contains("ASH") || upper.contains("MAPLE") ||
            upper.contains("ELM") || upper.contains("WYCH")) 
            return EntityType.RESOURCE_LOGS
            
        if (upper.contains("_ROCK") || upper.contains("_STONE") || upper.contains("LIMESTONE") || 
            upper.contains("GRANITE") || upper.contains("SANDSTONE") || upper.contains("TRAVERTINE") ||
            upper.contains("MARBLE") || upper.contains("BASALT") || upper.contains("SLATE")) 
            return EntityType.RESOURCE_ROCK
            
        if ((upper.contains("_HIDE") || upper.contains("HIDE_")) && 
            !upper.contains("MOB_") && !upper.contains("_MOB")) 
            return EntityType.RESOURCE_HIDE
        
        // Crystal mobs
        if (upper.contains("CRYSTALSPIDER") || upper.contains("CRYSTAL_SPIDER")) 
            return EntityType.CRYSTAL_SPIDER
        if (upper.contains("CRYSTALCOBRA") || upper.contains("CRYSTAL_COBRA")) 
            return EntityType.CRYSTAL_COBRA
        if (upper.contains("CRYSTALBEETLE") || upper.contains("CRYSTAL_BEETLE")) 
            return EntityType.CRYSTAL_BEETLE
        if (upper.contains("ARCANE_ELEMENTAL") || upper.contains("ARCANEELEMENTAL")) 
            return EntityType.ARCANE_ELEMENTAL
        
        // Mist bosses
        if (upper.contains("GRIFFIN") || upper.contains("GRYPHON")) 
            return EntityType.MIST_BOSS_GRIFFIN
        if (upper.contains("FAIRYDRAGON") || upper.contains("FEY_DRAGON") || 
            upper.contains("FAIRY_DRAGON")) 
            return EntityType.MIST_BOSS_FEY_DRAGON
        if (upper.contains("VEILWEAVER")) 
            return EntityType.MIST_BOSS_VEILWEAVER
        
        // Mist entities
        if (upper.contains("MISTS_WISP") || upper.contains("MIST_WISP") || 
            upper.contains("WISP_SMALL")) 
            return EntityType.MIST_WISP
        if (upper.contains("CAGEDWISP") || upper.contains("CAGED_WISP") || 
            upper.contains("WISP_CAGED")) 
            return EntityType.CAGED_WISP
        if (upper.contains("TURBULENT_WISP") || upper.contains("TURBULENTWISP")) 
            return EntityType.TURBULENT_WISP
        if (upper.contains("_MISTS_") || upper.contains("_MIST_") || upper.contains("MIST_MOB")) 
            return EntityType.MIST_MOB
        
        // Avalonian
        if (upper.contains("AVALON_DRONE") || upper.contains("AVALONIAN_DRONE") || 
            upper.contains("TREASURE_MINION") || upper.contains("TREASUREMINION")) 
            return EntityType.AVALONIAN_DRONE
        if (upper.contains("_AVALON_") || upper.contains("AVALONIAN")) 
            return EntityType.MOB_AVALONIAN
        
        // Hide animals
        if (upper.contains("CRITTER_HIDE") || upper.contains("HIDE_MOB") || 
            (upper.contains("_HIDE_") && upper.contains("MOB")) ||
            upper.contains("SWAMP_DEMON") || upper.contains("DIREBOAR") ||
            upper.contains("DIREWOLF") || upper.contains("DIRE_BEAR") ||
            upper.contains("GIANT_STAG") || upper.contains("MOOSE") ||
            upper.contains("CROCODILE") || upper.contains("ALLIGATOR")) 
            return EntityType.HIDE_ANIMAL
        
        // Faction mobs
        if (upper.contains("_HERETIC_") || upper.contains("HERETIC")) 
            return EntityType.MOB_HERETIC
        if (upper.contains("_MORGANA_") || upper.contains("MORGANA")) 
            return EntityType.MOB_MORGANA
        if (upper.contains("_KEEPER_") || upper.contains("KEEPER")) 
            return EntityType.MOB_KEEPER
        if (upper.contains("_UNDEAD_") || upper.contains("UNDEAD")) 
            return EntityType.MOB_UNDEAD
        if (upper.contains("_DEMON_") || upper.contains("DEMON")) 
            return EntityType.MOB_DEMON
        
        // Resource guardians
        if (upper.contains("GUARDIAN_") || upper.contains("CRITTER_FIBER") || 
            upper.contains("CRITTER_ORE") || upper.contains("CRITTER_ROCK") || 
            upper.contains("CRITTER_WOOD")) 
            return EntityType.RESOURCE_GUARDIAN
        
        // Bosses
        if (upper.contains("_VETERAN_BOSS") || upper.contains("VETERAN_BOSS")) 
            return EntityType.VETERAN_BOSS
        if (upper.contains("_MINIBOSS") || upper.contains("MINIBOSS")) 
            return EntityType.MINIBOSS
        if (upper.contains("_BOSS") && !upper.contains("VETERAN")) 
            return EntityType.BOSS
        if (upper.contains("_ELITE") || upper.contains("ELITE")) 
            return EntityType.ELITE_MOB
        
        // Special
        if (upper.contains("TREASURE_CHEST") || upper.contains("LOOT_CHEST") || 
            upper.contains("LOCKEDCHEST")) 
            return EntityType.TREASURE_CHEST
        if (upper.contains("DUNGEON_PORTAL") || upper.contains("DUNGEON_EXIT") ||
            upper.contains("DUNGEONPORTAL") || upper.contains("EXPEDITION_EXIT")) 
            return EntityType.DUNGEON_PORTAL
        if (upper.contains("HELLGATE") || upper.contains("HELLGATE_EXIT")) 
            return EntityType.HELLGATE
        if (upper.contains("POWERCRYSTAL") || upper.contains("POWER_CRYSTAL")) 
            return EntityType.POWER_CRYSTAL
        if (upper.contains("SILVER") || upper.contains("SILVERPILE")) 
            return EntityType.SILVER
        
        return EntityType.UNKNOWN
    }
    
    fun getMobCategory(typeName: String?): MobCategory {
        if (typeName.isNullOrEmpty()) return MobCategory.OTHER
        val upper = typeName.uppercase()
        
        return when {
            upper.contains("GUARDIAN_") || upper.contains("CRITTER_FIBER") || 
            upper.contains("CRITTER_ORE") || upper.contains("CRITTER_ROCK") || 
            upper.contains("CRITTER_WOOD") -> MobCategory.HARVESTABLE
            
            upper.contains("CRITTER_HIDE") || (upper.contains("_HIDE_") && upper.contains("MOB")) -> MobCategory.SKINNABLE
            
            upper.contains("CRYSTALSPIDER") || upper.contains("CRYSTALCOBRA") || 
            upper.contains("CRYSTALBEETLE") -> MobCategory.CRYSTAL
            
            upper.contains("_AVALON_") || upper.contains("AVALONIAN") -> MobCategory.AVALONIAN
            
            upper.contains("GRIFFIN") || upper.contains("FAIRYDRAGON") || 
            upper.contains("VEILWEAVER") -> MobCategory.MIST_BOSS
            
            upper.contains("_BOSS") || upper.contains("_MINIBOSS") || 
            upper.contains("_VETERAN_BOSS") -> MobCategory.BOSS
            
            else -> MobCategory.ENEMY
        }
    }
}
