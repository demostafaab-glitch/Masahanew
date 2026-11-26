package com.masahanew.dxfsurvey

import java.util.Locale

data class DxfPair(val code: Int, var value: String)

class DxfEntity(val pairs: MutableList<DxfPair>) {
    val type: String get() = pairs.firstOrNull()?.value ?: ""
    fun isSupported(): Boolean = type in SUPPORTED
    fun copy(): DxfEntity = DxfEntity(pairs.map { it.copy() }.toMutableList())
    fun getStr(code: Int): String? = pairs.firstOrNull { it.code == code }?.value
    fun setStr(code: Int, value: String) { pairs.firstOrNull { it.code == code }?.value = value }
    fun getNum(code: Int): Double? = getStr(code)?.trim()?.toDoubleOrNull()
    fun setNum(code: Int, value: Double) = setStr(code, fmt(value))
    fun setFirstPoint(x: Double, y: Double) { setNum(10, x); setNum(20, y) }

    fun move(dx: Double, dy: Double) {
        for (p in pairs) {
            val v = p.value.trim().toDoubleOrNull() ?: continue
            when (p.code) {
                in 10..18 -> p.value = fmt(v + dx)
                in 20..28 -> p.value = fmt(v + dy)
            }
        }
    }

    fun vertices(): List<DoubleArray> = when (type) {
        "LINE" -> listOfNotNull(point(10, 20), point(11, 21))
        "LWPOLYLINE" -> xyPairs()
        else -> listOfNotNull(point(10, 20))
    }

    private fun point(cx: Int, cy: Int): DoubleArray? {
        val x = getNum(cx) ?: return null
        val y = getNum(cy) ?: return null
        return doubleArrayOf(x, y)
    }

    private fun xyPairs(): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        var px: Double? = null
        for (p in pairs) when (p.code) {
            10 -> px = p.value.trim().toDoubleOrNull()
            20 -> {
                val y = p.value.trim().toDoubleOrNull()
                if (px != null && y != null) out.add(doubleArrayOf(px, y))
            }
        }
        return out
    }

    companion object {
        val SUPPORTED = setOf("POINT", "LINE", "CIRCLE", "TEXT", "LWPOLYLINE")
        fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)
    }
}

class DxfModel private constructor(
    private val head: List<DxfPair>,
    val entities: MutableList<DxfEntity>,
    private val tail: List<DxfPair>
) {
    private val undoStack = ArrayDeque<List<DxfEntity>>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()

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

    fun bounds(): DoubleArray? {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (e in entities) for (v in e.vertices()) {
            minX = minOf(minX, v[0]); maxX = maxOf(maxX, v[0])
            minY = minOf(minY, v[1]); maxY = maxOf(maxY, v[1])
        }
        if (minX > maxX) return null
        return doubleArrayOf(minX, minY, maxX, maxY)
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
                if (raw.isEmpty()) { i++; continue }
                val code = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("سطر غير صالح ${i + 1}: $raw")
                if (i + 1 >= lines.size) break
                pairs.add(DxfPair(code, lines[i + 1]))
                i += 2
            }

            val start = findEntitiesStart(pairs)
                ?: throw IllegalArgumentException("لا يوجد قسم ENTITIES في الملف")
            var end = start
            while (end < pairs.size && !(pairs[end].code == 0 && pairs[end].value == "ENDSEC")) end++

            val entities = mutableListOf<DxfEntity>()
            var current: MutableList<DxfPair>? = null
            for (p in pairs.subList(start, end)) {
                if (p.code == 0) {
                    current?.let { entities.add(DxfEntity(it)) }
                    current = mutableListOf()
                }
                current?.add(p)
            }
            current?.let { entities.add(DxfEntity(it)) }

            return DxfModel(
                head = pairs.subList(0, start).toList(),
                entities = entities,
                tail = pairs.subList(end, pairs.size).toList()
            )
        }

        private fun findEntitiesStart(p: List<DxfPair>): Int? {
            for (i in 0 until p.size - 1) {
                if (p[i].code == 0 && p[i].value == "SECTION" &&
                    p[i + 1].code == 2 && p[i + 1].value == "ENTITIES"
                ) return i + 2
            }
            return null
        }
    }
}
