package com.pobox.magicmagnifier

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where snapshots land.
 *
 * Its own album under Pictures, so magnifier shots stay out of the camera roll but still turn
 * up in the gallery where anyone would look for them.
 *
 * Writing here needs no permission at all on API 29 and up, which is the reason this app
 * requires Android 10. Saving on API 28 would have meant WRITE_EXTERNAL_STORAGE and a second
 * runtime dialog -- and a denial would have broken saving silently, with no interface
 * available to explain why. One system dialog in the app's whole life is worth dropping
 * Android 9 for.
 */
object SnapshotStore {

    private const val ALBUM = "Pictures/Magic Magnifier"

    fun outputOptions(context: Context): ImageCapture.OutputFileOptions {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MagicMagnifier_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
        }
        // CameraX manages IS_PENDING itself for MediaStore targets, so the file is not visible
        // to the gallery until it is completely written.
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
    }
}
