package ai.senp.validation

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider

/** Shared camera plumbing; CameraX's toBitmap handles RGBA row/pixel stride on vendor cameras. */
internal object LiveCameraFrame {
    fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()

    fun camera(provider: ProcessCameraProvider): CameraSelector = when {
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> throw IllegalStateException("No supported camera is available")
    }

    fun <T> withUprightBitmap(image: ImageProxy, block: (Bitmap) -> T): T {
        val raw = image.toBitmap()
        var upright: Bitmap? = null
        try {
            val rotation = image.imageInfo.rotationDegrees
            require(rotation in setOf(0, 90, 180, 270))
            val scale = minOf(1f, 640f / maxOf(raw.width, raw.height))
            val matrix = Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }
            val prepared = if (rotation == 0 && scale == 1f) raw else
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            upright = prepared
            return block(prepared)
        } finally {
            if (upright !== raw) upright?.recycle()
            raw.recycle()
        }
    }
}
