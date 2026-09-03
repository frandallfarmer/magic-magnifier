package com.pobox.magicmagnifier

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * Which lens to look through, and when to change.
 *
 * The wall this exists for: a phone's main camera stops focusing somewhere around 10cm, but a
 * magnifier wants to go closer than that. Getting closer means a different lens.
 *
 * Modern phones mostly hide this. Their back camera is a *logical* camera whose zoom range
 * dips below 1.0, and asking for 0.6x quietly hands over to the ultra-wide with its much
 * nearer focus. Where that is true there is nothing for us to do and [frameworkHandlesCrossover]
 * is set. Where it is not, we have to find a close-focusing physical camera and rebind to it
 * ourselves, which is visible and needs covering up.
 */
data class LensPlan(
    val primary: CameraSelector,
    val primaryProfile: LensProfile,
    val macro: CameraSelector?,
    val macroProfile: LensProfile?,
    val frameworkHandlesCrossover: Boolean,
) {
    /**
     * Distance at which to hand over to the macro lens: just outside the primary's own
     * close-focus limit, so we move before the image goes soft rather than after.
     */
    val crossoverMetres: Float
        get() = primaryProfile.minFocusMetres * CROSSOVER_MARGIN

    /**
     * A fixed-focus primary reports an infinite close-focus limit, which would put the
     * crossover at infinity and latch us onto the macro lens permanently. There is no distance
     * signal to drive a switch with in that case, so never attempt one.
     */
    val hasMacroCamera: Boolean
        get() = macro != null && !frameworkHandlesCrossover && !primaryProfile.isFixedFocus

    private companion object {
        const val CROSSOVER_MARGIN = 1.25f
    }
}

object LensStrategy {

    /** A macro lens must focus at least this much closer than the main one to be worth a switch. */
    private const val MACRO_ADVANTAGE = 1.6f

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun plan(
        cameraInfos: List<CameraInfo>,
        profileOf: (String) -> LensProfile,
        zoomMinOfPrimary: Float,
    ): LensPlan? {
        val backs = cameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        if (backs.isEmpty()) return null

        // The default back camera is whatever the framework considers primary; on a logical
        // multi-camera device that is the one wired up to do the wide/ultra-wide handoff.
        val primaryInfo = backs.first()
        val primaryId = Camera2CameraInfo.from(primaryInfo).cameraId
        val primaryProfile = profileOf(primaryId)

        // A zoom range reaching below 1.0 is the signal that ultra-wide is already reachable
        // through setZoomRatio, so the crossover costs us nothing and looks seamless.
        val frameworkHandles = zoomMinOfPrimary < 0.99f

        val macroInfo = backs
            .filter { Camera2CameraInfo.from(it).cameraId != primaryId }
            .map { it to profileOf(Camera2CameraInfo.from(it).cameraId) }
            .filter { (_, p) -> !p.isFixedFocus }
            // Higher diopters means closer focus; that is what makes a lens a macro lens.
            .filter { (_, p) -> p.minFocusDiopters > primaryProfile.minFocusDiopters * MACRO_ADVANTAGE }
            .maxByOrNull { (_, p) -> p.minFocusDiopters }

        return LensPlan(
            primary = selectorFor(primaryId),
            primaryProfile = primaryProfile,
            macro = macroInfo?.let { selectorFor(Camera2CameraInfo.from(it.first).cameraId) },
            macroProfile = macroInfo?.second,
            frameworkHandlesCrossover = frameworkHandles,
        )
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun selectorFor(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }
            .build()
}
