package com.promptcut.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.promptcut.app.EditorViewModel
import com.promptcut.app.PRIVACY_POLICY_URL
import com.promptcut.app.ThemePreference
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.KeyVault
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: EditorViewModel,
    onThemeChanged: (ThemePreference) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedProvider by remember { mutableStateOf(vm.settings.manualProviderId) }
    var keyInput by remember(selectedProvider) { mutableStateOf(vm.settings.keyFor(selectedProvider)) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(selectedProvider) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var availableModels by remember(selectedProvider) { mutableStateOf<List<String>>(emptyList()) }

    var routingAuto by remember { mutableStateOf(vm.settings.routingModeAuto) }
    var themePref by remember { mutableStateOf(vm.settings.themePreference) }
    var preview720p by remember { mutableStateOf(vm.settings.preview720pMode) }
    var allowFrames by remember { mutableStateOf(vm.settings.privacy.allowFrameSamplingToAi) }
    var allowStt by remember { mutableStateOf(vm.settings.privacy.allowAudioToGroqStt) }
    var claudeConsent by remember { mutableStateOf(vm.settings.privacy.claudePaidConsentGranted) }
    var showClaudeConsentDialog by remember { mutableStateOf(false) }

    val canClose = vm.settings.hasActiveKey
    BackHandler(enabled = canClose) { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (canClose) "AI, Security & Studio Settings" else "Connect an AI Provider") },
                navigationIcon = {
                    if (canClose) {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("settings_close_button")) {
                            Icon(Icons.Default.Close, contentDescription = "Close settings")
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "PromptCut edits and renders locally on your device. Choose one or more AI providers for natural-language planning and editing tools.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Routing strategy
            Text("Provider Routing", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = routingAuto,
                    onClick = {
                        routingAuto = true
                        vm.settings.routingModeAuto = true
                    },
                    label = { Text("Free-First Auto (Recommended)") },
                )
                FilterChip(
                    selected = !routingAuto,
                    onClick = {
                        routingAuto = false
                        vm.settings.routingModeAuto = false
                        vm.settings.manualProviderId = selectedProvider
                    },
                    label = { Text("Manual: ${selectedProvider.shortName}") },
                )
            }
            Text(
                "Auto routes visual understanding to Gemini (free) and text/speech to Groq (free). Paid Claude is NEVER auto-selected.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Provider cards
            ProviderId.entries.forEach { pid ->
                val isSelected = pid == selectedProvider
                val savedKey = vm.settings.keyFor(pid)
                OutlinedCard(
                    onClick = {
                        selectedProvider = pid
                        if (!routingAuto) vm.settings.manualProviderId = pid
                    },
                    border = if (isSelected) CardDefaults.outlinedCardBorder().copy(width = 2.dp) else CardDefaults.outlinedCardBorder(),
                    colors = if (isSelected) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.outlinedCardColors(),
                    modifier = Modifier.fillMaxWidth().testTag("provider_card_${pid.name}"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isSelected, onClick = {
                            selectedProvider = pid
                            if (!routingAuto) vm.settings.manualProviderId = pid
                        })
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(pid.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    if (pid.isPaidByDefault) "PAID API" else "FREE TIER",
                                    style = MonoTimecodeStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(providerTagline(pid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (savedKey.isNotBlank()) {
                                Text(
                                    "KeyVault AES-GCM: ${KeyVault.maskKey(savedKey)}" + (vm.settings.modelFor(pid)?.let { " · $it" } ?: ""),
                                    style = MonoTimecodeStyle,
                                )
                            }
                        }
                    }
                }
            }

            if (selectedProvider == ProviderId.CLAUDE) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Paid Claude API Consent", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Anthropic Claude bills your Anthropic API account. PromptCut will only call Claude when this consent is enabled and selected manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = claudeConsent,
                            onCheckedChange = { want ->
                                if (want) showClaudeConsentDialog = true else {
                                    claudeConsent = false
                                    vm.settings.privacy.claudePaidConsentGranted = false
                                }
                            },
                            modifier = Modifier.testTag("claude_consent_switch"),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selectedProvider.keyPageUrl))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open ${selectedProvider.shortName} Key Console") }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it.trim(); testResult = null },
                label = { Text("${selectedProvider.shortName} API Key (stored in Android Keystore)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth().testTag("api_key_field"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let {
                        keyInput = it; testResult = null
                    }
                }) { Text("Paste") }

                OutlinedButton(
                    enabled = vm.settings.hasKey(selectedProvider),
                    onClick = {
                        vm.settings.setKeyFor(selectedProvider, "")
                        keyInput = ""
                        testResult = true to "Cleared saved ${selectedProvider.shortName} key."
                    },
                ) { Text("Clear") }

                Button(
                    enabled = keyInput.isNotBlank() && !testing,
                    modifier = Modifier.weight(1f).testTag("test_save_key_button"),
                    onClick = {
                        if (selectedProvider == ProviderId.CLAUDE && !claudeConsent) {
                            showClaudeConsentDialog = true
                            return@Button
                        }
                        testing = true
                        scope.launch {
                            when (val res = vm.router.testConnection(selectedProvider, keyInput, vm.settings.modelFor(selectedProvider))) {
                                is AppResult.Success -> {
                                    val writeRes = vm.settings.setKeyFor(selectedProvider, keyInput)
                                    if (writeRes is AppResult.Success) {
                                        vm.settings.setModelFor(selectedProvider, res.value)
                                        if (!routingAuto) vm.settings.manualProviderId = selectedProvider
                                        val modelsRes = vm.router.providerFor(selectedProvider).listModels(keyInput)
                                        if (modelsRes is AppResult.Success) availableModels = modelsRes.value.take(6)
                                        testResult = true to "Connected. Active model: ${res.value}"
                                    } else {
                                        testResult = false to (writeRes.errorOrNull()?.message ?: "Keystore encryption failed.")
                                    }
                                }
                                is AppResult.Failure -> {
                                    testResult = false to res.error.message
                                }
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test & Save") }
            }

            testResult?.let { (ok, msg) ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (ok) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (availableModels.isNotEmpty()) {
                Text("Select ${selectedProvider.shortName} Model", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableModels.forEach { m ->
                        val active = vm.settings.modelFor(selectedProvider) == m
                        FilterChip(
                            selected = active,
                            onClick = { vm.settings.setModelFor(selectedProvider, m) },
                            label = { Text(m, style = MonoTimecodeStyle) },
                        )
                    }
                }
            }

            if (canClose) {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("start_editing_button")) {
                    Text("Return to Editor")
                }
            }

            HorizontalDivider()
            Text("Studio & Performance", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreference.entries.forEach { tp ->
                    FilterChip(
                        selected = themePref == tp,
                        onClick = {
                            themePref = tp
                            vm.settings.themePreference = tp
                            onThemeChanged(tp)
                        },
                        label = { Text(tp.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("720p Preview Mode (Redmi / Mid-Range Tuned)", style = MaterialTheme.typography.bodySmall)
                    Text("Uses lower preview decode & cache footprint while allowing 1080p export.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = preview720p, onCheckedChange = {
                    preview720p = it
                    vm.settings.preview720pMode = it
                })
            }

            HorizontalDivider()
            Text("Privacy Controls & Disclosures", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Send sampled still frames to Vision AI", style = MaterialTheme.typography.bodySmall)
                    Text("Full videos are NEVER uploaded. Disable to send text metadata only.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = allowFrames, onCheckedChange = {
                    allowFrames = it; vm.settings.privacy.allowFrameSamplingToAi = it
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Groq Whisper for speech subtitles", style = MaterialTheme.typography.bodySmall)
                    Text("Extracts compressed audio locally for transcription when Groq key is set.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = allowStt, onCheckedChange = {
                    allowStt = it; vm.settings.privacy.allowAudioToGroqStt = it
                })
            }
            vm.settings.privacy.disclosures().forEach { line ->
                Text("• $line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                "Session token totals: ${vm.settings.totalPromptTokensUsed} prompt · ${vm.settings.totalCompletionTokensUsed} completion",
                style = MonoTimecodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }) {
                Text("Full Privacy Policy")
            }
        }
    }

    if (showClaudeConsentDialog) {
        AlertDialog(
            onDismissRequest = { showClaudeConsentDialog = false },
            title = { Text("Enable Paid Anthropic Claude?") },
            text = {
                Text(
                    "Anthropic Claude is a paid API billed directly by Anthropic to your API key. " +
                        "PromptCut will never switch to Claude automatically and will only use it when you manually select Claude.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                Button(onClick = {
                    claudeConsent = true
                    vm.settings.privacy.claudePaidConsentGranted = true
                    showClaudeConsentDialog = false
                }) { Text("I Understand & Consent") }
            },
            dismissButton = {
                TextButton(onClick = { showClaudeConsentDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun providerTagline(pid: ProviderId): String = when (pid) {
    ProviderId.GEMINI -> "Free tier with vision + frames. Recommended for visual clip analysis."
    ProviderId.GROQ -> "Fast free open models + Whisper speech transcription."
    ProviderId.CLAUDE -> "Paid Anthropic API using your own key (requires explicit opt-in & consent)."
}
