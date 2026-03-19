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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceWizardScreen(
    viewModel: VoiceWizardViewModel,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var shouldExplainPermission by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }

    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        shouldExplainPermission = !granted && context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } == true
        if (!granted) viewModel.onSpeechError("Microphone permission is required for voice capture.")
    }

    val tts = remember(context) {
        TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    val speechRecognizer = remember(context) {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    fun startListening() {
        if (state.currentStep == WizardStep.COMPLETE) return
        if (!recognizerAvailable) {
            viewModel.onSpeechError("Speech service unavailable on this device.")
            return
        }
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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

    DisposableEffect(Unit) {
        viewModel.initialize()
        onDispose {}
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.persistForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            override fun onEndOfSpeech() {
                viewModel.setVoiceStatus("Processing...")
            }

            override fun onError(error: Int) {
                isListening = false
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> viewModel.onSpeechTimeout()
                    SpeechRecognizer.ERROR_NO_MATCH -> viewModel.onSpeechError("Could not understand speech. Please try again.")
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> viewModel.onSpeechError("Network issue detected. Offline recognizer may be unavailable.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> viewModel.onSpeechError("Microphone permission denied.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> viewModel.onSpeechError("Recognizer busy. Please try again.")
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    else -> viewModel.onSpeechError("Voice recognition failed. Please try again.")
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    viewModel.onSpeechTimeout()
                    return
                }
                viewModel.onSpeechResult(text)
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

    LaunchedEffect(state.promptVersion, ttsReady) {
        if (state.isLoading || state.currentPrompt.isBlank()) return@LaunchedEffect
        if (state.currentStep == WizardStep.COMPLETE) return@LaunchedEffect
        if (!ttsReady) {
            startListening()
            return@LaunchedEffect
        }
        viewModel.setVoiceStatus("Prompting...")
        tts.language = Locale.getDefault()
        tts.speak(state.currentPrompt, TextToSpeech.QUEUE_FLUSH, null, "wizard_prompt_${state.promptVersion}")
        kotlinx.coroutines.delay(promptDelayMs(state.currentPrompt))
        startListening()
    }

    LaunchedEffect(state.shouldExit) {
        if (state.shouldExit) {
            viewModel.markExitConsumed()
            onExit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Wizard") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.persistForBackground()
                        onBack()
                    }) {
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
            Text("Prompt: ${state.currentPrompt}", style = MaterialTheme.typography.titleMedium)
            Text("Status: ${state.voiceStatus}", style = MaterialTheme.typography.bodyLarge)

            if (state.jobName.isNotBlank()) Text("Job: ${state.jobName}")
            if (state.title.isNotBlank()) Text("Subject: ${state.title}")
            if (state.body.isNotBlank()) Text("Note: ${state.body}")
            if (state.tags.isNotEmpty()) Text("Tags: ${state.tags.joinToString(", ")}")

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            state.completionMessage?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { if (isListening) stopListening() else startListening() },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text(if (isListening) "Stop Listening" else "Start Listening")
                }
                TextButton(
                    onClick = {
                        viewModel.persistForBackground()
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Draft & Exit")
                }
            }

            if (shouldExplainPermission) {
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant Microphone Permission")
                }
            }
        }
    }
}

private fun promptDelayMs(prompt: String): Long {
    val words = prompt.split(" ").size.coerceAtLeast(1)
    return (words * 180L).coerceIn(1100L, 4500L)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
