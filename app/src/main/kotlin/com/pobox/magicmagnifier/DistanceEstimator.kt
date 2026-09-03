package com.pobox.magicmagnifier

/** What the estimator currently believes, and how much it believes it. */
data class DistanceEstimate(
    val metres: Float,
    val confident: Boolean,
)

/**
 * Turns the jittery per-frame focus signal into something a zoom control can follow.
 *
 * Three stages, in order:
 *  1. Median-of-5, which discards the single-frame outliers Camera2 sprays out.
 *  2. A 1-Euro filter, steady at rest and responsive in motion.
 *  3. Hold-last-good while autofocus is hunting. Mid-sweep the lens travels straight past
 *     the correct position, so those readings are actively misleading -- following them
 *     would swing the zoom wildly every time focus re-acquires.
 *
 * If focus stays lost past [lostFocusTimeoutMs] the estimate decays back out to
 * [passThroughMetres]. A magnifier that cannot focus is no use magnified, and being stuck
 * at 15x on a blurred frame is worse than dropping to 1x.
 */
class DistanceEstimator(
    private val lostFocusTimeoutMs: Long = 1_500L,
    private val passThroughMetres: Float = 10f,
) {
    private val window = FloatArray(WINDOW)
    private var windowCount = 0
    private var windowIndex = 0

    /**
     * beta is in Hz per (metre/second), which is the easy thing to get wrong here. Textbook
     * 1-Euro values around 0.5 assume a signal in pixels or degrees, where velocities run into
     * the hundreds. Distance in metres moves at ~0.3 m/s, so a small beta leaves the cutoff
     * essentially fixed and the filter lags the hand badly. Swept on the bench: 10 puts the
     * settling time after a 50cm-to-10cm approach at 0.10s, against 0.63s at 0.35, without
     * costing any stability at rest.
     */
    private val filter = OneEuroFilter(minCutoff = 0.6f, beta = 10f)

    private var lastGoodMetres = passThroughMetres
    private var lastGoodNanos = 0L
    private var everFocused = false

    fun reset() {
        windowCount = 0
        windowIndex = 0
        filter.reset()
        lastGoodMetres = passThroughMetres
        lastGoodNanos = 0L
        everFocused = false
    }

    fun update(sample: FocusSample): DistanceEstimate {
        if (sample.isScanning || !sample.isFocused) {
            val ageMs = (sample.timestampNanos - lastGoodNanos) / 1_000_000L
            if (everFocused && ageMs < lostFocusTimeoutMs) {
                // Hold. The lens is in flight; its reported distance is meaningless right now.
                return DistanceEstimate(lastGoodMetres, confident = true)
            }
            // Focus has been gone too long. Ease back to pass-through rather than stay zoomed
            // into a blur; the curve's own rate limit keeps this from snapping.
            lastGoodMetres = passThroughMetres
            filter.reset()
            windowCount = 0
            windowIndex = 0
            return DistanceEstimate(passThroughMetres, confident = false)
        }

        window[windowIndex] = sample.nominalMetres
        windowIndex = (windowIndex + 1) % WINDOW
        if (windowCount < WINDOW) windowCount++

        val median = medianOfWindow()
        val smoothed = filter.filter(median, sample.timestampNanos)

        lastGoodMetres = smoothed
        lastGoodNanos = sample.timestampNanos
        everFocused = true
        return DistanceEstimate(smoothed, confident = true)
    }

    private fun medianOfWindow(): Float {
        val copy = window.copyOf(windowCount)
        copy.sort()
        return copy[windowCount / 2]
    }

    private companion object {
        const val WINDOW = 5
    }
}
