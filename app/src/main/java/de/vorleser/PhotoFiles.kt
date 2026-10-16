package de.vorleser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

object PhotoFiles {
    /** Decode near the needed size before allocating; honor all EXIF rotations and reflections. */
    fun prepare(source: File, destination: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unreadable camera image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        val raw = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Unreadable camera image")
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            val scale = minOf(1f, 2400f / maxOf(rotated.width, rotated.height))
            scaled = if (scale < 1f) Bitmap.createScaledBitmap(rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true) else rotated
            if (!destination.parentFile!!.isDirectory && !destination.parentFile!!.mkdirs()) throw IOException("Cannot create photo directory")
            destination.outputStream().use { stream ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw IOException("Cannot save photo")
            }
        } finally {
            scaled?.takeIf { it !== rotated && it !== raw }?.recycle()
            rotated?.takeIf { it !== raw }?.recycle()
            raw.recycle()
        }
    }
}
