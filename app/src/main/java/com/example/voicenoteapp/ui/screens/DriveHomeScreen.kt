package com.example.voicenoteapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveHomeScreen(
    viewModel: DriveHomeViewModel,
    onStartJobTreadAssistant: () -> Unit,
    onOpenLegacyVoiceNotes: () -> Unit,
    onOpenUnsavedDrafts: () -> Unit,
    onOpenJobs: () -> Unit
) {
    val context = LocalContext.current
    val unsavedCount by viewModel.unsavedDraftCount.collectAsState()
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JobTread Assistant") },
                actions = {
                    Button(onClick = onOpenJobs) {
                        Icon(Icons.Default.List, contentDescription = "Jobs")
                        Text("Jobs", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (unsavedCount > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "You have unsaved notes.",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$unsavedCount draft${if (unsavedCount == 1) "" else "s"} waiting.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )
                        Button(onClick = onOpenUnsavedDrafts, modifier = Modifier.fillMaxWidth()) {
                            Text("Continue Unsaved Notes")
                        }
                    }
                }
            }

            Button(
                onClick = onStartJobTreadAssistant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
            ) {
                Text("Talk to JobTread", style = MaterialTheme.typography.headlineSmall)
            }

            Text(
                text = "Tap once, say what JobTread should turn into a To-Do, then review the parsed command before sending.",
                style = MaterialTheme.typography.bodyLarge
            )

            TextButton(onClick = onOpenLegacyVoiceNotes, modifier = Modifier.fillMaxWidth()) {
                Text("Use Legacy Note Wizard")
            }

            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Enable notifications for unsaved note reminders.")
                        Button(
                            onClick = { notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Text("Enable Notifications")
                        }
                    }
                }
            }
        }
    }
}
