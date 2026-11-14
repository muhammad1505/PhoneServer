package dev.pocketshare.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import dev.pocketshare.core.image.EncodedImage
import dev.pocketshare.core.image.ImageTransformer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Shrinks shared images with BitmapFactory before the server sends them.
 *
 * Photos keep their EXIF rotation, images with transparency stay lossless PNG, and everything else
 * is re-encoded as JPEG. Returning null hands the original bytes back to the caller.
 */
class AndroidImageTransformer : ImageTransformer {
    override fun downscale(
        source: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): EncodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        val rotated = applyExifRotation(source, decoded)
        if (rotated !== decoded) decoded.recycle()
        val scaled = scaleDown(rotated, maxDimension)
        if (scaled !== rotated) rotated.recycle()
        return try {
            encode(scaled, quality)
        } finally {
            scaled.recycle()
        }
    }

    private fun sampleSizeFor(
        width: Int,
        height: Int,
        maxDimension: Int,
    ): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxDimension) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(
        bitmap: Bitmap,
        maxDimension: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun applyExifRotation(
        source: ByteArray,
        bitmap: Bitmap,
    ): Bitmap {
        val orientation =
            try {
                ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            } catch (_: OutOfMemoryError) {
                ExifInterface.ORIENTATION_NORMAL
            }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    private fun encode(
        bitmap: Bitmap,
        quality: Int,
    ): EncodedImage {
        val output = ByteArrayOutputStream()
        return if (bitmap.hasAlpha()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            EncodedImage(output.toByteArray(), "image/png")
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), output)
            EncodedImage(output.toByteArray(), "image/jpeg")
        }
    }
}
