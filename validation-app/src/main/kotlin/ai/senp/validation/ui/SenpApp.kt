package ai.senp.validation.ui

import ai.senp.validation.ui.screens.AnalysisPlayerScreen
import ai.senp.validation.ui.screens.AnalysisProgressScreen
import ai.senp.validation.ui.screens.ConfigurationScreen
import ai.senp.validation.ui.state.AnalysisUiState
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpPageBackdrop
import ai.senp.validation.ui.theme.SenpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    onOpenLiveReference: () -> Unit,
    onOpenLivePushUp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    SenpTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (val state = uiState) {
                is AnalysisUiState.Idle -> {
                    ConfigurationScreen(
                        viewModel = viewModel,
                        onStartAnalysis = { viewModel.runAnalysis() },
                        onOpenLiveReference = onOpenLiveReference,
                        onOpenLivePushUp = onOpenLivePushUp,
                    )
                }

                is AnalysisUiState.Analyzing -> {
                    AnalysisProgressScreen(
                        activeStage = state.activeStage,
                        progressPercent = state.progressPercent,
                        statusMessage = state.statusMessage,
                        onBack = { viewModel.resetAnalysis() },
                    )
                }

                is AnalysisUiState.Success -> {
                    AnalysisPlayerScreen(
                        sourceUri = state.sourceUri,
                        referenceUri = state.referenceUri,
                        sourcePoseExtraction = state.sourcePoseExtraction,
                        referencePoseExtraction = state.referencePoseExtraction,
                        synchronizationRun = state.synchronizationRun,
                        synchronizationFailure = state.synchronizationFailure,
                        referenceAction = state.referenceAction,
                        referenceActionMessage = state.referenceActionMessage,
                        onReset = { viewModel.resetAnalysis() },
                        onBack = { viewModel.resetAnalysis() },
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
    }
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
            .background(SenpPageBackdrop)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = SenpCream, fontSize = 28.sp, modifier = Modifier.size(42.dp).clickable { onRetry() })
            Text("ANALYSIS", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        }
        Spacer(Modifier.height(18.dp))
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
