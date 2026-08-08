package ai.senp.validation.ui

import ai.senp.validation.ui.screens.AnalysisPlayerScreen
import ai.senp.validation.ui.screens.AnalysisProgressScreen
import ai.senp.validation.ui.screens.ConfigurationScreen
import ai.senp.validation.ui.state.AnalysisUiState
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
    viewModel: SenpEngineViewModel = viewModel()
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
                        onStartAnalysis = {}
                    )
                }

                is AnalysisUiState.Analyzing -> {
                    AnalysisProgressScreen(
                        activeStage = state.activeStage,
                        statusMessage = state.statusMessage
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
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = failureMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}
