package de.vorleser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source(width: Int, height: Int, orientation: Int): java.io.File {
        val file = temporary.newFile()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    @Test fun exifRotationIsAppliedBeforePreviewAndUpload() {
        val image = source(800, 1200, ExifInterface.ORIENTATION_ROTATE_90)
        val prepared = temporary.newFile()
        PhotoFiles.prepare(image, prepared)
        val bitmap = BitmapFactory.decodeFile(prepared.path)
        assertEquals(1200, bitmap.width)
        assertEquals(800, bitmap.height)
        bitmap.recycle()
    }

    @Test fun largePhotoIsBoundedWithoutChangingAspectRatio() {
        val image = source(5000, 3000, ExifInterface.ORIENTATION_NORMAL)
        val prepared = temporary.newFile()
        PhotoFiles.prepare(image, prepared)
        val bitmap = BitmapFactory.decodeFile(prepared.path)
        assertEquals(2400, bitmap.width)
        assertEquals(1440, bitmap.height)
        bitmap.recycle()
    }

    @Test fun brokenCameraImageIsReported() {
        val image = temporary.newFile().apply { writeText("broken jpeg") }
        assertThrows(java.io.IOException::class.java) { PhotoFiles.prepare(image, temporary.newFile()) }
    }
}
