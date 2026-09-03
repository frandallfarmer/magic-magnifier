import com.pobox.magicmagnifier.MagnificationCurve
import com.pobox.magicmagnifier.OneEuroFilter
import com.pobox.magicmagnifier.ZoomController
import kotlin.random.Random

class Med5 {
    private val w = FloatArray(5); private var n = 0; private var i = 0
    fun push(x: Float): Float { w[i] = x; i = (i+1)%5; if (n<5) n++
        val c = w.copyOf(n); c.sort(); return c[n/2] }
}

fun approachFrac(rate: Float): Pair<Float, Float> {
    val f = OneEuroFilter(0.6f, 10f); val m = Med5()
    val zc = ZoomController(maxRatioPerSecond = rate); zc.reset(1f)
    var t = 0L; val move = 45; val target = MagnificationCurve.zoomFor(0.10f)
    var atStop = 0f; var settle = -1
    repeat(move + 90) { i ->
        t += 33_000_000L
        val raw = if (i < move) 0.50f + (0.10f - 0.50f) * (i/(move-1f)) else 0.10f
        zc.next(MagnificationCurve.zoomFor(f.filter(m.push(raw), t)), t, 1f, 16f)
        if (i == move-1) atStop = zc.current
        if (i >= move && settle < 0 && zc.current >= target*0.95f) settle = i - move
    }
    return (atStop/target) to (if (settle<0) Float.NaN else settle*0.033f)
}

/** Autofocus re-locks onto something much nearer: an instant 1x -> 10x demand. */
fun stepRamp(rate: Float): Float {
    val zc = ZoomController(maxRatioPerSecond = rate); zc.reset(1f)
    var t = 0L
    repeat(300) { i ->
        t += 33_000_000L
        zc.next(10f, t, 1f, 16f)
        if (zc.current >= 9f) return i * 0.033f
    }
    return Float.NaN
}

fun restWrites(rate: Float): Int {
    val f = OneEuroFilter(0.6f, 10f); val m = Med5()
    val zc = ZoomController(maxRatioPerSecond = rate); val rng = Random(3)
    var t = 0L; var w = 0
    repeat(300) { i -> t += 33_000_000L
        val d = m.push(0.20f + (rng.nextFloat()-0.5f)*0.02f)
        if (zc.next(MagnificationCurve.zoomFor(f.filter(d,t)), t, 1f, 16f) != null && i>=60) w++ }
    return w
}

fun main() {
    println("(minCutoff=0.6, beta=10 fixed)")
    println("rate/s   %target@stop  settle(s)  AF-step 1x->9x  restWrites")
    for (r in listOf(2f, 3f, 4f, 5f, 6f, 8f, 12f)) {
        val (frac, settle) = approachFrac(r)
        println("  %4.1f      %5.0f%%      %6s       %6ss        %d"
            .format(r, frac*100f, "%.2f".format(settle), "%.2f".format(stepRamp(r)), restWrites(r)))
    }
}
