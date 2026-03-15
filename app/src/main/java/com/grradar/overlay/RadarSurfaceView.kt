package com.grradar.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.grradar.data.EntityStore
import com.grradar.model.EntityType
import com.grradar.model.RadarEntity

/**
 * RadarSurfaceView — Canvas-based radar rendering view
 *
 * COORDINATE SYSTEM:
 *   - World X increases East
 *   - World Y increases North
 *   - Canvas Y increases downward (inverted)
 *
 *   screenX = centerX + (entity.worldX - localX) * scale
 *   screenY = centerY - (entity.worldY - localY) * scale
 *
 * RENDERING:
 *   - Radar circle drawn at center
 *   - Local player at center (cyan dot)
 *   - Entities drawn relative to local player
 *   - Different colors/sizes for different entity types
 *   - Enchant levels shown as rings
 *
 * FEATURES:
 *   - Configurable radar size and zoom
 *   - Entity type filtering
 *   - Distance-based clipping
 *   - Efficient drawing with Paint caching
 */
class RadarSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "RadarSurfaceView"

        // Default settings
        private const val DEFAULT_WORLD_RANGE = 50f
        private const val DOT_SIZE_SMALL = 4f
        private const val DOT_SIZE_NORMAL = 6f
        private const val DOT_SIZE_LARGE = 8f
        private const val DOT_SIZE_XL = 12f
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * World range visible in each direction from center
     * Larger = zoom out, smaller = zoom in
     */
    var worldRange: Float = DEFAULT_WORLD_RANGE
        set(value) {
            field = value.coerceAtLeast(10f)
            invalidate()
        }

    // Entity type visibility toggles
    var showResources: Boolean = true
        set(value) { field = value; invalidate() }

    var showMobs: Boolean = true
        set(value) { field = value; invalidate() }

    var showPlayers: Boolean = true
        set(value) { field = value; invalidate() }

    var showChests: Boolean = true
        set(value) { field = value; invalidate() }

    var showDungeons: Boolean = true
        set(value) { field = value; invalidate() }

    var showSilver: Boolean = true
        set(value) { field = value; invalidate() }

    var showMist: Boolean = true
        set(value) { field = value; invalidate() }

    var showUnknown: Boolean = false
        set(value) { field = value; invalidate() }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAINTS (Cached for performance)
    // ═══════════════════════════════════════════════════════════════════════════

    // Border and grid paint
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(100, 255, 255, 255)
        isAntiAlias = true
    }

    // Fill paint for entities (reused)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Stroke paint for enchant rings
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // Local player paint
    private val selfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.CYAN
        isAntiAlias = true
    }

    // Text paint for labels
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f
        color = Color.WHITE
        isAntiAlias = true
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COLORS
    // ═══════════════════════════════════════════════════════════════════════════

    // Resource colors by tier (alpha increases with tier)
    private val resourceColors = arrayOf(
        Color.argb(120, 100, 150, 255),   // T1 - light blue
        Color.argb(140, 100, 170, 255),   // T2
        Color.argb(160, 100, 190, 255),   // T3
        Color.argb(180, 120, 200, 255),   // T4
        Color.argb(200, 140, 180, 255),   // T5
        Color.argb(220, 160, 160, 255),   // T6
        Color.argb(240, 180, 140, 255),   // T7
        Color.argb(255, 200, 120, 255)    // T8 - purple-ish
    )

    // Enchant ring colors
    private val enchantColors = arrayOf(
        Color.TRANSPARENT,                  // 0 - no enchant
        Color.argb(255, 0, 255, 0),         // 1 - green (.1)
        Color.argb(255, 0, 150, 255),       // 2 - blue (.2)
        Color.argb(255, 180, 0, 255),       // 3 - purple (.3)
        Color.argb(255, 255, 215, 0)        // 4 - gold (.4)
    )

    // Mob colors
    private val mobColors = mapOf(
        EntityType.NORMAL_MOB to Color.argb(220, 76, 175, 80),      // Green
        EntityType.ENCHANTED_MOB to Color.argb(220, 156, 39, 176),  // Purple
        EntityType.BOSS_MOB to Color.argb(220, 255, 152, 0)         // Orange
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // RENDERING
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate radar center and scale
        val centerX = width / 2f
        val centerY = height / 2f
        val radarRadius = minOf(width, height) / 2f - 10f
        val scale = radarRadius / worldRange

        // Draw radar background (semi-transparent)
        drawRadarBackground(canvas, centerX, centerY, radarRadius)

        // Draw grid lines
        drawGrid(canvas, centerX, centerY, radarRadius)

        // Get local player position
        val (localX, localY) = EntityStore.getLocalPlayerPosition()

        // Draw all entities
        drawEntities(canvas, EntityStore.getAllEntities(), centerX, centerY, localX, localY, scale, radarRadius)

        // Draw local player at center
        if (EntityStore.hasLocalPlayer()) {
            drawLocalPlayer(canvas, centerX, centerY)
        }
    }

    /**
     * Draw radar background circle
     */
    private fun drawRadarBackground(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        // Semi-transparent dark background
        fillPaint.color = Color.argb(40, 0, 0, 0)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        // Border circle
        canvas.drawCircle(centerX, centerY, radius, borderPaint)
    }

    /**
     * Draw grid lines
     */
    private fun drawGrid(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        // Cross-hair
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, borderPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, borderPaint)

        // Inner circle (half range)
        borderPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawCircle(centerX, centerY, radius / 2, borderPaint)
        borderPaint.color = Color.argb(100, 255, 255, 255)
    }

    /**
     * Draw all entities
     */
    private fun drawEntities(
        canvas: Canvas,
        entities: List<RadarEntity>,
        centerX: Float,
        centerY: Float,
        localX: Float,
        localY: Float,
        scale: Float,
        radarRadius: Float
    ) {
        for (entity in entities) {
            // Apply visibility filters
            if (!shouldDrawEntity(entity)) continue

            // Calculate screen position
            val screenX = centerX + (entity.worldX - localX) * scale
            val screenY = centerY - (entity.worldY - localY) * scale

            // Check if within radar circle
            val dx = screenX - centerX
            val dy = screenY - centerY
            if (dx * dx + dy * dy > radarRadius * radarRadius) continue

            // Draw entity
            drawEntity(canvas, entity, screenX, screenY)
        }
    }

    /**
     * Check if entity should be drawn based on type filters
     */
    private fun shouldDrawEntity(entity: RadarEntity): Boolean {
        return when {
            entity.isResource() -> showResources
            entity.isMob() -> showMobs
            entity.isPlayer() -> showPlayers
            entity.type == EntityType.CHEST -> showChests
            entity.type == EntityType.DUNGEON_PORTAL -> showDungeons
            entity.type == EntityType.SILVER -> showSilver
            entity.type == EntityType.MIST_WISP -> showMist
            entity.type == EntityType.UNKNOWN -> showUnknown
            else -> true
        }
    }

    /**
     * Draw a single entity
     */
    private fun drawEntity(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        when {
            entity.isResource() -> drawResource(canvas, entity, x, y)
            entity.isMob() -> drawMob(canvas, entity, x, y)
            entity.isPlayer() -> drawPlayer(canvas, entity, x, y)
            entity.type == EntityType.CHEST -> drawChest(canvas, x, y)
            entity.type == EntityType.DUNGEON_PORTAL -> drawDungeon(canvas, x, y)
            entity.type == EntityType.SILVER -> drawSilver(canvas, x, y)
            entity.type == EntityType.MIST_WISP -> drawMist(canvas, entity, x, y)
            else -> drawUnknown(canvas, x, y)
        }
    }

    /**
     * Draw resource node
     */
    private fun drawResource(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        val tier = (entity.tier - 1).coerceIn(0, 7)
        val enchant = entity.enchant.coerceIn(0, 4)

        // Base dot
        val color = resourceColors[tier]
        fillPaint.color = color

        val size = DOT_SIZE_NORMAL + (entity.tier - 1) * 0.5f
        canvas.drawCircle(x, y, size, fillPaint)

        // Enchant ring
        if (enchant > 0) {
            strokePaint.color = enchantColors[enchant]
            canvas.drawCircle(x, y, size + 3f, strokePaint)
        }
    }

    /**
     * Draw mob
     */
    private fun drawMob(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        val color = mobColors[entity.type] ?: Color.GREEN
        fillPaint.color = color

        val size = when (entity.type) {
            EntityType.BOSS_MOB -> DOT_SIZE_XL
            EntityType.ENCHANTED_MOB -> DOT_SIZE_LARGE
            else -> DOT_SIZE_NORMAL
        }

        canvas.drawCircle(x, y, size, fillPaint)

        // Enchant ring for enchanted mobs
        if (entity.enchant > 0 && entity.type != EntityType.BOSS_MOB) {
            strokePaint.color = enchantColors[entity.enchant.coerceIn(0, 4)]
            canvas.drawCircle(x, y, size + 2f, strokePaint)
        }
    }

    /**
     * Draw player
     */
    private fun drawPlayer(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        // Color based on hostility
        fillPaint.color = if (entity.isHostile) {
            Color.argb(255, 255, 50, 50) // Red
        } else {
            Color.argb(255, 255, 255, 255) // White
        }

        canvas.drawCircle(x, y, DOT_SIZE_LARGE, fillPaint)

        // Draw name if available
        if (entity.name.isNotEmpty()) {
            canvas.drawText(entity.name, x + DOT_SIZE_LARGE + 2, y + 4, textPaint)
        }
    }

    /**
     * Draw chest
     */
    private fun drawChest(canvas: Canvas, x: Float, y: Float) {
        fillPaint.color = Color.argb(220, 255, 193, 7) // Gold
        val size = DOT_SIZE_NORMAL
        canvas.drawRect(
            x - size, y - size,
            x + size, y + size,
            fillPaint
        )
    }

    /**
     * Draw dungeon portal
     */
    private fun drawDungeon(canvas: Canvas, x: Float, y: Float) {
        fillPaint.color = Color.argb(220, 150, 150, 150) // Gray
        val size = DOT_SIZE_LARGE
        val path = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size, y + size * 0.6f)
            lineTo(x - size, y + size * 0.6f)
            close()
        }
        canvas.drawPath(path, fillPaint)
    }

    /**
     * Draw silver
     */
    private fun drawSilver(canvas: Canvas, x: Float, y: Float) {
        fillPaint.color = Color.argb(220, 255, 235, 59) // Yellow
        canvas.drawCircle(x, y, DOT_SIZE_SMALL, fillPaint)
    }

    /**
     * Draw mist wisp
     */
    private fun drawMist(canvas: Canvas, entity: RadarEntity, x: Float, y: Float) {
        fillPaint.color = Color.argb(200, 200, 200, 200) // Light gray
        val size = DOT_SIZE_NORMAL
        val path = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size, y)
            lineTo(x, y + size)
            lineTo(x - size, y)
            close()
        }
        canvas.drawPath(path, fillPaint)
    }

    /**
     * Draw unknown entity
     */
    private fun drawUnknown(canvas: Canvas, x: Float, y: Float) {
        fillPaint.color = Color.argb(150, 128, 128, 128)
        canvas.drawCircle(x, y, DOT_SIZE_SMALL, fillPaint)
    }

    /**
     * Draw local player at center
     */
    private fun drawLocalPlayer(canvas: Canvas, centerX: Float, centerY: Float) {
        // Outer glow
        fillPaint.color = Color.argb(100, 0, 255, 255)
        canvas.drawCircle(centerX, centerY, DOT_SIZE_XL + 4, fillPaint)

        // Main dot
        canvas.drawCircle(centerX, centerY, DOT_SIZE_LARGE, selfPaint)
    }

    /**
     * Trigger redraw - call from overlay service refresh loop
     */
    fun refresh() {
        invalidate()
    }

    /**
     * Get current scale factor
     */
    fun getScale(): Float {
        val radarRadius = minOf(width, height) / 2f - 10f
        return radarRadius / worldRange
    }

    /**
     * Convert screen coordinates to world coordinates
     */
    fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val centerX = width / 2f
        val centerY = height / 2f
        val scale = getScale()
        val (localX, localY) = EntityStore.getLocalPlayerPosition()

        val worldX = localX + (screenX - centerX) / scale
        val worldY = localY - (screenY - centerY) / scale

        return Pair(worldX, worldY)
    }
}
