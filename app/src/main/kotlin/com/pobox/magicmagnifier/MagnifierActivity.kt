package com.pobox.magicmagnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
 */
class MagnifierActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView

    /** Holds the last good frame over a lens change, so the swap reads as a dissolve. */
    private lateinit var freezeFrame: ImageView

    private var engine: CameraEngine? = null
    private var started = false
    private var askedThisResume = false
    private var pendingSwitchFade = false

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

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView)
            addView(freezeFrame)
        }
        setContentView(root)

        // Drop the held frame the moment real frames are flowing again.
        previewView.previewStreamState.observe(this) { state ->
            if (pendingSwitchFade && state == PreviewView.StreamState.STREAMING) {
                pendingSwitchFade = false
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
    }

    override fun onDestroy() {
        super.onDestroy()
        Telemetry.stop()
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
            pendingSwitchFade = true
        }
        rebind()
        // Belt and braces: if the stream state never reports STREAMING (some devices go
        // straight back without a transition) make sure the held frame still clears.
        freezeFrame.postDelayed({
            if (pendingSwitchFade) {
                pendingSwitchFade = false
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
        const val CROSSFADE_MS = 180L
        const val FREEZE_TIMEOUT_MS = 900L
    }
}
