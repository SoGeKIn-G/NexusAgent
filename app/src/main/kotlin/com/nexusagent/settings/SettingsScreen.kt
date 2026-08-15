package com.nexusagent.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexusagent.agent.runtime.reasoning.ProviderId

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val draftKey by viewModel.draftKey.collectAsStateWithLifecycle()
    val draftBaseUrl by viewModel.draftBaseUrl.collectAsStateWithLifecycle()
    val draftModel by viewModel.draftModel.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reasoning model", style = MaterialTheme.typography.titleMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderId.entries.forEach { provider ->
                        FilterChip(
                            selected = settings.provider == provider,
                            onClick = { viewModel.setProvider(provider) },
                            label = { Text(provider.displayName.substringBefore(' ')) },
                        )
                    }
                }

                Text(
                    // Stated plainly because users reasonably assume a Claude or ChatGPT
                    // subscription carries over. It does not, and discovering that after
                    // pasting a key that silently fails is a bad first experience.
                    "Paste an API key for the provider you pick. A Claude or ChatGPT " +
                        "subscription does not include API access - that is billed " +
                        "separately. Gemini has a free tier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = draftKey,
                    onValueChange = viewModel::onKeyChanged,
                    label = { Text(if (settings.hasApiKey) "Replace API key" else "API key") },
                    placeholder = { Text(if (settings.provider == ProviderId.GEMINI) "AIza..." else "sk-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Only the OpenAI-compatible path has a meaningful endpoint choice; the
                // other two providers have exactly one.
                if (settings.provider == ProviderId.OPENAI) {
                    OutlinedTextField(
                        value = draftBaseUrl,
                        onValueChange = viewModel::onBaseUrlChanged,
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.groq.com/openai/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draftModel,
                        onValueChange = viewModel::onModelChanged,
                        label = { Text("Model") },
                        placeholder = { Text("llama-3.3-70b-versatile") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Any endpoint that speaks /v1/chat/completions works here — " +
                            "Groq, OpenRouter, Together, or a local Ollama.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::saveKey) { Text("Save") }
                    if (settings.hasApiKey) {
                        OutlinedButton(onClick = viewModel::clearKey) { Text("Remove") }
                    }
                }

                AssistChip(
                    onClick = {},
                    label = {
                        Text(if (settings.hasApiKey) "Key stored (encrypted)" else "No key yet")
                    },
                )

                Text(
                    "Stored encrypted with a hardware-backed Android Keystore key. " +
                        "Never written to source or to the APK.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Safety limits", style = MaterialTheme.typography.titleMedium)

                Text("Maximum steps per task: ${settings.maxSteps}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.maxSteps.toFloat(),
                    onValueChange = { viewModel.setMaxSteps(it.toInt()) },
                    valueRange = 5f..60f,
                    steps = 10,
                )
                Text(
                    "A hard stop. Without it a confused agent can loop indefinitely, " +
                        "tapping and burning API quota.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Confirm risky actions", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Pause and ask before sending a message, paying, or deleting.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.confirmDestructive,
                        onCheckedChange = viewModel::setConfirmDestructive,
                    )
                }
            }
        }

        message?.let {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    OutlinedButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
