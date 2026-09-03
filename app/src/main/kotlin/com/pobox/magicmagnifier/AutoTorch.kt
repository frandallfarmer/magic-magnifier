package com.pobox.magicmagnifier

import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

/**
 * At 10cm the phone shadows the very thing it is trying to magnify, so the light has to come
 * on by itself -- there is no button to press.
 *
 * The trap here, learned the hard way: the torch destroys the signal that turned it on. Decide
 * to switch *off* using brightness and you get an oscillator -- dark scene lights the torch,
 * torch brightens the scene, brightness says turn off, scene goes dark again. So brightness
 * only ever decides to turn the light ON. Distance alone decides when it goes off, and pulling
 * back is something the user is actually doing on purpose.
 *
 * Turn-on also requires the scene to be dark for a sustained stretch rather than one frame, so
 * a hand passing over the page does not set it off.
 */
class AutoTorch(
    /** Mean Y (0-255) below which the scene counts as too dark to read. */
    private val darkThreshold: Int = 45,
    /**
     * Only worth lighting when we are close enough for the torch to reach.
     *
     * Measured rather than guessed. A first attempt used 15cm and the light never once came
     * on: across a session with 555 consecutive genuinely dark frames -- luma 12, ISO pinned
     * at 6938, 41.6ms exposure -- the closest the phone ever got while dark was 15.3cm, three
     * millimetres outside the gate. Dark frames spanned 15.3cm to 75cm with a mean of 32cm,
     * because you naturally hold back a little in the dark and the phone starts shadowing its
     * own subject well before 15cm.
     */
    private val armDistanceMetres: Float = 0.35f,
    /** Pulling back this far turns it off; wider than arming, for hysteresis. */
    private val releaseDistanceMetres: Float = 0.50f,
    /** Consecutive dark frames before firing -- about half a second at preview rate. */
    private val darkFramesToArm: Int = 15,
    /** Once lit, stay lit at least this long so it can never read as a flicker. */
    private val minOnMs: Long = 1_500L,
) {
    private var on = false
    private var litAtMs = 0L
    private var darkStreak = 0
    private var lastLuma = -1

    val isOn: Boolean get() = on
    val luma: Int get() = lastLuma

    /** @return the new torch state if it changed, or null to leave it alone. */
    fun update(
        image: ImageProxy,
        distanceMetres: Float,
        distanceConfident: Boolean,
        nowMs: Long,
    ): Boolean? {
        val mean = centreLuma(image)
        lastLuma = mean

        if (!on) {
            // Count darkness on brightness alone, and check distance only when deciding to
            // fire. Folding distance into the streak means one autofocus hunt resets the
            // counter -- and hunting is exactly what the lens does in the dark, so the light
            // could never arm in the one situation it exists for.
            darkStreak = if (mean < darkThreshold) darkStreak + 1 else 0
            if (darkStreak < darkFramesToArm) return null
            if (!distanceConfident || distanceMetres > armDistanceMetres) return null
            on = true
            litAtMs = nowMs
            darkStreak = 0
            return true
        }

        // Deliberately does not look at `mean`: the torch is what is lighting the scene now,
        // so its brightness says nothing about whether the light is still needed.
        if (distanceConfident && distanceMetres > releaseDistanceMetres &&
            nowMs - litAtMs >= minOnMs
        ) {
            on = false
            litAtMs = nowMs
            return false
        }
        return null
    }

    fun reset() {
        on = false
        litAtMs = 0L
        darkStreak = 0
        lastLuma = -1
    }

    /** Mean of the Y plane over the centre ~30% of the frame, subsampled for cheapness. */
    private fun centreLuma(image: ImageProxy): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val w = image.width
        val h = image.height
        val x0 = (w * 0.35f).roundToInt()
        val x1 = (w * 0.65f).roundToInt()
        val y0 = (h * 0.35f).roundToInt()
        val y1 = (h * 0.65f).roundToInt()

        var sum = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
                x += STEP
            }
            y += STEP
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }

    private companion object {
        /** Subsample stride; the mean does not need every pixel and this runs per frame. */
        const val STEP = 8
    }
}
