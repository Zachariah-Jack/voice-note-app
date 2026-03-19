package com.example.voicenoteapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voicenoteapp.data.db.JobEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobListScreen(
    viewModel: JobListViewModel,
    onOpenJob: (Long) -> Unit,
    onQuickAddNote: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val jobs by viewModel.jobs.collectAsState()
    val showArchived by viewModel.showArchivedState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<JobEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jobs") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Archived")
                        Switch(checked = showArchived, onCheckedChange = viewModel::setShowArchived)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create Job")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (jobs.isEmpty()) {
                Text(
                    text = "No jobs yet. Create one to start capturing notes.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(jobs, key = { it.id }) { job ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenJob(job.id) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(job.name, style = MaterialTheme.typography.titleMedium)
                                    if (job.isArchived) Text("Archived", style = MaterialTheme.typography.labelMedium)
                                }
                                Row {
                                    IconButton(onClick = { onQuickAddNote(job.id) }) {
                                        Icon(Icons.Default.NoteAdd, contentDescription = "Quick add note")
                                    }
                                    IconButton(onClick = { renameTarget = job }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename job")
                                    }
                                    IconButton(onClick = { viewModel.setArchived(job, !job.isArchived) }) {
                                        Icon(Icons.Default.Archive, contentDescription = "Toggle archive")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        NameDialog(
            title = "Create Job",
            initial = "",
            onDismiss = { showCreateDialog = false },
            onSave = {
                viewModel.addJob(it)
                showCreateDialog = false
            }
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "Rename Job",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onSave = {
                viewModel.renameJob(target, it)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
