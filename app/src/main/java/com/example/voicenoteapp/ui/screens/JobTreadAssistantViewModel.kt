package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.CreateTodoParseResult
import com.example.voicenoteapp.assistant.CreateTodoParser
import com.example.voicenoteapp.settings.AssistantConfigField
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

data class JobTreadAssistantUiState(
    val stage: JobTreadAssistantStage = JobTreadAssistantStage.IDLE,
    val prompt: String = "How can I help?",
    val transcript: String = "",
    val parsedIntent: CreateTodoIntent? = null,
    val parserLabel: String = "",
    val settings: AssistantSettings = AssistantSettings(),
    val missingConfiguration: List<AssistantConfigField> = emptyList(),
    val errorMessage: String? = null,
    val placeholderMessage: String? = null,
    val captureNonce: Int = 0
) {
    val canConfirmPlaceholder: Boolean
        get() = parsedIntent != null && settings.hasJobTreadConfig
}

class JobTreadAssistantViewModel(
    credentialStore: CredentialStore,
    private val createTodoParser: CreateTodoParser
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        JobTreadAssistantUiState(parserLabel = createTodoParser.parserLabel)
    )
    val uiState = _uiState.asStateFlow()
    private var currentSettings = AssistantSettings()

    init {
        viewModelScope.launch {
            credentialStore.settings.collect { settings ->
                currentSettings = settings
                _uiState.update {
                    it.copy(
                        settings = settings,
                        missingConfiguration = settings.missingFields
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
            placeholderMessage = null
        )

        viewModelScope.launch {
            when (val result = createTodoParser.parse(cleaned, currentSettings)) {
                is CreateTodoParseResult.Success -> {
                    _uiState.update {
                        it.copy(
                            stage = JobTreadAssistantStage.RESULT,
                            parsedIntent = result.intent,
                            errorMessage = null,
                            parserLabel = result.parserLabel
                        )
                    }
                }

                is CreateTodoParseResult.MissingConfiguration -> {
                    _uiState.update {
                        it.copy(
                            stage = JobTreadAssistantStage.ERROR,
                            parsedIntent = null,
                            errorMessage = result.message,
                            parserLabel = result.parserLabel
                        )
                    }
                }

                is CreateTodoParseResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            stage = JobTreadAssistantStage.ERROR,
                            parsedIntent = null,
                            errorMessage = result.message,
                            parserLabel = result.parserLabel
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
            parsedIntent = null
        )
    }

    fun onNoTranscript() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.ERROR,
            errorMessage = "No transcript was captured. Try again.",
            transcript = "",
            parsedIntent = null
        )
    }

    fun onConfirmPlaceholder() {
        _uiState.value = _uiState.value.copy(
            placeholderMessage = if (_uiState.value.settings.hasJobTreadConfig) {
                "JobTread API hookup comes next. This confirmation is local-only for now."
            } else {
                "JobTread API settings are still missing. Save the base URL and API key in Settings before the next stage."
            }
        )
    }
}
