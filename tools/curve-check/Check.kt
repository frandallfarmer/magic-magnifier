import com.pobox.magicmagnifier.MagnificationCurve
import com.pobox.magicmagnifier.OneEuroFilter
import com.pobox.magicmagnifier.ZoomController
import kotlin.math.abs
import kotlin.random.Random

/** Mirrors DistanceEstimator's upstream median-of-5 so checks see the real signal path. */
class Median5 {
    private val w = FloatArray(5); private var n = 0; private var i = 0
    fun push(x: Float): Float { w[i] = x; i = (i + 1) % 5; if (n < 5) n++
        val c = w.copyOf(n); c.sort(); return c[n / 2] }
}

fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "  PASS  " else "  FAIL  ") + name + (if (detail.isEmpty()) "" else "  $detail"))
        if (!ok) failures++
    }

    println("== curve: distance -> zoom, and resulting apparent size (Z/d) ==")
    val ds = listOf(1.0f,0.6f,0.5f,0.4f,0.3f,0.25f,0.2f,0.15f,0.12f,0.1f,0.08f,0.06f,0.04f,0.03f,0.02f,0.01f)
    var prevZ = -1f; var prevApparent = -1f; var monoZ = true; var monoApparent = true
    var worstRatio = 0f
    for (d in ds) {
        val z = MagnificationCurve.zoomFor(d)
        val apparent = z / d
        println("   d=%6.3f m  zoom=%6.2fx  apparent=%8.1f".format(d, z, apparent))
        if (prevZ >= 0f) {
            if (z < prevZ - 1e-4f) monoZ = false
            if (apparent < prevApparent - 1e-4f) monoApparent = false
            worstRatio = maxOf(worstRatio, apparent / prevApparent)
        }
        prevZ = z; prevApparent = apparent
    }
    check("zoom increases monotonically as distance shrinks", monoZ)
    check("apparent size increases monotonically (never shrinks when moving closer)", monoApparent)
    check("clamped at the far end", MagnificationCurve.zoomFor(5f) == 1.0f)
    check("clamped at the near end", MagnificationCurve.zoomFor(0.005f) == 15.0f)

    println("\n== curve continuity: no jumps between anchors ==")
    var maxStep = 0f
    var d = 0.5f
    while (d > 0.02f) {
        val a = MagnificationCurve.zoomFor(d)
        val b = MagnificationCurve.zoomFor(d * 0.99f)
        maxStep = maxOf(maxStep, abs(b / a))
        d *= 0.99f
    }
    check("1% distance change never moves zoom more than 3%", maxStep < 1.03f, "max=%.4f".format(maxStep))

    println("\n== ZoomController: deadband holds a still hand steady ==")
    val zc = ZoomController()
    zc.reset(4.0f)
    var applied = 0
    var t = 0L
    val rng = Random(7)
    repeat(150) {
        t += 66_000_000L
        // 4.0x target with +-1.5% of jitter: below the 3% band, should never move.
        val jitter = 4.0f * (1f + (rng.nextFloat() - 0.5f) * 0.03f)
        if (zc.next(jitter, t, 1f, 16f) != null) applied++
    }
    check("no zoom writes from sub-deadband jitter", applied == 0, "writes=$applied")

    println("\n== ZoomController: rate limit ramps instead of lurching ==")
    zc.reset(1.0f)
    t = 0L
    var steps = 0
    while (zc.current < 9.9f && steps < 1000) {
        t += 66_000_000L
        zc.next(10f, t, 1f, 16f)
        steps++
    }
    val seconds = steps * 0.066f
    // 6x/sec over a 10x span: ln(10)/ln(6) ~= 1.3s -- fast enough to follow a hand, slow
    // enough that an autofocus step change still arrives as a ramp rather than a jump.
    check("an instant 1x -> 10x request is spread over >1s", seconds in 1.1f..1.7f, "%.2fs".format(seconds))
    check("never overshoots the max", zc.current <= 16f)

    println("\n== ZoomController: respects hardware zoom limits ==")
    zc.reset(1.0f)
    t = 0L
    repeat(400) { t += 66_000_000L; zc.next(50f, t, 1f, 6f) }
    check("clamped to device max zoom", abs(zc.current - 6f) < 1e-3f, "got %.3f".format(zc.current))

    println("\n== OneEuroFilter: steady at rest, responsive in motion ==")
    val f = OneEuroFilter(minCutoff = 0.6f, beta = 10f)
    var tn = 0L
    val noise = Random(11)
    var maxDev = 0f
    repeat(120) {
        tn += 33_000_000L
        val out = f.filter(0.20f + (noise.nextFloat() - 0.5f) * 0.02f, tn)
        if (it > 30) maxDev = maxOf(maxDev, abs(out - 0.20f))
    }
    check("attenuates +-10mm of noise on a still 20cm hold by >2x", maxDev < 0.005f,
          "maxdev=%.1f mm".format(maxDev * 1000f))

    println("\n== end to end: a still hand produces a still image ==")
    // The filter alone does not have to be perfect; the deadband is what guarantees
    // stillness. What matters is that the two together never move the zoom at rest.
    val fe = OneEuroFilter(minCutoff = 0.6f, beta = 10f)
    val zce = ZoomController()
    var te = 0L
    val n2 = Random(3)
    val med = Median5()
    var writesAtRest = 0
    var settleWrites = 0
    repeat(300) { i ->
        te += 33_000_000L
        val held = med.push(0.20f + (n2.nextFloat() - 0.5f) * 0.02f)   // +-1cm of focus jitter
        val smoothed = fe.filter(held, te)
        val z = MagnificationCurve.zoomFor(smoothed)
        if (zce.next(z, te, 1f, 16f) != null) {
            if (i < 60) settleWrites++ else writesAtRest++
        }
    }
    check("no zoom writes once settled on a static subject", writesAtRest == 0,
          "settling=$settleWrites, at rest=$writesAtRest")

    println("\n== end to end: tracks a deliberate approach without trailing ==")
    val fa = OneEuroFilter(minCutoff = 0.6f, beta = 10f)
    val zca = ZoomController()
    zca.reset(1f)
    var ta = 0L
    // 50cm -> 10cm over 1.5s, the pace of someone leaning in to read something.
    val frames = 45
    repeat(frames) { i ->
        ta += 33_000_000L
        val d = 0.50f + (0.10f - 0.50f) * (i / (frames - 1f))
        val smoothed = fa.filter(d, ta)
        zca.next(MagnificationCurve.zoomFor(smoothed), ta, 1f, 16f)
    }
    val wanted = MagnificationCurve.zoomFor(0.10f)
    val reached = zca.current
    // The zoom deliberately trails a little during the move and catches up on arrival;
    // pinning it to the hand frame-for-frame is what makes these apps feel twitchy.
    check("reaches at least 75% of target zoom by the end of the move",
          reached >= wanted * 0.75f, "wanted %.2fx, reached %.2fx".format(wanted, reached))

    val fh = OneEuroFilter(minCutoff = 0.6f, beta = 10f)
    val zch = ZoomController(); zch.reset(1f); val medh = Median5()
    var th = 0L
    var settleFrames = -1
    repeat(frames + 90) { i ->
        th += 33_000_000L
        val d = if (i < frames) 0.50f + (0.10f - 0.50f) * (i / (frames - 1f)) else 0.10f
        zch.next(MagnificationCurve.zoomFor(fh.filter(medh.push(d), th)), th, 1f, 16f)
        if (i >= frames && settleFrames < 0 && zch.current >= wanted * 0.95f) settleFrames = i - frames
    }
    val settleS = settleFrames * 0.033f
    check("settles to 95% of target within 0.25s of the hand stopping",
          settleFrames >= 0 && settleS <= 0.25f, "%.2fs".format(settleS))

    val f2 = OneEuroFilter(minCutoff = 0.6f, beta = 10f)
    var tn2 = 0L
    repeat(30) { tn2 += 33_000_000L; f2.filter(0.40f, tn2) }
    var out = 0f
    repeat(30) { tn2 += 33_000_000L; out = f2.filter(0.10f, tn2) }   // 1s sweep in
    check("tracks a fast 40cm->10cm move within 1s", out < 0.13f, "reached %.3f m".format(out))

    println("\n" + (if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED"))
    if (failures > 0) kotlin.system.exitProcess(1)
}
