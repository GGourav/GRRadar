package com.grradar.data

import com.grradar.model.RadarEntity
import com.grradar.model.EntityType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class EntityStore {
    
    private val entities = ConcurrentHashMap<Int, RadarEntity>()
    
    private val localPlayerId = AtomicReference<Int?>(null)
    private val localPlayerX = AtomicReference<Float>(0f)
    private val localPlayerY = AtomicReference<Float>(0f)
    private val currentZone = AtomicReference<String>("")
    
    fun putEntity(entity: RadarEntity) {
        entities[entity.id] = entity
    }
    
    fun removeEntity(id: Int): RadarEntity? = entities.remove(id)
    
    fun getEntity(id: Int): RadarEntity? = entities[id]
    
    fun getAllEntities(): List<RadarEntity> = entities.values.toList()
    
    fun getEntityCount(): Int = entities.size
    
    fun clearAll() {
        entities.clear()
    }
    
    fun setLocalPlayerId(id: Int) {
        localPlayerId.set(id)
    }
    
    fun getLocalPlayerId(): Int? = localPlayerId.get()
    
    fun setLocalPlayerPosition(x: Float, y: Float) {
        localPlayerX.set(x)
        localPlayerY.set(y)
    }
    
    fun getLocalPlayerPosition(): Pair<Float, Float> = Pair(localPlayerX.get(), localPlayerY.get())
    
    fun setCurrentZone(zone: String) {
        currentZone.set(zone)
    }
    
    fun getCurrentZone(): String = currentZone.get()
    
    fun getStats(): EntityStoreStats {
        return EntityStoreStats(
            totalEntities = entities.size,
            localPlayerId = localPlayerId.get(),
            localPlayerPos = getLocalPlayerPosition(),
            currentZone = currentZone.get()
        )
    }
}

data class EntityStoreStats(
    val totalEntities: Int,
    val localPlayerId: Int?,
    val localPlayerPos: Pair<Float, Float>,
    val currentZone: String
)
