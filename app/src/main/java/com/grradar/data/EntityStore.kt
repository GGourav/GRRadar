package com.grradar.data

import com.grradar.model.RadarEntity
import com.grradar.model.EntityType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe entity storage for radar
 * Supports concurrent reads/writes from VPN parser thread and UI render thread
 */
class EntityStore {
    
    // Main entity storage - ConcurrentHashMap for thread safety
    private val entities = ConcurrentHashMap<Int, RadarEntity>()
    
    // Local player state
    private val localPlayerId = AtomicReference<Int?>(null)
    private val localPlayerX = AtomicReference<Float>(0f)
    private val localPlayerY = AtomicReference<Float>(0f)
    private val currentZoneName = AtomicReference<String>("")
    private val isInSafeZone = AtomicReference<Boolean>(true)
    
    // Lock for bulk operations
    private val lock = ReentrantReadWriteLock()
    
    // Entity counts by type for filtering
    private val typeCounts = ConcurrentHashMap<EntityType, Int>()
    
    /**
     * Add or update an entity
     */
    fun putEntity(entity: RadarEntity) {
        lock.read {
            val previous = entities.put(entity.id, entity)
            updateTypeCount(entity.type, previous == null)
        }
    }
    
    /**
     * Add or update multiple entities at once
     */
    fun putEntities(newEntities: List<RadarEntity>) {
        lock.write {
            newEntities.forEach { entity ->
                val previous = entities.put(entity.id, entity)
                updateTypeCount(entity.type, previous == null)
            }
        }
    }
    
    /**
     * Remove an entity by ID
     */
    fun removeEntity(id: Int): RadarEntity? {
        lock.read {
            val entity = entities.remove(id)
            entity?.let { decrementTypeCount(it.type) }
            return entity
        }
    }
    
    /**
     * Remove multiple entities by ID
     */
    fun removeEntities(ids: List<Int>) {
        lock.write {
            ids.forEach { id ->
                entities.remove(id)?.let { decrementTypeCount(it.type) }
            }
        }
    }
    
    /**
     * Get entity by ID
     */
    fun getEntity(id: Int): RadarEntity? = entities[id]
    
    /**
     * Get all entities as a list (creates a copy)
     */
    fun getAllEntities(): List<RadarEntity> = lock.read { 
        entities.values.toList() 
    }
    
    /**
     * Get entities filtered by type
     */
    fun getEntitiesByType(type: EntityType): List<RadarEntity> = lock.read {
        entities.values.filter { it.type == type }
    }
    
    /**
     * Get entities filtered by multiple types
     */
    fun getEntitiesByTypes(types: Set<EntityType>): List<RadarEntity> = lock.read {
        entities.values.filter { it.type in types }
    }
    
    /**
     * Get entities within range of a position
     */
    fun getEntitiesInRange(centerX: Float, centerY: Float, range: Float): List<RadarEntity> = lock.read {
        entities.values.filter { entity ->
            val dx = entity.worldX - centerX
            val dy = entity.worldY - centerY
            (dx * dx + dy * dy) <= (range * range)
        }
    }
    
    /**
     * Get entities filtered by type and within range
     */
    fun getEntitiesInRangeByType(
        centerX: Float, 
        centerY: Float, 
        range: Float, 
        types: Set<EntityType>
    ): List<RadarEntity> = lock.read {
        entities.values.filter { entity ->
            if (entity.type !in types) return@filter false
            val dx = entity.worldX - centerX
            val dy = entity.worldY - centerY
            (dx * dx + dy * dy) <= (range * range)
        }
    }
    
    /**
     * Get entity count
     */
    fun getEntityCount(): Int = entities.size
    
    /**
     * Get entity count by type
     */
    fun getEntityCountByType(type: EntityType): Int = typeCounts[type] ?: 0
    
    /**
     * Check if entity exists
     */
    fun hasEntity(id: Int): Boolean = entities.containsKey(id)
    
    /**
     * Set local player ID
     */
    fun setLocalPlayerId(id: Int) {
        localPlayerId.set(id)
    }
    
    /**
     * Get local player ID
     */
    fun getLocalPlayerId(): Int? = localPlayerId.get()
    
    /**
     * Set local player position
     */
    fun setLocalPlayerPosition(x: Float, y: Float) {
        localPlayerX.set(x)
        localPlayerY.set(y)
    }
    
    /**
     * Get local player position
     */
    fun getLocalPlayerPosition(): Pair<Float, Float> = 
        Pair(localPlayerX.get(), localPlayerY.get())
    
    /**
     * Set current zone name
     */
    fun setCurrentZone(zoneName: String) {
        currentZoneName.set(zoneName)
    }
    
    /**
     * Get current zone name
     */
    fun getCurrentZone(): String = currentZoneName.get()
    
    /**
     * Set safe zone status
     */
    fun setSafeZone(isSafe: Boolean) {
        isInSafeZone.set(isSafe)
    }
    
    /**
     * Check if in safe zone
     */
    fun isInSafeZone(): Boolean = isInSafeZone.get()
    
    /**
     * Clear all entities (called on zone change)
     */
    fun clearAll() {
        lock.write {
            entities.clear()
            typeCounts.clear()
        }
    }
    
    /**
     * Clear entities by type
     */
    fun clearByType(type: EntityType) {
        lock.write {
            val idsToRemove = entities.entries
                .filter { it.value.type == type }
                .map { it.key }
            idsToRemove.forEach { entities.remove(it) }
            typeCounts.remove(type)
        }
    }
    
    /**
     * Remove old entities (by timestamp)
     */
    fun removeOldEntities(maxAgeMs: Long): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        lock.write {
            val idsToRemove = entities.entries
                .filter { it.value.timestamp < cutoff }
                .map { it.key }
            idsToRemove.forEach { id ->
                entities.remove(id)?.let { 
                    decrementTypeCount(it.type) 
                    removed++
                }
            }
        }
        return removed
    }
    
    // Helper functions for type counts
    private fun updateTypeCount(type: EntityType, isNew: Boolean) {
        if (isNew) {
            typeCounts.merge(type, 1) { old, inc -> old + inc }
        }
    }
    
    private fun decrementTypeCount(type: EntityType) {
        typeCounts.computeIfPresent(type) { _, count -> 
            if (count <= 1) null else count - 1 
        }
    }
    
    /**
     * Get statistics for debugging
     */
    fun getStats(): EntityStoreStats {
        return lock.read {
            EntityStoreStats(
                totalEntities = entities.size,
                typeCounts = typeCounts.toMap(),
                localPlayerId = localPlayerId.get(),
                localPlayerPos = Pair(localPlayerX.get(), localPlayerY.get()),
                currentZone = currentZoneName.get()
            )
        }
    }
}

/**
 * Statistics data class
 */
data class EntityStoreStats(
    val totalEntities: Int,
    val typeCounts: Map<EntityType, Int>,
    val localPlayerId: Int?,
    val localPlayerPos: Pair<Float, Float>,
    val currentZone: String
)
