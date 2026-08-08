package ai.senp.validation.ui.screens

import ai.senp.core.contracts.AlignmentPoint
import ai.senp.core.contracts.AnalysisResult
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseSequence
import ai.senp.validation.ui.components.DiagnosticsBottomSheet
import ai.senp.validation.ui.components.PoseLandmarkOverlay
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AnalysisPlayerScreen(
    result: AnalysisResult,
    sourcePoses: PoseSequence?,
    referencePoses: PoseSequence?,
    sourceUri: Uri,
    referenceUri: Uri,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Setup ExoPlayer instances
    val sourcePlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUri))
            prepare()
        }
    }

    val referencePlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(referenceUri))
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sourcePlayer.release()
            referencePlayer.release()
        }
    }

    // Track video aspect ratios from the actual decoded video
    var sourceAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var refAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(sourcePlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    sourceAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }
        }
        sourcePlayer.addListener(listener)
        onDispose { sourcePlayer.removeListener(listener) }
    }

    DisposableEffect(referencePlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    refAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }
        }
        referencePlayer.addListener(listener)
        onDispose { referencePlayer.removeListener(listener) }
    }

    var currentSourcePositionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    val totalSourceDurationMs = result.payload.sourceDuration.value.coerceAtLeast(1L)

    // Synchronize reference video playback position based on DTW alignment
    val alignmentPoints = result.payload.alignment.points
    val mappedRefMs = remember(currentSourcePositionMs) {
        mapSourceMsToRefMs(currentSourcePositionMs, alignmentPoints)
    }

    // Live position tracking loop
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val pos = sourcePlayer.currentPosition
            currentSourcePositionMs = pos
            val targetRefPos = mapSourceMsToRefMs(pos, alignmentPoints)
            if (kotlin.math.abs(referencePlayer.currentPosition - targetRefPos) > 150) {
                referencePlayer.seekTo(targetRefPos)
            }
            delay(33) // ~30 FPS UI refresh rate
        }
    }

    // Match PoseFrames for current timestamp
    val currentSourceFrame = remember(currentSourcePositionMs, sourcePoses) {
        findMatchingPoseFrame(currentSourcePositionMs, sourcePoses)
    }
    val currentRefFrame = remember(mappedRefMs, referencePoses) {
        findMatchingPoseFrame(mappedRefMs, referencePoses)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Motion Comparison",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    OutlinedButton(
                        onClick = { showDiagnosticsSheet = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Diagnostics", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // User Candidate Player Box — uses actual video aspect ratio
            VideoPlayerContainer(
                title = "User Candidate",
                player = sourcePlayer,
                poseFrame = currentSourceFrame,
                videoAspectRatio = sourceAspectRatio,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )

            // Reference Player Box — uses actual video aspect ratio
            VideoPlayerContainer(
                title = "Reference Form",
                player = referencePlayer,
                poseFrame = currentRefFrame,
                videoAspectRatio = refAspectRatio,
                accentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth()
            )

            // Synchronized DTW Scrubber Controls
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DTW Synced Scrubber",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${currentSourcePositionMs}ms / ${totalSourceDurationMs}ms",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Slider(
                        value = currentSourcePositionMs.toFloat().coerceIn(0f, totalSourceDurationMs.toFloat()),
                        onValueChange = { newPos ->
                            currentSourcePositionMs = newPos.toLong()
                            sourcePlayer.seekTo(newPos.toLong())
                            val refPos = mapSourceMsToRefMs(newPos.toLong(), alignmentPoints)
                            referencePlayer.seekTo(refPos)
                        },
                        valueRange = 0f..totalSourceDurationMs.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                if (isPlaying) {
                                    sourcePlayer.pause()
                                    referencePlayer.pause()
                                    isPlaying = false
                                } else {
                                    sourcePlayer.play()
                                    referencePlayer.play()
                                    isPlaying = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(if (isPlaying) "Pause" else "Play Both")
                        }

                        Button(
                            onClick = onReset,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("New Video")
                        }
                    }
                }
            }
        }
    }

    if (showDiagnosticsSheet) {
        DiagnosticsBottomSheet(
            result = result,
            sheetState = sheetState,
            onDismiss = { showDiagnosticsSheet = false }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerContainer(
    title: String,
    player: ExoPlayer,
    poseFrame: PoseFrame?,
    videoAspectRatio: Float,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        // Lock the box to the video's native aspect ratio so there is zero letterboxing
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(videoAspectRatio)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Pose skeleton overlay — uses the same aspect ratio so it maps 1:1
            PoseLandmarkOverlay(
                poseFrame = poseFrame,
                videoAspectRatio = videoAspectRatio,
                lineColor = accentColor,
                jointColor = Color.White
            )

            // Title badge overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = title,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun mapSourceMsToRefMs(sourceMs: Long, points: List<AlignmentPoint>): Long {
    if (points.isEmpty()) return sourceMs
    val closest = points.minByOrNull { kotlin.math.abs(it.sourceTimestamp.value - sourceMs) }
    return closest?.referenceTimestamp?.value ?: sourceMs
}

private fun findMatchingPoseFrame(timestampMs: Long, poses: PoseSequence?): PoseFrame? {
    if (poses == null || poses.frames.isEmpty()) return null
    return poses.frames.minByOrNull { kotlin.math.abs(it.timestamp.value - timestampMs) }
}
