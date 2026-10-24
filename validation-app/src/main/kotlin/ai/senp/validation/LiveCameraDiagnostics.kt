package ai.senp.validation

import ai.senp.core.contracts.PoseFrame
import android.graphics.Bitmap

/** Opt-in instrumentation seam. Callbacks run synchronously on the analyzer owner;
 * the bitmap is borrowed only for the callback and must never be retained/recycled.
 * No frames or landmarks are recorded unless a test attaches an observer.
 */
internal interface LiveCameraDiagnostics {
    fun onFrame(sample: LiveCameraSample, bitmap: Bitmap, rawPose: PoseFrame)
    fun onError(error: Throwable)
}

internal data class LiveCameraSample(
    val captureNanos: Long,
    val analyzerArrivalNanos: Long,
    val resultNanos: Long,
    val inferenceNanos: Long,
    val timestampSource: Int?,
    val modelSha256: String,
    val trackerResetCount: Long,
    val admissionDrops: Long,
    val nativeRecreationCount: Long,
)
