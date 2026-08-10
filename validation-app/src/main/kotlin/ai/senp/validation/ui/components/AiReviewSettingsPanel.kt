package ai.senp.validation.ui.components

import ai.senp.validation.AiReviewSettingsUiState
import ai.senp.validation.GeminiReviewModels
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpBorder
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import ai.senp.validation.ui.theme.SenpViolet
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AiReviewSettingsPanel(
    state: AiReviewSettingsUiState,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var customModelDraft by remember(state.modelId) { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, SenpBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AI REVIEW SETTINGS", color = SenpCream, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        GeminiReviewModels.title(state.modelId) + " · " +
                            if (state.apiKeyConfigured) "API key saved" else "API key needed",
                        color = if (state.apiKeyConfigured) SenpBlueBright else SenpMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(if (expanded) "−" else "+", color = SenpBlueBright, fontSize = 25.sp)
            }

            if (expanded) {
                HorizontalDivider(color = SenpBorder, modifier = Modifier.padding(vertical = 14.dp))
                Text("Gemini API key", color = SenpCream, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.apiKeyConfigured) "Encrypted on this device. Enter another key only to replace it."
                    else "Paste your Google AI Studio Gemini API key. The key is not built into the APK.",
                    color = SenpMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(if (state.apiKeyConfigured) "Enter replacement key" else "AIza…", color = SenpMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SenpCream,
                        unfocusedTextColor = SenpCream,
                        focusedBorderColor = SenpBlueBright,
                        unfocusedBorderColor = SenpBorder,
                        cursorColor = SenpBlueBright,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onSaveApiKey(apiKeyDraft); apiKeyDraft = "" },
                        enabled = apiKeyDraft.isNotBlank(),
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SenpBlue,
                            contentColor = Color.White,
                            disabledContainerColor = SenpSurfaceRaised,
                            disabledContentColor = SenpMuted,
                        ),
                    ) { Text(if (state.apiKeyConfigured) "REPLACE KEY" else "SAVE KEY", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    if (state.apiKeyConfigured) {
                        OutlinedButton(
                            onClick = onClearApiKey,
                            modifier = Modifier.weight(1f).height(42.dp),
                            border = BorderStroke(1.dp, SenpBorder),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SenpMuted),
                        ) { Text("CLEAR KEY", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Review model", color = SenpCream, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Models available to this API key", color = SenpMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    OutlinedButton(
                        onClick = onRefreshModels,
                        enabled = state.apiKeyConfigured && !state.isLoadingModels,
                        border = BorderStroke(1.dp, SenpBorder),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(if (state.isLoadingModels) "LOADING…" else "REFRESH", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
                state.modelLoadError?.let { error ->
                    Text(error, color = SenpMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 7.dp))
                }

                state.availableModels.forEach { option ->
                    val selected = option.id == state.modelId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(if (selected) SenpBlue.copy(alpha = 0.10f) else SenpSurface.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                            .border(1.dp, if (selected) SenpBlueBright.copy(alpha = 0.55f) else SenpBorder, RoundedCornerShape(14.dp))
                            .clickable { onSelectModel(option.id) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(18.dp).border(1.dp, if (selected) SenpBlueBright else SenpMuted, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Box(Modifier.size(10.dp).background(SenpBlueBright, CircleShape)) }
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(option.title, color = SenpCream, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(option.detail, color = SenpMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (selected) Text("ACTIVE", color = SenpViolet, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    }
                }

                Text("Custom model ID", color = SenpCream, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customModelDraft,
                        onValueChange = { customModelDraft = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("gemini-…", color = SenpMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SenpCream,
                            unfocusedTextColor = SenpCream,
                            focusedBorderColor = SenpViolet,
                            unfocusedBorderColor = SenpBorder,
                            cursorColor = SenpViolet,
                        ),
                    )
                    Button(
                        onClick = { onSelectModel(customModelDraft); customModelDraft = "" },
                        enabled = customModelDraft.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = SenpViolet, contentColor = Color.White),
                        shape = RoundedCornerShape(11.dp),
                    ) { Text("USE", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }

                Text(
                    "Pose analysis stays on-device. AI review uploads the reference and user clips to Gemini only when a review runs.",
                    color = SenpMuted.copy(alpha = 0.82f),
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
