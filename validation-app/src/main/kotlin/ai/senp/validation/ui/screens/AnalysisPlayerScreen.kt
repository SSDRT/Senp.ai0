package ai.senp.validation.ui.screens

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.MotionUnitCorrespondence
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.SynchronizationResult
import ai.senp.core.contracts.SynchronizationStatus
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.motion.ActionTrackingStatus
import ai.senp.motion.PhaseTimingClass
import ai.senp.sync.v2.VideoSynchronizationRun
import ai.senp.validation.toReferenceCueLabel
import ai.senp.validation.R
import ai.senp.validation.ui.components.DiagnosticsBottomSheet
import ai.senp.validation.ui.state.AiFrameReviewUiState
import ai.senp.validation.ui.state.ReferenceActionAnalysisUi
import ai.senp.validation.ui.components.PoseLandmarkOverlay
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpBorder
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpError
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpPageBackdrop
import ai.senp.validation.ui.theme.glassBackground
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import ai.senp.validation.ui.theme.SenpSuccess
import ai.senp.validation.ui.theme.SenpWarning
import ai.senp.validation.ui.theme.SenpViolet
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.PI

private const val PLAY_NONE = 0
private const val PLAY_BOTH = 1
private const val PLAY_SOURCE = 2
private const val PLAY_REFERENCE = 3

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AnalysisPlayerScreen(
    sourceUri: Uri,
    referenceUri: Uri,
    sourcePoseExtraction: VideoPoseExtraction,
    referencePoseExtraction: VideoPoseExtraction,
    synchronizationRun: VideoSynchronizationRun?,
    synchronizationFailure: AnalysisFailure?,
    referenceAction: ReferenceActionAnalysisUi?,
    referenceActionMessage: String?,
    aiFrameReview: AiFrameReviewUiState,
    onRequestAiReview: () -> Unit,
    onSignInAndRequestAiReview: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sourcePlayer = remember(sourceUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUri))
            prepare()
        }
    }
    val referencePlayer = remember(referenceUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(referenceUri))
            prepare()
        }
    }
    DisposableEffect(sourcePlayer, referencePlayer) {
        onDispose {
            sourcePlayer.release()
            referencePlayer.release()
        }
    }

    var sourceAspectRatio by remember { mutableStateOf(9f / 16f) }
    var referenceAspectRatio by remember { mutableStateOf(9f / 16f) }
    var currentSourcePositionMs by remember { mutableLongStateOf(0L) }
    var currentReferencePositionMs by remember { mutableLongStateOf(0L) }
    var playbackMode by remember { mutableIntStateOf(PLAY_NONE) }
    var sourceSkeletonVisible by remember { mutableStateOf(true) }
    var referenceSkeletonVisible by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showRawData by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val synchronization = synchronizationRun?.synchronization?.result
    val sourcePoses = sourcePoseExtraction.poses
    val referencePoses = referencePoseExtraction.poses
    val playbackMapping = remember(synchronization) {
        synchronization?.playbackMapping() ?: PlaybackMapping(emptyList())
    }
    val totalDuration = sourcePoseExtraction.duration.value.coerceAtLeast(1L)
    val referenceDuration = referencePoseExtraction.duration.value.coerceAtLeast(1L)
    fun referencePositionForSource(positionMs: Long): Long =
        playbackMapping.sourceToReference(positionMs)
            ?: ((positionMs.toDouble() / totalDuration.toDouble()) * referenceDuration.toDouble()).toLong()
    val matchedUnitCount = synchronization?.correspondences?.count { it is MotionUnitCorrespondence.MatchedUnit } ?: 0
    val unmatchedUnitCount = synchronization?.correspondences?.count {
        it is MotionUnitCorrespondence.SourceUnmatchedUnit || it is MotionUnitCorrespondence.ReferenceUnmatchedUnit
    } ?: 0
    val matchingPercentage = synchronization?.diagnostics?.correspondenceConfidence
        ?: referenceAction?.recognition?.trackedFraction
        ?: 0.0
    val angleDifferenceDegrees = referenceAction?.deviations
        ?.filter { it.feature.startsWith("angle") }
        ?.let { deviations ->
            deviations.map { abs(it.signedDeltaOutsideRange) * 180.0 / PI * it.confidence }
                .average()
                .takeIf(Double::isFinite)
        }
    val synchronizationPending = synchronizationRun == null && synchronizationFailure == null
    val statusAccent = when {
        synchronizationPending -> SenpBlueBright
        synchronization?.status == SynchronizationStatus.SYNCHRONIZED -> SenpSuccess
        synchronization?.status == SynchronizationStatus.PARTIAL -> SenpWarning
        synchronization?.status == SynchronizationStatus.REFUSED -> SenpError
        else -> SenpWarning
    }
    val statusLabel = when {
        synchronizationPending -> "CHECKING"
        synchronization?.status == SynchronizationStatus.SYNCHRONIZED -> "FULL"
        synchronization?.status == SynchronizationStatus.PARTIAL -> "PARTIAL"
        synchronization?.status == SynchronizationStatus.REFUSED -> "REFUSED"
        else -> "OPTIONAL"
    }
    val liveSkeletonColor = when {
        synchronization?.status == SynchronizationStatus.REFUSED -> SenpError
        synchronizationPending -> SenpWarning
        else -> {
            val targetReference = referencePositionForSource(currentSourcePositionMs).coerceIn(0L, referenceDuration)
            val drift = abs(targetReference - currentReferencePositionMs)
            when {
                drift <= 260L -> SenpSuccess
                drift <= 800L -> SenpWarning
                else -> SenpError
            }
        }
    }
    val stackVideos = sourceAspectRatio >= 1.2f || referenceAspectRatio >= 1.2f
    DisposableEffect(sourcePlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                sourceAspectRatio = videoSize.displayAspectRatio()
            }
        }
        sourcePlayer.addListener(listener)
        onDispose { sourcePlayer.removeListener(listener) }
    }
    DisposableEffect(referencePlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                referenceAspectRatio = videoSize.displayAspectRatio()
            }
        }
        referencePlayer.addListener(listener)
        onDispose { referencePlayer.removeListener(listener) }
    }

    LaunchedEffect(playbackMode) {
        var playbackObserved = false
        try {
            while (playbackMode != PLAY_NONE) {
                playbackObserved = playbackObserved || sourcePlayer.isPlaying || referencePlayer.isPlaying
                val sourceEnded = sourcePlayer.playbackState == Player.STATE_ENDED
                val referenceEnded = referencePlayer.playbackState == Player.STATE_ENDED
                if (playbackObserved && ((playbackMode == PLAY_SOURCE && sourceEnded) ||
                    (playbackMode == PLAY_REFERENCE && referenceEnded) ||
                    (playbackMode == PLAY_BOTH && (sourceEnded || referenceEnded)))
                ) {
                    // Decelerate both transports briefly before pausing so the end never
                    // looks like a dropped frame or an abrupt render stall.
                    sourcePlayer.setPlaybackSpeed(0.62f)
                    referencePlayer.setPlaybackSpeed(0.62f)
                    delay(90L)
                    sourcePlayer.setPlaybackSpeed(0.34f)
                    referencePlayer.setPlaybackSpeed(0.34f)
                    delay(90L)
                    sourcePlayer.pause()
                    referencePlayer.pause()
                    playbackMode = PLAY_NONE
                    break
                }

                when (playbackMode) {
                    PLAY_BOTH -> {
                        currentSourcePositionMs = sourcePlayer.currentPosition.coerceAtLeast(0L)
                        val targetReference = referencePositionForSource(currentSourcePositionMs)
                            .coerceIn(0L, referenceDuration)
                        val referencePosition = referencePlayer.currentPosition.coerceAtLeast(0L)
                        val drift = abs(targetReference - referencePosition)
                        // Seek-only sync: avoid setPlaybackSpeed() stutter entirely.
                        // Both players always run at 1.0x; correct drift via seek only
                        // when it exceeds a noticeable threshold.
                        if (drift > 150L) {
                            referencePlayer.seekTo(targetReference)
                        }
                        currentReferencePositionMs = referencePlayer.currentPosition.coerceAtLeast(0L)
                    }
                    PLAY_SOURCE -> {
                        currentSourcePositionMs = sourcePlayer.currentPosition.coerceAtLeast(0L)
                    }
                    PLAY_REFERENCE -> {
                        currentReferencePositionMs = referencePlayer.currentPosition.coerceAtLeast(0L)
                    }
                }
                delay(48L)
            }
        } finally {
            sourcePlayer.setPlaybackSpeed(1.0f)
            referencePlayer.setPlaybackSpeed(1.0f)
        }
    }

    fun pauseAll() {
        sourcePlayer.pause()
        referencePlayer.pause()
        playbackMode = PLAY_NONE
    }

    fun start(mode: Int) {
        sourcePlayer.pause()
        referencePlayer.pause()
        playbackMode = mode
        when (mode) {
            PLAY_BOTH -> {
                sourcePlayer.seekTo(0L)
                referencePlayer.seekTo(referencePositionForSource(0L).coerceIn(0L, referenceDuration))
                currentSourcePositionMs = 0L
                currentReferencePositionMs = referencePlayer.currentPosition.coerceAtLeast(0L)
                sourcePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.setPlaybackSpeed(1.0f)
                sourcePlayer.play()
                referencePlayer.play()
            }
            PLAY_SOURCE -> {
                sourcePlayer.seekTo(0L)
                currentSourcePositionMs = 0L
                sourcePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.setPlaybackSpeed(1.0f)
                sourcePlayer.play()
                referencePlayer.pause()
            }
            PLAY_REFERENCE -> {
                referencePlayer.seekTo(0L)
                currentReferencePositionMs = 0L
                sourcePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.play()
            }
        }
    }

    fun toggle(mode: Int) {
        // Every transport press is an explicit replay from the beginning.
        start(mode)
    }

    LaunchedEffect(showRawData) {
        if (showRawData) pauseAll()
    }

    if (showRawData) {
        RawDataScreen(
            synchronization = synchronization,
            synchronizationFailure = synchronizationFailure,
            synchronizationPending = synchronizationPending,
            playbackMapping = playbackMapping,
            matchingPercentage = matchingPercentage,
            angleDifferenceDegrees = angleDifferenceDegrees,
            sourceFrameCount = sourcePoseExtraction.diagnostics.sampledFrameCount,
            referenceFrameCount = referencePoseExtraction.diagnostics.sampledFrameCount,
            matchedUnitCount = matchedUnitCount,
            unmatchedUnitCount = unmatchedUnitCount,
            onBack = { showRawData = false },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SenpPageBackdrop)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "←",
                    color = SenpCream,
                    fontSize = 10.sp,
                    modifier = Modifier.size(0.dp),
                )
                Button(
                    onClick = onBack,
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SenpSurfaceRaised, contentColor = SenpCream),
                    shape = RoundedCornerShape(9.dp),
                ) { Text("BACK", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp) }
                Column {
                    Text("ANALYSIS COMPLETE", color = SenpBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Movement comparison", color = SenpCream, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val syncConfidence = synchronization?.diagnostics?.overallConfidence
                    ?.let { "${(it * 100).toInt()}%" }
                    ?: "—"
                StatPill("SYNC", syncConfidence, SenpBlueBright, Modifier.weight(1f))
                StatPill("STATUS", statusLabel, statusAccent, Modifier.weight(1f))
                StatPill("MATCHES", playbackMapping.pointCount.toString(), SenpViolet, Modifier.weight(1f))
            }

            Spacer(Modifier.height(28.dp))
             Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                 Column {
                     Text("SIDE-BY-SIDE STUDY", color = SenpCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                 }
             }

            Spacer(Modifier.height(12.dp))
            if (stackVideos) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComparisonVideoTile(
                        title = "YOUR MOVEMENT",
                        subtitle = "Candidate",
                        player = sourcePlayer,
                         poseFrame = if (sourceSkeletonVisible) findMatchingPoseFrame(currentSourcePositionMs, sourcePoses) else null,
                         skeletonVisible = sourceSkeletonVisible,
                         onSkeletonChanged = { sourceSkeletonVisible = it },
                        videoAspectRatio = sourceAspectRatio,
                        accent = SenpViolet,
                        skeletonColor = liveSkeletonColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ComparisonVideoTile(
                        title = "REF. VIDEO",
                        subtitle = "Reference",
                        player = referencePlayer,
                         poseFrame = if (referenceSkeletonVisible) findMatchingPoseFrame(currentReferencePositionMs, referencePoses) else null,
                         skeletonVisible = referenceSkeletonVisible,
                         onSkeletonChanged = { referenceSkeletonVisible = it },
                        videoAspectRatio = referenceAspectRatio,
                        accent = SenpBlueBright,
                        skeletonColor = SenpCream,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ComparisonVideoTile(
                        title = "YOUR MOVEMENT",
                        subtitle = "Candidate",
                        player = sourcePlayer,
                         poseFrame = if (sourceSkeletonVisible) findMatchingPoseFrame(currentSourcePositionMs, sourcePoses) else null,
                         skeletonVisible = sourceSkeletonVisible,
                         onSkeletonChanged = { sourceSkeletonVisible = it },
                        videoAspectRatio = sourceAspectRatio,
                        accent = SenpViolet,
                        skeletonColor = liveSkeletonColor,
                         modifier = Modifier.weight(1f),
                    )
                    ComparisonVideoTile(
                        title = "REF. VIDEO",
                        subtitle = "Reference",
                        player = referencePlayer,
                         poseFrame = if (referenceSkeletonVisible) findMatchingPoseFrame(currentReferencePositionMs, referencePoses) else null,
                         skeletonVisible = referenceSkeletonVisible,
                         onSkeletonChanged = { referenceSkeletonVisible = it },
                        videoAspectRatio = referenceAspectRatio,
                        accent = SenpBlueBright,
                        skeletonColor = SenpCream,
                         modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HighlightedExtractions(
                matchingPercentage = matchingPercentage,
                angleDifferenceDegrees = angleDifferenceDegrees,
                frameCount = sourcePoseExtraction.diagnostics.sampledFrameCount + referencePoseExtraction.diagnostics.sampledFrameCount,
                onOpenRawData = { showRawData = true },
            )

            Spacer(Modifier.height(12.dp))
            AiFrameReviewSlot(
                state = aiFrameReview,
                onRequestReview = onRequestAiReview,
                onSignInAndReview = onSignInAndRequestAiReview,
            )

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth().glassBackground(RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, SenpBorder),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PLAYBACK CONTROL", color = SenpCream, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(
                            "${formatTime(currentSourcePositionMs)} · ${formatTime(currentReferencePositionMs)}",
                            color = SenpBlueBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Slider(
                        modifier = Modifier.height(24.dp),
                        value = currentTimelinePosition(playbackMode, currentSourcePositionMs, currentReferencePositionMs)
                            .toFloat()
                            .coerceIn(0f, activeTimelineDuration(playbackMode, totalDuration, referenceDuration).toFloat()),
                        onValueChange = { position ->
                            val selected = position.toLong()
                            if (playbackMode == PLAY_REFERENCE) {
                                currentReferencePositionMs = selected
                                referencePlayer.seekTo(selected)
                            } else {
                                currentSourcePositionMs = selected
                                sourcePlayer.seekTo(selected)
                                if (playbackMode == PLAY_BOTH) {
                                    currentReferencePositionMs = referencePositionForSource(selected).coerceIn(0L, referenceDuration)
                                    referencePlayer.seekTo(currentReferencePositionMs)
                                }
                            }
                        },
                        valueRange = 0f..activeTimelineDuration(playbackMode, totalDuration, referenceDuration).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = SenpBlueBright, activeTrackColor = SenpBlue),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaybackButton(
                            text = "PLAY BOTH",
                            active = playbackMode == PLAY_BOTH,
                            accent = SenpBlue,
                            modifier = Modifier.weight(1f),
                        ) { toggle(PLAY_BOTH) }
                        PlaybackButton("▶ YOU", playbackMode == PLAY_SOURCE, SenpViolet, Modifier.weight(1f)) { toggle(PLAY_SOURCE) }
                        PlaybackButton("▶ REFERENCE", playbackMode == PLAY_REFERENCE, SenpBlueBright, Modifier.weight(1f)) { toggle(PLAY_REFERENCE) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("AI SUGGESTIONS", color = SenpCream, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = SenpSurface),
                border = BorderStroke(1.dp, SenpBorder),
            ) {
                Text("Suggestions will appear here.", color = SenpMuted, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SenpSurfaceRaised, contentColor = SenpCream),
            ) { Text("NEW COMPARISON", fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.8.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiagnostics && synchronizationRun != null) {
        DiagnosticsBottomSheet(run = synchronizationRun, sheetState = sheetState, onDismiss = { showDiagnostics = false })
    }
}

@Composable
private fun HighlightedExtractions(
    matchingPercentage: Double,
    angleDifferenceDegrees: Double?,
    frameCount: Int,
    onOpenRawData: () -> Unit,
) {
    val matchColor = if (matchingPercentage >= 0.8) SenpSuccess else SenpError
    val angleColor = if ((angleDifferenceDegrees ?: 0.0) <= 10.0) SenpSuccess else SenpError
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("KEY EXTRACTIONS", color = SenpCream, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Button(
                onClick = onOpenRawData,
                modifier = Modifier.height(30.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp),
                shape = RoundedCornerShape(7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SenpSurfaceRaised, contentColor = SenpCream),
            ) { Text("RAW DATA", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp) }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ExtractionBadge("MOVEMENT MATCH", "${(matchingPercentage * 100).toInt()}%", matchColor, Modifier.weight(1f))
            ExtractionBadge("ANGLE", angleDifferenceDegrees?.let { "${formatDecimal(it)}°" } ?: "—", angleColor, Modifier.weight(1f))
            ExtractionBadge("FRAMES ANALYSED", frameCount.toString(), SenpCream, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExtractionBadge(label: String, value: String, accent: Color, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = SenpSurface), border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp)) {
            Text(label, color = SenpMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RawDataScreen(
    synchronization: SynchronizationResult?,
    synchronizationFailure: AnalysisFailure?,
    synchronizationPending: Boolean,
    playbackMapping: PlaybackMapping,
    matchingPercentage: Double,
    angleDifferenceDegrees: Double?,
    sourceFrameCount: Int,
    referenceFrameCount: Int,
    matchedUnitCount: Int,
    unmatchedUnitCount: Int,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SenpPageBackdrop).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("<", color = SenpCream, fontSize = 25.sp, modifier = Modifier.size(40.dp).clickable { onBack() })
            Column {
                Text("RAW DATA", color = SenpCream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("MEASURED RESULTS", color = SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        RawDataCard("KEY RESULTS") {
            RawValueRow("Movement match", "${(matchingPercentage * 100).toInt()}%", if (matchingPercentage >= 0.8) SenpSuccess else SenpError)
            RawValueRow("Average angle difference", angleDifferenceDegrees?.let { "${formatDecimal(it)} degrees" } ?: "Not available", if ((angleDifferenceDegrees ?: 0.0) <= 10.0) SenpSuccess else SenpError)
        }
        Spacer(Modifier.height(10.dp))
        RawDataCard("FRAME COUNTS") {
            RawValueRow("Your video frames", sourceFrameCount.toString(), SenpCream)
            RawValueRow("Master video frames", referenceFrameCount.toString(), SenpCream)
        }
        Spacer(Modifier.height(10.dp))
        RawDataCard("TIMING QUALITY") {
            RawValueRow("Sync status", when {
                synchronizationPending -> "CHECKING"
                synchronization == null -> "UNAVAILABLE"
                else -> synchronization.status.name
            }, if (synchronization?.status == SynchronizationStatus.SYNCHRONIZED) SenpSuccess else SenpCream)
            RawValueRow("Matched movement sections", matchedUnitCount.toString(), SenpCream)
            RawValueRow("Unmatched sections", unmatchedUnitCount.toString(), if (unmatchedUnitCount == 0) SenpSuccess else SenpError)
            RawValueRow("Playback alignment points", playbackMapping.pointCount.toString(), SenpCream)
            synchronization?.let {
                RawValueRow("Your video coverage", "${(it.diagnostics.sourceAnalyzableFraction * 100).toInt()}%", SenpCream)
                RawValueRow("Master video coverage", "${(it.diagnostics.referenceAnalyzableFraction * 100).toInt()}%", SenpCream)
            }
            synchronizationFailure?.let { RawValueRow("ERROR", it.message, SenpError) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RawDataCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), colors = CardDefaults.cardColors(containerColor = SenpSurface), border = BorderStroke(1.dp, SenpBorder)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun RawValueRow(label: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = SenpMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(value, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ReferenceActionAnalysisSlot(
    analysis: ReferenceActionAnalysisUi?,
    unavailableMessage: String?,
) {
    val recognition = analysis?.recognition
    val statusLabel = when (recognition?.finalStatus) {
        ActionTrackingStatus.COMPLETED -> "ACTION RECOGNIZED"
        ActionTrackingStatus.NO_ACTION -> "NO ACTION MATCH"
        ActionTrackingStatus.LOST, ActionTrackingStatus.POSSIBLE_ENTRY, ActionTrackingStatus.TRACKING -> "UNCERTAIN"
        null -> "REFERENCE ACTION UNAVAILABLE"
    }
    val accent = when (recognition?.finalStatus) {
        ActionTrackingStatus.COMPLETED -> SenpSuccess
        ActionTrackingStatus.NO_ACTION -> SenpWarning
        else -> SenpBlueBright
    }
    val persistent = analysis?.deviations
        ?.filter { it.persistenceCandidate }
        ?.distinctBy { it.stateId to it.feature }
        ?.sortedByDescending { it.normalizedDeviation * it.confidence }
        .orEmpty()
    val timing = recognition?.estimates
        ?.mapNotNull { it.timing }
        ?.lastOrNull { it.confidence >= 0.45 }
    Card(
        modifier = Modifier.fillMaxWidth().glassBackground(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("REFERENCE ACTION", color = SenpBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(statusLabel, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
            if (analysis == null || recognition == null) {
                Text(
                    unavailableMessage ?: "The reference did not produce enough reliable body-centric motion evidence for action comparison.",
                    color = SenpMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            Text(
                "Recognized independently of absolute clip start time. ${(recognition.trackedFraction * 100).toInt()}% of candidate samples were confidently tracked through the reference-derived state sequence.",
                color = SenpMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("STATES", analysis.profile.states.size.toString(), SenpBlueBright, Modifier.weight(1f))
                StatPill("REPS", "${recognition.completedRepetitions}/${analysis.profile.referenceRepetitions}", SenpViolet, Modifier.weight(1f))
                StatPill("TRACKED", "${(recognition.trackedFraction * 100).toInt()}%", accent, Modifier.weight(1f))
            }
            if (persistent.isNotEmpty()) {
                Text("PERSISTENT DIFFERENCES", color = SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 14.dp))
                persistent.take(2).forEach { deviation ->
                    Text(
                        "• ${deviation.toReferenceCueLabel()} · ${(deviation.confidence * 100).toInt()}% evidence",
                        color = SenpCream,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else if (recognition.finalStatus == ActionTrackingStatus.COMPLETED) {
                Text("No persistent reference-relative geometry difference cleared the confidence gates.", color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
            if (timing != null) {
                val timingLabel = when (timing.classification) {
                    PhaseTimingClass.FASTER -> "faster than this reference phase"
                    PhaseTimingClass.WITHIN_REFERENCE_RANGE -> "within this reference phase range"
                    PhaseTimingClass.SLOWER -> "slower than this reference phase"
                }
                Text("Latest phase timing: $timingLabel.", color = SenpMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
            }
            Text(
                "This is similarity to the selected demonstration, not a universal biomechanics or medical correctness score.",
                color = SenpMuted.copy(alpha = 0.82f),
                fontSize = 10.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun AiFrameReviewSlot(
    state: AiFrameReviewUiState,
    onRequestReview: () -> Unit,
    onSignInAndReview: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface.copy(alpha = 0.88f)),
        border = BorderStroke(1.dp, SenpViolet.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AI FRAME REVIEW", color = SenpViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("LUNA", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }

            when (state) {
                AiFrameReviewUiState.NotRequested -> Text(
                    "AI review is waiting for flagged frame evidence.",
                    color = SenpMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )

                is AiFrameReviewUiState.Unavailable -> Text(
                    state.message,
                    color = SenpMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )

                is AiFrameReviewUiState.RequiresSignIn -> {
                    Text(
                        "${state.frameCount} flagged frame${if (state.frameCount == 1) "" else "s"} are ready. Sign in with ChatGPT once to send them to Luna for a visual review.",
                        color = SenpCream,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                    Button(
                        onClick = onSignInAndReview,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SenpViolet, contentColor = Color.White),
                    ) { Text("SIGN IN & REVIEW", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }

                is AiFrameReviewUiState.SigningIn -> Text(
                    "Opening ChatGPT sign-in for ${state.frameCount} flagged frame${if (state.frameCount == 1) "" else "s"}…",
                    color = SenpBlueBright,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )

                is AiFrameReviewUiState.Reviewing -> Text(
                    "Sending ${state.frameCount} flagged frame${if (state.frameCount == 1) "" else "s"} to Luna and waiting for coaching…",
                    color = SenpBlueBright,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )

                is AiFrameReviewUiState.Success -> {
                    Text(
                        state.text,
                        color = SenpCream,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "${state.frameCount} flagged frame${if (state.frameCount == 1) "" else "s"} reviewed · Luna",
                        color = SenpMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    OutlinedButton(
                        onClick = onRequestReview,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(40.dp),
                        border = BorderStroke(1.dp, SenpViolet.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SenpViolet),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("REVIEW AGAIN", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }

                is AiFrameReviewUiState.Failure -> {
                    Text(
                        state.message,
                        color = SenpError,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                    Button(
                        onClick = if (state.requiresSignIn) onSignInAndReview else onRequestReview,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(42.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SenpSurfaceRaised, contentColor = SenpCream),
                    ) {
                        Text(if (state.requiresSignIn) "SIGN IN AGAIN" else "TRY REVIEW AGAIN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                "Only frames already flagged by the on-device reference-action detector are sent. Frame selection itself is unchanged.",
                color = SenpMuted.copy(alpha = 0.78f),
                fontSize = 9.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, accent: Color, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SenpSurface), border = BorderStroke(1.dp, SenpBorder)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(label, color = SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
            Text(value, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ComparisonVideoTile(
    title: String,
    subtitle: String,
    player: ExoPlayer,
    poseFrame: PoseFrame?,
    skeletonVisible: Boolean,
    onSkeletonChanged: (Boolean) -> Unit,
    videoAspectRatio: Float,
    accent: Color,
    skeletonColor: Color,
    modifier: Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.52f)),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(
                        title,
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(subtitle, color = SenpMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SKEL", color = SenpMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                    Switch(
                        checked = skeletonVisible,
                        onCheckedChange = onSkeletonChanged,
                        modifier = Modifier.padding(start = 1.dp).scale(0.68f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = SenpMuted,
                            uncheckedTrackColor = SenpSurfaceRaised,
                        ),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(videoAspectRatio.coerceIn(0.45f, 2.2f)).clip(RoundedCornerShape(11.dp)).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
                PoseLandmarkOverlay(
                    poseFrame = poseFrame,
                    videoAspectRatio = videoAspectRatio,
                    lineColor = skeletonColor,
                    jointColor = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PlaybackButton(
    text: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (active) accent else SenpSurfaceRaised, contentColor = if (active) Color.White else SenpMuted),
        shape = RoundedCornerShape(10.dp),
    ) { Text(if (active) "Ⅱ ${text.removePrefix("▶ ")}" else text, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ExtractCard(title: String, detail: String, value: String, accent: Color, extra: String? = null) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = SenpSurface), border = BorderStroke(1.dp, SenpBorder)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, color = SenpCream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = SenpMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                if (extra != null) Text(extra, color = SenpMuted.copy(alpha = 0.8f), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun VideoSize.displayAspectRatio(): Float {
    if (width <= 0 || height <= 0) return 9f / 16f
    val encodedAspect = (width.toFloat() * pixelWidthHeightRatio.coerceAtLeast(0.01f)) / height.toFloat()
    val displayAspect = if (unappliedRotationDegrees % 180 != 0) 1f / encodedAspect else encodedAspect
    return displayAspect.coerceIn(0.1f, 10f)
}

private fun currentTimelinePosition(mode: Int, sourceMs: Long, referenceMs: Long): Long =
    if (mode == PLAY_REFERENCE) referenceMs else sourceMs

private fun activeTimelineDuration(mode: Int, sourceDurationMs: Long, referenceDurationMs: Long): Long =
    if (mode == PLAY_REFERENCE) referenceDurationMs else sourceDurationMs

private fun findMatchingPoseFrame(timestampMs: Long, poses: PoseSequence?): PoseFrame? {
    if (poses == null || poses.frames.isEmpty()) return null
    return poses.frames.minByOrNull { abs(it.timestamp.value - timestampMs) }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}

private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.2f", value)
