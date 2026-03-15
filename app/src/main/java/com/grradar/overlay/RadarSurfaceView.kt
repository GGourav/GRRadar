package com.grradar.overlay

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View
import com.grradar.entity.RadarEntity
import com.grradar.entity.EntityType
import com.grradar.vpn.AlbionVpnService
import com.grradar.parser.EventDispatcher
import com.grradar.discovery.DiscoveryLogger

/**
 * Custom View for rendering the radar overlay.
 * Displays entities relative to player position.
 */
class RadarSurfaceView(context: Context) : View(context) {
    
    companion object {
        private const val TAG = "RadarSurfaceView"
        
        // Radar configuration
        const val RADAR_RADIUS = 200f // Visual radius in pixels
        const val SCALE = 2f // World units per pixel
        
        // Colors
        private val BG_COLOR = Color.parseColor("#CC000000") // Semi-transparent black
        private val GRID_COLOR = Color.parseColor("#333333")
        private val PLAYER_COLOR = Color.GREEN
        private val TEXT_COLOR = Color.WHITE
        private val BORDER_COLOR = Color.parseColor("#444444")
    }
    
    // Entity data
    private var entities: List<RadarEntity> = emptyList()
    private var playerX: Float = 0f
    private var playerY: Float = 0f
    
    // Paint objects
    private val bgPaint = Paint().apply {
        color = BG_COLOR
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val gridPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    
    private val entityPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = TEXT_COLOR
        textSize = 24f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    
    private val smallTextPaint = Paint().apply {
        color = TEXT_COLOR
        textSize = 18f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    
    private val borderPaint = Paint().apply {
        color = BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    private val playerPaint = Paint().apply {
        color = PLAYER_COLOR
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // View dimensions
    private var viewWidth = 0
    private var viewHeight = 0
    private var centerX = 0f
    private var centerY = 0f
    
    // Debug info
    private var vpnActive = false
    private var packetCount = 0L
    private var packetCountAfter = 0L
    
    /**
     * Update entities to display.
     */
    fun updateEntities(newEntities: List<RadarEntity>, pX: Float, pY: Float) {
        entities = newEntities
        playerX = pX
        playerY = pY
        invalidate()
    }
    
    /**
     * Update VPN statistics.
     */
    fun updateVpnStats(active: Boolean, pCount: Long, pCountAfter: Long) {
        vpnActive = active
        packetCount = pCount
        packetCountAfter = pCountAfter
        invalidate()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        centerX = w / 2f
        centerY = h / 2f
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw background
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), bgPaint)
        
        // Draw radar circle
        canvas.drawCircle(centerX, centerY, RADAR_RADIUS, borderPaint)
        
        // Draw grid lines
        drawGrid(canvas)
        
        // Draw entities
        drawEntities(canvas)
        
        // Draw player at center
        drawPlayer(canvas)
        
        // Draw debug info
        drawDebugInfo(canvas)
    }
    
    /**
     * Draw grid lines.
     */
    private fun drawGrid(canvas: Canvas) {
        // Crosshairs
        canvas.drawLine(centerX - RADAR_RADIUS, centerY, centerX + RADAR_RADIUS, centerY, gridPaint)
        canvas.drawLine(centerX, centerY - RADAR_RADIUS, centerX, centerY + RADAR_RADIUS, gridPaint)
        
        // Range circles
        val ranges = floatArrayOf(0.25f, 0.5f, 0.75f)
        for (range in ranges) {
            canvas.drawCircle(centerX, centerY, RADAR_RADIUS * range, gridPaint)
        }
    }
    
    /**
     * Draw entities on radar.
     */
    private fun drawEntities(canvas: Canvas) {
        for (entity in entities) {
            // Calculate relative position
            val relX = (entity.posX - playerX) / SCALE
            val relY = (entity.posY - playerY) / SCALE
            
            // Check if within radar range
            val distance = kotlin.math.sqrt(relX * relX + relY * relY)
            if (distance > RADAR_RADIUS) continue
            
            // Convert to screen coordinates (Y is inverted in game coords)
            val screenX = centerX + relX
            val screenY = centerY - relY
            
            // Draw entity marker
            entityPaint.color = entity.getColor()
            
            // Size based on entity type
            val size = when (entity.entityType) {
                EntityType.PLAYER -> 8f
                EntityType.BOSS -> 10f
                EntityType.MOB -> 6f
                EntityType.RESOURCE -> 5f
                EntityType.CHEST -> 7f
                EntityType.GATE -> 7f
                EntityType.DUNGEON -> 7f
                EntityType.CAMP -> 6f
                EntityType.UNKNOWN -> 4f
            }
            
            canvas.drawCircle(screenX, screenY, size, entityPaint)
            
            // Draw tier indicator for resources
            if (entity.entityType == EntityType.RESOURCE && entity.tier > 0) {
                canvas.drawText("T${entity.tier}", screenX + 8f, screenY + 4f, smallTextPaint)
            }
        }
    }
    
    /**
     * Draw player marker at center.
     */
    private fun drawPlayer(canvas: Canvas) {
        // Draw player as triangle pointing up
        val path = Path().apply {
            moveTo(centerX, centerY - 10f)
            lineTo(centerX - 7f, centerY + 7f)
            lineTo(centerX + 7f, centerY + 7f)
            close()
        }
        canvas.drawPath(path, playerPaint)
    }
    
    /**
     * Draw debug information.
     */
    private fun drawDebugInfo(canvas: Canvas) {
        var y = 30f
        val lineHeight = 22f
        
        // Title
        canvas.drawText("GRRadar Debug", 10f, y, textPaint)
        y += lineHeight + 5f
        
        // VPN Status
        val vpnStatus = if (vpnActive) "ACTIVE" else "INACTIVE"
        val vpnColor = if (vpnActive) Color.GREEN else Color.RED
        smallTextPaint.color = vpnColor
        canvas.drawText("VPN: $vpnStatus", 10f, y, smallTextPaint)
        y += lineHeight
        
        // Packet counts
        smallTextPaint.color = TEXT_COLOR
        canvas.drawText("PC: $packetCount | PCA: $packetCountAfter", 10f, y, smallTextPaint)
        y += lineHeight
        
        // Entity count
        val entityCount = entities.size
        val entityColor = when {
            entityCount > 0 -> Color.GREEN
            else -> Color.YELLOW
        }
        smallTextPaint.color = entityColor
        canvas.drawText("Entities: $entityCount", 10f, y, smallTextPaint)
        y += lineHeight
        
        // Player position
        smallTextPaint.color = TEXT_COLOR
        canvas.drawText(String.format("Pos: (%.1f, %.1f)", playerX, playerY), 10f, y, smallTextPaint)
        y += lineHeight
        
        // Entity breakdown
        val entityGroups = entities.groupBy { it.entityType }
        for ((type, list) in entityGroups) {
            smallTextPaint.color = when (type) {
                EntityType.RESOURCE -> Color.parseColor("#00FF00")
                EntityType.MOB -> Color.RED
                EntityType.PLAYER -> Color.GREEN
                EntityType.CHEST -> Color.MAGENTA
                EntityType.BOSS -> Color.parseColor("#FF00FF")
                else -> Color.GRAY
            }
            canvas.drawText("${type.displayName}: ${list.size}", 10f, y, smallTextPaint)
            y += lineHeight
        }
        
        // EventDispatcher stats
        smallTextPaint.color = Color.CYAN
        canvas.drawText(EventDispatcher.getStats(), 10f, y, smallTextPaint)
    }
    
    /**
     * Force refresh the view.
     */
    fun refresh() {
        invalidate()
    }
}
