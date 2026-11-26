package com.masahanew.dxfsurvey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class DxfView(context: Context) : View(context) {
    var model: DxfModel? = null
        set(value) {
            field = value
            selected = null
            needFit = true
            fitToContent()
            invalidate()
        }

    var selected: DxfEntity? = null
        private set
    var onSelectionChanged: ((DxfEntity?) -> Unit)? = null
    var onModelChanged: (() -> Unit)? = null

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
        color = Color.DKGRAY; textSize = 28f
    }
    private val path = Path()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    fun select(e: DxfEntity?) {
        selected = e
        onSelectionChanged?.invoke(e)
        invalidate()
    }

    fun zoomBy(factor: Float) { zoomAt(factor, width / 2f, height / 2f) }

    private fun zoomAt(factor: Float, fx: Float, fy: Float) {
        val wx = (fx - offsetX) / scale
        val wy = (offsetY - fy) / scale
        scale = (scale * factor).coerceIn(1e-9f, 1e9f)
        offsetX = fx - wx * scale
        offsetY = fy + wy * scale
        invalidate()
    }

    fun fitToContent() {
        if (width == 0 || height == 0) { needFit = true; return }
        val b = model?.bounds()
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
            when (e.type) {
                "POINT" -> e.vertices().firstOrNull()?.let {
                    canvas.drawCircle(sx(it[0]), sy(it[1]), 5f, pointPaint)
                }
                "TEXT" -> e.vertices().firstOrNull()?.let {
                    canvas.drawText(e.getStr(1) ?: "", sx(it[0]), sy(it[1]), textPaint)
                }
                "LINE" -> {
                    val v = e.vertices()
                    if (v.size == 2) canvas.drawLine(sx(v[0][0]), sy(v[0][1]), sx(v[1][0]), sy(v[1][1]), paint)
                }
                "CIRCLE" -> {
                    val c = e.vertices().firstOrNull()
                    val r = e.getNum(40)
                    if (c != null && r != null) canvas.drawCircle(sx(c[0]), sy(c[1]), (r * scale).toFloat(), paint)
                }
                "LWPOLYLINE" -> {
                    val v = e.vertices()
                    if (v.size >= 2) {
                        path.reset()
                        path.moveTo(sx(v[0][0]), sy(v[0][1]))
                        for (k in 1 until v.size) path.lineTo(sx(v[k][0]), sy(v[k][1]))
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            dragEntity = null
            moved = true
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false
                snapshotTaken = false
                val s = selected
                dragEntity = if (s != null && hitDistance(s, event.x, event.y) <= hitRadius) s else null
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                if (!moved && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) moved = true
                if (!moved) return true
                val de = dragEntity
                if (de != null) {
                    if (!snapshotTaken) {
                        model?.snapshot()
                        snapshotTaken = true
                    }
                    de.move(dx / scale.toDouble(), -dy / scale.toDouble())
                    onModelChanged?.invoke()
                } else {
                    offsetX += dx; offsetY += dy
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val m = model
                    select(if (m != null) nearest(m, event.x, event.y) else null)
                }
            }
        }
        return true
    }

    private fun nearest(m: DxfModel, x: Float, y: Float): DxfEntity? {
        var best: DxfEntity? = null
        var bestD = hitRadius
        for (e in m.entities) {
            val d = hitDistance(e, x, y)
            if (d <= bestD) { bestD = d; best = e }
        }
        return best
    }

    private fun hitDistance(e: DxfEntity, x: Float, y: Float): Float {
        val v = e.vertices()
        if (v.isEmpty()) return Float.MAX_VALUE
        return when (e.type) {
            "CIRCLE" -> {
                val r = ((e.getNum(40) ?: 0.0) * scale).toFloat()
                abs(dist(x, y, sx(v[0][0]), sy(v[0][1])) - r)
            }
            "LINE", "LWPOLYLINE" -> {
                if (v.size == 1) return dist(x, y, sx(v[0][0]), sy(v[0][1]))
                var best = Float.MAX_VALUE
                for (k in 0 until v.size - 1) {
                    best = min(best, segDist(x, y, sx(v[k][0]), sy(v[k][1]), sx(v[k + 1][0]), sy(v[k + 1][1])))
                }
                best
            }
            else -> dist(x, y, sx(v[0][0]), sy(v[0][1]))
        }
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
