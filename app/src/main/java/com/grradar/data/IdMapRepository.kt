package com.grradar.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.grradar.logger.DiscoveryLogger
import java.io.InputStreamReader

/**
 * IdMapRepository — Loads and manages Photon parameter key mappings
 *
 * PURPOSE:
 *   After each Albion patch, the parameter keys in Photon events may change.
 *   This repository loads key mappings from id_map.json and provides them
 *   to the parser. Set a key to -1 to force Plan B scanner fallback.
 *
 * FILE LOCATION:
 *   app/src/main/assets/id_map.json
 *
 * USAGE:
 *   IdMapRepository.load(context)  // Call once at app start
 *   val posXKey = IdMapRepository.posXKey
 *
 * PLAN B SCANNERS:
 *   If a key is set to -1 or returns null, the parser uses fallback methods:
 *   - Position: Scan all float params for values in [-32768, +32768] range
 *   - TypeName: Scan all string params for "T{n}_" prefix pattern
 */
object IdMapRepository {

    private const val TAG = "IdMapRepository"
    private const val ASSET_FILE = "id_map.json"

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMON KEYS - Used by most entity events
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for network ObjectId (entity identifier)
     * Appears STABLE across patches
     */
    var objectIdKey: Int = 0
        private set

    /**
     * Key for world X coordinate (Float)
     * VERIFY after each patch via DiscoveryLogger
     */
    var posXKey: Int = 8
        private set

    /**
     * Key for world Y coordinate (Float)
     * VERIFY after each patch via DiscoveryLogger
     */
    var posYKey: Int = 9
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // JOIN FINISHED KEYS - Local player zone entry
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for local player ObjectId in JoinFinished event
     */
    var localObjectIdKey: Int = 0
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // HARVESTABLE KEYS - Resource nodes
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for resource type name string (e.g., "T4_FIBER@2")
     */
    var harvestTypeNameKey: Int = 1
        private set

    /**
     * Key for harvestable list in batch events
     */
    var harvestListKey: Int = 2
        private set

    /**
     * Key for resource tier (Int, 1-8)
     */
    var harvestTierKey: Int = 7
        private set

    /**
     * Key for resource enchant level (Int, 0-4)
     */
    var harvestEnchantKey: Int = 11
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // MOB KEYS - NPC enemies
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for mob type name string
     */
    var mobTypeNameKey: Int = 1
        private set

    /**
     * Key for mob tier (Int, 1-8)
     */
    var mobTierKey: Int = 7
        private set

    /**
     * Key for mob enchant level (Int, 0-4)
     */
    var mobEnchantKey: Int = 11
        private set

    /**
     * Key for mob boss flag (Boolean)
     */
    var mobIsBossKey: Int = 50
        private set

    /**
     * Key for mob health percentage (Float)
     */
    var mobHealthKey: Int = 12
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // PLAYER KEYS - Character entities
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for player character name (String)
     */
    var playerNameKey: Int = 1
        private set

    /**
     * Key for player guild name (String)
     */
    var playerGuildKey: Int = 3
        private set

    /**
     * Key for player alliance name (String)
     */
    var playerAllianceKey: Int = 4
        private set

    /**
     * Key for player faction/hostility flag (Byte/Int)
     * Non-zero = hostile
     */
    var playerFactionFlagKey: Int = 23
        private set

    /**
     * Key for player health percentage (Float)
     */
    var playerHealthKey: Int = 12
        private set

    /**
     * Key for player mount status (Boolean or Int)
     */
    var playerMountKey: Int = 26
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // SILVER KEYS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for silver type name
     */
    var silverTypeNameKey: Int = 1
        private set

    /**
     * Key for silver amount (Int)
     */
    var silverAmountKey: Int = 2
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // CHEST KEYS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for chest type name
     */
    var chestTypeNameKey: Int = 1
        private set

    /**
     * Key for chest rarity
     */
    var chestRarityKey: Int = 7
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // DUNGEON KEYS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for dungeon type name
     */
    var dungeonTypeNameKey: Int = 1
        private set

    /**
     * Key for dungeon rarity
     */
    var dungeonRarityKey: Int = 7
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // MIST KEYS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Key for mist type name
     */
    var mistTypeNameKey: Int = 1
        private set

    /**
     * Key for mist rarity
     */
    var mistRarityKey: Int = 7
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // PLAN B COORDINATE RANGE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Minimum valid world coordinate for Plan B scanner
     * Albion world coordinates are typically in [-32768, +32768] range
     */
    var coordinateMinValid: Float = -32768.0f
        private set

    /**
     * Maximum valid world coordinate for Plan B scanner
     */
    var coordinateMaxValid: Float = 32768.0f
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // RESOURCE TYPE PREFIXES - For Plan B type detection
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Known prefixes for each resource type
     * Used to identify resource category from type name string
     */
    private val resourcePrefixes = mapOf(
        "fiber" to listOf(
            "FIBER", "COTTON", "HEMP", "FLAX", "SILK", "SPONGE"
        ),
        "ore" to listOf(
            "ORE", "IRON", "STEEL", "TITANIUM", "RUNITE", "METEORITE"
        ),
        "logs" to listOf(
            "WOOD", "LOG", "BIRCH", "OAK", "CEDAR", "PINE", "FROSTWOOD"
        ),
        "rock" to listOf(
            "ROCK", "STONE", "LIMESTONE", "SANDSTONE", "TRAVERTINE", "GRANITE"
        ),
        "hide" to listOf(
            "HIDE", "LEATHER", "REPTILE", "BEAR", "DIREWOLF"
        ),
        "crop" to listOf(
            "WHEAT", "CROP", "CARROT", "TURNIP", "CABBAGE", "BEAN"
        )
    )

    /**
     * Boss type prefixes for boss detection
     */
    private val bossPrefixes = listOf(
        "BOSS", "ELDER", "ANCIENT", "GUARDIAN", "KEEPER", "MISTBOSS",
        "CHAMPION", "CHIEF", "CHOSEN", "COMMANDER", "LORD", "MASTER"
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private var loaded = false
    private var version: String = "unknown"

    /**
     * Load key mappings from id_map.json asset file
     * Call once at application start
     *
     * @param context Application context
     */
    fun load(context: Context) {
        if (loaded) {
            Log.d(TAG, "IdMapRepository already loaded")
            return
        }

        try {
            val json = context.assets.open(ASSET_FILE).use { input ->
                InputStreamReader(input, "UTF-8").use { reader ->
                    reader.readText()
                }
            }

            val root = Gson().fromJson(json, JsonObject::class.java)
            parseConfig(root)
            loaded = true

            DiscoveryLogger.i("IdMapRepository loaded from $ASSET_FILE")
            DiscoveryLogger.i("Version: $version")
            DiscoveryLogger.d("Keys: objectId=$objectIdKey, posX=$posXKey, posY=$posYKey")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $ASSET_FILE: ${e.message}, using defaults")
            DiscoveryLogger.w("Failed to load $ASSET_FILE, using defaults: ${e.message}")
            loaded = true // Use defaults
        }
    }

    /**
     * Parse JSON configuration
     */
    private fun parseConfig(root: JsonObject) {
        // Version
        version = root.get("version")?.asString ?: "unknown"

        // Common keys
        root.getAsJsonObject("common")?.let { obj ->
            objectIdKey = obj.get("objectIdKey")?.asInt ?: objectIdKey
            posXKey = obj.get("posXKey")?.asInt ?: posXKey
            posYKey = obj.get("posYKey")?.asInt ?: posYKey
        }

        // JoinFinished
        root.getAsJsonObject("joinFinished")?.let { obj ->
            localObjectIdKey = obj.get("localObjectIdKey")?.asInt ?: localObjectIdKey
        }

        // Harvestable
        root.getAsJsonObject("harvestable")?.let { obj ->
            harvestTypeNameKey = obj.get("typeNameKey")?.asInt ?: harvestTypeNameKey
            harvestListKey = obj.get("listKey")?.asInt ?: harvestListKey
            harvestTierKey = obj.get("tierKey")?.asInt ?: harvestTierKey
            harvestEnchantKey = obj.get("enchantKey")?.asInt ?: harvestEnchantKey
        }

        // Mob
        root.getAsJsonObject("mob")?.let { obj ->
            mobTypeNameKey = obj.get("typeNameKey")?.asInt ?: mobTypeNameKey
            mobTierKey = obj.get("tierKey")?.asInt ?: mobTierKey
            mobEnchantKey = obj.get("enchantKey")?.asInt ?: mobEnchantKey
            mobIsBossKey = obj.get("isBossKey")?.asInt ?: mobIsBossKey
            mobHealthKey = obj.get("healthKey")?.asInt ?: mobHealthKey
        }

        // Player
        root.getAsJsonObject("player")?.let { obj ->
            playerNameKey = obj.get("nameKey")?.asInt ?: playerNameKey
            playerGuildKey = obj.get("guildKey")?.asInt ?: playerGuildKey
            playerAllianceKey = obj.get("allianceKey")?.asInt ?: playerAllianceKey
            playerFactionFlagKey = obj.get("factionFlagKey")?.asInt ?: playerFactionFlagKey
            playerHealthKey = obj.get("healthKey")?.asInt ?: playerHealthKey
            playerMountKey = obj.get("mountKey")?.asInt ?: playerMountKey
        }

        // Silver
        root.getAsJsonObject("silver")?.let { obj ->
            silverTypeNameKey = obj.get("typeNameKey")?.asInt ?: silverTypeNameKey
            silverAmountKey = obj.get("amountKey")?.asInt ?: silverAmountKey
        }

        // Chest
        root.getAsJsonObject("chest")?.let { obj ->
            chestTypeNameKey = obj.get("typeNameKey")?.asInt ?: chestTypeNameKey
            chestRarityKey = obj.get("rarityKey")?.asInt ?: chestRarityKey
        }

        // Dungeon
        root.getAsJsonObject("dungeon")?.let { obj ->
            dungeonTypeNameKey = obj.get("typeNameKey")?.asInt ?: dungeonTypeNameKey
            dungeonRarityKey = obj.get("rarityKey")?.asInt ?: dungeonRarityKey
        }

        // Mist
        root.getAsJsonObject("mist")?.let { obj ->
            mistTypeNameKey = obj.get("typeNameKey")?.asInt ?: mistTypeNameKey
            mistRarityKey = obj.get("rarityKey")?.asInt ?: mistRarityKey
        }

        // Coordinate Plan B range
        root.getAsJsonObject("coordinatePlanB")?.let { obj ->
            coordinateMinValid = obj.get("minValid")?.asFloat ?: coordinateMinValid
            coordinateMaxValid = obj.get("maxValid")?.asFloat ?: coordinateMaxValid
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Match a type name string to a resource category
     *
     * @param typeName The type name string from the event
     * @return Resource category string ("fiber", "ore", etc.) or null
     */
    fun matchResourcePrefix(typeName: String): String? {
        if (typeName.isBlank()) return null

        val upper = typeName.uppercase()
        for ((category, prefixes) in resourcePrefixes) {
            if (prefixes.any { upper.contains(it) }) {
                return category
            }
        }
        return null
    }

    /**
     * Check if a type name indicates a boss mob
     *
     * @param typeName The type name string from the event
     * @return True if the type name matches boss patterns
     */
    fun isBossType(typeName: String): Boolean {
        if (typeName.isBlank()) return false

        val upper = typeName.uppercase()
        return bossPrefixes.any { upper.contains(it) }
    }

    /**
     * Parse tier from type name string
     * Format: "T{n}_{TYPE}" (e.g., "T4_FIBER" -> 4)
     *
     * @param typeName The type name string from the event
     * @return Tier (1-8) or 0 if not found
     */
    fun parseTier(typeName: String): Int {
        if (typeName.isBlank()) return 0

        // Try T{n}_ prefix pattern
        val tierMatch = Regex("^T(\\d)_").find(typeName)
        if (tierMatch != null) {
            return tierMatch.groupValues[1].toIntOrNull() ?: 0
        }

        // Try just T{n} anywhere
        val simpleMatch = Regex("T([1-8])").find(typeName)
        return simpleMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Parse enchant level from type name string
     * Format: "{TYPE}@{enchant}" (e.g., "T4_FIBER@2" -> 2)
     *
     * @param typeName The type name string from the event
     * @return Enchant level (0-4) or 0 if not found
     */
    fun parseEnchant(typeName: String): Int {
        if (typeName.isBlank()) return 0

        // @n suffix pattern
        val enchantMatch = Regex("@(\\d)$").find(typeName)
        return enchantMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Check if loaded successfully
     */
    fun isLoaded(): Boolean = loaded

    /**
     * Get version string
     */
    fun getVersion(): String = version
}
