package com.pobox.magicmagnifier

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The screen-wide half of the shutter confirmation.
 *
 * The ring's own pulse turned out to be invisible in use, for a reason no amount of tuning
 * would fix: it is centred exactly under the fingertip that triggers it. A cue for "the photo
 * was taken" has to be somewhere the finger is not, which in a full-bleed app means the whole
 * screen.
 *
 * The blink is black and then white, both opaque. That pairing is not decoration either. A
 * white flash is nearly invisible over a supermarket price tag, which is most of what this app
 * looks at, and a black one disappears against a dark subject. Two phases guarantee one of them
 * contrasts, whatever is underneath -- the same reasoning that made [ShutterTarget]'s ring
 * two-tone, applied to the entire display.
 */
class CaptureFlash(context: Context) : View(context) {

    private val paint = Paint()
    private var animator: ValueAnimator? = null

    /** Current wash: fully transparent when idle, so the view costs nothing between captures. */
    private var colour = Color.TRANSPARENT

    init {
        isClickable = false
        isFocusable = false
    }

    /**
     * The confirming blink: a shutter closing and opening again.
     *
     * One black-to-white transition is a single flash, which is safe. Anything that repeats
     * quickly is not, which is why [failureBlink] is deliberately slower rather than simply
     * being this played twice.
     */
    fun blink() {
        run(
            listOf(
                Phase(Color.BLACK, BLACK_MS, fade = false),
                Phase(Color.WHITE, WHITE_MS, fade = false),
                Phase(Color.WHITE, CLEAR_MS, fade = true),
            )
        )
    }

    /**
     * Something went wrong and there is no interface to say so in.
     *
     * Two slow black pulses: distinct from the confirming blink both in colour sequence and in
     * pace, and spread widely enough to stay under three flashes per second.
     */
    fun failureBlink() {
        run(
            listOf(
                Phase(Color.BLACK, FAIL_ON_MS, fade = false),
                Phase(Color.TRANSPARENT, FAIL_OFF_MS, fade = false),
                Phase(Color.BLACK, FAIL_ON_MS, fade = false),
                Phase(Color.BLACK, FAIL_FADE_MS, fade = true),
            )
        )
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        colour = Color.TRANSPARENT
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (Color.alpha(colour) == 0) return
        paint.color = colour
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private data class Phase(val colour: Int, val durationMs: Long, val fade: Boolean)

    /** Walks the phases on one animator, so a new blink cleanly replaces one in flight. */
    private fun run(phases: List<Phase>) {
        animator?.cancel()
        val total = phases.sumOf { it.durationMs }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                var elapsed = a.animatedFraction * total
                var applied = Color.TRANSPARENT
                for (phase in phases) {
                    if (elapsed <= phase.durationMs) {
                        applied = if (phase.fade) {
                            val remaining = 1f - (elapsed / phase.durationMs).coerceIn(0f, 1f)
                            withAlpha(phase.colour, remaining)
                        } else {
                            phase.colour
                        }
                        break
                    }
                    elapsed -= phase.durationMs
                }
                colour = applied
                invalidate()
            }
            addListener(
                onEnd = {
                    colour = Color.TRANSPARENT
                    invalidate()
                }
            )
            start()
        }
    }

    private fun withAlpha(colour: Int, fraction: Float): Int = Color.argb(
        (255 * fraction).toInt().coerceIn(0, 255),
        Color.red(colour),
        Color.green(colour),
        Color.blue(colour),
    )

    private fun ValueAnimator.addListener(onEnd: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
        })
    }

    private companion object {
        const val BLACK_MS = 90L
        const val WHITE_MS = 90L
        const val CLEAR_MS = 60L

        const val FAIL_ON_MS = 140L
        const val FAIL_OFF_MS = 260L
        const val FAIL_FADE_MS = 160L
    }
}
