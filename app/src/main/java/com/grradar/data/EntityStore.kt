package com.grradar.data

import com.grradar.model.RadarEntity
import java.util.concurrent.ConcurrentHashMap

object EntityStore {

    private val entities = ConcurrentHashMap<Int, RadarEntity>()

    @Volatile
    var localPlayerId: Int = -1

    @Volatile
    var localPlayerX: Float = 0f

    @Volatile
    var localPlayerY: Float = 0f

    fun addEntity(entity: RadarEntity) {
        entities[entity.id] = entity
    }

    fun removeEntity(id: Int) {
        entities.remove(id)
    }

    fun getEntity(id: Int): RadarEntity? = entities[id]

    fun getAllEntities(): Collection<RadarEntity> = entities.values

    fun clear() {
        entities.clear()
    }

    fun size(): Int = entities.size

    fun setLocalPlayer(id: Int, x: Float, y: Float) {
        localPlayerId = id
        localPlayerX = x
        localPlayerY = y
    }

    fun updatePosition(id: Int, x: Float, y: Float) {
        entities[id]?.let {
            it.worldX = x
            it.worldY = y
        }
    }
}
