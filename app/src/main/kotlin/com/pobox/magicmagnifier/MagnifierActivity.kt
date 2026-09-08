package com.pobox.magicmagnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The entire interface: a camera preview filling the screen, and nothing else.
 *
 * No buttons, no text, no overlays, no settings. Distance to whatever is in the middle of the
 * frame is the only input the app takes. The single unavoidable exception to "all video" is
 * Android's own camera permission dialog on first launch; it is system UI and cannot be
 * suppressed, and it is never seen again once granted.
 *
 * The one exception to "no controls" is the snapshot gesture, and it is summoned rather than
 * shown: touch anywhere to raise a ring, touch inside the ring to capture. Let it fade and
 * nothing happened. See [ShutterTarget].
 */
class MagnifierActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView

    /** Holds the last good frame over a lens change, so the swap reads as a dissolve. */
    private lateinit var freezeFrame: ImageView

    /** The summoned shutter ring; invisible until touched, gone again three seconds later. */
    private lateinit var shutterTarget: ShutterTarget

    private var shutterSound: MediaActionSound? = null

    /** The screen-wide blink; the ring alone sits under the finger and cannot be seen. */
    private lateinit var captureFlash: CaptureFlash

    /** Logged once, the first time a touch asks, purely so the values are diagnosable. */
    private var loggedGestureInsets = false

    private var engine: CameraEngine? = null
    private var started = false
    private var askedThisResume = false

    /**
     * Who put the held frame on screen. Both the lens-change dissolve and the capture cue use
     * [freezeFrame], and each must only clear its own: the preview-stream observer would
     * otherwise wipe a capture freeze the instant frames resumed, and the capture timer would
     * cut a lens change short.
     */
    private var freezeReason = FreezeReason.NONE

    private enum class FreezeReason { NONE, LENS_SWITCH, CAPTURE }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startEngine()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }

        freezeFrame = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = ImageView.GONE
        }

        shutterTarget = ShutterTarget(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        captureFlash = CaptureFlash(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView)
            addView(freezeFrame)
            // Above the held frame, so the blink washes over what was captured.
            addView(captureFlash)
            // Topmost, so the ring is never hidden behind a lens-change dissolve.
            addView(shutterTarget)
        }
        setContentView(root)

        shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }


        // Drop the held frame the moment real frames are flowing again.
        previewView.previewStreamState.observe(this) { state ->
            // Only a lens change waits on frames resuming. A capture freeze is deliberately
            // holding a still image while the camera keeps streaming underneath it.
            if (freezeReason == FreezeReason.LENS_SWITCH &&
                state == PreviewView.StreamState.STREAMING
            ) {
                freezeReason = FreezeReason.NONE
                fadeOutFreezeFrame()
            }
        }

        Telemetry.start(this)
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
        askedThisResume = false
        ensureCamera()
    }

    override fun onPause() {
        super.onPause()
        // stop() releases the engine's threads, so the instance is spent; onResume builds
        // a fresh one rather than restarting this one.
        engine?.stop()
        engine = null
        started = false
        shutterTarget.dismiss()
        captureFlash.cancel()
        freezeReason = FreezeReason.NONE
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound?.release()
        shutterSound = null
        Telemetry.stop()
    }

    /**
     * The snapshot gesture: touch once to arm, touch inside the ring to fire.
     *
     * Handled here rather than in onTouchEvent so it sees ACTION_DOWN first whatever any child
     * view decides to do with the event, and it never consumes -- the system back and edge
     * gestures have to keep working, since they are the only way out of a fullscreen app with
     * no interface.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Leave the edges alone. A touch that starts there is on its way to being a back,
            // home or recents gesture, and treating it as a shutter arm would put a ring on
            // screen every time you navigated -- worse, two quick back-swipes would land the
            // second inside the first's ring and fire the shutter.
            if (inSystemGestureArea(ev.x, ev.y)) {
                return super.dispatchTouchEvent(ev)
            }
            if (shutterTarget.isArmedAt(ev.x, ev.y)) {
                // Fire on the way down. At 5x the tap itself shakes the frame, so the delay
                // between contact and shutter is blur we can simply decline to add.
                // Ordered cheapest-and-most-urgent first, all on the main thread.
                //
                // takeSnapshot leads because it is shake-critical: the tap is already moving
                // the phone, and every millisecond before the shutter is blur. The blink
                // follows because it starts an animation clock and is nearly free. The
                // bitmap copy behind the freeze is the one expensive call here, so it goes
                // last, once everything the user can perceive is already under way.
                engine?.takeSnapshot(::onSnapshotSaved)
                captureFlash.blink()
                shutterSound?.play(MediaActionSound.SHUTTER_CLICK)
                confirmHaptic()
                shutterTarget.flashAndDismiss()
                Telemetry.logLine("shutter fired")
                freezeCapturedFrame()
            } else {
                // A miss re-arms where the finger landed rather than doing nothing. Making
                // someone start over for an imprecise tap would be a poor trade in an app
                // built for people who cannot see well.
                shutterTarget.arm(ev.x, ev.y)
                shutterTarget.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * True for touches starting in the OS gesture margins.
     *
     * The insets come from the device, so this adapts on its own: a phone on gesture
     * navigation reports a back-swipe strip down each side, one on three-button navigation
     * reports none. The floor covers the case those come back as zero -- with the system bars
     * hidden, an edge swipe still pulls them back into view, and that gesture deserves the
     * same deference.
     */
    private fun inSystemGestureArea(x: Float, y: Float): Boolean {
        val root = window.decorView

        // Asked at the moment of the touch rather than cached from an OnApplyWindowInsets
        // listener. With the system bars hidden that listener is never called on this device,
        // which left the field permanently at zero -- the edges were being protected by the
        // floor alone and would silently have gone unguarded had the floor ever been removed.
        val insets = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemGestures())
            ?: Insets.NONE

        if (!loggedGestureInsets) {
            loggedGestureInsets = true
            Telemetry.logLine("system gesture insets: $insets (floor ${EDGE_FLOOR_DP}dp)")
        }

        val floor = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, EDGE_FLOOR_DP, resources.displayMetrics,
        )
        return x < maxOf(insets.left.toFloat(), floor) ||
            x > root.width - maxOf(insets.right.toFloat(), floor) ||
            y < maxOf(insets.top.toFloat(), floor) ||
            y > root.height - maxOf(insets.bottom.toFloat(), floor)
    }

    /**
     * Hold the captured frame on screen so the image visibly stops dead.
     *
     * This is the strongest part of the cue, and it is nearly free: the bitmap is grabbed in
     * the same touch handler that fires the capture, so what freezes on screen is the frame
     * being written. A whole screen ceasing to move cannot be hidden behind a fingertip, which
     * is exactly how the ring's own pulse failed.
     */
    private fun freezeCapturedFrame() {
        val frame = previewView.bitmap ?: return
        freezeReason = FreezeReason.CAPTURE
        freezeFrame.setImageBitmap(frame)
        freezeFrame.alpha = 1f
        freezeFrame.visibility = ImageView.VISIBLE
        freezeFrame.postDelayed({
            if (freezeReason == FreezeReason.CAPTURE) {
                freezeReason = FreezeReason.NONE
                fadeOutFreezeFrame()
            }
        }, CAPTURE_HOLD_MS)
    }

    private fun onSnapshotSaved(uri: Uri?) {
        if (uri != null) return
        // The click and the blink have already fired, so success and failure would look
        // identical without this. Slower and black-only, so it cannot be misread as the
        // confirming blink.
        Telemetry.logLine("shutter fired but nothing was saved")
        captureFlash.failureBlink()
    }

    private fun confirmHaptic() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        shutterTarget.performHapticFeedback(constant)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun ensureCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        when {
            granted -> startEngine()
            // No explanatory screen: that would be interface. If the user has permanently
            // denied, the system silently declines and the screen simply stays black.
            !askedThisResume -> {
                askedThisResume = true
                requestCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startEngine() {
        if (started) return
        started = true
        engine = CameraEngine(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onLensSwitch = ::coverLensSwitch,
        ).also { it.start() }
    }

    /**
     * Rebinding to another physical camera blanks the preview for a moment. Freeze the last
     * frame on top, do the swap underneath, then dissolve back. A cross-fade between two
     * camera images is still video, not chrome.
     */
    private fun coverLensSwitch(rebind: () -> Unit) {
        val last = previewView.bitmap
        if (last != null) {
            freezeFrame.setImageBitmap(last)
            freezeFrame.alpha = 1f
            freezeFrame.visibility = ImageView.VISIBLE
            freezeReason = FreezeReason.LENS_SWITCH
        }
        rebind()
        // Belt and braces: if the stream state never reports STREAMING (some devices go
        // straight back without a transition) make sure the held frame still clears.
        freezeFrame.postDelayed({
            if (freezeReason == FreezeReason.LENS_SWITCH) {
                freezeReason = FreezeReason.NONE
                fadeOutFreezeFrame()
            }
        }, FREEZE_TIMEOUT_MS)
    }

    private fun fadeOutFreezeFrame() {
        freezeFrame.animate()
            .alpha(0f)
            .setDuration(CROSSFADE_MS)
            .withEndAction {
                freezeFrame.visibility = ImageView.GONE
                freezeFrame.setImageDrawable(null)
            }
            .start()
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        /**
         * Kept back from every edge regardless of what the system reports.
         *
         * This is load-bearing, not insurance. A Galaxy S24 Ultra on gesture navigation
         * reports Insets{left=0, top=128, right=0, bottom=126}: generous margins top and
         * bottom, and *nothing at all* down the sides, even though back-swipe is live on both
         * of them. With the system bars hidden Android simply does not report the side
         * strips, so the reported insets would leave the back gesture completely unguarded.
         *
         * 28dp clears Android's 24dp default back-gesture zone with a little room for the
         * sensitivity setting, and costs about 14% of the screen width for arming -- cheap,
         * since the ring can be summoned anywhere in the remainder.
         */
        const val EDGE_FLOOR_DP = 28f

        /** How long the captured frame is held before live resumes. */
        const val CAPTURE_HOLD_MS = 350L

        const val CROSSFADE_MS = 180L
        const val FREEZE_TIMEOUT_MS = 900L
    }
}
