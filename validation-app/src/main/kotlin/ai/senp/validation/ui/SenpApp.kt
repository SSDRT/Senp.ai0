package ai.senp.validation.ui

import ai.senp.validation.ui.screens.AnalysisPlayerScreen
import ai.senp.validation.ui.screens.AnalysisProgressScreen
import ai.senp.validation.ui.screens.ConfigurationScreen
import ai.senp.validation.model.PoseModelInstallState
import ai.senp.validation.model.PoseModelSpec
import ai.senp.validation.ui.state.AnalysisUiState
import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SenpApp(
    viewModel: SenpEngineViewModel = viewModel(),
    onOpenLivePushUp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    var showModelSetup by rememberSaveable { mutableStateOf(false) }
    var promptedForModel by rememberSaveable { mutableStateOf(false) }
    val modelReady = modelState is PoseModelInstallState.Ready

    LaunchedEffect(modelState) {
        when (modelState) {
            PoseModelInstallState.Missing -> if (!promptedForModel) {
                promptedForModel = true
                showModelSetup = true
            }
            is PoseModelInstallState.Ready -> showModelSetup = false
            else -> Unit
        }
    }

    SenpTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (val state = uiState) {
                is AnalysisUiState.Idle -> {
                    ConfigurationScreen(
                        viewModel = viewModel,
                        onStartAnalysis = {
                            if (modelReady) viewModel.runAnalysis() else showModelSetup = true
                        },
                        onOpenLivePushUp = {
                            if (modelReady) onOpenLivePushUp() else showModelSetup = true
                        },
                    )
                }

                is AnalysisUiState.Analyzing -> {
                    AnalysisProgressScreen(
                        activeStage = state.activeStage,
                        progressPercent = state.progressPercent,
                        statusMessage = state.statusMessage,
                    )
                }

                is AnalysisUiState.Success -> {
                    AnalysisPlayerScreen(
                        result = state.result,
                        sourcePoses = state.sourcePoses,
                        referencePoses = state.referencePoses,
                        sourceUri = state.sourceUri,
                        referenceUri = state.referenceUri,
                        onReset = { viewModel.resetAnalysis() }
                    )
                }

                is AnalysisUiState.Failure -> {
                    ErrorScreen(
                        failureMessage = state.failure.message,
                        stageName = state.failure.stage.name,
                        onRetry = { viewModel.resetAnalysis() }
                    )
                }
            }
        }

        if (showModelSetup && modelState !is PoseModelInstallState.Ready) {
            AnalysisModelSetupDialog(
                state = modelState,
                onDownload = viewModel::downloadAnalysisModel,
                onDismiss = {
                    if (modelState !is PoseModelInstallState.Downloading) showModelSetup = false
                },
            )
        }
    }
}

@Composable
private fun AnalysisModelSetupDialog(
    state: PoseModelInstallState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloading = state as? PoseModelInstallState.Downloading
    val progress = if (downloading != null && downloading.totalBytes > 0L) {
        (downloading.bytesDownloaded.toFloat() / downloading.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val downloadedMb = downloading?.bytesDownloaded?.div(1024.0 * 1024.0) ?: 0.0
    val totalMb = PoseModelSpec.EXPECTED_BYTES / (1024.0 * 1024.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install local analysis model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Senp.ai analyses videos on this phone using " + PoseModelSpec.DISPLAY_NAME + ". " +
                        "Your videos are not uploaded for pose analysis.",
                )
                Text(
                    "Download size: " + String.format("%.1f", totalMb) + " MB. The file is cryptographically verified before use.",
                    color = SenpMuted,
                    fontSize = 12.sp,
                )
                when (state) {
                    PoseModelInstallState.Checking -> Text("Checking installed model…", color = SenpMuted)
                    PoseModelInstallState.Missing -> Unit
                    is PoseModelInstallState.Downloading -> {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            String.format("%.1f", downloadedMb) + " / " + String.format("%.1f", totalMb) + " MB",
                            color = SenpMuted,
                            fontSize = 12.sp,
                        )
                    }
                    is PoseModelInstallState.Failed -> Text(
                        "Download failed: " + state.message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                    is PoseModelInstallState.Ready -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                enabled = state !is PoseModelInstallState.Downloading && state !is PoseModelInstallState.Checking,
            ) {
                Text(if (state is PoseModelInstallState.Failed) "Retry download" else "Download model")
            }
        },
        dismissButton = {
            if (state !is PoseModelInstallState.Downloading) {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

@Composable
private fun ErrorScreen(
    failureMessage: String,
    stageName: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SenpBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SenpSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Analysis Failed",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Failed in stage: $stageName",
                    fontSize = 13.sp,
                    color = SenpCream,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = failureMessage,
                    fontSize = 12.sp,
                    color = SenpMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SenpBlue,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}
