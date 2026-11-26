package com.masahanew.dxfsurvey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Which part of an entity a pick may snap to. */
enum class SnapMode { START, MIDDLE, END, POINT }

/** Role of a marked point on the drawing. */
enum class Role(val label: String) { STATION("المحطة"), ZERO("التصفير"), TARGET("الهدف") }

data class Marker(val role: Role, val x: Double, val y: Double)

/**
 * Draws every entity. Pinch to zoom, one finger to pan.
 * Tap an entity to select it, then drag it to move it.
 * In pick mode a tap selects the nearest point of the chosen snap type.
 */
class DxfView(context: Context) : View(context) {

    var model: DxfModel? = null
        set(value) {
            field = value
            selected = null
            refreshAll()
            fitToContent()
            invalidate()
        }

    var selected: DxfEntity? = null
        private set

    var snapMode: SnapMode = SnapMode.END

    var onSelectionChanged: ((DxfEntity?) -> Unit)? = null
    var onModelChanged: (() -> Unit)? = null
    var onPickMiss: (() -> Unit)? = null

    private var pickCallback: ((Double, Double) -> Unit)? = null
    private var hoverSnap: DoubleArray? = null

    // World to screen: sx = x * scale + offsetX, sy = offsetY - y * scale
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var needFit = true

    private var moved = false
    private var snapshotTaken = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragEntity: DxfEntity? = null

    private val touchSlop = 12f
    private val hitRadius = 36f

    /** Primitives of each entity in world coordinates, rebuilt when entities change. */
    private val cache = IdentityHashMap<DxfEntity, List<Prim>>()

    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(229, 57, 53); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE; style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 150, 60); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val path = Path()

    private val markers = mutableListOf<Marker>()
    private val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(229, 57, 53); style = Paint.Style.FILL
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 100, 230); style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 150, 60); style = Paint.Style.FILL
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 30f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    /** Puts (or moves) the marker of a role. Each role has one marker. */
    fun setMarker(role: Role, x: Double, y: Double) {
        markers.removeAll { it.role == role }
        markers.add(Marker(role, x, y))
        invalidate()
    }

    fun clearMarkers() {
        markers.clear()
        invalidate()
    }

    private fun drawMarkers(canvas: Canvas) {
        for (mk in markers) {
            val x = sx(mk.x)
            val y = sy(mk.y)
            when (mk.role) {
                Role.STATION -> {
                    path.reset()
                    path.moveTo(x, y - 14f)
                    path.lineTo(x - 12f, y + 10f)
                    path.lineTo(x + 12f, y + 10f)
                    path.close()
                    canvas.drawPath(path, stationPaint)
                }
                Role.ZERO -> canvas.drawRect(x - 9f, y - 9f, x + 9f, y + 9f, zeroPaint)
                Role.TARGET -> canvas.drawCircle(x, y, 10f, targetPaint)
            }
            canvas.drawText(mk.role.label, x + 16f, y - 12f, markerTextPaint)
        }
    }

    val isPicking: Boolean
        get() = pickCallback != null

    fun select(e: DxfEntity?) {
        selected = e
        onSelectionChanged?.invoke(e)
        invalidate()
    }

    /** Next tap on a snap point calls [callback] with its world coordinates. */
    fun startPick(callback: (Double, Double) -> Unit) {
        pickCallback = callback
        hoverSnap = null
        invalidate()
    }

    fun cancelPick() {
        pickCallback = null
        hoverSnap = null
        invalidate()
    }

    fun zoomBy(factor: Float) {
        zoomAt(factor, width / 2f, height / 2f)
    }

    fun refreshAll() {
        cache.clear()
        val m = model ?: return
        for (e in m.entities) cache[e] = Geometry.primitives(e, m.blocks)
        invalidate()
    }

    fun refreshEntity(e: DxfEntity) {
        val m = model ?: return
        cache[e] = Geometry.primitives(e, m.blocks)
        invalidate()
    }

    private fun zoomAt(factor: Float, fx: Float, fy: Float) {
        val wx = (fx - offsetX) / scale
        val wy = (offsetY - fy) / scale
        scale = (scale * factor).coerceIn(1e-9f, 1e9f)
        offsetX = fx - wx * scale
        offsetY = fy + wy * scale
        invalidate()
    }

    fun fitToContent() {
        if (width == 0 || height == 0) {
            needFit = true
            return
        }
        val b = worldBounds()
        if (b == null) {
            scale = 1f
            offsetX = width / 2f
            offsetY = height / 2f
            needFit = false
            invalidate()
            return
        }
        val spanX = max(b[2] - b[0], 1e-6)
        val spanY = max(b[3] - b[1], 1e-6)
        scale = (min(width / spanX, height / spanY) * 0.9).toFloat()
        val cx = (b[0] + b[2]) / 2
        val cy = (b[1] + b[3]) / 2
        offsetX = width / 2f - (cx * scale).toFloat()
        offsetY = height / 2f + (cy * scale).toFloat()
        needFit = false
        invalidate()
    }

    private fun worldBounds(): DoubleArray? {
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        fun inc(x: Double, y: Double) {
            b[0] = min(b[0], x)
            b[1] = min(b[1], y)
            b[2] = max(b[2], x)
            b[3] = max(b[3], y)
        }
        for (list in cache.values) {
            for (p in list) {
                when (p) {
                    is Prim.Poly -> for (v in p.pts) inc(v[0], v[1])
                    is Prim.Dot -> inc(p.x, p.y)
                    is Prim.Label -> inc(p.x, p.y)
                }
            }
        }
        return if (b[0] > b[2]) null else b
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (needFit) fitToContent()
    }

    private fun sx(x: Double): Float = (x * scale + offsetX).toFloat()
    private fun sy(y: Double): Float = (offsetY - y * scale).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val m = model ?: return
        for (e in m.entities) {
            val paint = if (e === selected) selectPaint else wirePaint
            val prims = cache[e] ?: continue
            for (p in prims) {
                when (p) {
                    is Prim.Poly -> {
                        path.reset()
                        p.pts.forEachIndexed { i, v ->
                            if (i == 0) path.moveTo(sx(v[0]), sy(v[1]))
                            else path.lineTo(sx(v[0]), sy(v[1]))
                        }
                        if (p.closed) path.close()
                        canvas.drawPath(path, paint)
                    }
                    is Prim.Dot -> canvas.drawCircle(sx(p.x), sy(p.y), 5f, pointPaint)
                    is Prim.Label -> {
                        textPaint.textSize = (p.height * scale).toFloat().coerceIn(8f, 300f)
                        canvas.drawText(p.text, sx(p.x), sy(p.y), textPaint)
                    }
                }
            }
        }
        drawMarkers(canvas)
        hoverSnap?.let { h ->
            val x = sx(h[0])
            val y = sy(h[1])
            canvas.drawRect(x - 12f, y - 12f, x + 12f, y + 12f, markerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            dragEntity = null
            moved = true
            hoverSnap = null
            invalidate()
            return true
        }
        val picking = isPicking
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                snapshotTaken = false
                val s = selected
                dragEntity = if (!picking && s != null && hitDistance(s, event.x, event.y) <= hitRadius) s else null
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (!moved && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) {
                    moved = true
                }
                if (!moved) {
                    if (picking) {
                        hoverSnap = snapAt(event.x, event.y)
                        invalidate()
                    }
                    return true
                }
                hoverSnap = null
                val de = dragEntity
                if (de != null) {
                    if (!snapshotTaken) {
                        model?.snapshot()
                        snapshotTaken = true
                    }
                    de.move((dx / scale).toDouble(), (-dy / scale).toDouble())
                    refreshEntity(de)
                    onModelChanged?.invoke()
                } else {
                    offsetX += dx
                    offsetY += dy
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val cb = pickCallback
                    if (cb != null) {
                        val c = snapAt(event.x, event.y)
                        if (c != null) {
                            pickCallback = null
                            hoverSnap = null
                            invalidate()
                            cb(c[0], c[1])
                        } else {
                            onPickMiss?.invoke()
                        }
                    } else {
                        val m = model
                        select(if (m != null) nearest(m, event.x, event.y) else null)
                    }
                }
                hoverSnap = null
                invalidate()
            }
        }
        return true
    }

    private fun nearest(m: DxfModel, x: Float, y: Float): DxfEntity? {
        var best: DxfEntity? = null
        var bestD = hitRadius
        for (e in m.entities) {
            val d = hitDistance(e, x, y)
            if (d <= bestD) {
                bestD = d
                best = e
            }
        }
        return best
    }

    /** Screen distance from a touch point to the nearest piece of the entity. */
    private fun hitDistance(e: DxfEntity, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (p in cache[e] ?: emptyList()) {
            val d = when (p) {
                is Prim.Poly -> polyDist(p, x, y)
                is Prim.Dot -> dist(x, y, sx(p.x), sy(p.y))
                is Prim.Label -> dist(x, y, sx(p.x), sy(p.y))
            }
            if (d < best) best = d
        }
        return best
    }

    private fun polyDist(p: Prim.Poly, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        val n = p.pts.size
        val segs = if (p.closed) n else n - 1
        for (i in 0 until segs) {
            val a = p.pts[i]
            val b = p.pts[(i + 1) % n]
            best = min(best, segDist(x, y, sx(a[0]), sy(a[1]), sx(b[0]), sy(b[1])))
        }
        return best
    }

    /** Nearest snap point of the current [snapMode] within the touch radius, in world coordinates. */
    private fun snapAt(x: Float, y: Float): DoubleArray? {
        var best: DoubleArray? = null
        var bestD = hitRadius
        for (list in cache.values) {
            for (p in list) {
                for (c in candidates(p)) {
                    val d = dist(x, y, sx(c[0]), sy(c[1]))
                    if (d <= bestD) {
                        bestD = d
                        best = c
                    }
                }
            }
        }
        return best
    }

    private fun candidates(p: Prim): List<DoubleArray> = when (p) {
        is Prim.Poly -> when (snapMode) {
            SnapMode.START -> listOf(p.pts.first())
            SnapMode.END -> listOf(p.pts.last())
            SnapMode.MIDDLE -> midpoints(p)
            SnapMode.POINT -> listOfNotNull(p.center)
        }
        is Prim.Dot -> if (snapMode == SnapMode.POINT) listOf(doubleArrayOf(p.x, p.y)) else emptyList()
        is Prim.Label -> if (snapMode == SnapMode.POINT) listOf(doubleArrayOf(p.x, p.y)) else emptyList()
    }

    private fun midpoints(p: Prim.Poly): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        val n = p.pts.size
        val segs = if (p.closed) n else n - 1
        for (i in 0 until segs) {
            val a = p.pts[i]
            val b = p.pts[(i + 1) % n]
            out.add(doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2))
        }
        return out
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float =
        hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return dist(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return dist(px, py, ax + t * dx, ay + t * dy)
    }
}
