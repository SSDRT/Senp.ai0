package ai.senp.validation.ui

import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseThresholds
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.VideoSource
import ai.senp.sync.v2.VideoSynchronizationOutcome
import ai.senp.sync.v2.VideoSynchronizationRequest
import ai.senp.validation.EngineComposition
import ai.senp.validation.ui.state.AnalysisUiState
import ai.senp.validation.ui.state.ConfigurationState
import ai.senp.validation.ui.state.VideoSelectionState
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SenpEngineViewModel(application: Application) : AndroidViewModel(application) {
    private val composition = EngineComposition(application)

    private val _videoSelectionState = MutableStateFlow(VideoSelectionState())
    val videoSelectionState: StateFlow<VideoSelectionState> = _videoSelectionState.asStateFlow()

    private val _configState = MutableStateFlow(ConfigurationState())
    val configState: StateFlow<ConfigurationState> = _configState.asStateFlow()

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    fun onSelectSourceVideo(uri: Uri) {
        calculateVideoHash(uri, isSource = true)
    }

    fun onSelectReferenceVideo(uri: Uri) {
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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
        val selection = videoSelectionState.value
        val config = configState.value
        val sourceUri = selection.sourceUri ?: return
        val referenceUri = selection.referenceUri ?: return
        val sourceSha = selection.sourceSha256 ?: return
        val referenceSha = selection.referenceSha256 ?: return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = AnalysisUiState.Analyzing(
                activeStage = PipelineStageId.VIDEO_POSE_SOURCE,
                progressPercent = 0.15f,
                statusMessage = "Extracting pose and synchronizing motion...",
            )

            val model = PoseModelConfiguration(
                modelSha256 = Sha256(MODEL_SHA256),
                modelVariant = config.modelVariant,
                thresholds = PoseThresholds(
                    minimumDetectionConfidence = config.minimumConfidence,
                    minimumPresenceConfidence = config.minimumConfidence,
                    minimumTrackingConfidence = config.minimumConfidence,
                ),
            )
            val request = VideoSynchronizationRequest(
                source = VideoSource(uri = sourceUri.toString(), sha256 = sourceSha),
                reference = VideoSource(uri = referenceUri.toString(), sha256 = referenceSha),
                sampling = SamplingConfiguration(
                    targetFramesPerSecond = config.targetFps,
                    longEdgeCapPx = config.longEdgeCapPx,
                ),
                model = model,
            )

            val outcome = composition.synchronizationPipeline.synchronize(request)
            withContext(Dispatchers.Main) {
                _uiState.value = when (outcome) {
                    is VideoSynchronizationOutcome.Success -> AnalysisUiState.Success(
                        run = outcome.run,
                        sourceUri = sourceUri,
                        referenceUri = referenceUri,
                    )
                    is VideoSynchronizationOutcome.Failure -> AnalysisUiState.Failure(outcome.failure)
                }
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

        Sha256(digest.digest().joinToString("") { byte -> "%02x".format(byte) })
    }

    companion object {
        private const val MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"

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
