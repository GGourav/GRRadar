package com.grradar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.SurfaceView
import com.grradar.data.EntityStore
import com.grradar.model.*
import kotlin.math.sqrt

class RadarSurfaceView(context: Context) : SurfaceView(context) {

    companion object {
        private const val DEFAULT_SCALE = 2.0f
    }

    private var entityStore: EntityStore? = null
    private var scale = DEFAULT_SCALE
    private var selfDotSize = 8f
    private var entityDotSize = 6f

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val selfPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 12f
        isAntiAlias = true
    }

    private val entityPaints = mutableMapOf<EntityType, Paint>()

    init {
        setZOrderOnTop(true)
        initPaints()
    }

    private fun initPaints() {
        EntityType.entries.forEach { type ->
            entityPaints[type] = Paint().apply {
                color = try { Color.parseColor(type.colorHex) } catch (e: Exception) { Color.GRAY }
                style = Paint.Style.FILL
                isAntiAlias = true
            }
        }
    }

    fun setEntityStore(store: EntityStore) {
        entityStore = store
    }

    fun setScale(newScale: Float) {
        scale = newScale
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val store = entityStore ?: return
        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = (kotlin.math.min(width, height) / 2) - 2f

        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
        canvas.drawCircle(centerX, centerY, radius, borderPaint)

        val (localX, localY) = store.getLocalPlayerPosition()
        val entities = store.getAllEntities()

        entities.forEach { entity ->
            val dx = (entity.worldX - localX) * scale
            val dy = (entity.worldY - localY) * scale

            val screenX = centerX + dx
            val screenY = centerY - dy

            // FIX: Use kotlin.math.sqrt with Float
            val distSq = (screenX - centerX) * (screenX - centerX) + 
                         (screenY - centerY) * (screenY - centerY)
            
            if (distSq <= radius * radius) {
                drawEntity(canvas, entity, screenX, screenY)
            }
        }

        canvas.drawCircle(centerX, centerY, selfDotSize, selfPaint)
    }

    private fun drawEntity(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        val paint = entityPaints[entity.type] ?: return

        val size = when {
            entity.isBoss -> entityDotSize * 2
            entity.isElite -> entityDotSize * 1.5f
            entity.isResource() -> entityDotSize * 0.8f
            else -> entityDotSize
        }

        canvas.drawCircle(x, y, size, paint)

        if (entity.isResource() && entity.enchantment.level > 0) {
            entity.enchantment.ringColorHex?.let { ringColor ->
                val ringPaint = Paint().apply {
                    color = try { Color.parseColor(ringColor) } catch (e: Exception) { Color.WHITE }
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    isAntiAlias = true
                }
                canvas.drawCircle(x, y, size + 3, ringPaint)
            }
        }

        if (entity.type == EntityType.PLAYER || entity.type == EntityType.HOSTILE_PLAYER) {
            if (entity.name.isNotEmpty()) {
                canvas.drawText(entity.name, x + size + 2, y + 4, textPaint)
            }
        }
    }
}
