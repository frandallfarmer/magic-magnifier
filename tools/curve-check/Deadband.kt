import com.pobox.magicmagnifier.MagnificationCurve
import com.pobox.magicmagnifier.OneEuroFilter
import com.pobox.magicmagnifier.ZoomController
import kotlin.random.Random

/**
 * The deadband was tuned against the old fixed curve. Fitting the curve to the device made it
 * steeper -- the same hand jitter now asks for a bigger zoom change -- so the band that kept a
 * still image still has to be re-sized. Swept here rather than guessed.
 */
class Med5 {
    private val w = FloatArray(5); private var n = 0; private var i = 0
    fun push(x: Float): Float { w[i] = x; i = (i+1)%5; if (n<5) n++
        val c = w.copyOf(n); c.sort(); return c[n/2] }
}

val CURVE = MagnificationCurve(nearestFocusMetres = 0.10f, maxZoomRatio = 10f)

/** Zoom writes after settling on a static subject, worst case over several seeds. */
fun restWrites(enter: Float, exit: Float, distance: Float): Int {
    var worst = 0
    for (seed in 1..6) {
        val f = OneEuroFilter(0.6f, 10f); val m = Med5()
        val zc = ZoomController(deadbandEnter = enter, deadbandExit = exit)
        val rng = Random(seed); var t = 0L; var w = 0
        repeat(400) { i ->
            t += 33_000_000L
            val d = m.push(distance * (1f + (rng.nextFloat()-0.5f)*0.10f))
            if (zc.next(CURVE.zoomFor(f.filter(d,t)), t, 1f, 10f) != null && i >= 100) w++
        }
        worst = maxOf(worst, w)
    }
    return worst
}

/** Fraction of target reached when the hand stops, and settle time after. */
fun approach(enter: Float, exit: Float): Pair<Float, Float> {
    val f = OneEuroFilter(0.6f, 10f); val m = Med5()
    val zc = ZoomController(deadbandEnter = enter, deadbandExit = exit); zc.reset(1f)
    var t = 0L; val move = 45
    val target = CURVE.zoomFor(0.10f)
    var atStop = 0f; var settle = -1
    repeat(move + 90) { i ->
        t += 33_000_000L
        val raw = if (i < move) 0.50f + (0.10f - 0.50f) * (i/(move-1f)) else 0.10f
        zc.next(CURVE.zoomFor(f.filter(m.push(raw), t)), t, 1f, 10f)
        if (i == move-1) atStop = zc.current
        if (i >= move && settle < 0 && zc.current >= target*0.95f) settle = i - move
    }
    return (atStop/target) to (if (settle<0) Float.NaN else settle*0.033f)
}

fun main() {
    println("deadband   rest writes @20cm  @6cm   %target@stop  settle(s)")
    for ((enter, exit) in listOf(0.03f to 0.01f, 0.04f to 0.015f, 0.05f to 0.02f,
                                 0.06f to 0.025f, 0.08f to 0.03f)) {
        val r20 = restWrites(enter, exit, 0.20f)
        val r6  = restWrites(enter, exit, 0.12f)
        val (frac, settle) = approach(enter, exit)
        val flag = if (r20 == 0 && r6 == 0 && frac >= 0.75f) "  <== ok" else ""
        println("  %.0f%%/%.1f%%        %4d          %4d       %5.0f%%      %6s%s".format(
            enter*100, exit*100, r20, r6, frac*100f,
            if (settle.isNaN()) "none" else "%.2f".format(settle), flag))
    }
}
