package com.pobox.magicmagnifier

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The whole product, really: distance in, zoom ratio out.
 *
 * The shape of the curve is hand-tuned and fixed; its *range* is fitted to whatever phone it
 * finds itself on. That split matters. A hardcoded table matches no actual device: on a Galaxy
 * S24 Ultra the original one spent its top half below 10cm, where the main camera cannot focus
 * and the image is a blur, so magnification stopped at 4.8x while the hardware offered 10x. A
 * Pixel 7 would have had the same problem with different numbers. Fitting the range instead
 * means one binary suits any phone, and each one gets its full usable travel.
 *
 * The one thing to keep in mind when editing [SHAPE]: moving closer already magnifies
 * optically, so apparent on-screen size goes as Z(d)/d, not Z(d). Doubling a number here more
 * than doubles what the eye sees. The defaults are deliberately gentler than instinct suggests.
 *
 * Distances are *nominal* metres (see [Calibration]). On an uncalibrated device they are in
 * arbitrary but monotonic units, and the shape needs refitting from the telemetry CSV.
 *
 * @param nearestFocusMetres the closest the lens can actually focus, from
 *   LENS_INFO_MINIMUM_FOCUS_DISTANCE. Infinite for a fixed-focus camera.
 * @param maxZoomRatio the most the camera will zoom, from its reported zoom range.
 */
class MagnificationCurve(
    nearestFocusMetres: Float,
    maxZoomRatio: Float,
) {
    /** The hand-tuned curve, stretched onto this device's reachable range. */
    val anchors: List<Pair<Float, Float>> = fit(nearestFocusMetres, maxZoomRatio)

    /**
     * Piecewise interpolation in log-log space, which keeps the curve smooth and monotone
     * and makes each anchor a proportional rather than absolute adjustment.
     */
    fun zoomFor(metres: Float): Float {
        val first = anchors.first()
        val last = anchors.last()
        if (metres >= first.first) return first.second
        if (metres <= last.first) return last.second

        for (i in 0 until anchors.size - 1) {
            val (dNear0, z0) = anchors[i]
            val (dNear1, z1) = anchors[i + 1]
            if (metres <= dNear0 && metres >= dNear1) {
                val t = (ln(metres) - ln(dNear0)) / (ln(dNear1) - ln(dNear0))
                return exp(ln(z0) + t * (ln(z1) - ln(z0)))
            }
        }
        return last.second
    }

    override fun toString(): String =
        anchors.joinToString(", ") { "%.0fcm->%.1fx".format(it.first * 100f, it.second) }

    companion object {
        /**
         * The reference shape: distance to zoom, far to near. Only its *proportions* are used,
         * so editing these changes how the magnification ramps, not where it starts or stops.
         */
        val SHAPE: List<Pair<Float, Float>> = listOf(
            0.50f to 1.0f,   // pass-through: beyond this it behaves as a plain viewfinder
            0.30f to 1.6f,
            0.20f to 2.5f,
            0.12f to 4.0f,
            0.08f to 6.0f,
            0.04f to 10.0f,
            0.02f to 15.0f,
        )

        /**
         * Where magnification starts. Fixed rather than derived, because it is a fact about
         * human reach and eyesight, not about the camera: beyond arm's length there is nothing
         * a magnifier can usefully do.
         */
        const val PASS_THROUGH_METRES = 0.50f

        /**
         * Stop a little short of the focus wall. Sitting exactly on it means the top of the
         * curve is only reachable when focus is perfect, which it is not while a hand is
         * moving.
         */
        const val NEAR_MARGIN = 1.10f

        /**
         * Stretch [SHAPE] onto the range this camera can actually reach.
         *
         * The shape is normalised into log space at both ends, so the curvature that was tuned
         * by hand survives the move; only the endpoints change.
         */
        fun fit(nearestFocusMetres: Float, maxZoomRatio: Float): List<Pair<Float, Float>> {
            // A fixed-focus camera reports an infinite closest distance and gives no distance
            // signal at all, so there is nothing to drive a curve with.
            if (!nearestFocusMetres.isFinite() || maxZoomRatio <= 1.01f) {
                return listOf(PASS_THROUGH_METRES to 1.0f, PASS_THROUGH_METRES / 2f to 1.0f)
            }

            val far = PASS_THROUGH_METRES
            // Guard the degenerate case of a lens that cannot focus closer than we pass
            // through at; leave at least an octave of travel to spread the curve over.
            val near = (nearestFocusMetres * NEAR_MARGIN).coerceAtMost(far * 0.5f)

            val refFar = SHAPE.first().first
            val refNear = SHAPE.last().first
            val refMaxZoom = SHAPE.last().second

            val distanceSpan = ln(refNear / refFar)
            val zoomSpan = ln(refMaxZoom)

            return SHAPE.map { (d, z) ->
                val u = ln(d / refFar) / distanceSpan      // 0 at the far end, 1 at the near
                val v = ln(z) / zoomSpan                   // 0 at 1x, 1 at full zoom
                val fittedDistance = far * (near / far).pow(u)
                val fittedZoom = maxZoomRatio.pow(v)
                fittedDistance to fittedZoom
            }
        }
    }
}

/**
 * Conditions the raw curve output into something the camera can be asked to do 15 times a
 * second without the image visibly breathing or lurching.
 */
class ZoomController(
    /**
     * Sized against the fitted curve, not guessed. Fitting the curve to the device made it
     * steeper -- up to 2.0% zoom per 1% of distance, against 1.1% for the old fixed table --
     * so the same hand jitter now asks for a bigger zoom change and the original 3% band let
     * it through: 11 stray zoom writes on a subject that was not moving. Swept at 3/4/5/6/8%,
     * 6% is the first that holds completely still at both 20cm and 12cm. It costs nothing in
     * tracking, because the band governs stillness at rest and not the ramp.
     */
    private val deadbandEnter: Float = 0.06f,
    private val deadbandExit: Float = 0.025f,   // hysteresis: narrower band to settle again
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
