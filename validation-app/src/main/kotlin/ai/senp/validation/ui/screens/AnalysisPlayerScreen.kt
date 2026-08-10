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
import ai.senp.validation.ui.components.DiagnosticsBottomSheet
import ai.senp.validation.ui.state.AiFrameReviewUiState
import ai.senp.validation.ui.state.ReferenceActionAnalysisUi
import ai.senp.validation.ui.components.PoseLandmarkOverlay
import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpBorder
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpError
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import ai.senp.validation.ui.theme.SenpSuccess
import ai.senp.validation.ui.theme.SenpWarning
import ai.senp.validation.ui.theme.SenpViolet
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private const val PLAY_NONE = 0
private const val PLAY_BOTH = 1
private const val PLAY_SOURCE = 2
private const val PLAY_REFERENCE = 3

private sealed interface RenderedComparisonUiState {
    data object WaitingForSynchronization : RenderedComparisonUiState
    data class Rendering(val progressPercent: Int) : RenderedComparisonUiState
    data class Ready(val file: File) : RenderedComparisonUiState
    data class Unavailable(val reason: String) : RenderedComparisonUiState
    data class Failed(val reason: String) : RenderedComparisonUiState
}

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
    var skeletonVisible by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val synchronization = synchronizationRun?.synchronization?.result
    val sourcePoses = sourcePoseExtraction.poses
    val referencePoses = referencePoseExtraction.poses
    val playbackMapping = remember(synchronization) {
        synchronization?.playbackMapping() ?: PlaybackMapping(emptyList())
    }
    val totalDuration = sourcePoseExtraction.duration.value.coerceAtLeast(1L)
    val referenceDuration = referencePoseExtraction.duration.value.coerceAtLeast(1L)
    val matchedUnitCount = synchronization?.correspondences?.count { it is MotionUnitCorrespondence.MatchedUnit } ?: 0
    val unmatchedUnitCount = synchronization?.correspondences?.count {
        it is MotionUnitCorrespondence.SourceUnmatchedUnit || it is MotionUnitCorrespondence.ReferenceUnmatchedUnit
    } ?: 0
    val synchronizationPending = synchronizationRun == null && synchronizationFailure == null
    val renderedPlan = remember(playbackMapping) { playbackMapping.renderedComparisonPlan() }
    val renderedExporter = remember(context) { RenderedComparisonExporter(context.applicationContext) }
    var renderedState by remember(sourceUri, referenceUri) {
        mutableStateOf<RenderedComparisonUiState>(RenderedComparisonUiState.WaitingForSynchronization)
    }

    LaunchedEffect(synchronizationRun, synchronizationFailure, renderedPlan, sourceUri, referenceUri) {
        when {
            synchronizationPending -> renderedState = RenderedComparisonUiState.WaitingForSynchronization
            synchronization == null -> renderedState = RenderedComparisonUiState.Unavailable(
                synchronizationFailure?.message ?: "Synchronized playback could not be prepared.",
            )
            synchronization.status == SynchronizationStatus.REFUSED -> renderedState = RenderedComparisonUiState.Unavailable(
                "The clips do not have enough trusted correspondence for a locked comparison.",
            )
            renderedPlan.segments.isEmpty() -> renderedState = RenderedComparisonUiState.Unavailable(
                "No continuous trusted playback span was available to render.",
            )
            else -> {
                renderedState = RenderedComparisonUiState.Rendering(0)
                try {
                    val file = renderedExporter.export(
                        sourceUri = sourceUri,
                        referenceUri = referenceUri,
                        sourcePoses = sourcePoses,
                        referencePoses = referencePoses,
                        plan = renderedPlan,
                        onProgress = { progress -> renderedState = RenderedComparisonUiState.Rendering(progress) },
                    )
                    renderedState = RenderedComparisonUiState.Ready(file)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    renderedState = RenderedComparisonUiState.Failed(
                        error.message?.take(160) ?: "Unable to render synchronized playback on this device.",
                    )
                }
            }
        }
    }

    val statusAccent = when {
        synchronizationPending -> SenpBlueBright
        synchronization?.status == SynchronizationStatus.SYNCHRONIZED -> SenpSuccess
        synchronization?.status == SynchronizationStatus.PARTIAL -> SenpWarning
        synchronization?.status == SynchronizationStatus.REFUSED -> SenpError
        else -> SenpWarning
    }
    val statusLabel = when {
        synchronizationPending -> "PENDING"
        synchronization?.status == SynchronizationStatus.SYNCHRONIZED -> "FULL"
        synchronization?.status == SynchronizationStatus.PARTIAL -> "PARTIAL"
        synchronization?.status == SynchronizationStatus.REFUSED -> "REFUSED"
        else -> "OPTIONAL"
    }

    val renderedFile = (renderedState as? RenderedComparisonUiState.Ready)?.file
    val renderedPlayer = remember(renderedFile) {
        renderedFile?.let { file ->
            ExoPlayer.Builder(context).build().apply {
                setSeekParameters(SeekParameters.EXACT)
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }
    }
    var renderedAspectRatio by remember(renderedFile) { mutableStateOf(16f / 9f) }
    var renderedPositionMs by remember(renderedFile) { mutableLongStateOf(0L) }
    var renderedDurationMs by remember(renderedFile) { mutableLongStateOf(renderedPlan.durationMs.coerceAtLeast(1L)) }
    var renderedPlaying by remember(renderedFile) { mutableStateOf(false) }
    DisposableEffect(renderedPlayer) {
        val player = renderedPlayer
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    renderedAspectRatio = videoSize.displayAspectRatio()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    renderedPlaying = isPlaying
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
    }
    LaunchedEffect(renderedPlayer) {
        val player = renderedPlayer ?: return@LaunchedEffect
        while (true) {
            renderedPositionMs = player.currentPosition.coerceAtLeast(0L)
            player.duration.takeIf { it > 0L }?.let { renderedDurationMs = it }
            renderedPlaying = player.isPlaying
            delay(32L)
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
                    sourcePlayer.pause()
                    referencePlayer.pause()
                    playbackMode = PLAY_NONE
                    break
                }

                when (playbackMode) {
                    PLAY_BOTH -> {
                        currentSourcePositionMs = sourcePlayer.currentPosition.coerceAtLeast(0L)
                        val mappedTarget = playbackMapping.sourceToReference(currentSourcePositionMs)
                        if (mappedTarget == null) {
                            sourcePlayer.pause()
                            referencePlayer.pause()
                            playbackMode = PLAY_NONE
                            break
                        }
                        val target = mappedTarget.coerceIn(0L, referenceDuration)
                        val referencePosition = referencePlayer.currentPosition.coerceAtLeast(0L)
                        val driftMs = target - referencePosition

                        // Let the reference catch up smoothly; seek only for a genuinely large drift.
                        if (abs(driftMs) > 360L) referencePlayer.seekTo(target)
                        referencePlayer.setPlaybackSpeed(
                            when {
                                driftMs > 90L -> 1.14f
                                driftMs < -90L -> 0.88f
                                else -> 1.0f
                            },
                        )
                        currentReferencePositionMs = referencePlayer.currentPosition.coerceAtLeast(0L)
                    }
                    PLAY_SOURCE -> {
                        currentSourcePositionMs = sourcePlayer.currentPosition.coerceAtLeast(0L)
                    }
                    PLAY_REFERENCE -> {
                        currentReferencePositionMs = referencePlayer.currentPosition.coerceAtLeast(0L)
                    }
                }
                delay(32L)
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
        if (mode == PLAY_BOTH && !playbackMapping.supportsSource(currentSourcePositionMs)) {
            playbackMode = PLAY_NONE
            return
        }
        playbackMode = mode
        when (mode) {
            PLAY_BOTH -> {
                val target = playbackMapping.sourceToReference(currentSourcePositionMs)
                    ?.coerceIn(0L, referenceDuration)
                    ?: return
                currentReferencePositionMs = target
                sourcePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.seekTo(target)
                sourcePlayer.play()
                referencePlayer.play()
            }
            PLAY_SOURCE -> {
                if (sourcePlayer.playbackState == Player.STATE_ENDED) {
                    sourcePlayer.seekTo(0L)
                    currentSourcePositionMs = 0L
                }
                sourcePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.setPlaybackSpeed(1.0f)
                sourcePlayer.play()
                referencePlayer.pause()
            }
            PLAY_REFERENCE -> {
                sourcePlayer.pause()
                if (referencePlayer.playbackState == Player.STATE_ENDED) {
                    referencePlayer.seekTo(0L)
                    currentReferencePositionMs = 0L
                }
                sourcePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.setPlaybackSpeed(1.0f)
                referencePlayer.play()
            }
        }
    }

    fun toggle(mode: Int) {
        if (playbackMode == mode) pauseAll() else start(mode)
    }

    BackHandler(onBack = onReset)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF06172F), SenpBackground, Color(0xFF100D25))))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ANALYSIS COMPLETE", color = SenpBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                OutlinedButton(
                    onClick = onReset,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp),
                    border = BorderStroke(1.dp, SenpBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SenpCream),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("CHANGE VIDEOS", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Text("Movement comparison", color = SenpCream, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))

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
            when (val state = renderedState) {
                is RenderedComparisonUiState.Ready -> {
                    val player = renderedPlayer
                    if (player != null) {
                        RenderedComparisonReadyPanel(
                            player = player,
                            videoAspectRatio = renderedAspectRatio,
                            positionMs = renderedPositionMs,
                            durationMs = renderedDurationMs.coerceAtLeast(1L),
                            isPlaying = renderedPlaying,
                            segmentCount = renderedPlan.segments.size,
                            onSeek = { selected ->
                                val target = selected.coerceIn(0L, renderedDurationMs.coerceAtLeast(1L))
                                renderedPositionMs = target
                                player.seekTo(target)
                            },
                            onTogglePlayback = {
                                if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    if (player.playbackState == Player.STATE_ENDED || renderedPositionMs >= renderedDurationMs - 40L) {
                                        player.seekTo(0L)
                                        renderedPositionMs = 0L
                                    }
                                    player.play()
                                }
                            },
                        )
                    } else {
                        RenderedComparisonPreparingPanel(
                            title = "OPENING LOCKED PLAYBACK",
                            detail = "The synchronized render is ready. Opening the single playback timeline…",
                        )
                    }
                }
                is RenderedComparisonUiState.Rendering -> RenderedComparisonPreparingPanel(
                    title = "RENDERING LOCKED PLAYBACK",
                    detail = "Baking both videos and both skeletons onto one trusted timeline. Unmatched gaps are removed from this preview.",
                    progressPercent = state.progressPercent,
                )
                RenderedComparisonUiState.WaitingForSynchronization -> RenderedComparisonPreparingPanel(
                    title = "PREPARING LOCKED PLAYBACK",
                    detail = "Finishing timestamp correspondence before the side-by-side preview is rendered.",
                )
                is RenderedComparisonUiState.Unavailable -> {
                    RenderedComparisonFallbackNotice(state.reason)
                    Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text("SIDE-BY-SIDE STUDY", color = SenpCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Swipe sideways to see each full clip", color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SKELETON", color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = skeletonVisible,
                            onCheckedChange = { skeletonVisible = it },
                            modifier = Modifier.padding(start = 5.dp),
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SenpBlue, uncheckedThumbColor = SenpMuted, uncheckedTrackColor = SenpSurfaceRaised),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (stackVideos) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ComparisonVideoTile(
                            title = "YOUR MOVEMENT",
                            subtitle = "Candidate",
                            player = sourcePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentSourcePositionMs, sourcePoses) else null,
                            videoAspectRatio = sourceAspectRatio,
                            accent = SenpViolet,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ComparisonVideoTile(
                            title = "REFERENCE MOVEMENT",
                            subtitle = "Reference",
                            player = referencePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentReferencePositionMs, referencePoses) else null,
                            videoAspectRatio = referenceAspectRatio,
                            accent = SenpBlueBright,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ComparisonVideoTile(
                            title = "YOUR MOVEMENT",
                            subtitle = "Candidate",
                            player = sourcePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentSourcePositionMs, sourcePoses) else null,
                            videoAspectRatio = sourceAspectRatio,
                            accent = SenpViolet,
                            modifier = Modifier.width(184.dp),
                        )
                        ComparisonVideoTile(
                            title = "REFERENCE MOVEMENT",
                            subtitle = "Reference",
                            player = referencePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentReferencePositionMs, referencePoses) else null,
                            videoAspectRatio = referenceAspectRatio,
                            accent = SenpBlueBright,
                            modifier = Modifier.width(184.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = SenpSurface),
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
                            value = currentTimelinePosition(playbackMode, currentSourcePositionMs, currentReferencePositionMs)
                                .toFloat()
                                .coerceIn(0f, activeTimelineDuration(playbackMode, totalDuration, referenceDuration).toFloat()),
                            onValueChange = { position ->
                                val selected = position.toLong()
                                if (playbackMode == PLAY_REFERENCE) {
                                    currentReferencePositionMs = selected
                                    referencePlayer.seekTo(selected)
                                    val mappedSource = playbackMapping.referenceToSource(selected)
                                    if (mappedSource != null) {
                                        currentSourcePositionMs = mappedSource
                                        sourcePlayer.seekTo(mappedSource)
                                    }
                                } else {
                                    currentSourcePositionMs = selected
                                    sourcePlayer.seekTo(selected)
                                    val mappedReference = playbackMapping.sourceToReference(selected)
                                    if (mappedReference != null) {
                                        currentReferencePositionMs = mappedReference
                                        referencePlayer.seekTo(mappedReference)
                                    } else if (playbackMode == PLAY_BOTH) {
                                        pauseAll()
                                    }
                                }
                            },
                            valueRange = 0f..activeTimelineDuration(playbackMode, totalDuration, referenceDuration).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = SenpBlueBright, activeTrackColor = SenpBlue),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlaybackButton(
                                text = when {
                                    playbackMapping.pointCount == 0 -> "NO SYNC"
                                    playbackMapping.supportsSource(currentSourcePositionMs) -> "▶ BOTH"
                                    else -> "NO MATCH HERE"
                                },
                                active = playbackMode == PLAY_BOTH,
                                accent = SenpBlue,
                                modifier = Modifier.weight(1f),
                                enabled = playbackMapping.supportsSource(currentSourcePositionMs),
                            ) { toggle(PLAY_BOTH) }
                            PlaybackButton("▶ YOU", playbackMode == PLAY_SOURCE, SenpViolet, Modifier.weight(1f)) { toggle(PLAY_SOURCE) }
                            PlaybackButton("▶ REFERENCE", playbackMode == PLAY_REFERENCE, SenpBlueBright, Modifier.weight(1f)) { toggle(PLAY_REFERENCE) }
                        }
                    }
                }
                }
                is RenderedComparisonUiState.Failed -> {
                    RenderedComparisonFallbackNotice(state.reason)
                    Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text("SIDE-BY-SIDE STUDY", color = SenpCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Swipe sideways to see each full clip", color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SKELETON", color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = skeletonVisible,
                            onCheckedChange = { skeletonVisible = it },
                            modifier = Modifier.padding(start = 5.dp),
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SenpBlue, uncheckedThumbColor = SenpMuted, uncheckedTrackColor = SenpSurfaceRaised),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (stackVideos) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ComparisonVideoTile(
                            title = "YOUR MOVEMENT",
                            subtitle = "Candidate",
                            player = sourcePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentSourcePositionMs, sourcePoses) else null,
                            videoAspectRatio = sourceAspectRatio,
                            accent = SenpViolet,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ComparisonVideoTile(
                            title = "REFERENCE MOVEMENT",
                            subtitle = "Reference",
                            player = referencePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentReferencePositionMs, referencePoses) else null,
                            videoAspectRatio = referenceAspectRatio,
                            accent = SenpBlueBright,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ComparisonVideoTile(
                            title = "YOUR MOVEMENT",
                            subtitle = "Candidate",
                            player = sourcePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentSourcePositionMs, sourcePoses) else null,
                            videoAspectRatio = sourceAspectRatio,
                            accent = SenpViolet,
                            modifier = Modifier.width(184.dp),
                        )
                        ComparisonVideoTile(
                            title = "REFERENCE MOVEMENT",
                            subtitle = "Reference",
                            player = referencePlayer,
                            poseFrame = if (skeletonVisible) findMatchingPoseFrame(currentReferencePositionMs, referencePoses) else null,
                            videoAspectRatio = referenceAspectRatio,
                            accent = SenpBlueBright,
                            modifier = Modifier.width(184.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = SenpSurface),
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
                            value = currentTimelinePosition(playbackMode, currentSourcePositionMs, currentReferencePositionMs)
                                .toFloat()
                                .coerceIn(0f, activeTimelineDuration(playbackMode, totalDuration, referenceDuration).toFloat()),
                            onValueChange = { position ->
                                val selected = position.toLong()
                                if (playbackMode == PLAY_REFERENCE) {
                                    currentReferencePositionMs = selected
                                    referencePlayer.seekTo(selected)
                                    val mappedSource = playbackMapping.referenceToSource(selected)
                                    if (mappedSource != null) {
                                        currentSourcePositionMs = mappedSource
                                        sourcePlayer.seekTo(mappedSource)
                                    }
                                } else {
                                    currentSourcePositionMs = selected
                                    sourcePlayer.seekTo(selected)
                                    val mappedReference = playbackMapping.sourceToReference(selected)
                                    if (mappedReference != null) {
                                        currentReferencePositionMs = mappedReference
                                        referencePlayer.seekTo(mappedReference)
                                    } else if (playbackMode == PLAY_BOTH) {
                                        pauseAll()
                                    }
                                }
                            },
                            valueRange = 0f..activeTimelineDuration(playbackMode, totalDuration, referenceDuration).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = SenpBlueBright, activeTrackColor = SenpBlue),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlaybackButton(
                                text = when {
                                    playbackMapping.pointCount == 0 -> "NO SYNC"
                                    playbackMapping.supportsSource(currentSourcePositionMs) -> "▶ BOTH"
                                    else -> "NO MATCH HERE"
                                },
                                active = playbackMode == PLAY_BOTH,
                                accent = SenpBlue,
                                modifier = Modifier.weight(1f),
                                enabled = playbackMapping.supportsSource(currentSourcePositionMs),
                            ) { toggle(PLAY_BOTH) }
                            PlaybackButton("▶ YOU", playbackMode == PLAY_SOURCE, SenpViolet, Modifier.weight(1f)) { toggle(PLAY_SOURCE) }
                            PlaybackButton("▶ REFERENCE", playbackMode == PLAY_REFERENCE, SenpBlueBright, Modifier.weight(1f)) { toggle(PLAY_REFERENCE) }
                        }
                    }
                }
                }
            }
            Spacer(Modifier.height(12.dp))
            AiFrameReviewSlot(
                state = aiFrameReview,
                onRequestReview = onRequestAiReview,
                onSignInAndReview = onSignInAndRequestAiReview,
            )

            Spacer(Modifier.height(12.dp))
            ReferenceActionAnalysisSlot(referenceAction, referenceActionMessage)

            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SYNC DECISIONS", color = SenpBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (synchronizationRun != null) {
                    OutlinedButton(
                        onClick = { showDiagnostics = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                        border = BorderStroke(1.dp, SenpBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SenpBlueBright),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("EXTRACTS", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text("Correspondence and refusal decisions from Synchronization Kernel v2", color = SenpMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(11.dp))
            when {
                synchronizationPending -> {
                    ExtractCard(
                        title = "CHECKING OPTIONAL CORRESPONDENCE",
                        detail = "Reference-action recognition is already available while Sync-v2 checks whether synchronized playback can be supported.",
                        value = "ACTION READY",
                        accent = SenpBlueBright,
                        extra = "The action result above does not depend on whole-video alignment.",
                    )
                }
                synchronization == null -> {
                    ExtractCard(
                        title = "CORRESPONDENCE UNAVAILABLE",
                        detail = synchronizationFailure?.message
                            ?: "Sync-v2 correspondence was unavailable, but reference-action recognition completed independently.",
                        value = "OPTIONAL",
                        accent = SenpWarning,
                        extra = "The action result above does not depend on whole-video alignment.",
                    )
                }
                synchronization.status == SynchronizationStatus.REFUSED -> {
                    ExtractCard(
                        title = "CORRESPONDENCE REFUSED",
                        detail = synchronization.refusal?.message ?: "The available motion evidence was not reliable enough to synchronize.",
                        value = synchronization.refusal?.reason?.name ?: "REFUSED",
                        accent = SenpError,
                        extra = "Reference-action recognition remains independent of this correspondence decision.",
                    )
                }
                else -> {
                    ExtractCard(
                        title = "MATCHED MOTION",
                        detail = "$matchedUnitCount matched units · $unmatchedUnitCount unmatched units · ${playbackMapping.pointCount} timestamp decisions",
                        value = "${(synchronization.diagnostics.correspondenceConfidence * 100).toInt()}%",
                        accent = statusAccent,
                        extra = "Source coverage ${(synchronization.diagnostics.sourceAnalyzableFraction * 100).toInt()}% · Reference ${(synchronization.diagnostics.referenceAnalyzableFraction * 100).toInt()}%",
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SenpSurface.copy(alpha = 0.76f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("SYNC TRACE", color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(synchronization?.status?.name ?: "UNAVAILABLE", color = statusAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${playbackMapping.pointCount} mapped points", color = SenpCream, fontSize = 13.sp)
                        Text("${sourcePoseExtraction.diagnostics.sampledFrameCount} + ${referencePoseExtraction.diagnostics.sampledFrameCount} frames", color = SenpMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
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

@OptIn(UnstableApi::class)
@Composable
private fun RenderedComparisonReadyPanel(
    player: ExoPlayer,
    videoAspectRatio: Float,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    segmentCount: Int,
    onSeek: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("LOCKED COMPARISON", color = SenpCream, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Video and skeleton share one rendered timeline", color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("READY", color = SenpSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(1.dp, SenpBorder),
        ) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(videoAspectRatio.coerceIn(0.7f, 2.4f))) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            Modifier.padding(10.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.68f)).padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Text("YOUR MOVEMENT", color = SenpViolet, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                        Box(
                            Modifier.padding(10.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.68f)).padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Text("REFERENCE", color = SenpBlueBright, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..durationMs.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = SenpBlueBright, activeTrackColor = SenpBlue),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                            color = SenpMuted,
                            fontSize = 10.sp,
                        )
                        Text(
                            "$segmentCount trusted span${if (segmentCount == 1) "" else "s"}",
                            color = SenpMuted,
                            fontSize = 10.sp,
                        )
                    }
                    Button(
                        onClick = onTogglePlayback,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) SenpSurfaceRaised else SenpBlue,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(if (isPlaying) "Ⅱ  PAUSE" else "▶  PLAY LOCKED COMPARISON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Unmatched gaps are skipped instead of freezing or inventing correspondence.",
                        color = SenpMuted.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderedComparisonPreparingPanel(
    title: String,
    detail: String,
    progressPercent: Int? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface),
        border = BorderStroke(1.dp, SenpBlue.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, color = SenpBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(detail, color = SenpCream, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            if (progressPercent != null) {
                LinearProgressIndicator(
                    progress = { progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(5.dp).clip(RoundedCornerShape(4.dp)),
                    color = SenpBlue,
                    trackColor = SenpSurfaceRaised,
                )
                Text("$progressPercent%", color = SenpMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun RenderedComparisonFallbackNotice(reason: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, SenpWarning.copy(alpha = 0.65f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("LOCKED PLAYBACK UNAVAILABLE", color = SenpWarning, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(reason, color = SenpMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Independent clip playback is shown below.", color = SenpCream, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        }
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface.copy(alpha = 0.76f)),
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
    videoAspectRatio: Float,
    accent: Color,
    modifier: Modifier,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.Black), border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(videoAspectRatio.coerceIn(0.45f, 2.2f))) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                PoseLandmarkOverlay(
                    poseFrame = poseFrame,
                    videoAspectRatio = videoAspectRatio,
                    lineColor = accent,
                    jointColor = Color.White,
                )
                Box(Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 9.dp, vertical = 6.dp)) {
                    Column {
                        Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
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
