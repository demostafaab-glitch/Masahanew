package com.masahanew.dxfsurvey

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One DXF group: a group code and its value, kept exactly as read. */
data class DxfPair(val code: Int, var value: String)

/** 2x3 affine transform: x' = a*x + c*y + e, y' = b*x + d*y + f. */
class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {

    fun apply(x: Double, y: Double): DoubleArray =
        doubleArrayOf(a * x + c * y + e, b * x + d * y + f)

    /** this ∘ inner: applies inner first, then this. */
    fun times(inner: Affine): Affine = Affine(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        e = a * inner.e + c * inner.f + e,
        f = b * inner.e + d * inner.f + f
    )

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** Block insertion: scale, then rotate (radians), then move to (ix, iy). */
        fun insert(ix: Double, iy: Double, sx: Double, sy: Double, rot: Double): Affine {
            val cs = cos(rot)
            val sn = sin(rot)
            return Affine(sx * cs, sx * sn, -sy * sn, sy * cs, ix, iy)
        }
    }
}

/** Drawable pieces of an entity, in world coordinates after transforms. */
sealed class Prim {
    class Poly(val pts: List<DoubleArray>, val closed: Boolean, val center: DoubleArray?) : Prim()
    class Dot(val x: Double, val y: Double) : Prim()
    class Label(val x: Double, val y: Double, val text: String, val height: Double) : Prim()
}

/** One entity: all pairs from its "0 TYPE" line up to the next entity. */
class DxfEntity(val pairs: MutableList<DxfPair>) {

    val type: String
        get() = pairs.firstOrNull()?.value ?: ""

    fun isSupported(): Boolean = type in Geometry.SUPPORTED

    fun copy(): DxfEntity = DxfEntity(pairs.map { it.copy() }.toMutableList())

    fun getStr(code: Int): String? = pairs.firstOrNull { it.code == code }?.value

    fun setStr(code: Int, value: String) {
        pairs.firstOrNull { it.code == code }?.value = value
    }

    fun getNum(code: Int): Double? = getStr(code)?.trim()?.toDoubleOrNull()

    fun setNum(code: Int, value: Double) {
        setStr(code, fmt(value))
    }

    fun flagSet(code: Int, bit: Int): Boolean = ((getNum(code)?.toInt() ?: 0) and bit) != 0

    /** Sets the first point (codes 10 and 20). */
    fun setFirstPoint(x: Double, y: Double) {
        setNum(10, x)
        setNum(20, y)
    }

    /** Moves every X value (codes 10-18) by dx and every Y value (codes 20-28) by dy. */
    fun move(dx: Double, dy: Double) {
        for (p in pairs) {
            val v = p.value.trim().toDoubleOrNull() ?: continue
            when (p.code) {
                in 10..18 -> p.value = fmt(v + dx)
                in 20..28 -> p.value = fmt(v + dy)
            }
        }
    }

    fun point(cx: Int, cy: Int): DoubleArray? {
        val x = getNum(cx) ?: return null
        val y = getNum(cy) ?: return null
        return doubleArrayOf(x, y)
    }

    /** Points of simple entities: LINE has two, everything else with 10/20 has one. */
    fun vertices(): List<DoubleArray> = when (type) {
        "LINE" -> listOfNotNull(point(10, 20), point(11, 21))
        else -> listOfNotNull(point(10, 20))
    }

    /**
     * All (10,20) vertex pairs. For POLYLINE only the VERTEX records are used,
     * so the header's own coordinates are ignored.
     */
    fun xyPairs(): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        val isPoly = type == "POLYLINE"
        var inVertex = !isPoly
        var px: Double? = null
        for (p in pairs) {
            if (p.code == 0) {
                if (isPoly) inVertex = p.value == "VERTEX"
                px = null
                continue
            }
            if (!inVertex) continue
            when (p.code) {
                10 -> px = p.value.trim().toDoubleOrNull()
                20 -> {
                    val y = p.value.trim().toDoubleOrNull()
                    if (px != null && y != null) out.add(doubleArrayOf(px, y))
                    px = null
                }
            }
        }
        return out
    }

    /** Text content. MTEXT has its text in code 3 chunks followed by code 1; formatting is stripped. */
    fun textValue(): String {
        val s = pairs.filter { it.code == 3 || it.code == 1 }.joinToString("") { it.value }
        if (type != "MTEXT") return s
        return s.replace("\\P", "\n")
            .replace(Regex("\\\\[A-Za-z][^;]*;"), "")
            .replace("{", "")
            .replace("}", "")
    }

    companion object {
        fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)
    }
}

/** Converts entities into drawable primitives. Unknown types produce nothing and stay in the file. */
object Geometry {

    val SUPPORTED = setOf(
        "POINT", "LINE", "LWPOLYLINE", "POLYLINE", "CIRCLE", "ARC", "ELLIPSE",
        "SPLINE", "TEXT", "MTEXT", "SOLID", "3DFACE", "INSERT"
    )

    private const val MAX_DEPTH = 8
    private const val ARC_STEPS = 64

    fun primitives(
        e: DxfEntity,
        blocks: Map<String, List<DxfEntity>>,
        xf: Affine = Affine.IDENTITY,
        depth: Int = 0
    ): List<Prim> {
        val out = mutableListOf<Prim>()
        if (depth > MAX_DEPTH) return out
        val k = sqrt(abs(xf.a * xf.d - xf.b * xf.c))

        fun addPoly(local: List<DoubleArray>, closed: Boolean, center: DoubleArray? = null) {
            if (local.size < 2) return
            val world = local.map { xf.apply(it[0], it[1]) }
            val c = center?.let { xf.apply(it[0], it[1]) }
            out.add(Prim.Poly(world, closed, c))
        }

        when (e.type) {
            "POINT" -> {
                val p = e.point(10, 20)
                if (p != null) {
                    val q = xf.apply(p[0], p[1])
                    out.add(Prim.Dot(q[0], q[1]))
                }
            }
            "LINE" -> addPoly(e.vertices(), false)
            "LWPOLYLINE" -> addPoly(e.xyPairs(), e.flagSet(70, 1))
            "POLYLINE" -> addPoly(e.xyPairs(), e.flagSet(70, 1))
            "SPLINE" -> addPoly(e.xyPairs(), false)
            "CIRCLE" -> {
                val c = e.point(10, 20)
                val r = e.getNum(40)
                if (c != null && r != null && r > 0) {
                    addPoly(arcPoints(c[0], c[1], r, 0.0, 2 * PI), false, c)
                }
            }
            "ARC" -> {
                val c = e.point(10, 20)
                val r = e.getNum(40)
                if (c != null && r != null) {
                    val a0 = (e.getNum(50) ?: 0.0) * PI / 180
                    var a1 = (e.getNum(51) ?: 0.0) * PI / 180
                    if (a1 <= a0) a1 += 2 * PI
                    addPoly(arcPoints(c[0], c[1], r, a0, a1), false, c)
                }
            }
            "ELLIPSE" -> {
                val c = e.point(10, 20)
                val m = e.point(11, 21)
                val ratio = e.getNum(40)
                if (c != null && m != null && ratio != null) {
                    val t0 = e.getNum(41) ?: 0.0
                    var t1 = e.getNum(42) ?: (2 * PI)
                    if (t1 <= t0) t1 += 2 * PI
                    val mx = m[0]
                    val my = m[1]
                    val nx = -my * ratio
                    val ny = mx * ratio
                    val n = 96
                    val pts = (0..n).map { i ->
                        val t = t0 + (t1 - t0) * i / n
                        doubleArrayOf(
                            c[0] + mx * cos(t) + nx * sin(t),
                            c[1] + my * cos(t) + ny * sin(t)
                        )
                    }
                    addPoly(pts, false, c)
                }
            }
            "TEXT", "MTEXT" -> {
                val p = e.point(10, 20)
                if (p != null) {
                    val q = xf.apply(p[0], p[1])
                    out.add(Prim.Label(q[0], q[1], e.textValue(), (e.getNum(40) ?: 1.0) * k))
                }
            }
            "SOLID" -> addPoly(corners(e, intArrayOf(0, 1, 3, 2)), true)
            "3DFACE" -> addPoly(corners(e, intArrayOf(0, 1, 2, 3)), true)
            "INSERT" -> {
                val body = blocks[e.getStr(2)?.trim() ?: ""]
                if (body != null) {
                    val ip = e.point(10, 20) ?: doubleArrayOf(0.0, 0.0)
                    val child = Affine.insert(
                        ip[0], ip[1],
                        e.getNum(41) ?: 1.0,
                        e.getNum(42) ?: 1.0,
                        (e.getNum(50) ?: 0.0) * PI / 180
                    )
                    val nxf = xf.times(child)
                    for (b in body) out.addAll(primitives(b, blocks, nxf, depth + 1))
                }
            }
        }
        return out
    }

    private fun corners(e: DxfEntity, order: IntArray): List<DoubleArray> {
        val all = (0..3).map { e.point(10 + it, 20 + it) }
        return order.map { all[it] }.filterNotNull()
    }

    private fun arcPoints(cx: Double, cy: Double, r: Double, a0: Double, a1: Double): List<DoubleArray> {
        val span = a1 - a0
        val n = max(2, (abs(span) / (2 * PI) * ARC_STEPS).toInt() + 1)
        return (0..n).map { i ->
            val a = a0 + span * i / n
            doubleArrayOf(cx + r * cos(a), cy + r * sin(a))
        }
    }
}

/**
 * The whole DXF file split into three parts:
 * head (before the ENTITIES section), entities (the ENTITIES section only), and tail (from ENDSEC on).
 * Only the entities part is ever changed. Everything else is written back unchanged.
 */
class DxfModel private constructor(
    private val head: List<DxfPair>,
    val entities: MutableList<DxfEntity>,
    private val tail: List<DxfPair>,
    val blocks: Map<String, List<DxfEntity>>
) {
    private val undoStack = ArrayDeque<List<DxfEntity>>()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    /** Call before every change so it can be undone. */
    fun snapshot() {
        undoStack.addLast(entities.map { it.copy() })
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val previous = undoStack.removeLast()
        entities.clear()
        entities.addAll(previous)
        return true
    }

    fun serialize(): String {
        val sb = StringBuilder()
        fun emit(list: List<DxfPair>) {
            for (p in list) sb.append(p.code).append('\n').append(p.value).append('\n')
        }
        emit(head)
        for (e in entities) emit(e.pairs)
        emit(tail)
        return sb.toString()
    }

    companion object {
        const val MAX_UNDO = 100

        fun parse(text: String): DxfModel {
            val lines = text.lines()
            val pairs = ArrayList<DxfPair>()
            var i = 0
            while (i < lines.size) {
                val raw = lines[i].trim()
                if (raw.isEmpty()) {
                    i++
                    continue
                }
                val code = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("سطر غير صالح ${i + 1}: $raw")
                if (i + 1 >= lines.size) break
                pairs.add(DxfPair(code, lines[i + 1]))
                i += 2
            }

            val start = sectionStart(pairs, "ENTITIES")
                ?: throw IllegalArgumentException("لا يوجد قسم ENTITIES في الملف")
            var end = start
            while (end < pairs.size && !(pairs[end].code == 0 && pairs[end].value == "ENDSEC")) end++

            return DxfModel(
                head = pairs.subList(0, start).toList(),
                entities = splitEntities(pairs.subList(start, end)),
                tail = pairs.subList(end, pairs.size).toList(),
                blocks = parseBlocks(pairs)
            )
        }

        /** Index of the first pair after "0 SECTION / 2 name", or null. */
        private fun sectionStart(p: List<DxfPair>, name: String): Int? {
            for (i in 0 until p.size - 1) {
                if (p[i].code == 0 && p[i].value == "SECTION" &&
                    p[i + 1].code == 2 && p[i + 1].value == name
                ) return i + 2
            }
            return null
        }

        /**
         * Splits pairs into entities. A POLYLINE is kept together with its VERTEX
         * records and its SEQEND, so writing back reproduces the same pairs.
         */
        private fun splitEntities(list: List<DxfPair>): MutableList<DxfEntity> {
            val result = mutableListOf<DxfEntity>()
            var current: MutableList<DxfPair>? = null
            var inPolyline = false
            for (p in list) {
                val cur = current
                if (p.code == 0 && !inPolyline) {
                    if (cur != null) result.add(DxfEntity(cur))
                    current = mutableListOf(p)
                    inPolyline = p.value == "POLYLINE"
                } else if (p.code == 0 && inPolyline && p.value == "SEQEND") {
                    cur?.add(p)
                    if (cur != null) result.add(DxfEntity(cur))
                    current = null
                    inPolyline = false
                } else {
                    cur?.add(p)
                }
            }
            current?.let { result.add(DxfEntity(it)) }
            return result
        }

        /** Reads block definitions from the BLOCKS section (read-only, used for INSERT drawing). */
        private fun parseBlocks(pairs: List<DxfPair>): Map<String, List<DxfEntity>> {
            val map = HashMap<String, List<DxfEntity>>()
            val s = sectionStart(pairs, "BLOCKS") ?: return map
            var i = s
            while (i < pairs.size && !(pairs[i].code == 0 && pairs[i].value == "ENDSEC")) {
                if (pairs[i].code == 0 && pairs[i].value == "BLOCK") {
                    var name = ""
                    var j = i + 1
                    while (j < pairs.size && pairs[j].code != 0) {
                        if (pairs[j].code == 2 && name.isEmpty()) name = pairs[j].value
                        j++
                    }
                    val body = ArrayList<DxfPair>()
                    while (j < pairs.size && !(pairs[j].code == 0 && pairs[j].value == "ENDBLK")) {
                        body.add(pairs[j])
                        j++
                    }
                    map[name] = splitEntities(body)
                    i = j
                } else {
                    i++
                }
            }
            return map
        }

    }
}
