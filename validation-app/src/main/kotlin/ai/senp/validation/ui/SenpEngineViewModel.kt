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
import ai.senp.validation.model.PoseModelInstallState
import ai.senp.validation.model.PoseModelSpec
import ai.senp.validation.model.PoseModelStore
import ai.senp.validation.ui.state.AnalysisUiState
import ai.senp.validation.ui.state.ConfigurationState
import ai.senp.validation.ui.state.VideoSelectionState
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class SenpEngineViewModel(application: Application) : AndroidViewModel(application) {

    private val modelStore = PoseModelStore(application)
    private val composition = EngineComposition(application) { modelStore.verifiedModelFileOrNull() }

    private val _videoSelectionState = MutableStateFlow(VideoSelectionState())
    val videoSelectionState: StateFlow<VideoSelectionState> = _videoSelectionState.asStateFlow()

    private val _configState = MutableStateFlow(ConfigurationState())
    val configState: StateFlow<ConfigurationState> = _configState.asStateFlow()

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private val _modelState = MutableStateFlow<PoseModelInstallState>(PoseModelInstallState.Checking)
    val modelState: StateFlow<PoseModelInstallState> = _modelState.asStateFlow()

    private var analysisJob: Job? = null
    private var modelDownloadJob: Job? = null

    init {
        refreshModelState()
    }

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


    fun refreshModelState() {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                modelStore.removeInvalidModel()
                modelStore.verifiedModelFileOrNull()
            }
            _modelState.value = ready?.let(PoseModelInstallState::Ready) ?: PoseModelInstallState.Missing
        }
    }

    fun downloadAnalysisModel() {
        if (modelDownloadJob?.isActive == true) return
        modelDownloadJob = viewModelScope.launch {
            _modelState.value = PoseModelInstallState.Downloading(0L, PoseModelSpec.EXPECTED_BYTES)
            try {
                val installed = modelStore.download { downloaded, total ->
                    _modelState.value = PoseModelInstallState.Downloading(downloaded, total)
                }
                _modelState.value = PoseModelInstallState.Ready(installed)
                Log.i(TAG, "Analysis model installed and verified")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Analysis model download failed", error)
                _modelState.value = PoseModelInstallState.Failed(
                    error.message ?: "Unable to download the local analysis model",
                )
            } finally {
                modelDownloadJob = null
            }
        }
    }

    fun isAnalysisModelReady(): Boolean = _modelState.value is PoseModelInstallState.Ready

    fun onSelectSourceVideo(uri: Uri) {
        viewModelScope.launch {
            _videoSelectionState.update { it.copy(isCalculatingHash = true, errorMessage = null) }
            try {
                val sha = calculateSha256(uri)
                _videoSelectionState.update {
                    it.copy(
                        sourceUri = uri,
                        sourceSha256 = sha,
                        isCalculatingHash = false,
                    )
                }
                Log.i(TAG, "Source video ready scheme=${uri.scheme ?: "path"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _videoSelectionState.update {
                    it.copy(isCalculatingHash = false, errorMessage = "Could not read this video. Choose another file.")
                }
            }
        }
    }

    fun onSelectReferenceVideo(uri: Uri) {
        viewModelScope.launch {
            _videoSelectionState.update { it.copy(isCalculatingHash = true, errorMessage = null) }
            try {
                val sha = calculateSha256(uri)
                _videoSelectionState.update {
                    it.copy(
                        referenceUri = uri,
                        referenceSha256 = sha,
                        isCalculatingHash = false,
                    )
                }
                Log.i(TAG, "Reference video ready scheme=${uri.scheme ?: "path"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _videoSelectionState.update {
                    it.copy(isCalculatingHash = false, errorMessage = "Could not read this video. Choose another file.")
                }
            }
        }
    }

    fun updateTargetFps(fps: Int) {
        _configState.update { it.copy(targetFps = fps.coerceIn(1, 60)) }
    }

    fun updateLongEdgeCap(capPx: Int) {
        _configState.update { it.copy(longEdgeCapPx = capPx.coerceIn(240, 1080)) }
    }

    fun updateExerciseProfile(profileId: String) {
        require(profileId in SUPPORTED_EXERCISE_PROFILES) { "Unsupported exercise profile: $profileId" }
        _configState.update { it.copy(exerciseProfileId = profileId) }
    }

    fun runAnalysis() {
        if (!isAnalysisModelReady()) {
            _videoSelectionState.update { it.copy(errorMessage = "Install the local analysis model before analysing videos.") }
            return
        }
        if (analysisJob?.isActive == true) return

        val selection = videoSelectionState.value
        val config = configState.value

        val sourceUri = selection.sourceUri ?: return
        val referenceUri = selection.referenceUri ?: return
        val sourceSha = selection.sourceSha256 ?: return
        val referenceSha = selection.referenceSha256 ?: return

        analysisJob = viewModelScope.launch {
            _uiState.value = AnalysisUiState.Analyzing(
                activeStage = PipelineStageId.VALIDATION,
                progressPercent = 0.05f,
                statusMessage = "Preparing local analysis..."
            )

            lastSourcePoses = null
            lastReferencePoses = null

            // The idle screen owns prepared ExoPlayers for both selected clips. Give Compose time
            // to dispose those players before the analysis pipeline allocates its MediaCodec decoder.
            // This avoids transient multi-decoder contention on stricter physical-device codecs.
            delay(300L)

            val request = AnalysisRequest(
                requestId = "req-" + UUID.randomUUID().toString().take(8),
                requestedAtEpochMs = TimestampMs(System.currentTimeMillis()),
                source = VideoSource(uri = sourceUri.toString(), sha256 = sourceSha),
                reference = VideoSource(uri = referenceUri.toString(), sha256 = referenceSha),
                configuration = AnalysisConfiguration(
                    model = PoseModelConfiguration(
                        modelSha256 = Sha256(PoseModelSpec.EXPECTED_SHA256),
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
                    normalizationVersion = "pelvis-torso-scale/1",
                    exerciseProfileVersion = "exercise-profiles/1/${config.exerciseProfileId}",
                )
            )

            Log.i(
                TAG,
                "Analysis start profile=${config.exerciseProfileId} sourceScheme=${sourceUri.scheme ?: "path"} referenceScheme=${referenceUri.scheme ?: "path"}",
            )

            val outcome = try {
                withContext(Dispatchers.Default) {
                    customPipeline.analyze(request)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Unexpected analysis exception", error)
                AnalysisOutcome.Failure(
                    failure = ai.senp.core.contracts.AnalysisFailure.Unexpected(
                        stage = PipelineStageId.VALIDATION,
                        exceptionType = error::class.qualifiedName ?: error.javaClass.name,
                        message = error.message ?: "Unexpected local analysis failure",
                    ),
                    timings = emptyList(),
                )
            }

            try {
                when (outcome) {
                    is AnalysisOutcome.Success -> {
                        Log.i(TAG, "Analysis success points=${outcome.result.payload.alignment.points.size} problems=${outcome.result.payload.problems.size}")
                        // MediaCodec release is asynchronous on some OEM stacks. Let the analysis
                        // decoder settle before the results screen prepares its two ExoPlayers.
                        delay(300L)
                        _uiState.value = AnalysisUiState.Success(
                            result = outcome.result,
                            sourcePoses = lastSourcePoses,
                            referencePoses = lastReferencePoses,
                            sourceUri = sourceUri,
                            referenceUri = referenceUri,
                        )
                    }
                    is AnalysisOutcome.Failure -> {
                        Log.e(TAG, "Analysis failure stage=${outcome.failure.stage} type=${outcome.failure::class.simpleName}: ${outcome.failure.message}")
                        _uiState.value = AnalysisUiState.Failure(
                            failure = outcome.failure,
                            timings = outcome.timings
                        )
                    }
                }
            } finally {
                analysisJob = null
            }
        }
    }

    fun resetAnalysis() {
        _uiState.value = AnalysisUiState.Idle
        _videoSelectionState.value = VideoSelectionState()
    }

    fun loadSamplePresetVideos() {
        val candidateFile = java.io.File("/sdcard/Download/pullups_wrong.mp4")
        val referenceFile = java.io.File("/sdcard/Download/pullups_right.mp4")
        if (candidateFile.exists() && referenceFile.exists()) {
            onSelectSourceVideo(Uri.fromFile(candidateFile))
            onSelectReferenceVideo(Uri.fromFile(referenceFile))
        }
    }

    private suspend fun calculateSha256(uri: Uri): Sha256 = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val context = getApplication<Application>()
        val inputStream = if (uri.scheme == "file") {
            java.io.FileInputStream(java.io.File(uri.path ?: uri.toString().removePrefix("file://")))
        } else {
            context.contentResolver.openInputStream(uri)
        }

        inputStream?.use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        } ?: throw IllegalArgumentException("Cannot open video URI: $uri")

        val hashHex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        Sha256(hashHex)
    }

    companion object {
        private const val TAG = "SenpEngineViewModel"

        val SUPPORTED_EXERCISE_PROFILES = setOf(
            "generic",
            "biceps_curl",
            "pushup",
            "squat",
            "leg_raise",
            "plank",
            "pullup",
        )
    }
}
