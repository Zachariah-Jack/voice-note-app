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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.voicenoteapp.jobtread.JobTreadLookupRepository
import com.example.voicenoteapp.jobtread.JobTreadOrganization
import com.example.voicenoteapp.jobtread.JobTreadOrganizationLoadResult
import com.example.voicenoteapp.settings.AssistantSettings
import com.example.voicenoteapp.settings.CredentialStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    credentialStore: CredentialStore,
    jobTreadLookupRepository: JobTreadLookupRepository,
    onBack: () -> Unit
) {
    val savedSettings by credentialStore.settings.collectAsState(initial = AssistantSettings())
    val scope = rememberCoroutineScope()

    var openAiApiKey by rememberSaveable(savedSettings.openAiApiKey) { mutableStateOf(savedSettings.openAiApiKey) }
    var openAiModel by rememberSaveable(savedSettings.openAiModel) { mutableStateOf(savedSettings.openAiModel) }
    var jobTreadBaseUrl by rememberSaveable(savedSettings.jobTreadBaseUrl) { mutableStateOf(savedSettings.jobTreadBaseUrl) }
    var jobTreadApiKey by rememberSaveable(savedSettings.jobTreadApiKey) { mutableStateOf(savedSettings.jobTreadApiKey) }
    var jobTreadOrganizationId by rememberSaveable(savedSettings.jobTreadOrganizationId) {
        mutableStateOf(savedSettings.jobTreadOrganizationId)
    }
    var jobTreadOrganizationName by rememberSaveable(savedSettings.jobTreadOrganizationName) {
        mutableStateOf(savedSettings.jobTreadOrganizationName)
    }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var organizationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var organizationErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isOrganizationLoading by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    val organizationOptions = remember { mutableStateListOf<JobTreadOrganization>() }

    LaunchedEffect(
        savedSettings.jobTreadBaseUrl,
        savedSettings.jobTreadApiKey,
        savedSettings.jobTreadOrganizationId,
        savedSettings.jobTreadOrganizationName,
        refreshNonce
    ) {
        organizationOptions.clear()
        organizationMessage = null
        organizationErrorMessage = null

        if (!savedSettings.hasJobTreadConfig) {
            isOrganizationLoading = false
            organizationMessage = "Save the JobTread Pave URL and grant key to load accessible organizations."
            return@LaunchedEffect
        }

        isOrganizationLoading = true
        when (val result = jobTreadLookupRepository.loadOrganizations(savedSettings)) {
            is JobTreadOrganizationLoadResult.Success -> {
                isOrganizationLoading = false
                organizationOptions.addAll(result.selection.organizations)
                jobTreadOrganizationId = result.selection.activeOrganization.id
                jobTreadOrganizationName = result.selection.activeOrganization.name
                organizationMessage = if (result.selection.wasAutoSelected) {
                    "Only one accessible organization was found, so it was selected automatically."
                } else {
                    "The saved default organization is ready to use."
                }

                if (result.selection.shouldPersistSelection) {
                    credentialStore.save(
                        savedSettings.copy(
                            jobTreadOrganizationId = result.selection.activeOrganization.id,
                            jobTreadOrganizationName = result.selection.activeOrganization.name
                        )
                    )
                    saveMessage = "Default JobTread organization updated locally."
                }
            }

            is JobTreadOrganizationLoadResult.SelectionRequired -> {
                isOrganizationLoading = false
                organizationOptions.addAll(result.organizations)
                if (result.organizations.none { organization ->
                        organization.id.equals(jobTreadOrganizationId, ignoreCase = true)
                    }
                ) {
                    jobTreadOrganizationId = ""
                    jobTreadOrganizationName = ""
                }
                organizationMessage = result.message
            }

            is JobTreadOrganizationLoadResult.MissingConfiguration -> {
                isOrganizationLoading = false
                organizationErrorMessage = result.message
            }

            is JobTreadOrganizationLoadResult.Failure -> {
                isOrganizationLoading = false
                organizationErrorMessage = result.message
            }
        }
    }

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
                text = "OpenAI settings power real voice parsing when configured. JobTread settings power read-only lookup, organization selection, and live To-Do creation.",
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
                placeholder = { Text("gpt-5-mini") },
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
                label = { Text("Pave URL") },
                placeholder = { Text("https://api.jobtread.com/pave") },
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
                label = { Text("Grant Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            SettingsSectionTitle("JobTread Organization")
            Text(
                text = "The assistant uses one saved default organization when a grant can access more than one.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Saved default: ${savedSettings.savedJobTreadOrganizationLabel ?: "Not saved yet"}",
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Pending selection: ${jobTreadOrganizationName.ifBlank { "None selected" }}",
                modifier = Modifier.padding(top = 4.dp)
            )

            if (isOrganizationLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
            }

            organizationMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 12.dp),
                    color = if (jobTreadOrganizationId.isBlank() && organizationOptions.size > 1) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            organizationErrorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (organizationOptions.isNotEmpty()) {
                Text(
                    text = "Accessible organizations",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )
                organizationOptions.forEach { organization ->
                    val isSelected = organization.id.equals(jobTreadOrganizationId, ignoreCase = true)
                    if (isSelected) {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text("Default: ${organization.name}")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                jobTreadOrganizationId = organization.id
                                jobTreadOrganizationName = organization.name
                                saveMessage = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use ${organization.name}")
                        }
                    }
                }
            }

            TextButton(
                onClick = { refreshNonce += 1 },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Refresh Organizations")
            }

            Button(
                onClick = {
                    scope.launch {
                        credentialStore.save(
                            AssistantSettings(
                                openAiApiKey = openAiApiKey,
                                openAiModel = openAiModel,
                                jobTreadBaseUrl = jobTreadBaseUrl,
                                jobTreadApiKey = jobTreadApiKey,
                                jobTreadOrganizationId = jobTreadOrganizationId,
                                jobTreadOrganizationName = jobTreadOrganizationName
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
            Text(
                text = "JobTread default org: ${savedSettings.savedJobTreadOrganizationLabel ?: "Not saved"}",
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
