package ai.senp.validation.ui.screens

import ai.senp.validation.R
import ai.senp.validation.ui.SenpEngineViewModel
import ai.senp.validation.ui.state.ConfigurationState
import ai.senp.validation.ui.state.ReferenceProfileUiState
import ai.senp.validation.ui.state.VideoSelectionState
import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBackgroundRaised
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpBorder
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpPageBackdrop
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private enum class VideoSlot { MASTER, USER }
private data class EditorRequest(val slot: VideoSlot, val uri: Uri)

@OptIn(UnstableApi::class)
@Composable
fun ConfigurationScreen(
    viewModel: SenpEngineViewModel,
    onStartAnalysis: () -> Unit,
    onOpenLiveCoach: () -> Unit,
) {
    val context = LocalContext.current
    val selectionState by viewModel.videoSelectionState.collectAsState()
    val configState by viewModel.configState.collectAsState()
    val referenceProfileState by viewModel.referenceProfileState.collectAsState()
    var editorRequest by remember { mutableStateOf<EditorRequest?>(null) }
    var pendingGallerySlot by remember { mutableStateOf<VideoSlot?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showUserSourceDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    fun openEditor(slot: VideoSlot, uri: Uri) {
        editorRequest = EditorRequest(slot, uri)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        openEditor(pendingGallerySlot ?: VideoSlot.USER, uri)
        pendingGallerySlot = null
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) pendingCameraUri?.let { openEditor(VideoSlot.USER, it) }
        pendingCameraUri = null
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(context, cameraLauncher) { pendingCameraUri = it }
    }

    fun chooseMaster() {
        pendingGallerySlot = VideoSlot.MASTER
        galleryLauncher.launch(arrayOf("video/*"))
    }

    fun chooseUserGallery() {
        showUserSourceDialog = false
        pendingGallerySlot = VideoSlot.USER
        galleryLauncher.launch(arrayOf("video/*"))
    }

    fun chooseUserCamera() {
        showUserSourceDialog = false
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
                if (request.slot == VideoSlot.MASTER) viewModel.onSelectReferenceVideo(editedUri)
                else viewModel.onSelectSourceVideo(editedUri)
                editorRequest = null
            },
            onCancel = { editorRequest = null },
        )
        return
    }

    LiveArenaHome(
        selectionState = selectionState,
        configState = configState,
        referenceProfileState = referenceProfileState,
        showSettings = showSettings,
        onChooseMaster = ::chooseMaster,
        onChooseUser = { showUserSourceDialog = true },
        onEditMaster = { selectionState.referenceUri?.let { openEditor(VideoSlot.MASTER, it) } },
        onEditUser = { selectionState.sourceUri?.let { openEditor(VideoSlot.USER, it) } },
        onRemoveMaster = viewModel::clearReferenceVideo,
        onRemoveUser = viewModel::clearSourceVideo,
        onStartAnalysis = onStartAnalysis,
        onOpenLiveCoach = onOpenLiveCoach,
        onToggleSettings = { showSettings = !showSettings },
        onUpdateFps = viewModel::updateTargetFps,
        onUpdateLongEdgeCap = viewModel::updateLongEdgeCap,
        onUpdateMinimumConfidence = viewModel::updateMinimumConfidence,
    )

    if (showUserSourceDialog) {
        UserVideoSourceDialog(
            onDismiss = { showUserSourceDialog = false },
            onUpload = ::chooseUserGallery,
            onRecord = ::chooseUserCamera,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveArenaHome(
    selectionState: VideoSelectionState,
    configState: ConfigurationState,
    referenceProfileState: ReferenceProfileUiState,
    showSettings: Boolean,
    onChooseMaster: () -> Unit,
    onChooseUser: () -> Unit,
    onEditMaster: () -> Unit,
    onEditUser: () -> Unit,
    onRemoveMaster: () -> Unit,
    onRemoveUser: () -> Unit,
    onStartAnalysis: () -> Unit,
    onOpenLiveCoach: () -> Unit,
    onToggleSettings: () -> Unit,
    onUpdateFps: (Int) -> Unit,
    onUpdateLongEdgeCap: (Int) -> Unit,
    onUpdateMinimumConfidence: (Double) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(SenpPageBackdrop).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Senp.ai", color = SenpCream, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(SenpCream))
        }
        Spacer(Modifier.height(30.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SectionLabel("MASTER")
                Spacer(Modifier.height(8.dp))
                if (selectionState.referenceUri == null) {
                    UploadCard("Master video", "Reference", "ADD", onChooseMaster, compact = true)
                } else {
                    VideoAssetCard("MASTER", selectionState.referenceUri, SenpCream, onEditMaster, onRemoveMaster, compact = true)
                }
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("USER")
                Spacer(Modifier.height(8.dp))
                if (selectionState.sourceUri == null) {
                    UploadCard("Your video", "Upload / record", "ADD", onChooseUser, compact = true)
                } else {
                    VideoAssetCard("USER", selectionState.sourceUri, SenpMuted, onEditUser, onRemoveUser, compact = true)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ANALYSIS SETTINGS")
            SmallAction(if (showSettings) "HIDE" else "TUNE", onToggleSettings)
        }
        if (showSettings) {
            AnalysisSettingsCard(
                configState = configState,
                onUpdateFps = onUpdateFps,
                onUpdateLongEdgeCap = onUpdateLongEdgeCap,
                onUpdateMinimumConfidence = onUpdateMinimumConfidence,
            )
        }
        ReferenceProfileCard(referenceProfileState)
        Button(
            onClick = onStartAnalysis,
            enabled = selectionState.isReadyForAnalysis && !selectionState.isCalculatingHash,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SenpCream,
                contentColor = Color.Black,
                disabledContainerColor = SenpSurfaceRaised,
                disabledContentColor = SenpMuted,
            ),
        ) { Text(if (selectionState.isCalculatingHash) "PREPARING" else "ANALYSE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp) }
        selectionState.errorMessage?.let {
            Text(it, color = Color(0xFFE57373), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("TRY THE LIVE COACH")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenLiveCoach,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SenpSurface, contentColor = SenpCream),
            border = BorderStroke(1.dp, SenpBorder),
        ) { Text("OPEN LIVE POSE COACH", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AnalysisSettingsCard(
    configState: ConfigurationState,
    onUpdateFps: (Int) -> Unit,
    onUpdateLongEdgeCap: (Int) -> Unit,
    onUpdateMinimumConfidence: (Double) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface),
        border = BorderStroke(1.dp, SenpBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            SettingSlider(
                label = "SAMPLE RATE",
                valueLabel = "${configState.targetFps} FPS",
                value = configState.targetFps.toFloat(),
                valueRange = 5f..30f,
                steps = 4,
                onValueChange = { onUpdateFps(it.roundToInt()) },
            )
            SettingSlider(
                label = "FRAME LONG EDGE",
                valueLabel = "${configState.longEdgeCapPx}px",
                value = configState.longEdgeCapPx.toFloat(),
                valueRange = 240f..1080f,
                steps = 3,
                onValueChange = { onUpdateLongEdgeCap(it.roundToInt()) },
            )
            SettingSlider(
                label = "POSE CONFIDENCE",
                valueLabel = "${(configState.minimumConfidence * 100).roundToInt()}%",
                value = configState.minimumConfidence.toFloat(),
                valueRange = 0.3f..0.9f,
                steps = 5,
                onValueChange = { onUpdateMinimumConfidence(it.toDouble()) },
            )
            Text(
                "These native controls affect extraction and rebuild the selected reference profile.",
                color = SenpMuted,
                fontSize = 9.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label)
        Text(valueLabel, color = SenpCream, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = SenpCream,
            activeTrackColor = SenpCream,
            inactiveTrackColor = SenpSurfaceRaised,
        ),
    )
}

@Composable
private fun ReferenceProfileCard(state: ReferenceProfileUiState) {
    val detail = when (state) {
        ReferenceProfileUiState.Empty -> "Select a master video to build a body-centric movement reference."
        is ReferenceProfileUiState.Preparing -> state.message
        is ReferenceProfileUiState.Ready -> "${state.profile.states.size} states · ${state.profile.referenceRepetitions} reference reps · ${(state.profile.confidence * 100).roundToInt()}% confidence"
        is ReferenceProfileUiState.Rejected -> state.message
    }
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, SenpBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel("REFERENCE ACTION")
                Text(
                    when (state) {
                        ReferenceProfileUiState.Empty -> "WAITING"
                        is ReferenceProfileUiState.Preparing -> "PREPARING"
                        is ReferenceProfileUiState.Ready -> "READY"
                        is ReferenceProfileUiState.Rejected -> "UNAVAILABLE"
                    },
                    color = SenpCream,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(detail, color = SenpMuted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun UploadCard(title: String, detail: String, action: String, onClick: () -> Unit, compact: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth().height(if (compact) 142.dp else 104.dp).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SenpSurface),
        border = BorderStroke(1.dp, SenpBorder),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 10.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)).background(SenpBackgroundRaised), contentAlignment = Alignment.Center) {
                Text("+", color = SenpCream, fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
            Column(Modifier.padding(start = 9.dp).weight(1f)) {
                Text(title, color = SenpCream, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = SenpMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
            }
            if (!compact) Text(action, color = SenpCream, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoAssetCard(label: String, uri: Uri, accent: Color, onEdit: () -> Unit, onRemove: () -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    var playing by remember(uri) { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = SenpSurface), border = BorderStroke(1.dp, SenpBorder)) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().height(if (compact) 112.dp else 164.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).clickable {
                if (playing) player.pause() else player.play()
                playing = !playing
            }) {
                AndroidView(
                    factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply { this.player = player } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                Box(Modifier.align(Alignment.Center).size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
                    Text(if (playing) "II" else ">", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (playing) "PLAYING" else "READY", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SmallAction("EDIT", onEdit)
                SmallAction("REMOVE", onRemove)
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(SenpSurfaceRaised).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(label, color = SenpCream, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UserVideoSourceDialog(onDismiss: () -> Unit, onUpload: () -> Unit, onRecord: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SenpSurface), border = BorderStroke(1.dp, SenpBorder)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("USER VIDEO", color = SenpCream, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Choose how to add your movement.", color = SenpMuted, fontSize = 11.sp)
                Button(onClick = onUpload, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(9.dp), colors = ButtonDefaults.buttonColors(containerColor = SenpCream, contentColor = Color.Black)) { Text("UPLOAD FROM GALLERY", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(9.dp), colors = ButtonDefaults.buttonColors(containerColor = SenpSurfaceRaised, contentColor = SenpCream)) { Text("RECORD VIDEO", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                Text("CANCEL", color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 8.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun launchCamera(context: Context, launcher: androidx.activity.result.ActivityResultLauncher<Uri>, onUriReady: (Uri) -> Unit) {
    val directory = File(context.cacheDir, "camera_videos").apply { mkdirs() }
    val output = File(directory, "capture_${UUID.randomUUID()}.mp4")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    onUriReady(uri)
    launcher.launch(uri)
}
