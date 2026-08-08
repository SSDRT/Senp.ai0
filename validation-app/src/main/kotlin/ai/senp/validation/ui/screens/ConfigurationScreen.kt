package ai.senp.validation.ui.screens

import ai.senp.validation.ui.SenpEngineViewModel
import ai.senp.validation.ui.state.ConfigurationState
import ai.senp.validation.ui.state.VideoSelectionState
import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBackgroundRaised
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpBorder
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import ai.senp.validation.ui.theme.SenpViolet
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.UUID

private val SenpBackdrop = Brush.verticalGradient(
    0f to Color(0xFF06142A),
    0.48f to SenpBackground,
    1f to Color(0xFF100D25),
)

private val SenpAccent = Brush.horizontalGradient(listOf(SenpBlue, SenpViolet))

private enum class VideoSlot { MASTER, USER }

private data class EditorRequest(val slot: VideoSlot, val uri: Uri)

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun ConfigurationScreen(
    viewModel: SenpEngineViewModel,
    onStartAnalysis: () -> Unit,
) {
    val context = LocalContext.current
    val selectionState by viewModel.videoSelectionState.collectAsState()
    val configState by viewModel.configState.collectAsState()

    var editorRequest by remember { mutableStateOf<EditorRequest?>(null) }
    var pendingGallerySlot by remember { mutableStateOf<VideoSlot?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun openEditor(slot: VideoSlot, uri: Uri) {
        editorRequest = EditorRequest(slot, uri)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val slot = pendingGallerySlot ?: VideoSlot.USER
            pendingGallerySlot = null
            openEditor(slot, uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success) pendingCameraUri?.let {
            openEditor(VideoSlot.USER, it)
            pendingCameraUri = null
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera(context, cameraLauncher) { pendingCameraUri = it }
    }

    fun chooseFromGallery(slot: VideoSlot) {
        pendingGallerySlot = slot
        galleryLauncher.launch(arrayOf("video/*"))
    }

    fun chooseCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera(context, cameraLauncher) { pendingCameraUri = it }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val request = editorRequest
    if (request != null) {
        VideoEditorScreen(
            videoUri = request.uri,
            onSave = { editedUri ->
                when (request.slot) {
                    VideoSlot.MASTER -> viewModel.onSelectReferenceVideo(editedUri)
                    VideoSlot.USER -> viewModel.onSelectSourceVideo(editedUri)
                }
                editorRequest = null
            },
            onCancel = { editorRequest = null },
        )
        return
    }

    WorkspaceScreen(
        selectionState = selectionState,
        configState = configState,
        showSettings = showSettings,
        onToggleSettings = { showSettings = !showSettings },
        onPickMaster = { chooseFromGallery(VideoSlot.MASTER) },
        onPickUser = { chooseFromGallery(VideoSlot.USER) },
        onRecordUser = { chooseCamera() },
        onEditMaster = { selectionState.referenceUri?.let { openEditor(VideoSlot.MASTER, it) } },
        onEditUser = { selectionState.sourceUri?.let { openEditor(VideoSlot.USER, it) } },
        onUpdateFps = viewModel::updateTargetFps,
        onUpdateResolution = viewModel::updateLongEdgeCap,
        onStartAnalysis = onStartAnalysis,
    )
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SenpBackdrop)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(54.dp))
            Text(
                text = "Senp.ai",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(SenpCream, Color.White)),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp,
                ),
            )
            Text(
                text = "MOTION ANALYSIS",
                color = SenpCream.copy(alpha = 0.88f),
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )

            Spacer(Modifier.weight(1f))
            CrossedMark()
            Spacer(Modifier.height(28.dp))
            Text(
                text = "AWAKEN",
                style = TextStyle(
                    brush = SenpAccent,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 10.sp,
                ),
            )
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(70.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(SenpBlue),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Embark on your quest for evolution.\nCompare your form and transform your fitness life.",
                color = SenpCream.copy(alpha = 0.88f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            GradientButton(
                text = "CONTINUE",
                enabled = true,
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CrossedMark() {
    Canvas(Modifier.size(width = 180.dp, height = 190.dp)) {
        val cream = SenpCream
        val blue = SenpBlueBright
        val center = Offset(size.width / 2f, size.height / 2f)
        fun sword(angle: Float) {
            rotate(angle, center) {
                drawLine(cream, Offset(center.x, center.y - 70f), Offset(center.x, center.y + 68f), 9f, StrokeCap.Round)
                drawLine(blue, Offset(center.x - 11f, center.y - 58f), Offset(center.x - 11f, center.y + 48f), 5f, StrokeCap.Round)
                drawLine(cream, Offset(center.x - 28f, center.y + 20f), Offset(center.x + 28f, center.y + 20f), 8f, StrokeCap.Round)
                drawCircle(blue, 10f, Offset(center.x, center.y + 74f))
                drawLine(cream, Offset(center.x, center.y - 70f), Offset(center.x - 20f, center.y - 48f), 6f, StrokeCap.Round)
                drawLine(cream, Offset(center.x, center.y - 70f), Offset(center.x + 20f, center.y - 48f), 6f, StrokeCap.Round)
            }
        }
        sword(-38f)
        sword(38f)
    }
}

@Composable
private fun ClassSelectionScreen(
    onClassSelected: (String) -> Unit,
    onContinue: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SenpBackdrop)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 40.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("×", color = SenpCream, fontSize = 32.sp, modifier = Modifier.clickable { })
                Text("◎", color = SenpCream, fontSize = 30.sp)
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(SenpBlue))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(7f).height(4.dp).clip(CircleShape).background(SenpSurfaceRaised))
            }
            Text("1/16", color = SenpBlueBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 28.dp))
            Spacer(Modifier.height(46.dp))
            Text("Choose your class", color = SenpCream, fontSize = 29.sp, fontWeight = FontWeight.Medium)
            Text("Which goal would you most like to achieve?", color = SenpMuted, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(54.dp))

            listOf(
                "WARRIOR" to "Muscle strengthening",
                "ASSASSIN" to "Weight loss",
                "MAGE" to "Stay in shape",
            ).forEach { (title, subtitle) ->
                val isSelected = selected == title
                ClassCard(
                    title = title,
                    subtitle = subtitle,
                    selected = isSelected,
                    onClick = {
                        selected = title
                        onClassSelected(title)
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.weight(1f))
            GradientButton(
                text = "NEXT",
                enabled = selected != null,
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ClassCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(104.dp).clickable { onClick() },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) SenpBackgroundRaised else SenpSurface),
        border = BorderStroke(1.dp, if (selected) SenpBlue else SenpBorder),
    ) {
        Column(Modifier.padding(horizontal = 28.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(title, color = SenpCream, fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
            Text(subtitle, color = SenpMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun WorkspaceScreen(
    selectionState: VideoSelectionState,
    configState: ConfigurationState,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onPickMaster: () -> Unit,
    onPickUser: () -> Unit,
    onRecordUser: () -> Unit,
    onEditMaster: () -> Unit,
    onEditUser: () -> Unit,
    onUpdateFps: (Int) -> Unit,
    onUpdateResolution: (Int) -> Unit,
    onStartAnalysis: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SenpBackdrop)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Senp.ai", color = SenpCream, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                Text("MOTION ANALYSIS", color = SenpMuted, fontSize = 8.sp, letterSpacing = 1.2.sp)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(SenpSurfaceRaised).border(1.dp, SenpBorder, CircleShape), contentAlignment = Alignment.Center) {
                Text("S", color = SenpBlueBright, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(30.dp))
        Text("AWAKEN YOUR FORM", color = SenpCream, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Text("Build a side-by-side study of your movement.", color = SenpMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 7.dp))

        Spacer(Modifier.height(30.dp))
        Eyebrow("01  MASTER VIDEO", "Your perfect form reference")
        Spacer(Modifier.height(10.dp))
        if (selectionState.referenceUri == null) {
            UploadPanel(
                title = "Add the master movement",
                subtitle = "Upload a clean reference video",
                action = "UPLOAD MASTER",
                onClick = onPickMaster,
            )
        } else {
            VideoPreviewCard(
                label = "MASTER FORM",
                uri = selectionState.referenceUri,
                accent = SenpBlueBright,
                onEdit = onEditMaster,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(26.dp))
        Eyebrow("02  YOUR VIDEO", "Record now or bring a take from your gallery")
        Spacer(Modifier.height(10.dp))
        if (selectionState.sourceUri == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionPanel("UPLOAD", "From gallery", "↑", onPickUser, Modifier.weight(1f))
                ActionPanel("RECORD", "On the spot", "●", onRecordUser, Modifier.weight(1f))
            }
        } else {
            VideoPreviewCard(
                label = "YOUR FORM",
                uri = selectionState.sourceUri,
                accent = SenpViolet,
                onEdit = onEditUser,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(26.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, SenpBorder),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onToggleSettings() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("ANALYSIS SETTINGS", color = SenpCream, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Offline pose tracking · MediaPipe 33 landmarks", color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(if (showSettings) "−" else "+", color = SenpBlueBright, fontSize = 25.sp)
                }
                if (showSettings) {
                    HorizontalDivider(color = SenpBorder, modifier = Modifier.padding(vertical = 14.dp))
                    Text("Sampling · ${configState.targetFps} FPS", color = SenpMuted, fontSize = 12.sp)
                    Slider(
                        value = configState.targetFps.toFloat(),
                        onValueChange = { onUpdateFps(it.toInt()) },
                        valueRange = 10f..30f,
                        steps = 3,
                        colors = SliderDefaults.colors(thumbColor = SenpBlueBright, activeTrackColor = SenpBlue),
                    )
                    Text("Resolution cap · ${configState.longEdgeCapPx}px", color = SenpMuted, fontSize = 12.sp)
                    Slider(
                        value = configState.longEdgeCapPx.toFloat(),
                        onValueChange = { onUpdateResolution(it.toInt()) },
                        valueRange = 360f..1080f,
                        steps = 3,
                        colors = SliderDefaults.colors(thumbColor = SenpViolet, activeTrackColor = SenpViolet),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        GradientButton(
            text = if (selectionState.isCalculatingHash) "PREPARING CLIPS…" else "ANALYSE MOVEMENT  →",
            enabled = selectionState.isReadyForAnalysis && !selectionState.isCalculatingHash,
            onClick = onStartAnalysis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (selectionState.errorMessage != null) {
            Text(
                text = selectionState.errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        Text("Your videos stay on this device.", color = SenpMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp))
    }
}

@Composable
private fun Eyebrow(title: String, subtitle: String) {
    Column {
        Text(title, color = SenpBlueBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Text(subtitle, color = SenpMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun UploadPanel(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(132.dp).clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x4412182C)),
        border = BorderStroke(1.dp, SenpBorder),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(SenpBackgroundRaised), contentAlignment = Alignment.Center) {
                Text("+", color = SenpBlueBright, fontSize = 28.sp, fontWeight = FontWeight.Light)
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title, color = SenpCream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(action, color = SenpBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }
    }
}

@Composable
private fun ActionPanel(title: String, subtitle: String, symbol: String, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.height(116.dp).clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface),
        border = BorderStroke(1.dp, SenpBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(symbol, color = SenpBlueBright, fontSize = 25.sp, fontWeight = FontWeight.Light)
            Column {
                Text(title, color = SenpCream, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Text(subtitle, color = SenpMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreviewCard(
    label: String,
    uri: Uri,
    accent: Color,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    var isPlaying by remember(uri) { mutableStateOf(false) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 18.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(if (compact) 142.dp else 206.dp).clickable {
                    if (isPlaying) player.pause() else player.play()
                    isPlaying = !isPlaying
                },
            ) {
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
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                Box(Modifier.align(Alignment.Center).size(if (compact) 38.dp else 48.dp).clip(CircleShape).background(accent.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
                    Text(if (isPlaying) "Ⅱ" else "▶", color = Color.White, fontSize = if (isPlaying) 14.sp else 17.sp)
                }
                Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (isPlaying) "Playing preview" else "Ready to edit", color = SenpMuted, fontSize = 11.sp)
                Button(
                    onClick = onEdit,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.18f), contentColor = accent),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("EDIT", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun GradientButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) SenpAccent else Brush.linearGradient(listOf(SenpSurfaceRaised, SenpSurfaceRaised)))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else SenpMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

private fun launchCamera(context: Context, launcher: androidx.activity.result.ActivityResultLauncher<Uri>, onUriReady: (Uri) -> Unit) {
    val directory = File(context.cacheDir, "camera_videos").apply { mkdirs() }
    val output = File(directory, "capture_${UUID.randomUUID()}.mp4")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    onUriReady(uri)
    launcher.launch(uri)
}
