package com.grradar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.grradar.data.EntityStore
import com.grradar.model.*

/**
 * Radar View - Custom view for rendering radar entities
 * 
 * Draws colored dots on a circular radar display centered on local player.
 * Uses regular View (not SurfaceView) for proper overlay rendering.
 */
class RadarSurfaceView(context: Context) : View(context) {

    companion object {
        private const val TAG = "RadarSurfaceView"
        private const val DEFAULT_SCALE = 2.0f
        private const val DEFAULT_RANGE = 200.0f
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
        color = Color.parseColor("#CC000000") // Darker background
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#80FFFFFF") // More visible border
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
        textSize = 12f
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // Entity paints by type
    private val entityPaints = mutableMapOf<EntityType, Paint>()

    init {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        if (width <= 0 || height <= 0) return
        
        val centerX = width / 2
        val centerY = height / 2
        val radius = (Math.min(width, height) / 2) - borderThickness

        // Draw background circle
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

        // Get entity store
        val store = entityStore
        if (store == null) {
            // Draw "No Data" text if no store
            canvas.drawText("Waiting for data...", centerX - 40, centerY, textPaint)
            // Draw self (center dot) anyway
            canvas.drawCircle(centerX, centerY, selfDotSize, selfPaint)
            return
        }

        // Get local player position
        val (localX, localY) = store.getLocalPlayerPosition()

        // Draw entities
        val entities = store.getAllEntities()
        val rangePixels = radius

        entities.forEach { entity ->
            val dx = (entity.worldX - localX) * scale
            val dy = (entity.worldY - localY) * scale

            val screenX = centerX + dx
            val screenY = centerY - dy

            val distanceFromCenter = Math.sqrt(
                ((screenX - centerX) * (screenX - centerX) + 
                 (screenY - centerY) * (screenY - centerY)).toDouble()
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

        val size: Float = when {
            entity.isBoss -> entityDotSize * 2f
            entity.isElite -> entityDotSize * 1.5f
            entity.isResource() -> entityDotSize * 0.8f
            else -> entityDotSize
        }

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
                canvas.drawCircle(x, y, size + 3f, ringPaint)
            }
        }

        // Draw name for players
        if (entity.type == EntityType.PLAYER || entity.type == EntityType.HOSTILE_PLAYER) {
            if (entity.name.isNotEmpty()) {
                canvas.drawText(entity.name, x + size + 2f, y + 4f, textPaint)
            }
        }
    }

    fun refresh() {
        invalidate()
    }
}
