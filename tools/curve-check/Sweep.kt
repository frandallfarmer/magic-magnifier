import com.pobox.magicmagnifier.MagnificationCurve
import com.pobox.magicmagnifier.OneEuroFilter
import com.pobox.magicmagnifier.ZoomController
import kotlin.math.abs
import kotlin.random.Random

// Mirrors DistanceEstimator's upstream median-of-5 so the sweep sees the same signal
// the filter will actually get on device.
class Median5 {
    private val w = FloatArray(5); private var n = 0; private var i = 0
    fun push(x: Float): Float {
        w[i] = x; i = (i + 1) % 5; if (n < 5) n++
        val c = w.copyOf(n); c.sort(); return c[n / 2]
    }
}

/** Zoom writes after the pipeline has settled on a static 20cm subject. Want 0. */
fun restWrites(minCutoff: Float, beta: Float): Int {
    val f = OneEuroFilter(minCutoff, beta); val m = Median5(); val zc = ZoomController()
    val rng = Random(3); var t = 0L; var writes = 0
    repeat(300) { i ->
        t += 33_000_000L
        val d = m.push(0.20f + (rng.nextFloat() - 0.5f) * 0.02f)
        if (zc.next(MagnificationCurve.zoomFor(f.filter(d, t)), t, 1f, 16f) != null && i >= 60) writes++
    }
    return writes
}

/** Fraction of target zoom reached at the instant the hand stops, and settle time after. */
fun approach(minCutoff: Float, beta: Float): Pair<Float, Float> {
    val f = OneEuroFilter(minCutoff, beta); val m = Median5(); val zc = ZoomController(); zc.reset(1f)
    var t = 0L
    val move = 45
    val target = MagnificationCurve.zoomFor(0.10f)
    var atStop = 0f
    var settleFrame = -1
    repeat(move + 90) { i ->
        t += 33_000_000L
        val raw = if (i < move) 0.50f + (0.10f - 0.50f) * (i / (move - 1f)) else 0.10f
        val d = m.push(raw)
        zc.next(MagnificationCurve.zoomFor(f.filter(d, t)), t, 1f, 16f)
        if (i == move - 1) atStop = zc.current
        if (i >= move && settleFrame < 0 && zc.current >= target * 0.95f) settleFrame = i - move
    }
    return (atStop / target) to (if (settleFrame < 0) Float.NaN else settleFrame * 0.033f)
}

fun main() {
    println("minCut  beta   restWrites  %target@stop  settle(s)")
    for (mc in listOf(0.4f, 0.6f, 0.8f, 1.0f)) {
        for (b in listOf(0.35f, 2f, 5f, 10f, 20f, 40f)) {
            val w = restWrites(mc, b)
            val (frac, settle) = approach(mc, b)
            val flag = if (w == 0 && frac >= 0.8f) "  <== ok" else ""
            println("  %.1f  %5.1f   %8d      %6.0f%%      %6s%s"
                .format(mc, b, w, frac * 100f, if (settle.isNaN()) "none" else "%.2f".format(settle), flag))
        }
    }
}
