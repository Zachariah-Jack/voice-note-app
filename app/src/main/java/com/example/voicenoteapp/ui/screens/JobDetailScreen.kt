package com.example.voicenoteapp.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    viewModel: JobDetailViewModel,
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    onOpenNote: (Long) -> Unit
) {
    val context = LocalContext.current
    val job by viewModel.job.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val query by viewModel.searchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name ?: "Job") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val exportBody = buildString {
                                appendLine("Job: ${job?.name.orEmpty()}")
                                appendLine()
                                notes.forEachIndexed { index, note ->
                                    appendLine("${index + 1}. ${note.title}")
                                    appendLine(note.body)
                                    appendLine()
                                }
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${job?.name.orEmpty()} Notes")
                                putExtra(Intent.EXTRA_TEXT, exportBody)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export notes"))
                        },
                        enabled = notes.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export notes")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNote) {
                Icon(Icons.Default.Add, contentDescription = "Add note")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                label = { Text("Search notes") },
                singleLine = true
            )

            if (notes.isEmpty()) {
                Text("No notes found for this job.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(notes, key = { it.id }) { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenNote(note.id) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(note.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        note.body.take(120).replace("\n", " "),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                if (note.isPinned) {
                                    Icon(Icons.Default.PushPin, contentDescription = "Pinned")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
