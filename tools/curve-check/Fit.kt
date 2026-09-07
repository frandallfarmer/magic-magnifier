import com.pobox.magicmagnifier.MagnificationCurve

/**
 * What the fitted curve looks like on real hardware, without needing the hardware.
 *
 * The point of fitting is that one binary suits any phone. This prints the curve each device
 * would get and, more importantly, how much of its zoom range is reachable before the lens
 * stops focusing -- the number the original hardcoded table got badly wrong.
 */
fun main() {
    val devices = listOf(
        Triple("S24 Ultra (measured)",       0.100f, 10.0f),
        Triple("Pixel 7, no tele (est.)",    0.100f,  7.0f),
        Triple("modest phone (est.)",        0.150f,  4.0f),
        Triple("macro-capable (est.)",       0.050f, 10.0f),
        Triple("fixed focus",   Float.POSITIVE_INFINITY, 8.0f),
    )
    for ((name, near, maxZoom) in devices) {
        val c = MagnificationCurve(near, maxZoom)
        println("== %-28s closest %5s  max %.1fx".format(
            name, if (near.isFinite()) "%.0fcm".format(near * 100f) else "none", maxZoom))
        println("   " + c)

        var prev = -1f
        var monotone = true
        var reachable = 0f
        var d = 1.0f
        while (d > 0.01f) {
            val z = c.zoomFor(d)
            if (z < prev - 1e-4f) monotone = false
            if (near.isFinite() && d >= near) reachable = maxOf(reachable, z)
            prev = z
            d *= 0.98f
        }
        if (near.isFinite()) {
            println("   monotone %s | reachable in focus: %.1fx of %.1fx (%.0f%% of the range)"
                .format(if (monotone) "yes" else "NO", reachable, maxZoom, reachable / maxZoom * 100f))
        } else {
            println("   monotone %s | no distance signal, stays at 1x as it should"
                .format(if (monotone) "yes" else "NO"))
        }
        println()
    }
    println("For contrast, the old hardcoded table on the S24 Ultra reached 4.8x of 10x --")
    println("48% of the range, because everything past 10cm was beyond the focus wall.")
}
