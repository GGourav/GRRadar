package com.grradar.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.grradar.R
import java.io.InputStreamReader

data class IdMap(
    val version: String,
    val comment: String,
    val common: KeyConfig,
    val joinFinished: JoinFinishedConfig,
    val harvestable: HarvestableConfig,
    val mob: MobConfig,
    val player: PlayerConfig,
    val silver: SilverConfig,
    val chest: ChestConfig,
    val dungeon: DungeonConfig,
    val mist: MistConfig,
    val knownPrefixes: Map<String, List<String>>,
    val coordinatePlanB: CoordinatePlanB
)

data class KeyConfig(
    @SerializedName("objectIdKey") val objectIdKey: Int,
    @SerializedName("posXKey") val posXKey: Int,
    @SerializedName("posYKey") val posYKey: Int
)

data class JoinFinishedConfig(
    val comment: String,
    @SerializedName("localObjectIdKey") val localObjectIdKey: Int,
    @SerializedName("posXKey") val posXKey: Int,
    @SerializedName("posYKey") val posYKey: Int
)

data class HarvestableConfig(
    @SerializedName("typeNameKey") val typeNameKey: Int,
    @SerializedName("listKey") val listKey: Int,
    @SerializedName("tierKey") val tierKey: Int,
    @SerializedName("enchantKey") val enchantKey: Int
)

data class MobConfig(
    @SerializedName("typeNameKey") val typeNameKey: Int,
    @SerializedName("tierKey") val tierKey: Int,
    @SerializedName("enchantKey") val enchantKey: Int,
    @SerializedName("isBossKey") val isBossKey: Int,
    @SerializedName("healthKey") val healthKey: Int
)

data class PlayerConfig(
    @SerializedName("nameKey") val nameKey: Int,
    @SerializedName("guildKey") val guildKey: Int,
    @SerializedName("allianceKey") val allianceKey: Int,
    @SerializedName("factionFlagKey") val factionFlagKey: Int,
    @SerializedName("healthKey") val healthKey: Int,
    @SerializedName("mountKey") val mountKey: Int
)

data class SilverConfig(
    @SerializedName("typeNameKey") val typeNameKey: Int,
    @SerializedName("amountKey") val amountKey: Int
)

data class ChestConfig(
    @SerializedName("typeNameKey") val typeNameKey: Int,
    @SerializedName("rarityKey") val rarityKey: Int
)

data class DungeonConfig(
    @SerializedName("typeNameKey") val typeNameKey: Int,
    @SerializedName("rarityKey") val rarityKey: Int
)

data class MistConfig(
    @SerializedName("typeNameKey") val typeNameKey: Int,
    @SerializedName("rarityKey") val rarityKey: Int
)

data class CoordinatePlanB(
    @SerializedName("minValid") val minValid: Float,
    @SerializedName("maxValid") val maxValid: Float
)

object IdMapRepository {

    private const val TAG = "IdMapRepository"

    private var idMap: IdMap? = null

    // Known Photon event code to name mapping (seed values, will be updated by discovery)
    private val eventCodeNames = mutableMapOf<Int, String>(
        1 to "NewCharacter",
        2 to "Leave",
        3 to "Move",
        4 to "NewMob",
        5 to "NewSimpleHarvestableObject",
        6 to "NewSimpleHarvestableObjectList",
        7 to "NewHarvestableObject",
        8 to "JoinFinished",
        9 to "ChangeCluster",
        10 to "HarvestFinished",
        11 to "HealthUpdate",
        12 to "NewSilverObject",
        13 to "NewLootChest",
        14 to "NewTreasureChest",
        15 to "NewRandomDungeonExit",
        16 to "NewMistsCagedWisp",
        17 to "NewMistsWispSpawn",
        18 to "ForcedMovement",
        19 to "MountHealthUpdate",
        20 to "InventoryMoveItem"
    )

    fun init(context: Context) {
        try {
            // Try to load from assets first
            val inputStream = context.assets.open("id_map.json")
            val reader = InputStreamReader(inputStream)
            idMap = Gson().fromJson(reader, IdMap::class.java)
            reader.close()
            inputStream.close()
            Log.i(TAG, "Loaded id_map.json from assets")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load id_map.json from assets, using defaults")
            // Create default config
            idMap = createDefaultIdMap()
        }
    }

    private fun createDefaultIdMap(): IdMap {
        return IdMap(
            version = "2026-03-14-v1",
            comment = "Default config",
            common = KeyConfig(objectIdKey = 0, posXKey = 8, posYKey = 9),
            joinFinished = JoinFinishedConfig(
                comment = "Local player zone entry",
                localObjectIdKey = 0,
                posXKey = 8,
                posYKey = 9
            ),
            harvestable = HarvestableConfig(
                typeNameKey = 1,
                listKey = 2,
                tierKey = 7,
                enchantKey = 11
            ),
            mob = MobConfig(
                typeNameKey = 1,
                tierKey = 7,
                enchantKey = 11,
                isBossKey = 50,
                healthKey = 12
            ),
            player = PlayerConfig(
                nameKey = 1,
                guildKey = 3,
                allianceKey = 4,
                factionFlagKey = 23,
                healthKey = 12,
                mountKey = 26
            ),
            silver = SilverConfig(typeNameKey = 1, amountKey = 2),
            chest = ChestConfig(typeNameKey = 1, rarityKey = 7),
            dungeon = DungeonConfig(typeNameKey = 1, rarityKey = 7),
            mist = MistConfig(typeNameKey = 1, rarityKey = 7),
            knownPrefixes = mapOf(
                "fiber" to listOf("FIBER", "COTTON", "HEMP", "FLAX", "SILK", "SPONGE"),
                "ore" to listOf("ORE", "IRON", "STEEL", "TITANIUM", "RUNITE", "METEORITE"),
                "logs" to listOf("WOOD", "LOG", "BIRCH", "OAK", "CEDAR", "PINE", "FROSTWOOD"),
                "rock" to listOf("ROCK", "STONE", "LIMESTONE", "SANDSTONE", "TRAVERTINE", "GRANITE"),
                "hide" to listOf("HIDE", "LEATHER", "REPTILE", "BEAR", "DIREWOLF"),
                "crop" to listOf("WHEAT", "CROP", "CARROT", "TURNIP", "CABBAGE", "BEAN"),
                "mist" to listOf("MIST_WISP", "MIST_KEEPER", "MIST_HERALD", "MISTS_CAGEDWISP"),
                "boss" to listOf("BOSS", "ELDER", "ANCIENT", "GUARDIAN", "KEEPER", "MISTBOSS")
            ),
            coordinatePlanB = CoordinatePlanB(minValid = -32768f, maxValid = 32768f)
        )
    }

    fun get(): IdMap = idMap ?: createDefaultIdMap()

    fun resolveEventName(code: Int): String? {
        return eventCodeNames[code]
    }

    fun registerEventName(code: Int, name: String) {
        eventCodeNames[code] = name
    }

    fun getKnownPrefixes(): Map<String, List<String>> = get().knownPrefixes

    fun getCoordinateRange(): Pair<Float, Float> {
        val planB = get().coordinatePlanB
        return Pair(planB.minValid, planB.maxValid)
    }
}
