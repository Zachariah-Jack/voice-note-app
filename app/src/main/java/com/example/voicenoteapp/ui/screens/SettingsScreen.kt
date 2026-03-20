package com.example.voicenoteapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.voicenoteapp.settings.AssistantSettings
import com.example.voicenoteapp.settings.CredentialStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    credentialStore: CredentialStore,
    onBack: () -> Unit
) {
    val savedSettings by credentialStore.settings.collectAsState(initial = AssistantSettings())
    val scope = rememberCoroutineScope()

    var openAiApiKey by rememberSaveable(savedSettings.openAiApiKey) { mutableStateOf(savedSettings.openAiApiKey) }
    var openAiModel by rememberSaveable(savedSettings.openAiModel) { mutableStateOf(savedSettings.openAiModel) }
    var jobTreadBaseUrl by rememberSaveable(savedSettings.jobTreadBaseUrl) { mutableStateOf(savedSettings.jobTreadBaseUrl) }
    var jobTreadApiKey by rememberSaveable(savedSettings.jobTreadApiKey) { mutableStateOf(savedSettings.jobTreadApiKey) }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Assistant configuration is stored locally on this device.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "OpenAI and JobTread entries are placeholders for the next integration stage.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )

            SettingsSectionTitle("OpenAI")
            OutlinedTextField(
                value = openAiApiKey,
                onValueChange = {
                    openAiApiKey = it
                    saveMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            OutlinedTextField(
                value = openAiModel,
                onValueChange = {
                    openAiModel = it
                    saveMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                label = { Text("Model Name") },
                placeholder = { Text("gpt-4.1-mini") },
                singleLine = true
            )

            SettingsSectionTitle("JobTread")
            OutlinedTextField(
                value = jobTreadBaseUrl,
                onValueChange = {
                    jobTreadBaseUrl = it
                    saveMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.jobtread.example") },
                singleLine = true
            )
            OutlinedTextField(
                value = jobTreadApiKey,
                onValueChange = {
                    jobTreadApiKey = it
                    saveMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Button(
                onClick = {
                    scope.launch {
                        credentialStore.save(
                            AssistantSettings(
                                openAiApiKey = openAiApiKey,
                                openAiModel = openAiModel,
                                jobTreadBaseUrl = jobTreadBaseUrl,
                                jobTreadApiKey = jobTreadApiKey
                            )
                        )
                        saveMessage = "Settings saved locally."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            ) {
                Text("Save Settings")
            }

            saveMessage?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Current configuration status",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "OpenAI: ${if (savedSettings.hasOpenAiConfig) "Configured" else "Missing ${savedSettings.missingOpenAiFields.joinToString(", ") { it.label }}"}",
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "JobTread: ${if (savedSettings.hasJobTreadConfig) "Configured" else "Missing ${savedSettings.missingJobTreadFields.joinToString(", ") { it.label }}"}",
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
    )
}
