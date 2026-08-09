package ai.senp.validation.ui.screens

import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBackgroundRaised
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpPageBackdrop
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ai.senp.validation.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs

private enum class EditorMode { TRIM, CROP }

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(
    videoUri: Uri,
    onSave: (Uri) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var editorMode by rememberSaveable { mutableStateOf(EditorMode.TRIM) }
    var videoDurationMs by remember { mutableLongStateOf(1000L) }
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(1000L) }
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(1f) }
    var cropBottom by remember { mutableFloatStateOf(1f) }
    var cropZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }

    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            play()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = Unit

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = player.duration
                    if (duration > 0) {
                        videoDurationMs = duration
                        if (trimEndMs <= 1000L) trimEndMs = duration
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(trimStartMs, trimEndMs) {
        while (true) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            if (currentPositionMs < trimStartMs || currentPositionMs > trimEndMs) {
                player.seekTo(trimStartMs)
            }
            delay(80L)
        }
    }

    Scaffold(
        containerColor = SenpBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SenpBackgroundRaised.copy(alpha = 0.94f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "X",
                    color = if (isExporting) SenpMuted else SenpCream,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .size(42.dp)
                        .clickable(enabled = !isExporting) { onCancel() },
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Edit Your Clip", color = SenpCream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("TRIM  ·  CROP", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                Button(
                    onClick = {
                        isExporting = true
                        coroutineScope.launch {
                            exportVideo(
                                context = context,
                                uri = videoUri,
                                trimStartMs = trimStartMs,
                                trimEndMs = trimEndMs,
                                cropLeft = cropLeft,
                                cropTop = cropTop,
                                cropRight = cropRight,
                                cropBottom = cropBottom,
                                onProgress = { exportProgress = it },
                                onSuccess = { outUri ->
                                    isExporting = false
                                    onSave(outUri)
                                },
                                onError = { error ->
                                    isExporting = false
                                    Toast.makeText(context, "Could not save clip: ${error.message}", Toast.LENGTH_LONG).show()
                                },
                            )
                        }
                    },
                    enabled = !isExporting,
                    modifier = Modifier.height(42.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SenpBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("SAVE", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SenpPageBackdrop),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                                this.player = player
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (editorMode == EditorMode.CROP) {
                        CropOverlay(
                            left = cropLeft,
                            top = cropTop,
                            right = cropRight,
                            bottom = cropBottom,
                            onCropChanged = { left, top, right, bottom ->
                                cropLeft = left
                                cropTop = top
                                cropRight = right
                                cropBottom = bottom
                            },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(SenpBlue.copy(alpha = 0.86f))
                            .clickable {
                                if (player.isPlaying) {
                                    player.pause()
                                    isPlaying = false
                                } else {
                                    player.play()
                                    isPlaying = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (isPlaying) "Ⅱ" else "▶", color = Color.White, fontSize = 18.sp) }
                }

                if (editorMode == EditorMode.TRIM) EditorTimeline(
                    durationMs = videoDurationMs,
                    positionMs = currentPositionMs,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    onRangeChange = { range ->
                        trimStartMs = range.start.toLong()
                        trimEndMs = range.endInclusive.toLong().coerceAtLeast(trimStartMs + 100L)
                        player.seekTo(trimStartMs)
                    },
                    onStep = { delta ->
                        trimEndMs = (trimEndMs + delta).coerceIn(trimStartMs + 100L, videoDurationMs)
                        player.seekTo(trimStartMs)
                    },
                    onPlay = {
                        if (player.isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            player.play()
                            isPlaying = true
                        }
                    },
                    onReset = {
                        trimStartMs = 0L
                        trimEndMs = videoDurationMs
                        cropLeft = 0f
                        cropTop = 0f
                        cropRight = 1f
                        cropBottom = 1f
                        cropZoom = 1f
                        player.seekTo(0L)
                    },
                )
                if (editorMode == EditorMode.CROP) {
                    CropControls(
                        zoom = cropZoom,
                        onZoom = { zoom ->
                            cropZoom = zoom
                            val visible = (1f / zoom).coerceIn(0.1f, 1f)
                            val inset = (1f - visible) / 2f
                            cropLeft = inset
                            cropTop = inset
                            cropRight = 1f - inset
                            cropBottom = 1f - inset
                        },
                        onReset = {
                            cropZoom = 1f
                            cropLeft = 0f
                            cropTop = 0f
                            cropRight = 1f
                            cropBottom = 1f
                        },
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditorTab("CROP", editorMode == EditorMode.CROP, Modifier.weight(1f)) { editorMode = EditorMode.CROP }
                    EditorTab("TRIM", editorMode == EditorMode.TRIM, Modifier.weight(1f)) { editorMode = EditorMode.TRIM }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isExporting) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xE608090C)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(progress = { exportProgress / 100f }, color = SenpBlueBright)
                        Spacer(Modifier.height(14.dp))
                        Text("Saving clip ${(exportProgress).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CropControls(
    zoom: Float,
    onZoom: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xCC11141A)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("CROP FRAME", color = SenpCream, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Text("${String.format(java.util.Locale.US, "%.1f", zoom)}x", color = SenpBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = zoom,
            onValueChange = onZoom,
            valueRange = 1f..2.5f,
            colors = SliderDefaults.colors(thumbColor = SenpBlueBright, activeTrackColor = SenpBlue, inactiveTrackColor = SenpSurfaceRaised),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Drag the frame to reposition", color = SenpMuted, fontSize = 10.sp)
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(SenpSurfaceRaised).clickable { onReset() }.padding(horizontal = 11.dp, vertical = 7.dp),
            ) { Text("RESET", color = SenpCream, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun EditorTimeline(
    durationMs: Long,
    positionMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onStep: (Long) -> Unit,
    onPlay: () -> Unit,
    onReset: () -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1000L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xCC111318))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TRIM TIMELINE", color = SenpCream, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(formatEditorTime(positionMs), color = SenpBlueBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        ThumbnailStrip(
            startFraction = trimStartMs.toFloat() / safeDuration,
            endFraction = trimEndMs.toFloat() / safeDuration,
            positionFraction = positionMs.toFloat() / safeDuration,
        )
        RangeSlider(
            value = trimStartMs.toFloat()..trimEndMs.toFloat(),
            onValueChange = onRangeChange,
            valueRange = 0f..safeDuration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = SenpBlueBright,
                activeTrackColor = SenpBlue,
                inactiveTrackColor = SenpSurfaceRaised,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("START  ${formatEditorTime(trimStartMs)}", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("END  ${formatEditorTime(trimEndMs)}", color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TimelineButton("−", Modifier.weight(1f)) { onStep(-100L) }
            Text("0.1s", color = SenpMuted, fontSize = 11.sp, modifier = Modifier.width(42.dp))
            TimelineButton("+", Modifier.weight(1f)) { onStep(100L) }
            TimelineButton(if (positionMs in trimStartMs..trimEndMs) "▶" else "▶", Modifier.weight(1f), accent = SenpBlue) { onPlay() }
            TimelineButton("↻", Modifier.weight(1.2f), accent = SenpSurfaceRaised) { onReset() }
        }
    }
}

@Composable
private fun ThumbnailStrip(startFraction: Float, endFraction: Float, positionFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF262A31)),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(12) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF52565D).copy(alpha = 0.82f - index * 0.02f),
                                    Color(0xFF17191E),
                                ),
                            ),
                        ),
                )
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth((endFraction - startFraction).coerceIn(0.02f, 1f))
                .padding(start = 0.dp)
                .background(SenpBlue.copy(alpha = 0.22f)),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(positionFraction.coerceIn(0.01f, 1f))
                .padding(end = 1.dp),
        ) {
            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxSize().background(Color.White))
        }
    }
}

@Composable
private fun TimelineButton(text: String, modifier: Modifier, accent: Color = SenpSurfaceRaised, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent == SenpBlue) Color.White else SenpCream, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun EditorTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) SenpBlue.copy(alpha = 0.22f) else Color(0x661A1D22))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) SenpBlueBright else SenpMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
fun CropOverlay(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    onCropChanged: (Float, Float, Float, Float) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(left, top, right, bottom) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (canvasSize.width == 0f) return@detectDragGestures
                    val dx = dragAmount.x / canvasSize.width
                    val dy = dragAmount.y / canvasSize.height
                    val touchX = change.position.x / canvasSize.width
                    val touchY = change.position.y / canvasSize.height
                    val edge = 0.14f
                    var newLeft = left
                    var newTop = top
                    var newRight = right
                    var newBottom = bottom
                    val leftEdge = abs(touchX - left) < edge
                    val rightEdge = abs(touchX - right) < edge
                    val topEdge = abs(touchY - top) < edge
                    val bottomEdge = abs(touchY - bottom) < edge
                    if (leftEdge && !rightEdge) newLeft = (left + dx).coerceIn(0f, right - 0.1f)
                    if (rightEdge && !leftEdge) newRight = (right + dx).coerceIn(left + 0.1f, 1f)
                    if (topEdge && !bottomEdge) newTop = (top + dy).coerceIn(0f, bottom - 0.1f)
                    if (bottomEdge && !topEdge) newBottom = (bottom + dy).coerceIn(top + 0.1f, 1f)
                    if (!leftEdge && !rightEdge && !topEdge && !bottomEdge) {
                        val width = right - left
                        val height = bottom - top
                        newLeft = (left + dx).coerceIn(0f, 1f - width)
                        newRight = newLeft + width
                        newTop = (top + dy).coerceIn(0f, 1f - height)
                        newBottom = newTop + height
                    }
                    onCropChanged(newLeft, newTop, newRight, newBottom)
                }
            },
    ) {
        canvasSize = size
        val cropRect = Rect(left * size.width, top * size.height, right * size.width, bottom * size.height)
        val scrim = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
            addRect(cropRect)
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.58f))
        drawRect(SenpBlueBright, cropRect.topLeft, cropRect.size, style = Stroke(width = 5f))
    }
}

@OptIn(UnstableApi::class)
private suspend fun exportVideo(
    context: Context,
    uri: Uri,
    trimStartMs: Long,
    trimEndMs: Long,
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float,
    onProgress: (Float) -> Unit,
    onSuccess: (Uri) -> Unit,
    onError: (Exception) -> Unit,
) = withContext(Dispatchers.Main) {
    try {
        val outDir = File(context.cacheDir, "editor_videos").apply { mkdirs() }
        val outFile = File(outDir, "edited_${UUID.randomUUID()}.mp4")
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trimStartMs)
            .setEndPositionMs(trimEndMs)
            .build()
        val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
        val crop = Crop(
            (cropLeft * 2f) - 1f,
            (cropRight * 2f) - 1f,
            1f - (cropBottom * 2f),
            1f - (cropTop * 2f),
        )
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(crop)))
            .build()
        val transformer = Transformer.Builder(context)
            .setEncoderFactory(androidx.media3.transformer.DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = onSuccess(Uri.fromFile(outFile))
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) = onError(exportException)
            })
            .build()
        transformer.start(edited, outFile.absolutePath)
        while (true) {
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            when (transformer.getProgress(progressHolder)) {
                Transformer.PROGRESS_STATE_AVAILABLE -> onProgress(progressHolder.progress.toFloat())
                Transformer.PROGRESS_STATE_UNAVAILABLE, Transformer.PROGRESS_STATE_NOT_STARTED -> break
            }
            delay(250L)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onError(error)
    }
}

private fun formatEditorTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%02d:%02d.%02d".format(totalSeconds / 60L, totalSeconds % 60L, (milliseconds % 1000L) / 10L)
}
