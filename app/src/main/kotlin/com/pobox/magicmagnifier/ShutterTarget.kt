package com.pobox.magicmagnifier

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The only thing in this app that could be called an interface, and it is gone in three seconds.
 *
 * A shutter button that sits on screen is out of the question here, and so is a single tap --
 * at 5x a palm brushing the glass would fire it constantly. So the gesture is two-stage: touch
 * once to summon this ring, touch again inside it to capture. The confirm is anchored to a
 * place, which is what makes an accidental brush harmless: it would have to land twice, in the
 * same spot, within the window.
 *
 * The ring is drawn as two adjacent opaque bands, one white and one black. That is not
 * decoration. This view has no idea what is behind it -- white paper, black text, a bright
 * screen, a dark object -- and a single-colour ring vanishes against its own colour exactly
 * when it is needed most. With two bands touching, at least one always contrasts. Crosshairs
 * and map markers solve it the same way.
 */
class ShutterTarget(context: Context) : View(context) {

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(BAND_DP)
    }

    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = dp(BAND_DP)
    }

    private val innerRadius = dp(RADIUS_DP)

    /** Sits immediately outside the white band, edge to edge, with no gap to leak through. */
    private val outerRadius = innerRadius + dp(BAND_DP)

    private val hitRadius = dp(HIT_RADIUS_DP)

    private var centreX = 0f
    private var centreY = 0f

    /** 0 when gone, 1 at full strength. Both bands share it, so the pair never separates. */
    private var strength = 0f

    /** 0 until the shutter fires, then 1 as the ring pulses outward and disappears. */
    private var flash = 0f

    private var animator: ValueAnimator? = null
    private var flashing = false

    init {
        // Purely decorative and short-lived; it must never eat a touch.
        isClickable = false
        isFocusable = false
    }

    /** Summon the ring at this point, or move it here and restart the countdown. */
    fun arm(x: Float, y: Float) {
        centreX = x
        centreY = y
        flashing = false
        flash = 0f

        animator?.cancel()
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = HOLD_MS + FADE_MS
            // Hold at full strength, then fade. Squeezing both into one animator keeps the
            // two bands locked together for the whole life of the ring.
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                val holdFraction = HOLD_MS.toFloat() / (HOLD_MS + FADE_MS)
                strength = if (t < holdFraction) 1f else 1f - (t - holdFraction) / (1f - holdFraction)
                invalidate()
            }
            start()
        }
        invalidate()
    }

    /** True while the ring is still visible and this point is close enough to count. */
    fun isArmedAt(x: Float, y: Float): Boolean {
        if (flashing || strength <= VISIBLE_FLOOR) return false
        return Math.hypot((x - centreX).toDouble(), (y - centreY).toDouble()) <= hitRadius
    }

    /** One outward pulse to acknowledge the shutter, then gone. */
    fun flashAndDismiss() {
        flashing = true
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLASH_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                flash = it.animatedFraction
                strength = 1f - flash
                invalidate()
            }
            start()
        }
    }

    /** Take it away with no ceremony. */
    fun dismiss() {
        animator?.cancel()
        animator = null
        strength = 0f
        flash = 0f
        flashing = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (strength <= 0f) return

        // The pulse widens the ring as it goes, so the acknowledgement reads as the target
        // opening outward rather than simply blinking out.
        val scale = 1f + flash * FLASH_GROWTH
        val alpha = (strength * 255f).toInt().coerceIn(0, 255)

        whitePaint.alpha = alpha
        blackPaint.alpha = alpha

        canvas.drawCircle(centreX, centreY, innerRadius * scale, whitePaint)
        canvas.drawCircle(centreX, centreY, outerRadius * scale, blackPaint)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )

    private companion object {
        const val RADIUS_DP = 44f
        const val BAND_DP = 3f

        /**
         * Deliberately wider than the ring is drawn, so a touch that lands on the edge still
         * counts. Someone who needs a magnifier may not place a finger precisely.
         */
        const val HIT_RADIUS_DP = 56f

        const val HOLD_MS = 600L
        const val FADE_MS = 2400L
        const val FLASH_MS = 220L
        const val FLASH_GROWTH = 0.35f

        /** Below this the ring is too faint to be seen, so it should not be hittable either. */
        const val VISIBLE_FLOOR = 0.02f
    }
}
