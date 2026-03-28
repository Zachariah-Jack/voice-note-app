package app.voicenote.demo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.voicenote.android.speech.AndroidSpeechRecognizerGateway
import app.voicenote.android.tts.AndroidTextToSpeechAssistantSpeaker
import app.voicenote.wizard.DraftStatus
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
    private lateinit var newSessionButton: Button
    private lateinit var activeSessionContainer: LinearLayout
    private lateinit var activeSessionTextView: TextView
    private lateinit var draftListContainer: LinearLayout
    private lateinit var emptyDraftsTextView: TextView
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
    private var pendingResumeAfterPermission = false

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

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && pendingResumeAfterPermission) {
            resumeActiveVoiceSession()
            return
        }

        pendingResumeAfterPermission = false
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
        newSessionButton = findViewById(R.id.newSessionButton)
        activeSessionContainer = findViewById(R.id.activeSessionContainer)
        activeSessionTextView = findViewById(R.id.activeSessionTextView)
        draftListContainer = findViewById(R.id.draftListContainer)
        emptyDraftsTextView = findViewById(R.id.emptyDraftsTextView)
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
        newSessionButton.setOnClickListener {
            startNewSessionFromInbox()
        }

        startSessionButton.setOnClickListener {
            resumeActiveVoiceSession()
        }

        stopSessionButton.setOnClickListener {
            pendingResumeAfterPermission = false
            statusNotice = null
            voiceTurnController.cancel()
            renderState(store.load())
        }
    }

    private fun startNewSessionFromInbox() {
        pendingResumeAfterPermission = false
        statusNotice = null
        try {
            if (isOpenAiConfigured() && hasRecordAudioPermission()) {
                voiceTurnController.startSession(initialAssistantMessage)
            } else {
                sessionLoopService.startNewSession()
                statusNotice = when {
                    !isOpenAiConfigured() -> getString(R.string.missing_openai_api_key)
                    else -> getString(R.string.session_created_resume_when_ready)
                }
            }
        } catch (exception: Exception) {
            statusNotice = getString(
                R.string.start_session_failed,
                exception.message ?: exception::class.java.simpleName,
            )
        }
        renderState(store.load())
    }

    private fun resumeSelectedDraft(draftId: String) {
        pendingResumeAfterPermission = false
        statusNotice = null
        try {
            val resumed = sessionLoopService.resumeDraft(draftId)
            if (resumed == null) {
                statusNotice = getString(R.string.resume_draft_failed)
            } else if (isOpenAiConfigured() && hasRecordAudioPermission()) {
                voiceTurnController.resumeSession()
            } else {
                statusNotice = when {
                    !isOpenAiConfigured() -> getString(R.string.missing_openai_api_key)
                    else -> getString(R.string.draft_selected_resume_when_ready)
                }
            }
        } catch (exception: Exception) {
            statusNotice = getString(
                R.string.resume_draft_failed_with_reason,
                exception.message ?: exception::class.java.simpleName,
            )
        }
        renderState(store.load())
    }

    private fun resumeActiveVoiceSession() {
        pendingResumeAfterPermission = false
        statusNotice = null
        val state = store.load()
        if (state.session.draftId == null) {
            statusNotice = getString(R.string.no_active_session)
            renderState(state)
            return
        }
        if (!isOpenAiConfigured()) {
            statusNotice = getString(R.string.missing_openai_api_key)
            renderState(state)
            return
        }
        if (!hasRecordAudioPermission()) {
            pendingResumeAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestRecordAudioCode)
            renderState(state)
            return
        }

        try {
            voiceTurnController.resumeSession()
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
        val inboxState = DraftInboxViewStateFactory.create(state)

        renderActiveSession(inboxState)
        renderDraftList(inboxState, state)

        assistantMessageTextView.text = state.displayAssistantMessage(draft)
        partialTranscriptTextView.text = state.session.speechRecognition.partialTranscript
            ?: getString(R.string.empty_partial_transcript)
        finalTranscriptTextView.text = draft?.committedTranscriptText()
            ?: getString(R.string.empty_committed_transcript)
        statusTextView.text = buildStatusText(state)

        val sessionBusy = state.session.phase in activeSessionPhases
        newSessionButton.isEnabled = !sessionBusy
        startSessionButton.isEnabled = state.session.draftId != null && !sessionBusy
        stopSessionButton.isEnabled = state.session.draftId != null || state.session.phase != SessionPhase.IDLE
    }

    private fun renderActiveSession(inboxState: DraftInboxViewState) {
        val activeSession = inboxState.activeSession
        if (activeSession == null) {
            activeSessionContainer.visibility = View.GONE
            activeSessionTextView.text = ""
            return
        }

        activeSessionContainer.visibility = View.VISIBLE
        activeSessionTextView.text = buildString {
            appendLine(
                if (activeSession.isRecoverablePaused) {
                    getString(R.string.recoverable_paused_session_label)
                } else {
                    getString(R.string.active_session_label)
                },
            )
            appendLine(
                getString(
                    R.string.active_session_summary,
                    activeSession.status.name,
                    activeSession.phase.name,
                    activeSession.transcriptCount,
                ),
            )
            append(
                getString(
                    R.string.active_session_snippet,
                    activeSession.lastSnippet,
                ),
            )
        }.trim()
    }

    private fun renderDraftList(
        inboxState: DraftInboxViewState,
        state: WizardAppState,
    ) {
        draftListContainer.removeAllViews()
        emptyDraftsTextView.visibility = if (inboxState.drafts.isEmpty()) View.VISIBLE else View.GONE

        val sessionBusy = state.session.phase in activeSessionPhases
        inboxState.drafts.forEachIndexed { index, item ->
            draftListContainer.addView(
                buildDraftRow(
                    item = item,
                    enableResume = !sessionBusy && item.status == DraftStatus.IN_PROGRESS,
                ),
            )
            if (index < inboxState.drafts.lastIndex) {
                draftListContainer.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(1),
                        ).apply {
                            topMargin = dpToPx(8)
                            bottomMargin = dpToPx(8)
                        }
                        setBackgroundColor(0xFFDDDDDD.toInt())
                    },
                )
            }
        }
    }

    private fun buildDraftRow(
        item: DraftInboxItem,
        enableResume: Boolean,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, dpToPx(4), 0, dpToPx(4))

        addView(
            TextView(context).apply {
                text = buildDraftMetaText(item)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        addView(
            TextView(context).apply {
                text = getString(
                    R.string.draft_updated_time,
                    DateUtils.getRelativeTimeSpanString(
                        item.lastUpdatedEpochMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
            },
        )
        addView(
            TextView(context).apply {
                text = getString(R.string.draft_snippet, item.lastSnippet)
            },
        )
        addView(
            Button(context).apply {
                text = getString(R.string.resume_draft)
                isEnabled = enableResume
                setOnClickListener {
                    resumeSelectedDraft(item.draftId)
                }
            },
        )
    }

    private fun buildDraftMetaText(item: DraftInboxItem): String {
        val segments = mutableListOf(
            item.status.name,
            getString(R.string.draft_turn_count, item.transcriptCount),
        )
        item.sessionPhase?.let { phase ->
            segments += getString(R.string.draft_phase, phase.name)
        }
        if (item.isActive) {
            segments += getString(R.string.draft_active_marker)
        }
        return segments.joinToString(separator = " • ")
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

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

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
