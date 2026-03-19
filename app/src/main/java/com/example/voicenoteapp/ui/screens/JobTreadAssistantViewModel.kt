package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.MockCreateTodoIntentParser
import com.example.voicenoteapp.voice.SpeechParsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val errorMessage: String? = null,
    val placeholderMessage: String? = null,
    val captureNonce: Int = 0
)

class JobTreadAssistantViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(JobTreadAssistantUiState())
    val uiState = _uiState.asStateFlow()

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
            stage = JobTreadAssistantStage.RESULT,
            transcript = cleaned,
            parsedIntent = MockCreateTodoIntentParser.parse(cleaned),
            errorMessage = null
        )
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
            placeholderMessage = "JobTread API hookup comes next. This confirmation is local-only for now."
        )
    }
}
