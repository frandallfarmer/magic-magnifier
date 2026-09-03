package com.pobox.magicmagnifier

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1-Euro filter (Casiez, Roussel & Vogel, CHI 2012).
 *
 * An adaptive low-pass: heavy smoothing when the signal is nearly static, light smoothing
 * when it is moving fast. Focus distance needs precisely this. A fixed low-pass would force
 * a choice between the image breathing while the phone is held still and the zoom lagging
 * behind the hand while it moves; this gives us both ends.
 */
class OneEuroFilter(
    private val minCutoff: Float,
    private val beta: Float,
    private val dCutoff: Float = 1.0f,
) {
    private var xPrev = Float.NaN
    private var dxPrev = 0f
    private var tPrevNanos = 0L

    fun reset() {
        xPrev = Float.NaN
        dxPrev = 0f
        tPrevNanos = 0L
    }

    fun filter(x: Float, tNanos: Long): Float {
        if (xPrev.isNaN()) {
            xPrev = x
            tPrevNanos = tNanos
            dxPrev = 0f
            return x
        }
        val dt = (tNanos - tPrevNanos).coerceAtLeast(1L) / 1_000_000_000f
        tPrevNanos = tNanos

        val dx = (x - xPrev) / dt
        val edx = lowpass(dx, dxPrev, alpha(dCutoff, dt))
        dxPrev = edx

        val filtered = lowpass(x, xPrev, alpha(minCutoff + beta * abs(edx), dt))
        xPrev = filtered
        return filtered
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lowpass(x: Float, prev: Float, a: Float) = a * x + (1f - a) * prev
}
