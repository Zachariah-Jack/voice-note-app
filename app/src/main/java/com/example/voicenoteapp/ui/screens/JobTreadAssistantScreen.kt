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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedButton
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
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.CreateTodoParserMode
import com.example.voicenoteapp.jobtread.JobTreadAssignee
import com.example.voicenoteapp.jobtread.JobTreadCreateReadiness
import com.example.voicenoteapp.jobtread.JobTreadCreatedTodo
import com.example.voicenoteapp.jobtread.JobTreadJob
import com.example.voicenoteapp.jobtread.JobTreadLookupResolution
import com.example.voicenoteapp.jobtread.JobTreadOrganization
import com.example.voicenoteapp.settings.AssistantSettings
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobTreadAssistantScreen(
    viewModel: JobTreadAssistantViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
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
                },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Speak one request or load a demo sample. I will parse it into a strict create_todo result, resolve JobTread context, and only send the create call when the request is fully safe.",
                style = MaterialTheme.typography.bodyLarge
            )

            ConfigurationStatusCard(
                parserMode = state.parserMode,
                parserLabel = state.parserLabel,
                settings = state.settings,
                onOpenSettings = onOpenSettings
            )
            DemoModeCard(
                demoModeEnabled = state.demoModeEnabled,
                activeDemoScenarioId = state.activeDemoScenarioId,
                activeDemoScenarioTitle = state.activeDemoScenarioTitle,
                onSetDemoModeEnabled = viewModel::setDemoModeEnabled,
                onLoadScenario = viewModel::loadDemoScenario
            )
            AssistantFlowCard(state)

            when (state.stage) {
                JobTreadAssistantStage.IDLE -> {
                    Button(onClick = viewModel::startCapture, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (state.demoModeEnabled) {
                                "Start Live Voice Capture"
                            } else {
                                "Start Speaking"
                            }
                        )
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
                        Text(
                            text = if (state.transcript.isBlank()) "Voice capture issue" else "Parser issue",
                            style = MaterialTheme.typography.titleMedium
                        )
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
                ParsedCreateTodoCard(
                    parsedIntent = parsedIntent,
                    parserMode = state.parserMode,
                    parserLabel = state.parserLabel
                )
                JobTreadLookupCard(
                    state = state,
                    onOpenSettings = onOpenSettings,
                    onSelectOrganization = viewModel::onSelectOrganization
                )
                CreateStatusCard(
                    state = state,
                    onStartNewRequest = viewModel::startCapture
                )
                Text(
                    text = readinessLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.createReadiness == JobTreadCreateReadiness.READY) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )

                if (state.createStage != JobTreadCreateStage.SUCCESS) {
                    Button(
                        onClick = viewModel::onConfirmCreate,
                        enabled = state.canSubmitCreate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(confirmButtonLabel(state))
                    }
                }

                if (!state.settings.hasJobTreadConfig) {
                    Text(
                        text = "Add the JobTread Pave URL and grant key in Settings to enable lookup and the live create action.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (parsedIntent.missingFields.isNotEmpty()) {
                    Text(
                        text = "The parsed result is still missing required fields, so create stays disabled until the request is clearer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.createStage != JobTreadCreateStage.SUCCESS) {
                    TextButton(
                        onClick = viewModel::startCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retry Voice Capture")
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
private fun DemoModeCard(
    demoModeEnabled: Boolean,
    activeDemoScenarioId: String?,
    activeDemoScenarioTitle: String?,
    onSetDemoModeEnabled: (Boolean) -> Unit,
    onLoadScenario: (JobTreadAssistantDemoScenario) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Demo Mode", style = MaterialTheme.typography.titleMedium)
            Text(
                if (demoModeEnabled) {
                    "Demo mode is enabled. Load a canned scenario to show the parser, lookup, ready, success, or retryable error states without relying on live speech or live API behavior."
                } else {
                    "Enable demo mode to load canned assistant scenarios for a smooth walkthrough."
                }
            )
            ParsedField(
                "Mode",
                if (demoModeEnabled) "Demo enabled" else "Live-only"
            )
            ParsedField(
                "Loaded Sample",
                activeDemoScenarioTitle ?: "None"
            )
            if (demoModeEnabled) {
                Button(
                    onClick = { onSetDemoModeEnabled(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Turn Demo Mode Off")
                }
                jobTreadAssistantDemoScenarios.forEach { scenario ->
                    val isSelected = scenario.id == activeDemoScenarioId
                    val buttonText = if (isSelected) {
                        "Loaded: ${scenario.title}"
                    } else {
                        scenario.title
                    }
                    val onClick = { onLoadScenario(scenario) }
                    if (isSelected) {
                        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                            Text(buttonText)
                        }
                    } else {
                        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                            Text(buttonText)
                        }
                    }
                    Text(
                        scenario.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Button(
                    onClick = { onSetDemoModeEnabled(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Turn Demo Mode On")
                }
            }
        }
    }
}

@Composable
private fun AssistantFlowCard(state: JobTreadAssistantUiState) {
    val steps = buildAssistantFlowSteps(state)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Assistant Flow", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (state.activeDemoScenarioTitle != null) {
                    "Showing demo scenario: ${state.activeDemoScenarioTitle}"
                } else {
                    "This card tracks the live or demo assistant state from prompt through create."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            steps.forEach { step ->
                AssistantFlowStepRow(step)
            }
        }
    }
}

@Composable
private fun AssistantFlowStepRow(step: AssistantFlowStep) {
    val statusColor = when (step.status) {
        "Blocked",
        "Retry" -> MaterialTheme.colorScheme.error

        "Success" -> MaterialTheme.colorScheme.primary
        "Current" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(step.label, style = MaterialTheme.typography.titleSmall)
            Text(step.status, color = statusColor, style = MaterialTheme.typography.labelLarge)
            Text(step.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfigurationStatusCard(
    parserMode: CreateTodoParserMode,
    parserLabel: String,
    settings: AssistantSettings,
    onOpenSettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Parser Seam", style = MaterialTheme.typography.titleMedium)
            Text("Mode: ${parserMode.label}", style = MaterialTheme.typography.bodyMedium)
            Text("Implementation: $parserLabel", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (settings.hasOpenAiConfig) {
                    "OpenAI: Configured"
                } else {
                    "OpenAI: Missing ${settings.missingOpenAiFields.joinToString(", ") { it.label }}"
                }
            )
            Text(
                text = if (settings.hasJobTreadConfig) {
                    "JobTread: Configured"
                } else {
                    "JobTread: Missing ${settings.missingJobTreadFields.joinToString(", ") { it.label }}"
                }
            )
            Text(
                text = "Default JobTread org: ${settings.savedJobTreadOrganizationLabel ?: "Not saved yet"}"
            )
            if (parserMode == CreateTodoParserMode.FALLBACK && !settings.hasOpenAiConfig) {
                Text(
                    text = "OpenAI is not fully configured, so this screen will use the local fallback parser.",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text("OpenAI parsing is ready when a transcript is captured.")
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun JobTreadLookupCard(
    state: JobTreadAssistantUiState,
    onOpenSettings: () -> Unit,
    onSelectOrganization: (JobTreadOrganization) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("JobTread Lookup", style = MaterialTheme.typography.titleMedium)
            ParsedField("Create Readiness", readinessLabel(state))

            when (state.lookupStage) {
                JobTreadLookupStage.LOADING -> {
                    CircularProgressIndicator()
                    Text("Resolving JobTread organization, assignee, and job references...")
                }

                JobTreadLookupStage.ERROR -> {
                    Text(
                        text = state.lookupErrorMessage ?: "JobTread lookup failed.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                JobTreadLookupStage.IDLE,
                JobTreadLookupStage.READY -> Unit
            }

            state.activeOrganization?.let { organization ->
                ParsedField("Active Organization", organization.name)
            }

            if (state.organizationSelectionMessage != null) {
                Text(
                    text = state.organizationSelectionMessage,
                    color = MaterialTheme.colorScheme.error
                )
                if (state.availableOrganizations.isNotEmpty()) {
                    ParsedField(
                        "Accessible Organizations",
                        state.availableOrganizations.joinToString(" | ") { it.name }
                    )
                    state.availableOrganizations.forEach { organization ->
                        OutlinedButton(
                            onClick = { onSelectOrganization(organization) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use ${organization.name}")
                        }
                    }
                }
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage Default In Settings")
                }
            } else {
                state.lookupSummary?.let { summary ->
                    ParsedField(
                        "Assignee Match",
                        resolutionLabel(
                            resolution = summary.assigneeResolution,
                            resolvedLabel = JobTreadAssignee::summaryLabel
                        )
                    )
                    ParsedField(
                        "Job Match",
                        resolutionLabel(
                            resolution = summary.jobResolution,
                            resolvedLabel = JobTreadJob::summaryLabel
                        )
                    )
                    if (summary.messages.isNotEmpty()) {
                        ParsedField("Lookup Messages", summary.messages.joinToString("; "))
                    }
                } ?: run {
                    Text(
                        text = idleLookupMessage(state),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateStatusCard(
    state: JobTreadAssistantUiState,
    onStartNewRequest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isDemoCreate = state.activeDemoScenarioId != null

            ParsedField(
                "Selected Organization",
                state.activeOrganization?.name ?: "Not resolved yet"
            )
            ParsedField(
                "Send Mode",
                if (isDemoCreate) "Demo mode sample" else "Live JobTread create"
            )

            when (state.createStage) {
                JobTreadCreateStage.IDLE -> {
                    Text(
                        text = if (isDemoCreate) "Demo ready" else "Ready to create",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (isDemoCreate) {
                            "No live JobTread request has been sent. Use confirm to replay the demo outcome for this sample."
                        } else {
                            "Nothing has been sent to JobTread yet. Confirm only after the parsed fields, organization, and lookup matches look correct."
                        }
                    )
                }

                JobTreadCreateStage.SENDING -> {
                    CircularProgressIndicator()
                    Text(
                        text = if (isDemoCreate) "Running demo create" else "Creating in JobTread",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (isDemoCreate) {
                            "Simulating the final create step for the selected demo scenario."
                        } else {
                            "Sending the resolved To-Do request to JobTread now."
                        }
                    )
                }

                JobTreadCreateStage.SUCCESS -> {
                    val createdTodo = state.createdTodo
                    Text(
                        text = if (isDemoCreate) "Demo success" else "To-Do created",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (isDemoCreate) {
                            "This is a demo success state. No live JobTread mutation was sent."
                        } else {
                            "This request was sent successfully."
                        }
                    )
                    if (createdTodo != null) {
                        CreatedTodoDetails(
                            createdTodo = createdTodo,
                            activeOrganization = state.activeOrganization
                        )
                    }
                    Button(onClick = onStartNewRequest, modifier = Modifier.fillMaxWidth()) {
                        Text("Start New Request")
                    }
                }

                JobTreadCreateStage.ERROR -> {
                    Text(
                        text = if (isDemoCreate) "Demo create failed" else "Create failed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = state.createErrorMessage ?: "JobTread did not accept the create request.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (isDemoCreate) {
                            "The demo keeps the transcript, parsed fields, and lookup results in place so you can replay or switch scenarios."
                        } else {
                            "The transcript, parsed fields, and lookup results were kept so you can retry safely."
                        }
                    )
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
private fun ParsedCreateTodoCard(
    parsedIntent: CreateTodoIntent,
    parserMode: CreateTodoParserMode,
    parserLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Parsed create_todo", style = MaterialTheme.typography.titleMedium)
            ParsedField("Parser Mode", parserMode.label)
            ParsedField("Parser", parserLabel)
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
private fun CreatedTodoDetails(
    createdTodo: JobTreadCreatedTodo,
    activeOrganization: JobTreadOrganization?
) {
    ParsedField("JobTread ID", createdTodo.id)
    ParsedField("Name", createdTodo.name)
    ParsedField("Organization", activeOrganization?.name ?: "Not returned")
    ParsedField("Description", createdTodo.description ?: "Not returned")
    ParsedField("Target Type", createdTodo.targetType ?: "Not returned")
    ParsedField("Due Date", createdTodo.dueDateIso ?: "Not returned")
    ParsedField("Due Time", createdTodo.dueTimeLocal ?: "Not returned")
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

private data class AssistantFlowStep(
    val label: String,
    val status: String,
    val detail: String
)

private fun buildAssistantFlowSteps(state: JobTreadAssistantUiState): List<AssistantFlowStep> {
    val hasTranscript = state.transcript.isNotBlank()
    val hasParsedIntent = state.parsedIntent != null
    val parsedTitle = state.parsedIntent?.todo?.title
    val lookupBlocked = state.lookupStage == JobTreadLookupStage.ERROR || state.organizationSelectionMessage != null
    val lookupDone = state.lookupSummary != null || (state.activeOrganization != null && !lookupBlocked)
    val isSendAttempted = state.createStage == JobTreadCreateStage.SENDING ||
        state.createStage == JobTreadCreateStage.SUCCESS ||
        state.createStage == JobTreadCreateStage.ERROR

    return listOf(
        AssistantFlowStep(
            label = "Ready / How can I help?",
            status = if (
                state.stage == JobTreadAssistantStage.IDLE &&
                !hasTranscript &&
                !hasParsedIntent
            ) {
                "Current"
            } else {
                "Done"
            },
            detail = if (state.demoModeEnabled) {
                "Push to talk for live capture or load a demo sample."
            } else {
                "Push to talk starts the one-turn JobTread assistant flow."
            }
        ),
        AssistantFlowStep(
            label = "Listening",
            status = when (state.stage) {
                JobTreadAssistantStage.PROMPTING,
                JobTreadAssistantStage.LISTENING -> "Current"

                JobTreadAssistantStage.RESULT,
                JobTreadAssistantStage.ERROR -> if (hasTranscript) "Done" else "Waiting"

                JobTreadAssistantStage.IDLE -> "Waiting"
            },
            detail = when (state.stage) {
                JobTreadAssistantStage.PROMPTING -> "Prompting the user before capture begins."
                JobTreadAssistantStage.LISTENING -> "Capturing one utterance."
                else -> "Waiting for a live capture attempt."
            }
        ),
        AssistantFlowStep(
            label = "Transcript captured",
            status = when {
                hasTranscript -> "Done"
                state.stage == JobTreadAssistantStage.ERROR -> "Blocked"
                else -> "Waiting"
            },
            detail = if (hasTranscript) {
                state.transcript
            } else {
                "No transcript is available yet."
            }
        ),
        AssistantFlowStep(
            label = "Parsed To-Do",
            status = when {
                hasParsedIntent -> "Done"
                hasTranscript && state.errorMessage != null -> "Blocked"
                else -> "Waiting"
            },
            detail = when {
                parsedTitle != null -> "Title: $parsedTitle"
                hasTranscript && state.errorMessage != null -> state.errorMessage
                else -> "The assistant will convert the transcript into the strict create_todo model."
            }
        ),
        AssistantFlowStep(
            label = "Lookup resolution",
            status = when {
                state.lookupStage == JobTreadLookupStage.LOADING -> "Current"
                lookupBlocked -> "Blocked"
                lookupDone && hasParsedIntent -> "Done"
                hasParsedIntent -> "Waiting"
                else -> "Waiting"
            },
            detail = when {
                state.organizationSelectionMessage != null -> state.organizationSelectionMessage
                state.lookupErrorMessage != null -> state.lookupErrorMessage
                state.lookupStage == JobTreadLookupStage.LOADING -> "Resolving organization, assignee, and job matches."
                state.lookupSummary != null -> readinessLabel(state)
                else -> "Lookup starts after parsing when JobTread is configured."
            }
        ),
        AssistantFlowStep(
            label = "Ready to create",
            status = when {
                hasParsedIntent && state.createReadiness == JobTreadCreateReadiness.READY && state.createStage == JobTreadCreateStage.IDLE -> "Current"
                state.createStage == JobTreadCreateStage.SENDING || state.createStage == JobTreadCreateStage.SUCCESS -> "Done"
                hasParsedIntent && state.createReadiness != JobTreadCreateReadiness.READY -> "Blocked"
                else -> "Waiting"
            },
            detail = readinessLabel(state)
        ),
        AssistantFlowStep(
            label = "Sending",
            status = when (state.createStage) {
                JobTreadCreateStage.SENDING -> "Current"
                JobTreadCreateStage.SUCCESS,
                JobTreadCreateStage.ERROR -> "Done"
                JobTreadCreateStage.IDLE -> "Waiting"
            },
            detail = if (state.activeDemoScenarioId != null) {
                "Demo mode can simulate the final create outcome."
            } else {
                "A live create is only sent after all safety checks pass."
            }
        ),
        AssistantFlowStep(
            label = "Success / retryable error",
            status = when (state.createStage) {
                JobTreadCreateStage.SUCCESS -> "Success"
                JobTreadCreateStage.ERROR -> if (isSendAttempted) "Retry" else "Blocked"
                JobTreadCreateStage.IDLE,
                JobTreadCreateStage.SENDING -> "Waiting"
            },
            detail = when (state.createStage) {
                JobTreadCreateStage.SUCCESS -> "The final result is on screen below."
                JobTreadCreateStage.ERROR -> state.createErrorMessage ?: state.errorMessage ?: "The last attempt can be retried safely."
                JobTreadCreateStage.SENDING -> "Waiting for the create response."
                JobTreadCreateStage.IDLE -> "No create attempt has been made yet."
            }
        )
    )
}

private fun idleLookupMessage(state: JobTreadAssistantUiState): String {
    return when {
        !state.settings.hasJobTreadConfig -> "JobTread lookup is unavailable until the Pave URL and grant key are saved."
        state.lookupStage == JobTreadLookupStage.LOADING -> "Resolving JobTread references..."
        state.parsedIntent == null -> "Lookup will run after parsing."
        state.activeOrganization == null -> "Organization and lookup details will appear here."
        state.parsedIntent.todo.assigneeReferenceText.isNullOrBlank() &&
            state.parsedIntent.todo.jobReferenceText.isNullOrBlank() -> "No assignee or job reference was parsed, so only organization context was required."
        else -> "Lookup details will appear here."
    }
}

private fun readinessLabel(state: JobTreadAssistantUiState): String {
    return if (
        state.createReadiness == JobTreadCreateReadiness.BLOCKED_ORGANIZATION_SELECTION_REQUIRED &&
        state.organizationSelectionMessage != null
    ) {
        state.organizationSelectionMessage
    } else {
        state.createReadiness.label
    }
}

private fun confirmButtonLabel(state: JobTreadAssistantUiState): String {
    val isDemoCreate = state.activeDemoScenarioId != null
    return when (state.createStage) {
        JobTreadCreateStage.ERROR -> if (isDemoCreate) "Retry Demo Create" else "Retry Create To-Do"
        JobTreadCreateStage.SENDING -> if (isDemoCreate) "Running Demo..." else "Creating To-Do..."
        JobTreadCreateStage.SUCCESS -> if (isDemoCreate) "Demo Complete" else "Created"
        JobTreadCreateStage.IDLE -> if (isDemoCreate) "Run Demo Create" else "Create JobTread To-Do"
    }
}

private fun <T> resolutionLabel(
    resolution: JobTreadLookupResolution<T>,
    resolvedLabel: (T) -> String
): String {
    return when (resolution) {
        JobTreadLookupResolution.NotRequested -> "Not requested"
        is JobTreadLookupResolution.Resolved -> resolvedLabel(resolution.match)
        is JobTreadLookupResolution.Unresolved -> "Unresolved: ${resolution.referenceText}"
        is JobTreadLookupResolution.Ambiguous -> buildString {
            append("Ambiguous: ")
            append(
                resolution.candidates.joinToString(" | ") { candidate ->
                    resolvedLabel(candidate)
                }
            )
        }
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
