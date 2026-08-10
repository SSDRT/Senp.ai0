package ai.senp.codex

import ai.senp.review.ReviewFrame
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Turns decoded frames into review payloads.
 *
 * Downscaling is not cosmetic. A full-resolution JPEG is roughly 300 KB, which becomes 400 KB of
 * base64; eight of those is a multi-megabyte request over mobile data and the single biggest term in
 * end-to-end review latency. [DEFAULT_MAX_EDGE_PX] keeps a frame near 40 KB while leaving joint
 * positions legible.
 */
object ReviewFrames {
    const val DEFAULT_MAX_EDGE_PX = 768
    const val DEFAULT_JPEG_QUALITY = 70

    /**
     * [maxEdgePx] and [quality] are tuning knobs, not constants to inline: the legible floor depends
     * on framing, subject distance and how much of the frame the body occupies. Measure on real
     * clips before trusting the defaults.
     */
    fun encode(
        bitmap: Bitmap,
        label: String,
        timestampMs: Long,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): ReviewFrame {
        require(maxEdgePx > 0) { "maxEdgePx must be positive" }
        require(quality in 1..100) { "quality must be in 1..100" }

        val scaled = bitmap.downscaledTo(maxEdgePx)
        val bytes = ByteArrayOutputStream().use { buffer ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            buffer.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()

        return ReviewFrame(label, timestampMs, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun Bitmap.downscaledTo(maxEdgePx: Int): Bitmap {
        val longestEdge = maxOf(width, height)
        if (longestEdge <= maxEdgePx) return this
        val scale = maxEdgePx.toDouble() / longestEdge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}
