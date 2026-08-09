package ai.senp.validation.ui

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseThresholds
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.sync.v2.VideoSynchronizationRequest
import ai.senp.validation.EngineComposition
import ai.senp.validation.analyzeReferenceActionCatching
import ai.senp.validation.assembleRecordedComparisonCatching
import ai.senp.validation.PreparedReferenceAction
import ai.senp.validation.ReferenceActionProfileStore
import ai.senp.validation.ReferencePreparationOutcome
import ai.senp.validation.ui.state.AnalysisUiState
import ai.senp.validation.ui.state.ConfigurationState
import ai.senp.validation.ui.state.ReferenceActionAnalysisUi
import ai.senp.validation.ui.state.ReferenceProfileUiState
import ai.senp.validation.ui.state.VideoSelectionState
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SenpEngineViewModel(application: Application) : AndroidViewModel(application) {
    private val composition = EngineComposition(application)
    private var referencePreparationJob: Job? = null
    private var analysisJob: Job? = null

    private val _videoSelectionState = MutableStateFlow(VideoSelectionState())
    val videoSelectionState: StateFlow<VideoSelectionState> = _videoSelectionState.asStateFlow()

    private val _configState = MutableStateFlow(ConfigurationState())
    val configState: StateFlow<ConfigurationState> = _configState.asStateFlow()

    private val _referenceProfileState = MutableStateFlow<ReferenceProfileUiState>(ReferenceProfileUiState.Empty)
    val referenceProfileState: StateFlow<ReferenceProfileUiState> = _referenceProfileState.asStateFlow()

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    fun onSelectSourceVideo(uri: Uri) {
        calculateVideoHash(uri, isSource = true)
    }

    fun onSelectReferenceVideo(uri: Uri) {
        referencePreparationJob?.cancel()
        ReferenceActionProfileStore.clear()
        _referenceProfileState.value = ReferenceProfileUiState.Empty
        calculateVideoHash(uri, isSource = false)
    }

    private fun calculateVideoHash(uri: Uri, isSource: Boolean) {
        viewModelScope.launch {
            _videoSelectionState.update { it.copy(isCalculatingHash = true, errorMessage = null) }
            try {
                val sha = calculateSha256(uri)
                _videoSelectionState.update { current ->
                    if (isSource) {
                        current.copy(sourceUri = uri, sourceSha256 = sha, isCalculatingHash = false)
                    } else {
                        current.copy(referenceUri = uri, referenceSha256 = sha, isCalculatingHash = false)
                    }
                }
                if (!isSource) scheduleReferencePreparation(uri, sha)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _videoSelectionState.update {
                    it.copy(isCalculatingHash = false, errorMessage = "Could not read this video. Choose another file.")
                }
                if (!isSource) {
                    ReferenceActionProfileStore.clear()
                    _referenceProfileState.value = ReferenceProfileUiState.Rejected("Could not read the reference video.")
                }
            }
        }
    }

    fun updateTargetFps(fps: Int) {
        _configState.update { it.copy(targetFps = fps.coerceIn(1, 60)) }
        reprepareCurrentReference()
    }

    fun updateLongEdgeCap(capPx: Int) {
        _configState.update { it.copy(longEdgeCapPx = capPx.coerceIn(240, 1080)) }
        reprepareCurrentReference()
    }

    fun runAnalysis() {
        val selection = videoSelectionState.value
        val config = configState.value
        val sourceUri = selection.sourceUri ?: return
        val referenceUri = selection.referenceUri ?: return
        val sourceSha = selection.sourceSha256 ?: return
        val referenceSha = selection.referenceSha256 ?: return

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            referencePreparationJob?.cancelAndJoin()
            _uiState.value = AnalysisUiState.Analyzing(
                activeStage = PipelineStageId.VIDEO_POSE_REFERENCE,
                progressPercent = 0.12f,
                statusMessage = "Preparing body-centric motion from both clips…",
            )

            val model = modelConfiguration(config)
            val sampling = samplingConfiguration(config)
            val source = VideoSource(uri = sourceUri.toString(), sha256 = sourceSha)
            val reference = VideoSource(uri = referenceUri.toString(), sha256 = referenceSha)
            val analysisFramesPerSecond = sampling.targetFramesPerSecond.toDouble()

            try {
                val referenceExtraction = when (
                    val result = composition.referenceActionSession.extractPose(
                        role = VideoRole.REFERENCE,
                        source = reference,
                        sampling = sampling,
                        model = model,
                    )
                ) {
                    is StageResult.Success -> result.value
                    is StageResult.Failure -> {
                        withContext(Dispatchers.Main) { _uiState.value = AnalysisUiState.Failure(result.failure) }
                        return@launch
                    }
                }
                _uiState.value = AnalysisUiState.Analyzing(
                    activeStage = PipelineStageId.VIDEO_POSE_SOURCE,
                    progressPercent = 0.34f,
                    statusMessage = "Reading your movement independently of clip timing…",
                )
                val sourceExtraction = when (
                    val result = composition.referenceActionSession.extractPose(
                        role = VideoRole.SOURCE,
                        source = source,
                        sampling = sampling,
                        model = model,
                    )
                ) {
                    is StageResult.Success -> result.value
                    is StageResult.Failure -> {
                        withContext(Dispatchers.Main) { _uiState.value = AnalysisUiState.Failure(result.failure) }
                        return@launch
                    }
                }

                val prepared = composition.referenceActionSession.compileReference(
                    extraction = referenceExtraction,
                    analysisFramesPerSecond = analysisFramesPerSecond,
                )
                val referenceAction: ReferenceActionAnalysisUi?
                val referenceActionMessage: String?
                when (prepared) {
                    is ReferencePreparationOutcome.Ready -> {
                        ReferenceActionProfileStore.set(
                            PreparedReferenceAction(
                                profile = prepared.profile,
                                referenceSha256 = referenceSha.value,
                                analysisFramesPerSecond = analysisFramesPerSecond,
                            ),
                        )
                        _referenceProfileState.value = ReferenceProfileUiState.Ready(
                            profile = prepared.profile,
                            referenceSha256 = referenceSha,
                            analysisFramesPerSecond = analysisFramesPerSecond,
                        )
                        val actionAttempt = analyzeReferenceActionCatching {
                            composition.referenceActionSession.analyzeRecorded(
                                profile = prepared.profile,
                                extraction = sourceExtraction,
                                analysisFramesPerSecond = analysisFramesPerSecond,
                            )
                        }
                        val recorded = actionAttempt.result
                        if (recorded != null) {
                            referenceAction = ReferenceActionAnalysisUi(
                                profile = recorded.profile,
                                recognition = recorded.recognition,
                                deviations = recorded.deviations,
                            )
                            referenceActionMessage = null
                        } else {
                            referenceAction = null
                            referenceActionMessage = actionAttempt.message
                        }
                    }
                    is ReferencePreparationOutcome.Rejected -> {
                        ReferenceActionProfileStore.clear()
                        _referenceProfileState.value = ReferenceProfileUiState.Rejected(prepared.message)
                        referenceAction = null
                        referenceActionMessage = "Reference action unavailable: ${prepared.message}"
                    }
                }

                val actionReadyState = AnalysisUiState.Success(
                    sourceUri = sourceUri,
                    referenceUri = referenceUri,
                    sourcePoseExtraction = sourceExtraction,
                    referencePoseExtraction = referenceExtraction,
                    referenceAction = referenceAction,
                    referenceActionMessage = referenceActionMessage,
                )
                withContext(Dispatchers.Main) { _uiState.value = actionReadyState }

                val assembled = assembleRecordedComparisonCatching(referenceAction to referenceActionMessage) {
                    composition.synchronizationPipeline.synchronize(
                        VideoSynchronizationRequest(
                            source = source,
                            reference = reference,
                            sampling = sampling,
                            model = model,
                        ),
                    )
                }
                val nextState = actionReadyState.copy(
                    synchronizationRun = assembled.synchronizationRun,
                    synchronizationFailure = assembled.synchronizationFailure,
                    referenceAction = assembled.actionResult.first,
                    referenceActionMessage = assembled.actionResult.second,
                )
                withContext(Dispatchers.Main) { _uiState.value = nextState }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failure = AnalysisFailure.Unexpected(
                    stage = PipelineStageId.MOTION_SOURCE,
                    exceptionType = error::class.qualifiedName ?: error.javaClass.name,
                    message = error.message ?: "Reference action analysis failed unexpectedly.",
                )
                withContext(Dispatchers.Main) { _uiState.value = AnalysisUiState.Failure(failure) }
            }
        }
    }

    fun resetAnalysis() {
        analysisJob?.cancel()
        referencePreparationJob?.cancel()
        ReferenceActionProfileStore.clear()
        _referenceProfileState.value = ReferenceProfileUiState.Empty
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

    private fun reprepareCurrentReference() {
        val selection = videoSelectionState.value
        val uri = selection.referenceUri ?: return
        val sha = selection.referenceSha256 ?: return
        scheduleReferencePreparation(uri, sha)
    }

    private fun scheduleReferencePreparation(uri: Uri, sha: Sha256) {
        referencePreparationJob?.cancel()
        ReferenceActionProfileStore.clear()
        val config = configState.value
        val sampling = samplingConfiguration(config)
        val model = modelConfiguration(config)
        _referenceProfileState.value = ReferenceProfileUiState.Preparing()
        referencePreparationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                delay(250L)
                val outcome = composition.referenceActionSession.prepareReference(
                    source = VideoSource(uri = uri.toString(), sha256 = sha),
                    sampling = sampling,
                    model = model,
                )
                val stillCurrent = videoSelectionState.value.referenceSha256 == sha &&
                    configState.value.targetFps == sampling.targetFramesPerSecond &&
                    configState.value.longEdgeCapPx == sampling.longEdgeCapPx
                if (!stillCurrent) return@launch
                when (outcome) {
                    is ReferencePreparationOutcome.Ready -> {
                        ReferenceActionProfileStore.set(
                            PreparedReferenceAction(
                                profile = outcome.profile,
                                referenceSha256 = sha.value,
                                analysisFramesPerSecond = sampling.targetFramesPerSecond.toDouble(),
                            ),
                        )
                        _referenceProfileState.value = ReferenceProfileUiState.Ready(
                            profile = outcome.profile,
                            referenceSha256 = sha,
                            analysisFramesPerSecond = sampling.targetFramesPerSecond.toDouble(),
                        )
                    }
                    is ReferencePreparationOutcome.Rejected -> {
                        ReferenceActionProfileStore.clear()
                        _referenceProfileState.value = ReferenceProfileUiState.Rejected(outcome.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val stillCurrent = videoSelectionState.value.referenceSha256 == sha
                if (stillCurrent) {
                    ReferenceActionProfileStore.clear()
                    _referenceProfileState.value = ReferenceProfileUiState.Rejected(
                        "The reference could not be prepared from reliable on-device pose evidence.",
                    )
                }
            }
        }
    }

    private fun modelConfiguration(config: ConfigurationState): PoseModelConfiguration = PoseModelConfiguration(
        modelSha256 = Sha256(MODEL_SHA256),
        modelVariant = config.modelVariant,
        thresholds = PoseThresholds(
            minimumDetectionConfidence = config.minimumConfidence,
            minimumPresenceConfidence = config.minimumConfidence,
            minimumTrackingConfidence = config.minimumConfidence,
        ),
    )

    private fun samplingConfiguration(config: ConfigurationState): SamplingConfiguration = SamplingConfiguration(
        targetFramesPerSecond = config.targetFps,
        longEdgeCapPx = config.longEdgeCapPx,
    )

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

        Sha256(digest.digest().joinToString("") { byte -> "%02x".format(byte) })
    }

    companion object {
        private const val MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
    }
}
