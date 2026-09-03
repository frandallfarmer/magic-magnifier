package com.pobox.magicmagnifier

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The whole product, really: distance in, zoom ratio out.
 *
 * This is a lookup table rather than a formula because it is a *tuning* surface. Getting the
 * feel right is done by walking around with the phone and moving these numbers, not by
 * finding a better equation.
 *
 * The one thing to keep in mind while tuning: moving closer already magnifies optically, so
 * apparent on-screen size goes as Z(d)/d, not Z(d). Doubling a number here more than doubles
 * what the eye sees. The defaults are deliberately gentler than instinct suggests.
 *
 * Distances are *nominal* metres (see [Calibration]). On an uncalibrated device they are in
 * arbitrary but monotonic units, and these anchors get refitted from the telemetry CSV.
 */
object MagnificationCurve {

    /** distance (nominal metres) to zoom ratio, ordered far to near. */
    val ANCHORS: List<Pair<Float, Float>> = listOf(
        0.50f to 1.0f,   // beyond 50cm: pass-through, behaves as a plain viewfinder
        0.30f to 1.6f,
        0.20f to 2.5f,
        0.12f to 4.0f,
        0.08f to 6.0f,   // the main camera's minimum-focus wall lives around here
        0.04f to 10.0f,  // macro / ultra-wide territory
        0.02f to 15.0f,
    )

    /**
     * Piecewise interpolation in log-log space, which keeps the curve smooth and monotone
     * and makes each anchor a proportional rather than absolute adjustment.
     */
    fun zoomFor(metres: Float): Float {
        val first = ANCHORS.first()
        val last = ANCHORS.last()
        if (metres >= first.first) return first.second
        if (metres <= last.first) return last.second

        for (i in 0 until ANCHORS.size - 1) {
            val (dNear0, z0) = ANCHORS[i]
            val (dNear1, z1) = ANCHORS[i + 1]
            if (metres <= dNear0 && metres >= dNear1) {
                val t = (ln(metres) - ln(dNear0)) / (ln(dNear1) - ln(dNear0))
                return exp(ln(z0) + t * (ln(z1) - ln(z0)))
            }
        }
        return last.second
    }
}

/**
 * Conditions the raw curve output into something the camera can be asked to do 15 times a
 * second without the image visibly breathing or lurching.
 */
class ZoomController(
    private val deadbandEnter: Float = 0.03f,   // 3% -- start moving
    private val deadbandExit: Float = 0.01f,    // 1% -- settle again (hysteresis)
    /**
     * Budgeted against real hand movement rather than guesswork. This limit exists to absorb
     * the step change that arrives when autofocus re-acquires on something at a very different
     * distance, not to slow the user down, and those two pull in opposite directions.
     *
     * Swept on the bench against a 50cm-to-10cm approach over 1.5s: at 3x/s the zoom is only
     * 66% of the way there when the hand stops and takes another 0.30s to catch up; at 6x/s it
     * reaches 79% and settles in 0.10s. Past 6 the gain flattens out. Meanwhile an instant
     * 1x-to-10x demand still takes 1.2s to play out at this setting, so a focus re-lock reads
     * as a ramp rather than a jump.
     */
    private val maxRatioPerSecond: Float = 6.0f,
) {
    private var applied = 1f
    private var moving = false
    private var lastNanos = 0L

    fun reset(startAt: Float = 1f) {
        applied = startAt
        moving = false
        lastNanos = 0L
    }

    /** @return the zoom ratio to apply now, or null if it is not worth disturbing the camera. */
    fun next(target: Float, nowNanos: Long, min: Float, max: Float): Float? {
        val clampedTarget = target.coerceIn(min, max)
        val relative = abs(ln(clampedTarget / applied))

        // Hysteresis: a wide band to start moving, a narrow one to stop. Without the gap the
        // controller sits on the threshold and stutters.
        moving = if (moving) relative > deadbandExit else relative > deadbandEnter
        if (!moving) {
            lastNanos = nowNanos
            return null
        }

        val dt = if (lastNanos == 0L) 0.033f
                 else ((nowNanos - lastNanos).coerceAtLeast(1L) / 1_000_000_000f).coerceAtMost(0.25f)
        lastNanos = nowNanos

        // Rate limit multiplicatively, so the cap means the same thing at 1x as at 12x.
        val maxFactor = maxRatioPerSecond.pow(dt)
        applied = clampedTarget.coerceIn(applied / maxFactor, applied * maxFactor).coerceIn(min, max)
        return applied
    }

    val current: Float get() = applied
}
