package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.CreateTodoParseResult
import com.example.voicenoteapp.assistant.CreateTodoParser
import com.example.voicenoteapp.assistant.CreateTodoParserDescriptor
import com.example.voicenoteapp.assistant.CreateTodoParserMode
import com.example.voicenoteapp.jobtread.JobTreadCreateReadiness
import com.example.voicenoteapp.jobtread.JobTreadLookupLoadResult
import com.example.voicenoteapp.jobtread.JobTreadLookupRepository
import com.example.voicenoteapp.jobtread.JobTreadResolutionSummary
import com.example.voicenoteapp.jobtread.JobTreadResolvers
import com.example.voicenoteapp.settings.AssistantSettings
import com.example.voicenoteapp.settings.CredentialStore
import com.example.voicenoteapp.voice.SpeechParsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class JobTreadAssistantStage {
    IDLE,
    PROMPTING,
    LISTENING,
    RESULT,
    ERROR
}

enum class JobTreadLookupStage {
    IDLE,
    LOADING,
    READY,
    ERROR
}

data class JobTreadAssistantUiState(
    val stage: JobTreadAssistantStage = JobTreadAssistantStage.IDLE,
    val prompt: String = "How can I help?",
    val transcript: String = "",
    val parsedIntent: CreateTodoIntent? = null,
    val parserMode: CreateTodoParserMode = CreateTodoParserMode.FALLBACK,
    val parserLabel: String = "",
    val settings: AssistantSettings = AssistantSettings(),
    val errorMessage: String? = null,
    val placeholderMessage: String? = null,
    val lookupStage: JobTreadLookupStage = JobTreadLookupStage.IDLE,
    val lookupSummary: JobTreadResolutionSummary? = null,
    val lookupErrorMessage: String? = null,
    val captureNonce: Int = 0
) {
    val createReadiness: JobTreadCreateReadiness
        get() = JobTreadResolvers.determineCreateReadiness(
            intent = parsedIntent,
            hasJobTreadConfig = settings.hasJobTreadConfig,
            lookupInFlight = lookupStage == JobTreadLookupStage.LOADING,
            lookupErrorMessage = lookupErrorMessage,
            summary = lookupSummary
        )

    val canConfirmPlaceholder: Boolean
        get() = parsedIntent != null && createReadiness == JobTreadCreateReadiness.READY
}

class JobTreadAssistantViewModel(
    credentialStore: CredentialStore,
    private val createTodoParser: CreateTodoParser,
    private val jobTreadLookupRepository: JobTreadLookupRepository
) : ViewModel() {
    private val initialDescriptor = createTodoParser.describe(AssistantSettings())
    private val _uiState = MutableStateFlow(
        JobTreadAssistantUiState(
            parserMode = initialDescriptor.mode,
            parserLabel = initialDescriptor.parserLabel
        )
    )
    val uiState = _uiState.asStateFlow()
    private var currentSettings = AssistantSettings()

    init {
        viewModelScope.launch {
            credentialStore.settings.collect { settings ->
                currentSettings = settings
                val descriptor = createTodoParser.describe(settings)
                _uiState.update {
                    it.copy(
                        settings = settings,
                        parserMode = descriptor.mode,
                        parserLabel = descriptor.parserLabel
                    )
                }
            }
        }
    }

    fun startCapture() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.PROMPTING,
            transcript = "",
            parsedIntent = null,
            errorMessage = null,
            placeholderMessage = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null,
            captureNonce = _uiState.value.captureNonce + 1
        )
    }

    fun onListeningStarted() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.LISTENING,
            errorMessage = null,
            placeholderMessage = null
        )
    }

    fun onSpeechResult(transcript: String) {
        val cleaned = SpeechParsing.clean(transcript)
        if (cleaned.isBlank()) {
            onNoTranscript()
            return
        }

        _uiState.value = _uiState.value.copy(
            transcript = cleaned,
            parsedIntent = null,
            errorMessage = null,
            placeholderMessage = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null
        )

        viewModelScope.launch {
            when (val result = createTodoParser.parse(cleaned, currentSettings)) {
                is CreateTodoParseResult.Success -> {
                    val parsedIntent = result.intent
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.RESULT,
                            parsedIntent = parsedIntent,
                            errorMessage = null
                        )
                    }
                    resolveLookups(parsedIntent)
                }

                is CreateTodoParseResult.MissingConfiguration -> {
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.ERROR,
                            parsedIntent = null,
                            errorMessage = result.message
                        )
                    }
                }

                is CreateTodoParseResult.Failure -> {
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.ERROR,
                            parsedIntent = null,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun onSpeechError(message: String) {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.ERROR,
            errorMessage = message,
            transcript = "",
            parsedIntent = null,
            placeholderMessage = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null
        )
    }

    fun onNoTranscript() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.ERROR,
            errorMessage = "No transcript was captured. Try again.",
            transcript = "",
            parsedIntent = null,
            placeholderMessage = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null
        )
    }

    fun onConfirmPlaceholder() {
        val readiness = _uiState.value.createReadiness
        _uiState.value = _uiState.value.copy(
            placeholderMessage = if (readiness == JobTreadCreateReadiness.READY) {
                "Lookup resolution is complete. The final JobTread create To-Do call comes next."
            } else {
                readiness.label
            }
        )
    }

    private suspend fun resolveLookups(intent: CreateTodoIntent) {
        if (!currentSettings.hasJobTreadConfig) {
            return
        }
        if (!requiresJobTreadLookup(intent)) {
            return
        }

        _uiState.update {
            it.copy(
                lookupStage = JobTreadLookupStage.LOADING,
                lookupSummary = null,
                lookupErrorMessage = null
            )
        }

        when (val result = jobTreadLookupRepository.loadLookupSnapshot(currentSettings)) {
            is JobTreadLookupLoadResult.Success -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.READY,
                        lookupSummary = JobTreadResolvers.resolve(intent, result.snapshot),
                        lookupErrorMessage = null
                    )
                }
            }

            is JobTreadLookupLoadResult.MissingConfiguration -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.ERROR,
                        lookupSummary = null,
                        lookupErrorMessage = result.message
                    )
                }
            }

            is JobTreadLookupLoadResult.AmbiguousOrganization -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.ERROR,
                        lookupSummary = null,
                        lookupErrorMessage = result.message
                    )
                }
            }

            is JobTreadLookupLoadResult.Failure -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.ERROR,
                        lookupSummary = null,
                        lookupErrorMessage = result.message
                    )
                }
            }
        }
    }

    private fun requiresJobTreadLookup(intent: CreateTodoIntent): Boolean {
        return !intent.todo.assigneeReferenceText.isNullOrBlank() || !intent.todo.jobReferenceText.isNullOrBlank()
    }

    private fun applyParserResult(
        descriptor: CreateTodoParserDescriptor,
        update: JobTreadAssistantUiState.() -> JobTreadAssistantUiState
    ) {
        _uiState.update { current ->
            with(
                current.copy(
                    parserMode = descriptor.mode,
                    parserLabel = descriptor.parserLabel
                ),
                update
            )
        }
    }
}
