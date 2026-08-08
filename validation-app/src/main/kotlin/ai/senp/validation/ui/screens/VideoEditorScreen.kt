package ai.senp.validation.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBackgroundRaised
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoEditorScreen(
    videoUri: Uri,
    onSave: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    // Video properties
    var videoDurationMs by remember { mutableLongStateOf(1000L) }
    var videoWidth by remember { mutableIntStateOf(1080) }
    var videoHeight by remember { mutableIntStateOf(1920) }

    // Editor state
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(1000L) }

    // Crop box state (normalized 0..1)
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(1f) }
    var cropBottom by remember { mutableFloatStateOf(1f) }

    // Player setup
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
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = player.duration
                    if (duration > 0 && trimEndMs <= 1000L) {
                        videoDurationMs = duration
                        trimEndMs = duration
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

    // Loop player within trim bounds
    LaunchedEffect(trimStartMs, trimEndMs) {
        while (true) {
            val currentPos = player.currentPosition
            if (currentPos < trimStartMs || currentPos > trimEndMs) {
                player.seekTo(trimStartMs)
            }
            delay(100)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EDIT YOUR CLIP", color = SenpCream, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text("TRIM · CROP · AWAKEN", color = SenpBlueBright, fontSize = 9.sp, letterSpacing = 1.1.sp)
                    }
                },
                navigationIcon = {
                    Text("×", color = SenpCream, fontSize = 30.sp, modifier = Modifier.padding(horizontal = 16.dp).clickable(enabled = !isExporting) { onCancel() })
                },
                actions = {
                    Text("SAVE", color = if (isExporting) SenpMuted else SenpBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(horizontal = 16.dp).clickable(enabled = !isExporting) {
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
                                onError = { e ->
                                    isExporting = false
                                    Toast.makeText(context, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SenpBackgroundRaised)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Brush.verticalGradient(listOf(Color(0xFF02050B), SenpBackground)))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Video Preview with Crop Overlay
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    // Lock aspect ratio so crop math matches perfectly
                    val aspect = if (videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 16f/9f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
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

                        // Crop Overlay
                        CropOverlay(
                            left = cropLeft,
                            top = cropTop,
                            right = cropRight,
                            bottom = cropBottom,
                            onCropChanged = { l, t, r, b ->
                                cropLeft = l
                                cropTop = t
                                cropRight = r
                                cropBottom = b
                            }
                        )
                    }
                }

                // Trim Controls
                Card(
                    colors = CardDefaults.cardColors(containerColor = SenpSurface),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("TRIM TIMELINE", fontWeight = FontWeight.Bold, color = SenpCream, fontSize = 13.sp, letterSpacing = 1.sp)

                        // Range Slider for trimming
                        RangeSlider(
                            value = (trimStartMs.toFloat())..(trimEndMs.toFloat()),
                            onValueChange = { range ->
                                trimStartMs = range.start.toLong()
                                trimEndMs = range.endInclusive.toLong()
                                player.seekTo(trimStartMs)
                            },
                            valueRange = 0f..videoDurationMs.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = SenpBlueBright,
                                activeTrackColor = SenpBlue
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("START  ${trimStartMs}ms", fontSize = 11.sp, color = SenpMuted)
                            Text("END  ${trimEndMs}ms", fontSize = 11.sp, color = SenpMuted)
                        }
                    }
                }
            }

            // Exporting Overlay
            if (isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xE6050A16)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SenpBlueBright)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Exporting Video... ${String.format("%.0f", exportProgress)}%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Please wait, applying crop and trim.",
                            color = SenpMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CropOverlay(
    left: Float, top: Float, right: Float, bottom: Float,
    onCropChanged: (Float, Float, Float, Float) -> Unit
) {
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (canvasSize.width == 0f) return@detectDragGestures

                    val dx = dragAmount.x / canvasSize.width
                    val dy = dragAmount.y / canvasSize.height

                    val touchX = change.position.x / canvasSize.width
                    val touchY = change.position.y / canvasSize.height

                    // Simple drag logic: if touching near center, translate.
                    // To keep it robust, we'll just implement a center pan for now,
                    // and allow dragging edges to resize.

                    val margin = 0.15f
                    val isLeftEdge = abs(touchX - left) < margin
                    val isRightEdge = abs(touchX - right) < margin
                    val isTopEdge = abs(touchY - top) < margin
                    val isBottomEdge = abs(touchY - bottom) < margin

                    var newL = left
                    var newT = top
                    var newR = right
                    var newB = bottom

                    if (isLeftEdge && !isRightEdge) newL = (newL + dx).coerceIn(0f, newR - 0.1f)
                    if (isRightEdge && !isLeftEdge) newR = (newR + dx).coerceIn(newL + 0.1f, 1f)
                    if (isTopEdge && !isBottomEdge) newT = (newT + dy).coerceIn(0f, newB - 0.1f)
                    if (isBottomEdge && !isTopEdge) newB = (newB + dy).coerceIn(newT + 0.1f, 1f)

                    // If touching center, translate the whole box
                    if (!isLeftEdge && !isRightEdge && !isTopEdge && !isBottomEdge) {
                        val boxW = right - left
                        val boxH = bottom - top
                        newL = (left + dx).coerceIn(0f, 1f - boxW)
                        newR = newL + boxW
                        newT = (top + dy).coerceIn(0f, 1f - boxH)
                        newB = newT + boxH
                    }

                    onCropChanged(newL, newT, newR, newB)
                }
            }
    ) {
        canvasSize = size
        val rect = Rect(
            left = left * size.width,
            top = top * size.height,
            right = right * size.width,
            bottom = bottom * size.height
        )

        // Draw scrim outside crop box
        val path = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
            addRect(rect)
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
        drawPath(path, color = Color.Black.copy(alpha = 0.6f))

        // Draw bounding box
        drawRect(
            color = SenpBlueBright,
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = 6f)
        )
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
    onError: (Exception) -> Unit
) = withContext(Dispatchers.Main) {
    try {
        val outDir = File(context.cacheDir, "editor_videos")
        outDir.mkdirs()
        val outFile = File(outDir, "cropped_${UUID.randomUUID()}.mp4")

        // 1. Clipping (Trim)
        val clippingConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(trimStartMs)
            .setEndPositionMs(trimEndMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clippingConfig)
            .build()

        // 2. Crop Effect
        // Crop coordinates in Media3 are -1 to 1 (center is 0,0)
        // Our coordinates are 0 to 1 (top-left is 0,0)
        val m3Left = (cropLeft * 2f) - 1f
        val m3Right = (cropRight * 2f) - 1f
        // Media3 Y is flipped: top is 1, bottom is -1
        val m3Top = 1f - (cropTop * 2f)
        val m3Bottom = 1f - (cropBottom * 2f)

        val cropEffect = Crop(m3Left, m3Right, m3Bottom, m3Top)

        // Force output to 16:9 vertical by presentation? No, just keep the aspect of the crop for now,
        // or actually Presentation.createForHeight(1280) could normalize it.
        // We will just use Crop effect.
        val effects = Effects(
            listOf(),
            listOf(cropEffect)
        )

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(effects)
            .build()

        var transformer: Transformer? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                onSuccess(Uri.fromFile(outFile))
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                onError(exportException)
            }
        }

        val encoderFactory = androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .build()

        transformer = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory)
            .addListener(listener)
            .build()

        transformer.start(editedMediaItem, outFile.absolutePath)

        // Poll progress
        while (true) {
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            val state = transformer.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(progressHolder.progress.toFloat())
            } else if (state == Transformer.PROGRESS_STATE_UNAVAILABLE || state == Transformer.PROGRESS_STATE_NOT_STARTED) {
                // finished or error
                break
            }
            delay(500)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // Let coroutine cancellation propagate naturally
    } catch (e: Exception) {
        onError(e)
    }
}
