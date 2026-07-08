package dev.realrudrap.beacon.ui.dashboard.home.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

private const val JPEG_QUALITY = 80
private const val MAX_DIMENSION = 1280

/**
 * Compresses and saves a JPEG to internal storage.
 * - Downscales to [MAX_DIMENSION]px on the longest side if larger
 * - Fixes EXIF rotation so the image is always upright
 * - Saves to filesDir/attendance/
 *
 * @param context Application context
 * @param sourceFile Raw file from CameraX ImageCapture
 * @param fileName Target file name (e.g. "uuid_in.jpg")
 * @return The saved [File], or null if compression failed
 */
fun compressAndSaveAttendancePhoto(
    context: Context,
    sourceFile: File,
    fileName: String
): File? {
    return try {
        // Fix rotation from EXIF before decoding
        val exif = ExifInterface(sourceFile.absolutePath)
        val rotation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix().apply {
            when (rotation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
        }

        // Decode with inSampleSize to avoid OOM on high-res cameras
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(sourceFile.absolutePath, options)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false

        val raw = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?: return null

        // Apply rotation fix
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated != raw) raw.recycle()

        // Downscale if needed
        val scaled = downscaleIfNeeded(rotated)
        if (scaled != rotated) rotated.recycle()

        // Save to internal storage
        val dir = File(context.filesDir, "attendance").apply { mkdirs() }
        val outFile = File(dir, fileName)
        FileOutputStream(outFile).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        scaled.recycle()

        // Clean up the raw CameraX temp file
        sourceFile.delete()

        outFile
    } catch (e: Exception) {
        null
    }
}

private fun calculateInSampleSize(width: Int, height: Int): Int {
    var inSampleSize = 1
    if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
        val halfWidth = width / 2
        val halfHeight = height / 2
        while (halfWidth / inSampleSize >= MAX_DIMENSION &&
            halfHeight / inSampleSize >= MAX_DIMENSION
        ) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return bitmap
    val scale = MAX_DIMENSION.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(
        bitmap,
        (w * scale).toInt(),
        (h * scale).toInt(),
        true
    )
}