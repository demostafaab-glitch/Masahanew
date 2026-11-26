package com.masahanew.dxfsurvey

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Survey computations.
 * Angles are in gon (400 per circle), measured from North clockwise.
 * Distances are in metres.
 */
object Survey {
    data class Pt(val e: Double, val n: Double)
    data class Result(
        val backDirGon: Double,
        val targetDirGon: Double,
        val horizontalGon: Double,
        val distance: Double
    )

    fun normalizeGon(a: Double): Double {
        val r = a % 400.0
        return if (r < 0) r + 400.0 else r
    }

    fun directionGon(from: Pt, to: Pt): Double {
        val dE = to.e - from.e
        val dN = to.n - from.n
        return normalizeGon(atan2(dE, dN) * 200.0 / PI)
    }

    fun horizontalAngleGon(station: Pt, backsight: Pt, target: Pt): Double =
        normalizeGon(directionGon(station, target) - directionGon(station, backsight))

    fun distance(a: Pt, b: Pt): Double = hypot(b.e - a.e, b.n - a.n)

    fun compute(station: Pt, backsight: Pt, target: Pt): Result = Result(
        backDirGon = directionGon(station, backsight),
        targetDirGon = directionGon(station, target),
        horizontalGon = horizontalAngleGon(station, backsight, target),
        distance = distance(station, target)
    )
}
