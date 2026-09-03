package com.pobox.magicmagnifier

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app has no interface, so this is the only way to see what it is thinking.
 *
 * Everything goes to logcat under a single tag plus a CSV in the app's external files dir,
 * for fitting [MagnificationCurve.ANCHORS] to a specific device offline. Debug builds only.
 *
 *   adb logcat -s MagMag
 *   adb pull /sdcard/Android/data/com.pobox.magicmagnifier/files/magmag-*.csv
 */
object Telemetry {
    const val TAG = "MagMag"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "magmag-telemetry") }
    private var writer: FileWriter? = null
    private var enabled = false

    fun start(context: Context) {
        if (!BuildConfig.TELEMETRY) return
        enabled = true
        io.execute {
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(context.getExternalFilesDir(null), "magmag-$stamp.csv")
                writer = FileWriter(file, true).apply {
                    write("elapsed_ns,raw_diopters,af_state,nominal_m,confident,target_zoom,applied_zoom,camera_id,torch,luma,iso,exposure_ns,ae_state\n")
                    flush()
                }
                Log.i(TAG, "telemetry csv: ${file.absolutePath}")
            }.onFailure { Log.w(TAG, "telemetry csv unavailable: ${it.message}") }
        }
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        io.execute { runCatching { writer?.flush(); writer?.close() }; writer = null }
    }

    /** Startup dump: the numbers that decide whether the whole approach works on this device. */
    fun logProfile(profile: LensProfile, zoomMin: Float, zoomMax: Float) {
        Log.i(TAG, "=== camera ${profile.cameraId} ===")
        Log.i(TAG, "focus calibration : ${profile.calibration}" +
                if (profile.calibration.isPhysical) " (distances are real metres)"
                else " (distances are NOMINAL -- refit curve anchors from CSV)")
        Log.i(TAG, "min focus distance: ${profile.minFocusDiopters} diopters" +
                if (profile.isFixedFocus) " -> FIXED FOCUS, no distance signal available"
                else " -> closest ${"%.3f".format(profile.minFocusMetres)} m")
        Log.i(TAG, "focal lengths mm  : ${profile.focalLengthsMm}")
        Log.i(TAG, "sensor mm         : ${profile.sensorSizeMm.first} x ${profile.sensorSizeMm.second}")
        Log.i(TAG, "active array      : ${profile.activeArray}")
        Log.i(TAG, "physical cameras  : ${profile.physicalIds}")
        Log.i(TAG, "zoom ratio range  : $zoomMin .. $zoomMax (max digital ${profile.maxDigitalZoom})")
        Log.i(TAG, "flash             : ${profile.hasFlash}")
        Log.i(TAG, "stabilization     : ${profile.supportedStabilization}")
    }

    /**
     * Every camera the device exposes, physical sub-cameras included. Which of them can focus
     * closest is what decides how much magnification is actually reachable, so it is worth
     * dumping once even though the app only ever binds one or two of them.
     */
    fun logAllCameras(profiles: List<LensProfile>) {
        Log.i(TAG, "=== all cameras (id, closest focus, focal length, calibration) ===")
        profiles.sortedBy { it.cameraId }.forEach { p ->
            val closest = if (p.isFixedFocus) "fixed-focus"
                          else "%.1f cm".format(p.minFocusMetres * 100f)
            Log.i(TAG, "  id=%-3s closest=%-12s focal=%-8s %s".format(
                p.cameraId, closest, p.focalLengthsMm.joinToString(","), p.calibration))
        }
    }

    fun logLine(msg: String) {
        if (BuildConfig.TELEMETRY) Log.i(TAG, msg)
    }

    fun sample(
        elapsedNanos: Long,
        rawDiopters: Float,
        afState: Int,
        nominalMetres: Float,
        confident: Boolean,
        targetZoom: Float,
        appliedZoom: Float,
        cameraId: String,
        torch: Boolean,
        luma: Int,
        iso: Int,
        exposureNanos: Long,
        aeState: Int,
    ) {
        if (!enabled) return
        val row = "$elapsedNanos,$rawDiopters,$afState,$nominalMetres,$confident," +
                "$targetZoom,$appliedZoom,$cameraId,$torch,$luma,$iso,$exposureNanos,$aeState\n"
        io.execute { runCatching { writer?.write(row) } }
    }

    fun flush() {
        if (!enabled) return
        io.execute { runCatching { writer?.flush() } }
    }
}
