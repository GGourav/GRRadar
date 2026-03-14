package com.grradar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.SurfaceView
import android.view.View
import com.grradar.data.EntityStore
import com.grradar.model.*

/**
 * Radar Surface View - Custom view for rendering radar entities
 * 
 * Draws colored dots on a circular radar display centered on local player.
 */
class RadarSurfaceView(context: Context) : SurfaceView(context) {

    companion object {
        private const val TAG = "RadarSurfaceView"
        private const val DEFAULT_SCALE = 2.0f
    }

    // Entity data
    private var entityStore: EntityStore? = null

    // Rendering config
    private var scale = DEFAULT_SCALE
    private var showCircle = true
    private var showBorder = true
    private var selfDotSize = 8f
    private var entityDotSize = 6f
    private var borderThickness = 2f

    // Paints
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = borderThickness
        isAntiAlias = true
    }

    private val selfPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f
        isAntiAlias = true
    }

    // Entity paints by type
    private val entityPaints = mutableMapOf<EntityType, Paint>()

    init {
        setZOrderOnTop(true)
        initPaints()
    }

    private fun initPaints() {
        EntityType.entries.forEach { type ->
            entityPaints[type] = Paint().apply {
                try {
                    color = Color.parseColor(type.colorHex)
                } catch (e: Exception) {
                    color = Color.GRAY
                }
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

    fun setDisplayOptions(circle: Boolean, border: Boolean) {
        showCircle = circle
        showBorder = border
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val store = entityStore ?: return
        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = (Math.min(width, height) / 2) - borderThickness

        // Draw background
        if (showCircle) {
            canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
        } else {
            canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        }

        // Draw border
        if (showBorder) {
            if (showCircle) {
                canvas.drawCircle(centerX, centerY, radius, borderPaint)
            } else {
                canvas.drawRect(
                    borderThickness / 2,
                    borderThickness / 2,
                    width - borderThickness / 2,
                    height - borderThickness / 2,
                    borderPaint
                )
            }
        }

        // Get local player position
        val (localX, localY) = store.getLocalPlayerPosition()

        // Draw entities
        val entities = store.getAllEntities()
        val rangePixels = radius

        entities.forEach { entity ->
            // Convert world coordinates to screen coordinates
            val dx = (entity.worldX - localX) * scale
            val dy = (entity.worldY - localY) * scale

            // Canvas Y is inverted
            val screenX = centerX + dx
            val screenY = centerY - dy

            // Check if within radar bounds
            val distanceFromCenter = Math.sqrt(
                (screenX - centerX) * (screenX - centerX) + 
                (screenY - centerY) * (screenY - centerY)
            )
            
            if (distanceFromCenter <= rangePixels) {
                drawEntity(canvas, entity, screenX, screenY)
            }
        }

        // Draw self (center dot)
        canvas.drawCircle(centerX, centerY, selfDotSize, selfPaint)
    }

    private fun drawEntity(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        val paint = entityPaints[entity.type] ?: return

        // Size based on entity type
        val size = when {
            entity.isBoss -> entityDotSize * 2
            entity.isElite -> entityDotSize * 1.5f
            entity.isResource() -> entityDotSize * 0.8f
            else -> entityDotSize
        }

        // Draw main dot
        canvas.drawCircle(x, y, size, paint)

        // Draw enchantment ring for resources
        if (entity.isResource() && entity.enchantment.level > 0) {
            val ringColor = entity.enchantment.ringColorHex
            if (ringColor != null) {
                val ringPaint = Paint().apply {
                    color = try {
                        Color.parseColor(ringColor)
                    } catch (e: Exception) {
                        Color.WHITE
                    }
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    isAntiAlias = true
                }
                canvas.drawCircle(x, y, size + 3, ringPaint)
            }
        }

        // Draw name for players
        if (entity.type == EntityType.PLAYER || entity.type == EntityType.HOSTILE_PLAYER) {
            if (entity.name.isNotEmpty()) {
                canvas.drawText(entity.name, x + size + 2, y + 4, textPaint)
            }
        }
    }

    fun refresh() {
        invalidate()
    }
}
