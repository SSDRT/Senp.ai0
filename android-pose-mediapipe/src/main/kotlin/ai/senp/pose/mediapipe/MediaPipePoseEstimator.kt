package ai.senp.pose.mediapipe

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoPoseDiagnostics
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.core.contracts.VideoPoseFailureKind
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.contracts.WorldLandmark
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.video.DecodeCancellation
import ai.senp.video.DecodeConfig
import ai.senp.video.SequentialVideoDecoder
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidVideoPoseExtractor(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VideoPoseExtractor {
    private val appContext = context.applicationContext
    private val cancelRequested = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    fun cancel() {
        cancelRequested.set(true)
    }

    override suspend fun extract(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
    ): StageResult<VideoPoseExtraction> {
        if (!active.compareAndSet(false, true)) {
            return StageResult.Failure(
                AnalysisFailure.VideoPose(role, VideoPoseFailureKind.INFERENCE, "This extractor is already processing another video"),
            )
        }
        cancelRequested.set(false)
        return try {
            withContext(dispatcher) {
                extractBlocking(role, source, sampling, model, currentCoroutineContext())
            }
        } finally {
            active.set(false)
            cancelRequested.set(false)
        }
    }

    private fun extractBlocking(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
        coroutineContext: CoroutineContext,
    ): StageResult<VideoPoseExtraction> {
        coroutineContext.ensureActive()
        val resolved = when (val result = resolveVideo(role, source)) {
            is StageResult.Failure -> return result
            is StageResult.Success -> result.value
        }
        try {
            val actualVideoSha = try {
                sha256(resolved.file, coroutineContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return failure(
                    role,
                    VideoPoseFailureKind.CORRUPT_VIDEO,
                    error.message ?: "Unable to read video for SHA-256 verification",
                )
            }
            if (actualVideoSha != source.sha256.value) {
                return StageResult.Failure(
                    AnalysisFailure.VideoPose(
                        role,
                        VideoPoseFailureKind.CORRUPT_VIDEO,
                        "Video SHA-256 mismatch: expected " + source.sha256.value + ", got " + actualVideoSha,
                    ),
                )
            }

            val estimator = try {
                MediaPipePoseEstimator.create(
                    context = appContext,
                    modelAssetPath = modelAssetPath,
                    expectedModelSha256 = model.modelSha256.value,
                    config = MediaPipePoseEstimator.Config(
                        detectionConfidence = model.thresholds.minimumDetectionConfidence.toFloat(),
                        presenceConfidence = model.thresholds.minimumPresenceConfidence.toFloat(),
                        trackingConfidence = model.thresholds.minimumTrackingConfidence.toFloat(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: MediaPipeAdapterException.ModelLoad) {
                return StageResult.Failure(
                    AnalysisFailure.VideoPose(role, VideoPoseFailureKind.MODEL_LOAD, error.message ?: "Pose model load failed"),
                )
            }

            return estimator.use { poseEstimator ->
                val collector = ExtractionCollector(role)
                val decoder = SequentialVideoDecoder(
                    DecodeConfig(
                        targetFps = sampling.targetFramesPerSecond.toDouble(),
                        longEdgeCapPx = sampling.longEdgeCapPx,
                    ),
                )
                val decoded = decoder.decode(
                    role = role,
                    file = resolved.file,
                    cancellation = DecodeCancellation {
                        coroutineContext.ensureActive()
                        cancelRequested.get()
                    },
                ) { frame ->
                    coroutineContext.ensureActive()
                    collector.acceptingFrame {
                        val estimate = try {
                            poseEstimator.estimate(
                                timestampMs = frame.timestampMs,
                                diagnosticFrameIndex = collector.sampledFrameCount.toLong(),
                                width = frame.width,
                                height = frame.height,
                                argb8888 = frame.argb8888,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: MediaPipeAdapterException.NonMonotonicTimestamp) {
                            return@decode StageResult.Failure(
                                AnalysisFailure.VideoPose(
                                    role,
                                    VideoPoseFailureKind.NON_MONOTONIC_TIMESTAMP,
                                    error.message ?: "Non-monotonic pose timestamp",
                                ),
                            )
                        } catch (error: MediaPipeAdapterException.Inference) {
                            return@decode StageResult.Failure(
                                AnalysisFailure.VideoPose(role, VideoPoseFailureKind.INFERENCE, error.message ?: "Pose inference failed"),
                            )
                        }
                        collector.add(estimate)
                    }
                    StageResult.Success(Unit)
                }
                when (decoded) {
                    is StageResult.Failure -> decoded
                    is StageResult.Success -> {
                        val decode = decoded.value
                        val duration = maxOf(
                            decode.info.durationMs,
                            (collector.frames.lastOrNull()?.timestamp?.value ?: -1L) + 1L,
                        )
                        StageResult.Success(
                            VideoPoseExtraction(
                                role = role,
                                duration = DurationMs(duration),
                                poses = PoseSequence(role, collector.frames.toList()),
                                diagnostics = VideoPoseDiagnostics(
                                    decodedFrameCount = decode.diagnostics.decodedFrames,
                                    sampledFrameCount = collector.sampledFrameCount,
                                    detectedFrameCount = collector.detectedFrameCount,
                                    noPersonFrameCount = collector.noPersonFrameCount,
                                    unusableTrackingFrameCount = collector.unusableTrackingFrameCount,
                                    decodeNanos = decode.diagnostics.decodeNanos,
                                    inferenceNanos = collector.inferenceNanos,
                                    maxInFlightFrames = 1,
                                    peakInFlightFrames = collector.peakInFlightFrames,
                                ),
                            ),
                        )
                    }
                }
            }
        } finally {
            if (resolved.deleteAfterUse) resolved.file.delete()
        }
    }

    private fun resolveVideo(role: VideoRole, source: VideoSource): StageResult<ResolvedVideo> {
        val uri = Uri.parse(source.uri)
        return when (uri.scheme?.lowercase()) {
            null, "" -> resolveFile(role, File(source.uri), false)
            "file" -> {
                val path = uri.path
                if (path == null) failure(role, VideoPoseFailureKind.SOURCE_MISSING, "File URI has no path")
                else resolveFile(role, File(path), false)
            }
            "content" -> {
                try {
                    val staged = stageContentFile(appContext.cacheDir) {
                        appContext.contentResolver.openInputStream(uri)
                    } ?: return failure(role, VideoPoseFailureKind.SOURCE_MISSING, "Unable to open content URI")
                    StageResult.Success(ResolvedVideo(staged, true))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failure(role, VideoPoseFailureKind.SOURCE_MISSING, error.message ?: "Unable to stage content URI")
                }
            }
            else -> failure(role, VideoPoseFailureKind.UNSUPPORTED_VIDEO, "Unsupported video URI scheme: " + uri.scheme)
        }
    }

    private fun resolveFile(role: VideoRole, file: File, deleteAfterUse: Boolean): StageResult<ResolvedVideo> =
        if (file.isFile) StageResult.Success(ResolvedVideo(file, deleteAfterUse))
        else failure(role, VideoPoseFailureKind.SOURCE_MISSING, "Video does not exist: " + file.absolutePath)

    private fun failure(role: VideoRole, kind: VideoPoseFailureKind, message: String): StageResult.Failure =
        StageResult.Failure(AnalysisFailure.VideoPose(role, kind, message))

    private data class ResolvedVideo(val file: File, val deleteAfterUse: Boolean)

    companion object {
        const val DEFAULT_MODEL_ASSET = "pose_landmarker_full.task"
    }
}

internal fun stageContentFile(cacheDir: File, openInputStream: () -> InputStream?): File? {
    val input = openInputStream() ?: return null
    var staged: File? = null
    return try {
        input.use { sourceStream ->
            val stagedFile = File.createTempFile("senp-video-", ".bin", cacheDir)
            staged = stagedFile
            stagedFile.outputStream().use(sourceStream::copyTo)
        }
        staged
    } catch (error: Throwable) {
        staged?.delete()
        throw error
    }
}

internal class ExtractionCollector(private val role: VideoRole) {
    val frames = mutableListOf<PoseFrame>()
    var sampledFrameCount = 0
        private set
    var detectedFrameCount = 0
        private set
    var noPersonFrameCount = 0
        private set
    var unusableTrackingFrameCount = 0
        private set
    var inferenceNanos = 0L
        private set
    var peakInFlightFrames = 0
        private set
    private var inFlightFrames = 0

    inline fun acceptingFrame(block: () -> Unit) {
        inFlightFrames++
        peakInFlightFrames = maxOf(peakInFlightFrames, inFlightFrames)
        check(inFlightFrames <= 1) { "Pixel-frame memory bound exceeded for " + role }
        try {
            block()
        } finally {
            inFlightFrames--
        }
    }

    fun add(estimate: PoseEstimate) {
        val previous = frames.lastOrNull()?.timestamp
        require(previous == null || previous < estimate.frame.timestamp) { "Pose timestamps must be strictly increasing" }
        frames += estimate.frame
        sampledFrameCount++
        inferenceNanos += estimate.inferenceNanos + estimate.mappingNanos
        when (estimate.state) {
            TrackingState.DETECTED -> detectedFrameCount++
            TrackingState.NO_PERSON -> noPersonFrameCount++
            TrackingState.UNUSABLE -> unusableTrackingFrameCount++
        }
    }
}

internal class MediaPipePoseEstimator private constructor(
    private val landmarker: PoseLandmarker,
    private val mapper: PoseResultMapper,
) : AutoCloseable {
    private var previousTimestampMs: Long? = null
    private var reusableBitmap: Bitmap? = null
    private var closed = false

    @Synchronized
    fun estimate(
        timestampMs: Long,
        diagnosticFrameIndex: Long,
        width: Int,
        height: Int,
        argb8888: IntArray,
    ): PoseEstimate {
        check(!closed) { "Pose estimator is closed" }
        require(width > 0 && height > 0 && argb8888.size == width * height)
        val previous = previousTimestampMs
        if (previous != null && timestampMs <= previous) {
            throw MediaPipeAdapterException.NonMonotonicTimestamp(previous, timestampMs)
        }
        previousTimestampMs = timestampMs

        val bitmap = reusableBitmap(width, height)
        bitmap.setPixels(argb8888, 0, width, 0, 0, width, height)
        val mediaPipeImage = BitmapImageBuilder(bitmap).build()
        val inferenceStarted = System.nanoTime()
        val result = try {
            mediaPipeImage.use { landmarker.detectForVideo(it, timestampMs) }
        } catch (error: Throwable) {
            throw MediaPipeAdapterException.Inference("MediaPipe Pose Landmarker inference failed", error)
        }
        val inferenceNanos = System.nanoTime() - inferenceStarted
        return try {
            mapper.map(timestampMs, diagnosticFrameIndex, result.toRaw(), inferenceNanos)
        } catch (error: MediaPipeAdapterException) {
            throw error
        } catch (error: Throwable) {
            throw MediaPipeAdapterException.Inference("MediaPipe pose result mapping failed", error)
        }
    }

    private fun reusableBitmap(width: Int, height: Int): Bitmap {
        val existing = reusableBitmap
        if (existing != null && existing.width == width && existing.height == height && !existing.isRecycled) return existing
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        reusableBitmap?.recycle()
        reusableBitmap = null
        landmarker.close()
    }

    data class Config(
        val detectionConfidence: Float = 0.5f,
        val presenceConfidence: Float = 0.5f,
        val trackingConfidence: Float = 0.5f,
        val minimumUsableLandmarks: Int = 20,
        val usableVisibility: Float = 0.35f,
        val usablePresence: Float = 0.35f,
    ) {
        init {
            require(detectionConfidence in 0f..1f)
            require(presenceConfidence in 0f..1f)
            require(trackingConfidence in 0f..1f)
            require(minimumUsableLandmarks in 1..PoseLandmarkId.COUNT)
            require(usableVisibility in 0f..1f)
            require(usablePresence in 0f..1f)
        }
    }

    companion object {
        fun create(
            context: Context,
            modelAssetPath: String,
            expectedModelSha256: String,
            config: Config = Config(),
        ): MediaPipePoseEstimator {
            try {
                val actualSha = context.assets.open(modelAssetPath).use(::sha256)
                if (actualSha != expectedModelSha256) {
                    throw MediaPipeAdapterException.ModelLoad(
                        "Pose model SHA-256 mismatch: expected " + expectedModelSha256 + ", got " + actualSha,
                    )
                }
                val baseOptions = BaseOptions.builder().setModelAssetPath(modelAssetPath).build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(config.detectionConfidence)
                    .setMinPosePresenceConfidence(config.presenceConfidence)
                    .setMinTrackingConfidence(config.trackingConfidence)
                    .setOutputSegmentationMasks(false)
                    .build()
                val landmarker = PoseLandmarker.createFromOptions(context.applicationContext, options)
                return MediaPipePoseEstimator(
                    landmarker,
                    PoseResultMapper(config.minimumUsableLandmarks, config.usableVisibility, config.usablePresence),
                )
            } catch (error: MediaPipeAdapterException.ModelLoad) {
                throw error
            } catch (error: Throwable) {
                throw MediaPipeAdapterException.ModelLoad("Unable to initialize MediaPipe Pose Landmarker Full", error)
            }
        }
    }
}

internal class PoseResultMapper(
    private val minimumUsableLandmarks: Int = 20,
    private val usableVisibility: Float = 0.35f,
    private val usablePresence: Float = 0.35f,
) {
    fun map(timestampMs: Long, frameIndex: Long, result: RawPoseResult, inferenceNanos: Long): PoseEstimate {
        val mappingStarted = System.nanoTime()
        if (result.imageLandmarks.isEmpty()) {
            return PoseEstimate(
                placeholderFrame(timestampMs, frameIndex, FrameValidityReason.NO_PERSON),
                TrackingState.NO_PERSON,
                inferenceNanos,
                System.nanoTime() - mappingStarted,
            )
        }
        if (result.imageLandmarks.size != PoseLandmarkId.COUNT) {
            return PoseEstimate(
                placeholderFrame(timestampMs, frameIndex, FrameValidityReason.UNUSABLE_TRACKING),
                TrackingState.UNUSABLE,
                inferenceNanos,
                System.nanoTime() - mappingStarted,
            )
        }
        if (result.imageLandmarks.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            return PoseEstimate(
                placeholderFrame(timestampMs, frameIndex, FrameValidityReason.NON_FINITE_INPUT),
                TrackingState.UNUSABLE,
                inferenceNanos,
                System.nanoTime() - mappingStarted,
            )
        }

        val landmarks = PoseLandmarkId.entries.map { id ->
            val image = result.imageLandmarks[id.index]
            val world = result.worldLandmarks.getOrNull(id.index)?.takeIf {
                it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
            }
            PoseLandmark(
                id = id,
                image = ImageLandmark(image.x.toDouble(), image.y.toDouble(), image.z.toDouble()),
                world = world?.let { WorldLandmark(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) },
                visibility = image.visibility?.toDouble(),
                presence = image.presence?.toDouble(),
            )
        }
        val effectiveConfidences = result.imageLandmarks.map { landmark ->
            val visibility = landmark.visibility ?: 0.0f
            val presence = landmark.presence ?: 0.0f
            visibility to presence
        }
        val usableCount = effectiveConfidences.count { (visibility, presence) ->
            visibility >= usableVisibility && presence >= usablePresence
        }
        val confidence = effectiveConfidences
            .map { (visibility, presence) -> minOf(visibility, presence).toDouble() }
            .average()
            .coerceIn(0.0, 1.0)
        val state = if (usableCount >= minimumUsableLandmarks) TrackingState.DETECTED else TrackingState.UNUSABLE
        val validity = if (state == TrackingState.DETECTED) {
            FrameValidity(FrameValidityStatus.VALID, confidence)
        } else {
            FrameValidity(FrameValidityStatus.BLIND, confidence, setOf(FrameValidityReason.UNUSABLE_TRACKING))
        }
        return PoseEstimate(
            PoseFrame(TimestampMs(timestampMs), frameIndex, landmarks, validity),
            state,
            inferenceNanos,
            System.nanoTime() - mappingStarted,
        )
    }

    private fun placeholderFrame(timestampMs: Long, frameIndex: Long, reason: FrameValidityReason): PoseFrame =
        PoseFrame(
            TimestampMs(timestampMs),
            frameIndex,
            PoseLandmarkId.entries.map { id -> PoseLandmark(id, ImageLandmark(0.0, 0.0, 0.0)) },
            FrameValidity(FrameValidityStatus.BLIND, 0.0, setOf(reason)),
        )
}

internal data class RawLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float? = null,
    val presence: Float? = null,
)

internal data class RawPoseResult(
    val imageLandmarks: List<RawLandmark>,
    val worldLandmarks: List<RawLandmark> = emptyList(),
)

internal data class PoseEstimate(
    val frame: PoseFrame,
    val state: TrackingState,
    val inferenceNanos: Long,
    val mappingNanos: Long,
)

internal enum class TrackingState { DETECTED, NO_PERSON, UNUSABLE }

internal sealed class MediaPipeAdapterException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelLoad(message: String, cause: Throwable? = null) : MediaPipeAdapterException(message, cause)
    class Inference(message: String, cause: Throwable? = null) : MediaPipeAdapterException(message, cause)
    class NonMonotonicTimestamp(previousMs: Long, currentMs: Long) :
        MediaPipeAdapterException("Pose timestamps must strictly increase: " + previousMs + " -> " + currentMs)
}

private fun PoseLandmarkerResult.toRaw(): RawPoseResult {
    val image = landmarks().firstOrNull().orEmpty().map { landmark ->
        RawLandmark(
            landmark.x(),
            landmark.y(),
            landmark.z(),
            landmark.visibility().orElse(null),
            landmark.presence().orElse(null),
        )
    }
    val world = worldLandmarks().firstOrNull().orEmpty().map { landmark ->
        RawLandmark(
            landmark.x(),
            landmark.y(),
            landmark.z(),
            landmark.visibility().orElse(null),
            landmark.presence().orElse(null),
        )
    }
    return RawPoseResult(image, world)
}

private fun sha256(file: File, coroutineContext: CoroutineContext): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun sha256(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
