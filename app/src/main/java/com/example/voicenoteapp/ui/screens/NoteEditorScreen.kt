package com.example.voicenoteapp.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onEditByVoice: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isListening by remember { mutableStateOf(false) }
    var shouldExplainPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        shouldExplainPermission = !granted && context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } == true
        if (!granted) {
            viewModel.setError("Microphone permission is required for voice notes.")
        }
    }

    val speechRecognizer = remember(context) {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    DisposableEffect(speechRecognizer) {
        onDispose { speechRecognizer?.destroy() }
    }

    fun startListening() {
        if (!recognizerAvailable) {
            viewModel.setError("Speech service unavailable on this device. You can still type notes.")
            return
        }
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        viewModel.clearError()
        viewModel.setVoiceStatus("Listening...")
        isListening = true
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        viewModel.setVoiceStatus("Processing...")
        speechRecognizer?.stopListening()
    }

    DisposableEffect(speechRecognizer) {
        if (speechRecognizer == null) return@DisposableEffect onDispose {}

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                viewModel.setVoiceStatus("Processing...")
            }

            override fun onError(error: Int) {
                isListening = false
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error. Try again."
                    SpeechRecognizer.ERROR_CLIENT -> "Voice capture cancelled."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error. Offline recognition may be unavailable on this device."
                    SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand speech. Tap and retry."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Try again in a moment."
                    SpeechRecognizer.ERROR_SERVER -> "Speech server error. Try typing instead."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap to talk again."
                    else -> "Voice recognition failed. Please retry."
                }
                viewModel.setVoiceStatus("Ready")
                viewModel.setError(message)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    viewModel.setError("No transcript received. Please retry.")
                    viewModel.setVoiceStatus("Ready")
                    return
                }
                viewModel.applyTranscript(text)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.setRecognitionListener(null) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNewNote) "New Note" else "Edit Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.togglePinned() }) {
                        Icon(Icons.Default.PushPin, contentDescription = "Pin or unpin")
                    }
                    IconButton(onClick = { viewModel.saveNote(onSaved) }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                    if (!state.isNewNote) {
                        IconButton(onClick = { viewModel.deleteNote(onDeleted) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::updateBody,
                label = { Text("Body") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )
            OutlinedTextField(
                value = state.tags,
                onValueChange = viewModel::updateTags,
                label = { Text("Tags (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = {
                    if (isListening) stopListening() else startListening()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(if (isListening) "Tap to Stop" else "Tap to Talk")
            }

            if (!state.isNewNote && onEditByVoice != null) {
                Button(
                    onClick = onEditByVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Edit by Voice")
                }
            }

            Text(
                text = "Status: ${state.voiceStatus}",
                style = MaterialTheme.typography.bodyMedium
            )

            if (shouldExplainPermission) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Enable microphone permission to capture voice notes.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("Grant")
                    }
                }
            }

            state.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
