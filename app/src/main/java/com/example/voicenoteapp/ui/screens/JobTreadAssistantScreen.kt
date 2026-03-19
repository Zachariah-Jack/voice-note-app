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
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.voicenoteapp.assistant.AssistantIntentType
import com.example.voicenoteapp.assistant.CreateTodoIntent
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobTreadAssistantScreen(
    viewModel: JobTreadAssistantViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var shouldExplainPermission by remember { mutableStateOf(false) }
    var pendingStartListening by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }

    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val tts = remember(context) {
        TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }
    val speechRecognizer = remember(context) {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    fun beginListeningInternal() {
        if (!recognizerAvailable) {
            pendingStartListening = false
            viewModel.onSpeechError("Speech service unavailable on this device.")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        pendingStartListening = false
        viewModel.onListeningStarted()
        speechRecognizer?.startListening(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        shouldExplainPermission = !granted && context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } == true

        if (granted && pendingStartListening) {
            beginListeningInternal()
        } else if (!granted) {
            pendingStartListening = false
            viewModel.onSpeechError("Microphone permission is required for the JobTread assistant.")
        }
    }

    fun startListening() {
        if (!hasPermission) {
            pendingStartListening = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginListeningInternal()
    }

    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    DisposableEffect(speechRecognizer) {
        if (speechRecognizer == null) return@DisposableEffect onDispose {}

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> viewModel.onNoTranscript()
                    SpeechRecognizer.ERROR_NO_MATCH -> viewModel.onSpeechError("I could not understand that request.")
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> viewModel.onSpeechError("Speech recognition hit a network issue.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> viewModel.onSpeechError("Microphone permission denied.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> viewModel.onSpeechError("Recognizer is busy. Try again.")
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    else -> viewModel.onSpeechError("Voice capture failed. Try again.")
                }
            }

            override fun onResults(results: Bundle?) {
                val transcript = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (transcript.isNullOrBlank()) {
                    viewModel.onNoTranscript()
                } else {
                    viewModel.onSpeechResult(transcript)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.setRecognitionListener(null)
            speechRecognizer.destroy()
        }
    }

    LaunchedEffect(state.captureNonce, state.stage, ttsReady) {
        if (state.stage != JobTreadAssistantStage.PROMPTING || state.captureNonce == 0) return@LaunchedEffect

        if (ttsReady) {
            tts.language = Locale.getDefault()
            tts.speak(state.prompt, TextToSpeech.QUEUE_FLUSH, null, "jobtread_prompt_${state.captureNonce}")
            kotlinx.coroutines.delay(jobTreadPromptDelayMs(state.prompt))
        }

        startListening()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JobTread Assistant") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Speak one request and I will turn it into a mocked JobTread create_todo confirmation.",
                style = MaterialTheme.typography.bodyLarge
            )

            when (state.stage) {
                JobTreadAssistantStage.IDLE -> {
                    Button(onClick = viewModel::startCapture, modifier = Modifier.fillMaxWidth()) {
                        Text("Start Speaking")
                    }
                }

                JobTreadAssistantStage.PROMPTING -> {
                    ListeningStatusCard(
                        title = "Getting ready",
                        body = "Prompting: ${state.prompt}"
                    )
                }

                JobTreadAssistantStage.LISTENING -> {
                    ListeningStatusCard(
                        title = "Listening",
                        body = "Listening for a single JobTread request..."
                    )
                }

                JobTreadAssistantStage.RESULT,
                JobTreadAssistantStage.ERROR -> Unit
            }

            state.errorMessage?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try again", style = MaterialTheme.typography.titleMedium)
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = viewModel::startCapture, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry")
                        }
                    }
                }
            }

            if (state.transcript.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Raw Transcript", style = MaterialTheme.typography.titleMedium)
                        Text(state.transcript, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            state.parsedIntent?.let { parsedIntent ->
                ParsedCreateTodoCard(parsedIntent)
                Button(
                    onClick = viewModel::onConfirmPlaceholder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm Create To-Do")
                }
                TextButton(
                    onClick = viewModel::startCapture,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Retry Voice Capture")
                }
            }

            state.placeholderMessage?.let { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Next Step Placeholder", style = MaterialTheme.typography.titleMedium)
                        Text(message, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            if (shouldExplainPermission) {
                TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Microphone Permission")
                }
            }
        }
    }
}

@Composable
private fun ListeningStatusCard(
    title: String,
    body: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ParsedCreateTodoCard(parsedIntent: CreateTodoIntent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Mock Parsed create_todo", style = MaterialTheme.typography.titleMedium)
            ParsedField("Intent", parsedIntent.intent.name.lowercase())
            ParsedField("Schema", parsedIntent.schemaVersion)
            ParsedField("Title", parsedIntent.todo.title ?: "Missing")
            ParsedField("Description", parsedIntent.todo.description ?: "Not captured")
            ParsedField("Job Reference", parsedIntent.todo.jobReferenceText ?: "Not captured")
            ParsedField("Assignee", parsedIntent.todo.assigneeReferenceText ?: "Not captured")
            ParsedField("Due Date", parsedIntent.todo.dueDateIso ?: "Not captured")
            ParsedField("Due Time", parsedIntent.todo.dueTimeLocal ?: "Not captured")
            ParsedField("Priority", parsedIntent.todo.priority?.name?.lowercase() ?: "Not captured")
            ParsedField("Tags", parsedIntent.todo.tags.joinToString(", ").ifBlank { "None" })
            ParsedField(
                "Missing Fields",
                parsedIntent.missingFields.joinToString(", ") { it.name.lowercase() }.ifBlank { "None" }
            )
            ParsedField("Ambiguities", parsedIntent.ambiguities.joinToString("; ").ifBlank { "None" })
        }
    }
}

@Composable
private fun ParsedField(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun jobTreadPromptDelayMs(prompt: String): Long {
    val words = prompt.split(" ").size.coerceAtLeast(1)
    return (words * 180L).coerceIn(900L, 2500L)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
