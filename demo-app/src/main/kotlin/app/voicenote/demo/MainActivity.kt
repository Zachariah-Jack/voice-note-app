package app.voicenote.demo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import app.voicenote.android.speech.AndroidSpeechRecognizerGateway
import app.voicenote.android.tts.AndroidTextToSpeechAssistantSpeaker
import app.voicenote.wizard.HttpOpenAiResponsesTransport
import app.voicenote.wizard.JsonFileAppStateStore
import app.voicenote.wizard.OpenAiWizardClientConfig
import app.voicenote.wizard.OpenAiWizardTurnClient
import app.voicenote.wizard.SessionLoopService
import app.voicenote.wizard.SessionPhase
import app.voicenote.wizard.TranscriptSpeaker
import app.voicenote.wizard.VoiceTurnController
import app.voicenote.wizard.WizardAppState
import app.voicenote.wizard.WizardDraft
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var startSessionButton: Button
    private lateinit var stopSessionButton: Button
    private lateinit var assistantMessageTextView: TextView
    private lateinit var partialTranscriptTextView: TextView
    private lateinit var finalTranscriptTextView: TextView
    private lateinit var statusTextView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val turnExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var store: ObservableAppStateStore
    private lateinit var speaker: AndroidTextToSpeechAssistantSpeaker
    private lateinit var recognizer: AndroidSpeechRecognizerGateway
    private lateinit var sessionLoopService: SessionLoopService
    private lateinit var voiceTurnController: VoiceTurnController

    private var statusNotice: String? = null
    private var pendingStartAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initializeVoiceRuntime()
        bindActions()
        renderState(store.load())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != requestRecordAudioCode) {
            return
        }

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && pendingStartAfterPermission) {
            startVoiceSession()
            return
        }

        pendingStartAfterPermission = false
        statusNotice = getString(R.string.record_audio_permission_denied)
        renderState(store.load())
    }

    override fun onDestroy() {
        if (isFinishing) {
            releaseVoiceRuntime()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        startSessionButton = findViewById(R.id.startSessionButton)
        stopSessionButton = findViewById(R.id.stopSessionButton)
        assistantMessageTextView = findViewById(R.id.assistantMessageTextView)
        partialTranscriptTextView = findViewById(R.id.partialTranscriptTextView)
        finalTranscriptTextView = findViewById(R.id.finalTranscriptTextView)
        statusTextView = findViewById(R.id.statusTextView)
    }

    private fun initializeVoiceRuntime() {
        val stateFile = File(filesDir, "wizard-demo/app-state.json").toPath()
        store = ObservableAppStateStore(
            delegate = JsonFileAppStateStore(stateFile),
            onSave = { state ->
                runOnUiThread {
                    renderState(state)
                }
            },
        )
        speaker = AndroidTextToSpeechAssistantSpeaker(applicationContext)
        recognizer = AndroidSpeechRecognizerGateway(applicationContext)
        sessionLoopService = SessionLoopService(
            store = store,
            wizardTurnClient = OpenAiWizardTurnClient(
                config = OpenAiWizardClientConfig(
                    apiKey = BuildConfig.OPENAI_API_KEY,
                    model = BuildConfig.OPENAI_WIZARD_MODEL.ifBlank {
                        OpenAiWizardClientConfig.DEFAULT_MODEL
                    },
                    baseUrl = BuildConfig.OPENAI_BASE_URL.ifBlank {
                        OpenAiWizardClientConfig.DEFAULT_BASE_URL
                    },
                    organization = BuildConfig.OPENAI_ORGANIZATION.ifBlank { null },
                ),
                transport = HttpOpenAiResponsesTransport(),
            ),
            assistantSpeaker = speaker,
        )
        voiceTurnController = VoiceTurnController(
            store = store,
            sessionLoopService = sessionLoopService,
            speechRecognizerGateway = recognizer,
            eventExecutor = mainExecutor,
            turnExecutor = turnExecutor,
        )
    }

    private fun bindActions() {
        startSessionButton.setOnClickListener {
            if (!isOpenAiConfigured()) {
                statusNotice = getString(R.string.missing_openai_api_key)
                renderState(store.load())
            } else if (!hasRecordAudioPermission()) {
                pendingStartAfterPermission = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestRecordAudioCode)
            } else {
                startVoiceSession()
            }
        }

        stopSessionButton.setOnClickListener {
            pendingStartAfterPermission = false
            statusNotice = null
            voiceTurnController.cancel()
            renderState(store.load())
        }
    }

    private fun startVoiceSession() {
        pendingStartAfterPermission = false
        statusNotice = null
        try {
            voiceTurnController.startSession(initialAssistantMessage)
        } catch (exception: Exception) {
            statusNotice = getString(
                R.string.start_session_failed,
                exception.message ?: exception::class.java.simpleName,
            )
        }
        renderState(store.load())
    }

    private fun releaseVoiceRuntime() {
        try {
            voiceTurnController.cancel()
        } catch (_: Exception) {
            // Best-effort cleanup during activity shutdown.
        }
        sessionLoopService.setAssistantSpeakerEventObserver(null)
        recognizer.setEventListener(null)
        try {
            recognizer.release()
        } catch (_: Exception) {
            // Best-effort cleanup during activity shutdown.
        }
        try {
            speaker.release()
        } catch (_: Exception) {
            // Best-effort cleanup during activity shutdown.
        }
        turnExecutor.shutdownNow()
    }

    private fun renderState(state: WizardAppState) {
        val draft = state.displayDraft()
        assistantMessageTextView.text = state.displayAssistantMessage(draft)
        partialTranscriptTextView.text = state.session.speechRecognition.partialTranscript
            ?: getString(R.string.empty_partial_transcript)
        finalTranscriptTextView.text = draft?.committedTranscriptText()
            ?: getString(R.string.empty_committed_transcript)
        statusTextView.text = buildStatusText(state)

        startSessionButton.isEnabled = state.session.phase !in activeSessionPhases
        stopSessionButton.isEnabled = state.session.draftId != null || state.session.phase != SessionPhase.IDLE
    }

    private fun buildStatusText(state: WizardAppState): String {
        val lines = mutableListOf<String>()
        statusNotice?.let(lines::add)
        lines += getString(R.string.status_phase, state.session.phase.name)
        lines += getString(
            R.string.status_assistant,
            state.session.assistantSpeech.status.name,
        )
        state.session.assistantSpeech.errorCode?.let { errorCode ->
            lines += getString(R.string.status_assistant_error_code, errorCode)
        }
        state.session.assistantSpeech.errorMessage?.let { errorMessage ->
            lines += getString(R.string.status_assistant_error_message, errorMessage)
        }
        lines += getString(
            R.string.status_recognition,
            state.session.speechRecognition.status.name,
        )
        state.session.speechRecognition.errorType?.let { errorType ->
            lines += getString(R.string.status_recognition_error, errorType.name)
        }
        state.session.speechRecognition.errorCode?.let { errorCode ->
            lines += getString(R.string.status_recognition_error_code, errorCode)
        }
        lines += if (hasRecordAudioPermission()) {
            getString(R.string.status_microphone_ready)
        } else {
            getString(R.string.status_microphone_permission_needed)
        }
        if (!isOpenAiConfigured()) {
            lines += getString(R.string.status_openai_not_configured)
        }
        return lines.joinToString(separator = "\n")
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isOpenAiConfigured(): Boolean = BuildConfig.OPENAI_API_KEY.isNotBlank()

    private fun WizardAppState.displayDraft(): WizardDraft? =
        session.draftId?.let { activeDraftId ->
            drafts.firstOrNull { draft -> draft.id == activeDraftId }
        } ?: drafts.maxByOrNull { draft -> draft.updatedAtEpochMillis }

    private fun WizardAppState.displayAssistantMessage(draft: WizardDraft?): String =
        session.assistantSpeech.message
            ?: draft?.transcript
                ?.lastOrNull { turn -> turn.speaker == TranscriptSpeaker.WIZARD }
                ?.text
            ?: getString(R.string.empty_assistant_message)

    private fun WizardDraft.committedTranscriptText(): String =
        transcript.joinToString(separator = "\n") { turn ->
            val speaker = when (turn.speaker) {
                TranscriptSpeaker.USER -> getString(R.string.transcript_user_prefix)
                TranscriptSpeaker.WIZARD -> getString(R.string.transcript_assistant_prefix)
            }
            "$speaker ${turn.text}"
        }.ifBlank { getString(R.string.empty_committed_transcript) }

    companion object {
        private const val requestRecordAudioCode = 1_001
        private const val initialAssistantMessage =
            "Hi. I am ready for one voice note turn. When I finish speaking, say your note."

        private val activeSessionPhases = setOf(
            SessionPhase.SPEAKING_ASSISTANT,
            SessionPhase.LISTENING_USER,
            SessionPhase.RUNNING_WIZARD_TURN,
        )
    }
}
