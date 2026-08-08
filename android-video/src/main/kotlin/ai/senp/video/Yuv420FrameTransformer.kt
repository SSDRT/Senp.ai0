package ai.senp.video

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Converts decoder YUV_420_888 output directly into a reusable ARGB buffer while applying metadata
 * rotation and long-edge scaling. Geometry is derived from the decoder's visible crop, not the
 * coded buffer size, and is rebuilt only if the decoder changes that crop.
 */
internal class Yuv420FrameTransformer(
    rotationDegrees: Int,
    private val longEdgeCapPx: Int,
) {
    private val rotation = FrameGeometry.normalizeRotation(rotationDegrees)
    private var geometry: Geometry? = null

    init {
        require(longEdgeCapPx > 0)
    }

    fun transform(image: Image): TransformedPixels {
        if (image.format != ImageFormat.YUV_420_888) {
            throw VideoDecodeException.Codec("Expected YUV_420_888 decoder image, got format=${image.format}")
        }
        if (image.planes.size < 3) {
            throw VideoDecodeException.Codec("YUV decoder image exposes only ${image.planes.size} planes")
        }

        val crop = image.cropRect
        if (
            crop.width() <= 0 || crop.height() <= 0 || crop.left < 0 || crop.top < 0 ||
            crop.right > image.width || crop.bottom > image.height
        ) {
            throw VideoDecodeException.Codec(
                "Invalid decoder crop $crop for image ${image.width}x${image.height}",
            )
        }
        val activeGeometry = geometryFor(crop)
        val yPlane = PlaneReader(image.planes[0])
        val uPlane = PlaneReader(image.planes[1])
        val vPlane = PlaneReader(image.planes[2])
        for (outputIndex in activeGeometry.output.indices) {
            val sourceX = activeGeometry.sourceXByOutput[outputIndex]
            val sourceY = activeGeometry.sourceYByOutput[outputIndex]
            val y = yPlane.get(sourceX, sourceY)
            val u = uPlane.get(sourceX / 2, sourceY / 2)
            val v = vPlane.get(sourceX / 2, sourceY / 2)
            activeGeometry.output[outputIndex] = yuvToArgb(y, u, v)
        }
        return TransformedPixels(
            visibleWidth = activeGeometry.visibleWidth,
            visibleHeight = activeGeometry.visibleHeight,
            outputWidth = activeGeometry.outputWidth,
            outputHeight = activeGeometry.outputHeight,
            argb8888 = activeGeometry.output,
        )
    }

    private fun geometryFor(crop: Rect): Geometry {
        val current = geometry
        if (current != null && current.matches(crop)) return current

        val visibleWidth = crop.width()
        val visibleHeight = crop.height()
        val (orientedWidth, orientedHeight) = FrameGeometry.orientedSize(visibleWidth, visibleHeight, rotation)
        val (outputWidth, outputHeight) = FrameGeometry.cappedSize(orientedWidth, orientedHeight, longEdgeCapPx)
        val output = IntArray(outputWidth * outputHeight)
        val sourceXByOutput = IntArray(output.size)
        val sourceYByOutput = IntArray(output.size)

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
                val source = FrameGeometry.croppedSourceCoordinate(
                    orientedX = orientedX,
                    orientedY = orientedY,
                    visibleWidth = visibleWidth,
                    visibleHeight = visibleHeight,
                    rotationDegrees = rotation,
                    cropLeft = crop.left,
                    cropTop = crop.top,
                )
                sourceXByOutput[outputIndex] = source.first
                sourceYByOutput[outputIndex] = source.second
                outputIndex++
            }
        }

        return Geometry(
            cropLeft = crop.left,
            cropTop = crop.top,
            cropRight = crop.right,
            cropBottom = crop.bottom,
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            output = output,
            sourceXByOutput = sourceXByOutput,
            sourceYByOutput = sourceYByOutput,
        ).also { geometry = it }
    }

    private data class Geometry(
        val cropLeft: Int,
        val cropTop: Int,
        val cropRight: Int,
        val cropBottom: Int,
        val visibleWidth: Int,
        val visibleHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val output: IntArray,
        val sourceXByOutput: IntArray,
        val sourceYByOutput: IntArray,
    ) {
        fun matches(crop: Rect): Boolean =
            cropLeft == crop.left && cropTop == crop.top && cropRight == crop.right && cropBottom == crop.bottom
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

internal data class TransformedPixels(
    val visibleWidth: Int,
    val visibleHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val argb8888: IntArray,
)
