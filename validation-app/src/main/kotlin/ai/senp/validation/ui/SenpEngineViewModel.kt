package ai.senp.validation.ui

import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.AnalysisOutcome
import ai.senp.core.contracts.AnalysisRequest
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.PoseThresholds
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.pipeline.VideoPoseExtractor
import ai.senp.validation.EngineComposition
import ai.senp.validation.ui.state.AnalysisUiState
import ai.senp.validation.ui.state.ConfigurationState
import ai.senp.validation.ui.state.VideoSelectionState
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class SenpEngineViewModel(application: Application) : AndroidViewModel(application) {

    private val composition = EngineComposition(application)

    private val _videoSelectionState = MutableStateFlow(VideoSelectionState())
    val videoSelectionState: StateFlow<VideoSelectionState> = _videoSelectionState.asStateFlow()

    private val _configState = MutableStateFlow(ConfigurationState())
    val configState: StateFlow<ConfigurationState> = _configState.asStateFlow()

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    // Captured pose sequences for visual overlay rendering
    private var lastSourcePoses: PoseSequence? = null
    private var lastReferencePoses: PoseSequence? = null

    // Interceptor to capture pose sequences for UI visualization
    private val uiPoseExtractor = object : VideoPoseExtractor {
        override suspend fun extract(
            role: VideoRole,
            source: VideoSource,
            sampling: SamplingConfiguration,
            model: PoseModelConfiguration
        ): StageResult<ai.senp.core.contracts.VideoPoseExtraction> {
            val result = composition.videoPoseExtractor.extract(role, source, sampling, model)
            if (result is StageResult.Success) {
                if (role == VideoRole.SOURCE) {
                    lastSourcePoses = result.value.poses
                } else {
                    lastReferencePoses = result.value.poses
                }
            }
            return result
        }
    }

    private val customPipeline = ai.senp.core.pipeline.AnalysisPipeline(
        videoPoseExtractor = uiPoseExtractor,
        motionProcessor = composition.motionProcessor,
        phaseDetector = composition.phaseDetector,
        alignmentEngine = composition.alignmentEngine,
        cache = composition.cache,
        monotonicClock = { android.os.SystemClock.elapsedRealtime() },
        wallClock = { TimestampMs(System.currentTimeMillis()) },
        engineVersion = EngineComposition.ENGINE_VERSION,
    )

    fun onSelectSourceVideo(uri: Uri) {
        viewModelScope.launch {
            _videoSelectionState.update { it.copy(isCalculatingHash = true) }
            val sha = calculateSha256(uri)
            _videoSelectionState.update {
                it.copy(
                    sourceUri = uri,
                    sourceSha256 = sha,
                    isCalculatingHash = false
                )
            }
        }
    }

    fun onSelectReferenceVideo(uri: Uri) {
        viewModelScope.launch {
            _videoSelectionState.update { it.copy(isCalculatingHash = true) }
            val sha = calculateSha256(uri)
            _videoSelectionState.update {
                it.copy(
                    referenceUri = uri,
                    referenceSha256 = sha,
                    isCalculatingHash = false
                )
            }
        }
    }

    fun updateTargetFps(fps: Int) {
        _configState.update { it.copy(targetFps = fps.coerceIn(1, 60)) }
    }

    fun updateLongEdgeCap(capPx: Int) {
        _configState.update { it.copy(longEdgeCapPx = capPx.coerceIn(240, 1080)) }
    }

    fun runAnalysis() {
        val selection = videoSelectionState.value
        val config = configState.value

        val sourceUri = selection.sourceUri ?: return
        val referenceUri = selection.referenceUri ?: return
        val sourceSha = selection.sourceSha256 ?: return
        val referenceSha = selection.referenceSha256 ?: return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = AnalysisUiState.Analyzing(
                activeStage = PipelineStageId.VALIDATION,
                progressPercent = 0.05f,
                statusMessage = "Validating request parameters..."
            )

            lastSourcePoses = null
            lastReferencePoses = null

            val request = AnalysisRequest(
                requestId = "req-" + UUID.randomUUID().toString().take(8),
                requestedAtEpochMs = TimestampMs(System.currentTimeMillis()),
                source = VideoSource(uri = sourceUri.toString(), sha256 = sourceSha),
                reference = VideoSource(uri = referenceUri.toString(), sha256 = referenceSha),
                configuration = AnalysisConfiguration(
                    model = PoseModelConfiguration(
                        modelSha256 = Sha256("5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"),
                        modelVariant = config.modelVariant,
                        thresholds = PoseThresholds(
                            minimumDetectionConfidence = config.minimumConfidence,
                            minimumPresenceConfidence = config.minimumConfidence,
                            minimumTrackingConfidence = config.minimumConfidence,
                        )
                    ),
                    pipelineVersion = EngineComposition.PIPELINE_VERSION,
                    sampling = SamplingConfiguration(
                        targetFramesPerSecond = config.targetFps,
                        longEdgeCapPx = config.longEdgeCapPx,
                    ),
                    normalizationVersion = "senp-normalization/1",
                    exerciseProfileVersion = "biceps-curl/1",
                )
            )

            val outcome = customPipeline.analyze(request)

            withContext(Dispatchers.Main) {
                when (outcome) {
                    is AnalysisOutcome.Success -> {
                        _uiState.value = AnalysisUiState.Success(
                            result = outcome.result,
                            sourcePoses = lastSourcePoses,
                            referencePoses = lastReferencePoses,
                            sourceUri = sourceUri,
                            referenceUri = referenceUri,
                        )
                    }
                    is AnalysisOutcome.Failure -> {
                        _uiState.value = AnalysisUiState.Failure(
                            failure = outcome.failure,
                            timings = outcome.timings
                        )
                    }
                }
            }
        }
    }

    fun resetAnalysis() {
        _uiState.value = AnalysisUiState.Idle
    }

    private suspend fun calculateSha256(uri: Uri): Sha256 = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val context = getApplication<Application>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        } ?: throw IllegalArgumentException("Cannot open video URI: $uri")

        val hashHex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        Sha256(hashHex)
    }
}
