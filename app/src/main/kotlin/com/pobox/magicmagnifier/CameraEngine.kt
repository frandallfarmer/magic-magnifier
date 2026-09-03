package com.pobox.magicmagnifier

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires the focus signal to the zoom control, and nothing else.
 *
 * The loop is deliberately split in two. Focus samples arrive at preview frame rate and are
 * filtered as they land, because the median and the 1-Euro filter want every sample. The zoom
 * is only *applied* at [ZOOM_HZ], because setZoomRatio is not free and the eye cannot see
 * faster corrections anyway.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onLensSwitch: (onComplete: () -> Unit) -> Unit,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "magmag-analysis")
    }
    private var ticker: ScheduledExecutorService? = null

    private val estimator = DistanceEstimator()
    private val zoomController = ZoomController()
    private val autoTorch = AutoTorch()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var plan: LensPlan? = null

    @Volatile private var latestEstimate = DistanceEstimate(10f, confident = false)
    @Volatile private var latestSample: FocusSample? = null
    @Volatile private var onMacroLens = false
    private val switching = AtomicBoolean(false)

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                buildPlanAndBind(p)
            }.onFailure { Log.e(Telemetry.TAG, "camera start failed", it) }
        }, mainExecutor)
    }

    /** Terminal: the activity builds a fresh engine on resume, so release the threads. */
    fun stop() {
        ticker?.shutdownNow()
        ticker = null
        analysisExecutor.shutdownNow()
        provider?.unbindAll()
        camera = null
        estimator.reset()
        zoomController.reset()
        autoTorch.reset()
        Telemetry.flush()
    }

    // --- setup -------------------------------------------------------------------------

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun buildPlanAndBind(p: ProcessCameraProvider) {
        // Bind the default back camera once purely to read its zoom range, which is what tells
        // us whether the ultra-wide handoff is already handled for us.
        val infos = p.availableCameraInfos
        val defaultBack = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        if (defaultBack == null) {
            Log.e(Telemetry.TAG, "no back-facing camera")
            return
        }
        val zoomState = defaultBack.zoomState.value
        val zoomMin = zoomState?.minZoomRatio ?: 1f

        val built = LensStrategy.plan(
            cameraInfos = infos,
            profileOf = { id -> LensProfile.read(cameraManager, id) },
            zoomMinOfPrimary = zoomMin,
        )
        if (built == null) {
            Log.e(Telemetry.TAG, "no usable lens plan")
            return
        }
        plan = built

        Telemetry.logProfile(built.primaryProfile, zoomMin, zoomState?.maxZoomRatio ?: 1f)

        // Includes the physical sub-cameras hiding behind the logical one; their close-focus
        // limits are what set the real ceiling on magnification.
        val allIds = (cameraManager.cameraIdList.toList() + built.primaryProfile.physicalIds).distinct()
        Telemetry.logAllCameras(allIds.mapNotNull { id ->
            runCatching { LensProfile.read(cameraManager, id) }.getOrNull()
        })
        Telemetry.logLine(
            "lens plan: frameworkHandlesCrossover=${built.frameworkHandlesCrossover} " +
                "macro=${built.macroProfile?.cameraId ?: "none"} " +
                "crossover=${"%.3f".format(built.crossoverMetres)}m"
        )
        if (built.primaryProfile.isFixedFocus) {
            Log.w(
                Telemetry.TAG,
                "primary camera is fixed focus -- there is no distance signal on this device, " +
                    "magnification will stay at 1x"
            )
        }
        if (!built.primaryProfile.calibration.isPhysical) {
            Log.w(
                Telemetry.TAG,
                "focus distance is ${built.primaryProfile.calibration}: curve anchors are in " +
                    "nominal units and must be refitted from the telemetry CSV"
            )
        }

        bind(built.primary, built.primaryProfile)
        startTicker()
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bind(selector: CameraSelector, profile: LensProfile) {
        val p = provider ?: return
        val source = FocusDistanceSource { sample ->
            latestSample = sample
            latestEstimate = estimator.update(sample)
        }

        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).apply {
            // Continuous AF is what generates the distance signal in the first place; we never
            // ask for a one-shot lock because we want it tracking constantly.
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )

            // Pin metering to the middle of the frame. The product is "magnify whatever is in
            // the centre", so the distance we care about is the centre object's, not the
            // scene's average.
            if (!profile.activeArray.isEmpty) {
                val region = LensProfile.centreRegion(profile.activeArray, CENTRE_FRACTION)
                val rects = arrayOf(MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX))
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, rects)
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, rects)
            }

            stabilizationMode(profile)?.let {
                setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
            }

            setSessionCaptureCallback(source.captureCallback)
        }
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyse) }

        p.unbindAll()
        camera = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)

        val z = camera?.cameraInfo?.zoomState?.value
        zoomController.reset(startAt = z?.zoomRatio ?: 1f)
        Telemetry.logLine(
            "bound ${profile.cameraId} zoom=${z?.minZoomRatio}..${z?.maxZoomRatio} " +
                "calibration=${profile.calibration}"
        )
    }

    private fun stabilizationMode(profile: LensProfile): Int? {
        // Preview stabilization is worth a lot above about 6x, where hand shake is magnified
        // right along with the subject. It costs a small crop, which is an easy trade here.
        val preview = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        } else {
            null
        }
        return when {
            preview != null && profile.supportedStabilization.contains(preview) -> preview
            profile.supportedStabilization.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) ->
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> null
        }
    }

    // --- running loop ------------------------------------------------------------------

    private fun analyse(image: androidx.camera.core.ImageProxy) {
        try {
            val estimate = latestEstimate
            val decision = autoTorch.update(
                image = image,
                distanceMetres = estimate.metres,
                distanceConfident = estimate.confident,
                nowMs = SystemClock.elapsedRealtime(),
            )
            if (decision != null) {
                mainExecutor.execute {
                    camera?.cameraControl?.enableTorch(decision)
                    Telemetry.logLine("torch=$decision luma=${autoTorch.luma}")
                }
            }
        } finally {
            image.close()
        }
    }

    private fun startTicker() {
        ticker?.shutdownNow()
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "magmag-zoom") }
            .also {
                val periodMs = 1_000L / ZOOM_HZ
                it.scheduleAtFixedRate(::tick, periodMs, periodMs, TimeUnit.MILLISECONDS)
            }
    }

    private fun tick() {
        val cam = camera ?: return
        val currentPlan = plan ?: return
        if (switching.get()) return

        val estimate = latestEstimate
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val now = SystemClock.elapsedRealtimeNanos()

        if (maybeSwitchLens(currentPlan, estimate.metres)) return

        val target = MagnificationCurve.zoomFor(estimate.metres)
        val applied = zoomController.next(
            target = target,
            nowNanos = now,
            min = zoomState.minZoomRatio,
            max = zoomState.maxZoomRatio,
        )
        if (applied != null) {
            cam.cameraControl.setZoomRatio(applied)
        }

        latestSample?.let { s ->
            Telemetry.sample(
                elapsedNanos = now,
                rawDiopters = s.rawDiopters,
                afState = s.afState,
                nominalMetres = estimate.metres,
                confident = estimate.confident,
                targetZoom = target,
                appliedZoom = applied ?: zoomController.current,
                cameraId = if (onMacroLens) currentPlan.macroProfile?.cameraId.orEmpty()
                           else currentPlan.primaryProfile.cameraId,
                torch = autoTorch.isOn,
                luma = autoTorch.luma,
                iso = s.iso,
                exposureNanos = s.exposureNanos,
                aeState = s.aeState,
            )
        }
    }

    /**
     * Hand over to the macro lens when we get closer than the main one can focus.
     *
     * Only reached on devices where the framework does not already do this through the zoom
     * ratio. The rebind takes the preview down for a beat, so the caller paints the last frame
     * over the top and dissolves it out -- video covering video, no interface.
     */
    private fun maybeSwitchLens(currentPlan: LensPlan, metres: Float): Boolean {
        if (!currentPlan.hasMacroCamera) return false
        val macroSelector = currentPlan.macro ?: return false
        val macroProfile = currentPlan.macroProfile ?: return false

        val enter = currentPlan.crossoverMetres
        val exit = enter * SWITCH_HYSTERESIS

        val wantMacro = when {
            !onMacroLens && metres < enter -> true
            onMacroLens && metres > exit -> false
            else -> return false
        }
        if (!switching.compareAndSet(false, true)) return true

        val (selector, profile) = if (wantMacro) macroSelector to macroProfile
                                  else currentPlan.primary to currentPlan.primaryProfile
        Telemetry.logLine("lens switch -> ${profile.cameraId} at ${"%.3f".format(metres)}m")

        mainExecutor.execute {
            onLensSwitch {
                runCatching { bind(selector, profile) }
                    .onFailure { Log.e(Telemetry.TAG, "lens switch failed", it) }
                onMacroLens = wantMacro
                estimator.reset()
                switching.set(false)
            }
        }
        return true
    }

    private companion object {
        const val ZOOM_HZ = 15L
        const val CENTRE_FRACTION = 0.15f
        const val SWITCH_HYSTERESIS = 1.35f
    }
}
