package com.grradar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.grradar.data.EntityStore
import com.grradar.model.*
import com.grradar.vpn.AlbionVpnService

class RadarSurfaceView(context: Context) : View(context) {

    companion object {
        private const val TAG = "RadarSurfaceView"
        private const val DEFAULT_SCALE = 2.0f
        private const val DEFAULT_RANGE = 200.0f
    }

    private var entityStore: EntityStore? = null

    private var scale = DEFAULT_SCALE
    private var showCircle = true
    private var showBorder = true
    private var selfDotSize = 8f
    private var entityDotSize = 6f
    private var borderThickness = 2f

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#E6000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#80FFFFFF")
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
        textSize = 11f
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private val textPaintSmall = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 9f
        isAntiAlias = true
    }

    private val textPaintGreen = Paint().apply {
        color = Color.parseColor("#00FF00")
        textSize = 10f
        isAntiAlias = true
    }

    private val textPaintYellow = Paint().apply {
        color = Color.parseColor("#FFFF00")
        textSize = 10f
        isAntiAlias = true
    }

    private val textPaintRed = Paint().apply {
        color = Color.parseColor("#FF6666")
        textSize = 10f
        isAntiAlias = true
    }

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

        if (showCircle) {
            canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
        } else {
            canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        }

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

        drawStatsHeader(canvas, width)

        val store = entityStore
        if (store == null) {
            canvas.drawText("No EntityStore", centerX - 45, centerY, textPaintRed)
            canvas.drawCircle(centerX, centerY, selfDotSize, selfPaint)
            return
        }

        val (localX, localY) = store.getLocalPlayerPosition()
        val localPlayerId = store.getLocalPlayerId()

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

        canvas.drawCircle(centerX, centerY, selfDotSize, selfPaint)
        
        if (localPlayerId != null) {
            canvas.drawText("ID: $localPlayerId", centerX - 25, centerY + selfDotSize + 12, textPaintSmall)
        }
    }

    private fun drawStatsHeader(canvas: Canvas, width: Float) {
        val y = 14f
        
        canvas.drawText("PKG: com.albiononline", 4f, y, textPaintGreen)
        
        val pktCount = AlbionVpnService.packetCount.get()
        val albCount = AlbionVpnService.albionCount.get()
        val entCount = entityStore?.getEntityCount() ?: AlbionVpnService.entityCount
        val vpnRunning = AlbionVpnService.isRunning()
        
        val statusText = if (vpnRunning) "VPN: ON" else "VPN: OFF"
        val statusPaint = if (vpnRunning) textPaintGreen else textPaintRed
        canvas.drawText(statusText, 4f, y + 12, statusPaint)
        
        canvas.drawText("PKT: $pktCount", 4f, y + 24, textPaintYellow)
        canvas.drawText("ALB: $albCount", 70f, y + 24, textPaintYellow)
        
        val entityPaint = if (entCount > 0) textPaintGreen else textPaintRed
        canvas.drawText("ENT: $entCount", 4f, y + 36, entityPaint)
        
        val localId = entityStore?.getLocalPlayerId()
        val idText = if (localId != null) "LID: $localId" else "LID: --"
        canvas.drawText(idText, 70f, y + 36, textPaint)
        
        val (lx, ly) = entityStore?.getLocalPlayerPosition() ?: Pair(0f, 0f)
        canvas.drawText("POS: ${lx.toInt()},${ly.toInt()}", 4f, y + 48, textPaintSmall)
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
