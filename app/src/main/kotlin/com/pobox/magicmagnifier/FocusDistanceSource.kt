package com.pobox.magicmagnifier

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraCaptureSession
import android.os.SystemClock

/**
 * How much faith the hardware asks us to put in its focus-distance numbers.
 *
 * Only CALIBRATED and APPROXIMATE report anything resembling diopters. On UNCALIBRATED
 * devices the values are, per the Camera2 docs, in units that "do not correspond to any
 * physical units" -- but they remain monotonic in real distance, which is the only property
 * this app actually needs. We therefore treat 1/value as *nominal* metres everywhere and
 * retune the magnification anchors per device from telemetry.
 */
enum class Calibration {
    CALIBRATED, APPROXIMATE, UNCALIBRATED, UNSUPPORTED;

    /** True when nominal metres can be read as real metres. */
    val isPhysical: Boolean get() = this == CALIBRATED || this == APPROXIMATE
}

/** Static facts about one camera, read once at startup. */
data class LensProfile(
    val cameraId: String,
    val calibration: Calibration,
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE, in diopters. 0 means a fixed-focus lens. */
    val minFocusDiopters: Float,
    val focalLengthsMm: List<Float>,
    val sensorSizeMm: Pair<Float, Float>,
    val activeArray: Rect,
    val physicalIds: Set<String>,
    val hasFlash: Boolean,
    val maxDigitalZoom: Float,
    val supportedStabilization: List<Int>,
) {
    val isFixedFocus: Boolean get() = minFocusDiopters <= 0f

    /** Closest focusable distance in nominal metres; infinite for a fixed-focus lens. */
    val minFocusMetres: Float
        get() = if (isFixedFocus) Float.POSITIVE_INFINITY else 1f / minFocusDiopters

    companion object {
        fun read(manager: CameraManager, cameraId: String): LensProfile {
            val c = manager.getCameraCharacteristics(cameraId)

            val calibration = when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> Calibration.CALIBRATED
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> Calibration.APPROXIMATE
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> Calibration.UNCALIBRATED
                else -> Calibration.UNSUPPORTED
            }

            val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

            return LensProfile(
                cameraId = cameraId,
                calibration = calibration,
                minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
                    ?: emptyList(),
                sensorSizeMm = (physical?.width ?: 0f) to (physical?.height ?: 0f),
                activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(),
                physicalIds = runCatching { c.physicalCameraIds }.getOrDefault(emptySet()),
                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                supportedStabilization =
                    c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList()
                        ?: emptyList(),
            )
        }

        /** Ignoring the sensor's own aspect, a centred rect covering [fraction] of each axis. */
        fun centreRegion(activeArray: Rect, fraction: Float): Rect {
            val halfW = (activeArray.width() * fraction / 2f).toInt().coerceAtLeast(1)
            val halfH = (activeArray.height() * fraction / 2f).toInt().coerceAtLeast(1)
            val cx = activeArray.centerX()
            val cy = activeArray.centerY()
            return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
    }
}

/** One frame's worth of focus telemetry. */
data class FocusSample(
    val nominalMetres: Float,
    val rawDiopters: Float,
    val afState: Int,
    val timestampNanos: Long,
    /** What auto-exposure had to do to make the frame look normal. */
    val iso: Int,
    val exposureNanos: Long,
    val aeState: Int,
) {
    /** The lens has settled; the distance reading means something. */
    val isFocused: Boolean
        get() = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

    /** The lens is mid-sweep; readings will fly past the true value and must be ignored. */
    val isScanning: Boolean
        get() = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
}

/**
 * Pulls LENS_FOCUS_DISTANCE off the repeating capture results.
 *
 * Camera2 reports focus distance in diopters (1/metres), where 0 is infinity, so the
 * conversion to distance is a reciprocal. Values arrive at preview frame rate.
 */
class FocusDistanceSource(
    private val onSample: (FocusSample) -> Unit,
) {
    /** Distance treated as "no magnification wanted" when the lens reports infinity. */
    private val farClampMetres = 10f

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                ?: CaptureResult.CONTROL_AF_STATE_INACTIVE

            val metres = if (diopters <= 0f) farClampMetres else (1f / diopters)
            onSample(
                FocusSample(
                    nominalMetres = metres.coerceAtMost(farClampMetres),
                    rawDiopters = diopters,
                    afState = afState,
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1,
                )
            )
        }
    }

}
