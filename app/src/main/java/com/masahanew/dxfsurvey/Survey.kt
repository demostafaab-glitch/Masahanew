package com.masahanew.dxfsurvey

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Survey computations.
 * Angles are in gon (400 per circle), measured from North clockwise.
 * Distances are in metres. Coordinates: E = X (east), N = Y (north).
 */
object Survey {

    data class Pt(val e: Double, val n: Double)

    data class Result(
        val backDirGon: Double,
        val targetDirGon: Double,
        val horizontalGon: Double,
        val distance: Double
    )

    /** Normalises an angle into [0, 400). */
    fun normalizeGon(a: Double): Double {
        val r = a % 400.0
        return if (r < 0) r + 400.0 else r
    }

    /** Direction from one point to another: atan2(dE, dN), in gon. */
    fun directionGon(from: Pt, to: Pt): Double {
        val dE = to.e - from.e
        val dN = to.n - from.n
        return normalizeGon(atan2(dE, dN) * 200.0 / PI)
    }

    /** Horizontal angle = direction to target minus direction to the zero (backsight), clockwise. */
    fun horizontalAngleGon(station: Pt, zero: Pt, target: Pt): Double =
        normalizeGon(directionGon(station, target) - directionGon(station, zero))

    fun distance(a: Pt, b: Pt): Double = hypot(b.e - a.e, b.n - a.n)

    /** Stake-out: angle from the zero clockwise to the target, and distance to the target. */
    fun compute(station: Pt, zero: Pt, target: Pt): Result = Result(
        backDirGon = directionGon(station, zero),
        targetDirGon = directionGon(station, target),
        horizontalGon = horizontalAngleGon(station, zero, target),
        distance = distance(station, target)
    )

    /** Detail survey: from station, zero, a measured clockwise angle (gon) and distance, get the point. */
    fun pointFromAngleDistance(station: Pt, zero: Pt, angleGon: Double, dist: Double): Pt {
        val dir = normalizeGon(directionGon(station, zero) + angleGon)
        val rad = dir * PI / 200.0
        return Pt(station.e + dist * sin(rad), station.n + dist * cos(rad))
    }
}
