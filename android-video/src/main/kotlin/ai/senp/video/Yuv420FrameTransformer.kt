package ai.senp.video

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Converts decoder YUV_420_888 output directly into a reusable ARGB buffer while applying metadata
 * rotation and long-edge scaling. A bounded source-coordinate map is built once so every subsequent
 * frame avoids floating-point scaling, coordinate pairs, and intermediate full-size RGB bitmaps.
 */
internal class Yuv420FrameTransformer(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    rotationDegrees: Int,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    private val rotation = FrameGeometry.normalizeRotation(rotationDegrees)
    private val orientedSize = FrameGeometry.orientedSize(sourceWidth, sourceHeight, rotation)
    private val output = IntArray(outputWidth * outputHeight)
    private val sourceXByOutput = IntArray(output.size)
    private val sourceYByOutput = IntArray(output.size)

    init {
        val orientedWidth = orientedSize.first
        val orientedHeight = orientedSize.second
        var outputIndex = 0
        for (outputY in 0 until outputHeight) {
            val orientedY = min(
                orientedHeight - 1,
                (((2L * outputY + 1L) * orientedHeight) / (2L * outputHeight)).toInt(),
            )
            for (outputX in 0 until outputWidth) {
                val orientedX = min(
                    orientedWidth - 1,
                    (((2L * outputX + 1L) * orientedWidth) / (2L * outputWidth)).toInt(),
                )
                when (rotation) {
                    0 -> {
                        sourceXByOutput[outputIndex] = orientedX
                        sourceYByOutput[outputIndex] = orientedY
                    }
                    90 -> {
                        sourceXByOutput[outputIndex] = orientedY
                        sourceYByOutput[outputIndex] = sourceHeight - 1 - orientedX
                    }
                    180 -> {
                        sourceXByOutput[outputIndex] = sourceWidth - 1 - orientedX
                        sourceYByOutput[outputIndex] = sourceHeight - 1 - orientedY
                    }
                    270 -> {
                        sourceXByOutput[outputIndex] = sourceWidth - 1 - orientedY
                        sourceYByOutput[outputIndex] = orientedX
                    }
                }
                outputIndex++
            }
        }
    }

    fun transform(image: Image): IntArray {
        if (image.format != ImageFormat.YUV_420_888) {
            throw VideoDecodeException.Codec("Expected YUV_420_888 decoder image, got format=${image.format}")
        }
        val crop = image.cropRect
        if (crop.width() != sourceWidth || crop.height() != sourceHeight) {
            throw VideoDecodeException.Codec(
                "Decoder crop ${crop.width()}x${crop.height()} does not match track ${sourceWidth}x${sourceHeight}",
            )
        }
        if (image.planes.size < 3) {
            throw VideoDecodeException.Codec("YUV decoder image exposes only ${image.planes.size} planes")
        }

        val yPlane = PlaneReader(image.planes[0])
        val uPlane = PlaneReader(image.planes[1])
        val vPlane = PlaneReader(image.planes[2])
        for (outputIndex in output.indices) {
            val sourceX = sourceXByOutput[outputIndex] + crop.left
            val sourceY = sourceYByOutput[outputIndex] + crop.top
            val y = yPlane.get(sourceX, sourceY)
            val u = uPlane.get(sourceX / 2, sourceY / 2)
            val v = vPlane.get(sourceX / 2, sourceY / 2)
            output[outputIndex] = yuvToArgb(y, u, v)
        }
        return output
    }

    private class PlaneReader(plane: Image.Plane) {
        private val buffer: ByteBuffer = plane.buffer.duplicate()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride
        private val base = buffer.position()

        fun get(x: Int, y: Int): Int = buffer.get(base + y * rowStride + x * pixelStride).toInt() and 0xff
    }

    companion object {
        internal fun yuvToArgb(y: Int, u: Int, v: Int): Int {
            val c = (y - 16).coerceAtLeast(0)
            val d = u - 128
            val e = v - 128
            val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
