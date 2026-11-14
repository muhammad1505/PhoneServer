package dev.pocketshare.core.image

/** Re-encoded image ready to be served, together with the format it was written in. */
data class EncodedImage(
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * Platform hook that shrinks shared images before they leave the server.
 *
 * Implementations live next to the platform decoder (Android uses BitmapFactory) and must return
 * null when the source format cannot be decoded, so the caller can fall back to the original bytes.
 */
interface ImageTransformer {
    fun downscale(
        source: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): EncodedImage?
}
