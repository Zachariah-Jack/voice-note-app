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
import app.voicenote.wizard.CreateTodoExecutionStatus
import app.voicenote.wizard.CreateTodoReadinessStatus
import app.voicenote.wizard.DraftStatus
import app.voicenote.wizard.HttpOpenAiResponsesTransport
import app.voicenote.wizard.JobTreadLookupConfig
import app.voicenote.wizard.JobTreadOrganizationSelectionState
import app.voicenote.wizard.JsonFileAppStateStore
import app.voicenote.wizard.LiveJobTreadCreateTodoRepository
import app.voicenote.wizard.OpenAiWizardClientConfig
import app.voicenote.wizard.OpenAiWizardTurnClient
import app.voicenote.wizard.ReadOnlyJobTreadLookupRepository
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
    private lateinit var primarySessionButton: Button
    private lateinit var devInfoContainer: LinearLayout
    private lateinit var activeSessionContainer: LinearLayout
    private lateinit var activeSessionTextView: TextView
    private lateinit var draftListContainer: LinearLayout
    private lateinit var emptyDraftsTextView: TextView
    private lateinit var assistantMessageTextView: TextView
    private lateinit var partialTranscriptTextView: TextView
    private lateinit var finalTranscriptTextView: TextView
    private lateinit var jobTreadOrganizationTextView: TextView
    private lateinit var refreshJobTreadOrganizationsButton: Button
    private lateinit var jobTreadOrganizationOptionsContainer: LinearLayout
    private lateinit var jobTreadLookupTextView: TextView
    private lateinit var createTodoReadinessTextView: TextView
    private lateinit var createTodoBlockersTextView: TextView
    private lateinit var createTodoSummaryTextView: TextView
    private lateinit var createTodoExecutionTextView: TextView
    private lateinit var confirmCreateTodoButton: Button
    private lateinit var unconfirmCreateTodoButton: Button
    private lateinit var sendCreateTodoButton: Button
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
        setStatusNotice(getString(R.string.record_audio_permission_denied), isCritical = true)
        renderState(store.load())
    }

    override fun onDestroy() {
        if (isFinishing) {
            releaseVoiceRuntime()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        primarySessionButton = findViewById(R.id.primarySessionButton)
        devInfoContainer = findViewById(R.id.devInfoContainer)
        activeSessionContainer = findViewById(R.id.activeSessionContainer)
        activeSessionTextView = findViewById(R.id.activeSessionTextView)
        draftListContainer = findViewById(R.id.draftListContainer)
        emptyDraftsTextView = findViewById(R.id.emptyDraftsTextView)
        assistantMessageTextView = findViewById(R.id.assistantMessageTextView)
        partialTranscriptTextView = findViewById(R.id.partialTranscriptTextView)
        finalTranscriptTextView = findViewById(R.id.finalTranscriptTextView)
        jobTreadOrganizationTextView = findViewById(R.id.jobTreadOrganizationTextView)
        refreshJobTreadOrganizationsButton = findViewById(R.id.refreshJobTreadOrganizationsButton)
        jobTreadOrganizationOptionsContainer = findViewById(R.id.jobTreadOrganizationOptionsContainer)
        jobTreadLookupTextView = findViewById(R.id.jobTreadLookupTextView)
        createTodoReadinessTextView = findViewById(R.id.createTodoReadinessTextView)
        createTodoBlockersTextView = findViewById(R.id.createTodoBlockersTextView)
        createTodoSummaryTextView = findViewById(R.id.createTodoSummaryTextView)
        createTodoExecutionTextView = findViewById(R.id.createTodoExecutionTextView)
        confirmCreateTodoButton = findViewById(R.id.confirmCreateTodoButton)
        unconfirmCreateTodoButton = findViewById(R.id.unconfirmCreateTodoButton)
        sendCreateTodoButton = findViewById(R.id.sendCreateTodoButton)
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
            jobTreadLookupRepository = ReadOnlyJobTreadLookupRepository(
                configProvider = ::buildJobTreadLookupConfig,
            ),
            jobTreadCreateTodoRepository = LiveJobTreadCreateTodoRepository(
                configProvider = ::buildJobTreadLookupConfig,
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
        primarySessionButton.setOnClickListener {
            handlePrimarySessionAction()
        }

        refreshJobTreadOrganizationsButton.setOnClickListener {
            refreshJobTreadOrganizations()
        }

        confirmCreateTodoButton.setOnClickListener {
            updateCreateTodoConfirmation(confirmed = true)
        }

        unconfirmCreateTodoButton.setOnClickListener {
            updateCreateTodoConfirmation(confirmed = false)
        }

        sendCreateTodoButton.setOnClickListener {
            executeCreateTodo()
        }
    }

    private fun startNewSessionFromInbox() {
        pendingResumeAfterPermission = false
        clearStatusNotice()
        try {
            if (isOpenAiConfigured() && hasRecordAudioPermission()) {
                voiceTurnController.startSession(initialAssistantMessage)
            } else {
                sessionLoopService.startNewSession()
                setStatusNotice(
                    message = when {
                        !isOpenAiConfigured() -> getString(R.string.missing_openai_api_key)
                        else -> getString(R.string.session_created_resume_when_ready)
                    },
                    isCritical = true,
                )
            }
        } catch (exception: Exception) {
            setStatusNotice(
                message = getString(
                    R.string.start_session_failed,
                    exception.message ?: exception::class.java.simpleName,
                ),
                isCritical = true,
            )
        }
        renderState(store.load())
    }

    private fun resumeSelectedDraft(draftId: String) {
        pendingResumeAfterPermission = false
        clearStatusNotice()
        try {
            val resumed = sessionLoopService.resumeDraft(draftId)
            if (resumed == null) {
                setStatusNotice(getString(R.string.resume_draft_failed))
            } else if (isOpenAiConfigured() && hasRecordAudioPermission()) {
                voiceTurnController.resumeSession()
            } else {
                setStatusNotice(
                    message = when {
                        !isOpenAiConfigured() -> getString(R.string.missing_openai_api_key)
                        else -> getString(R.string.draft_selected_resume_when_ready)
                    },
                    isCritical = true,
                )
            }
        } catch (exception: Exception) {
            setStatusNotice(
                getString(
                    R.string.resume_draft_failed_with_reason,
                    exception.message ?: exception::class.java.simpleName,
                ),
            )
        }
        renderState(store.load())
    }

    private fun resumeActiveVoiceSession() {
        pendingResumeAfterPermission = false
        clearStatusNotice()
        val state = store.load()
        if (state.session.draftId == null) {
            setStatusNotice(getString(R.string.no_active_session))
            renderState(state)
            return
        }
        if (!isOpenAiConfigured()) {
            setStatusNotice(getString(R.string.missing_openai_api_key), isCritical = true)
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
            setStatusNotice(
                message = getString(
                    R.string.start_session_failed,
                    exception.message ?: exception::class.java.simpleName,
                ),
                isCritical = true,
            )
        }
        renderState(store.load())
    }

    private fun refreshJobTreadOrganizations() {
        clearStatusNotice()
        try {
            sessionLoopService.refreshJobTreadOrganizations()
        } catch (exception: Exception) {
            setStatusNotice(
                getString(
                    R.string.start_session_failed,
                    exception.message ?: exception::class.java.simpleName,
                ),
            )
        }
        renderState(store.load())
    }

    private fun saveDefaultJobTreadOrganization(organizationId: String) {
        clearStatusNotice()
        try {
            sessionLoopService.saveDefaultJobTreadOrganization(organizationId)
        } catch (exception: Exception) {
            setStatusNotice(
                getString(
                    R.string.start_session_failed,
                    exception.message ?: exception::class.java.simpleName,
                ),
            )
        }
        renderState(store.load())
    }

    private fun updateCreateTodoConfirmation(confirmed: Boolean) {
        clearStatusNotice()
        val draft = store.load().displayDraft()
        if (draft == null) {
            renderState(store.load())
            return
        }
        try {
            sessionLoopService.updateCreateTodoConfirmation(
                draftId = draft.id,
                confirmed = confirmed,
            )
        } catch (exception: Exception) {
            setStatusNotice(
                getString(
                    R.string.start_session_failed,
                    exception.message ?: exception::class.java.simpleName,
                ),
            )
        }
        renderState(store.load())
    }

    private fun executeCreateTodo() {
        clearStatusNotice()
        val draft = store.load().displayDraft()
        if (draft == null) {
            renderState(store.load())
            return
        }

        turnExecutor.execute {
            try {
                sessionLoopService.executeConfirmedCreateTodo(draft.id)
            } catch (exception: Exception) {
                runOnUiThread {
                    setStatusNotice(
                        getString(
                            R.string.create_todo_send_failed,
                            exception.message ?: exception::class.java.simpleName,
                        ),
                    )
                    renderState(store.load())
                }
            }
        }
        renderState(store.load())
    }

    private fun handlePrimarySessionAction() {
        val state = store.load()
        if (state.session.phase in activeSessionPhases) {
            pendingResumeAfterPermission = false
            clearStatusNotice()
            voiceTurnController.cancel()
            renderState(store.load())
            return
        }

        if (state.session.draftId != null) {
            resumeActiveVoiceSession()
        } else {
            startNewSessionFromInbox()
        }
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
        val createTodo = draft?.createTodo
        val inboxState = DraftInboxViewStateFactory.create(state)

        renderActiveSession(inboxState)
        renderDraftList(inboxState, state)
        renderJobTreadOrganizationSelection(state.jobTreadOrganizationSelection)

        assistantMessageTextView.text = state.displayAssistantMessage(draft)
        partialTranscriptTextView.text = state.session.speechRecognition.partialTranscript
            ?: getString(R.string.empty_partial_transcript)
        finalTranscriptTextView.text = draft?.committedTranscriptText()
            ?: getString(R.string.empty_committed_transcript)
        jobTreadLookupTextView.text = draft?.jobTreadLookup?.summaryText()
            ?: getString(R.string.empty_jobtread_lookup)
        renderCreateTodoReview(draft)
        statusTextView.text = buildStatusText(state)
        renderPrimarySessionButton(state)
        devInfoContainer.visibility = View.GONE

        val sessionBusy = state.session.phase in activeSessionPhases
        refreshJobTreadOrganizationsButton.isEnabled = !sessionBusy
        confirmCreateTodoButton.isEnabled = !sessionBusy &&
            createTodo != null &&
            createTodo.readinessStatus == CreateTodoReadinessStatus.READY_FOR_CONFIRMATION &&
            !createTodo.isConfirmed
        unconfirmCreateTodoButton.isEnabled = !sessionBusy && createTodo?.isConfirmed == true
        sendCreateTodoButton.isEnabled = !sessionBusy &&
            createTodo != null &&
            createTodo.readinessStatus == CreateTodoReadinessStatus.READY_FOR_CONFIRMATION &&
            createTodo.isConfirmed &&
            createTodo.execution.status != CreateTodoExecutionStatus.SENDING &&
            createTodo.execution.status != CreateTodoExecutionStatus.SUCCESS
    }

    private fun renderPrimarySessionButton(state: WizardAppState) {
        primarySessionButton.text = when {
            state.session.phase in activeSessionPhases -> getString(R.string.end_voice_chat)
            else -> getString(R.string.start_voice_chat)
        }
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

    private fun renderJobTreadOrganizationSelection(selection: JobTreadOrganizationSelectionState) {
        jobTreadOrganizationTextView.text = selection.summaryText()
        jobTreadOrganizationOptionsContainer.removeAllViews()

        if (selection.organizations.size <= 1) {
            return
        }

        selection.organizations.forEach { organization ->
            jobTreadOrganizationOptionsContainer.addView(
                Button(this).apply {
                    val isDefault = organization.id == selection.defaultOrganizationId
                    text = if (isDefault) {
                        getString(R.string.saved_jobtread_default_organization, organization.name)
                    } else {
                        getString(R.string.save_jobtread_default_organization, organization.name)
                    }
                    isEnabled = !isDefault
                    setOnClickListener {
                        saveDefaultJobTreadOrganization(organization.id)
                    }
                },
            )
        }
    }

    private fun renderCreateTodoReview(draft: WizardDraft?) {
        val createTodo = draft?.createTodo
        if (createTodo == null) {
            createTodoReadinessTextView.text = getString(R.string.empty_create_todo_readiness)
            createTodoBlockersTextView.text = getString(R.string.empty_create_todo_blockers)
            createTodoSummaryTextView.text = getString(R.string.empty_create_todo_summary)
            createTodoExecutionTextView.text = getString(R.string.empty_create_todo_execution)
            return
        }

        val confirmedSuffix = if (createTodo.isConfirmed) {
            getString(R.string.create_todo_confirmed_suffix)
        } else {
            ""
        }
        createTodoReadinessTextView.text = getString(
            R.string.create_todo_state,
            createTodo.readinessStatus.name,
            confirmedSuffix,
        )
        createTodoBlockersTextView.text = if (createTodo.blockers.isEmpty()) {
            getString(R.string.empty_create_todo_blockers)
        } else {
            getString(
                R.string.create_todo_blockers_prefix,
                createTodo.blockers.joinToString(separator = "\n") { blocker -> "- ${blocker.message}" },
            )
        }
        createTodoSummaryTextView.text = createTodo.confirmationSummary?.let { summary ->
            getString(
                R.string.create_todo_summary_format,
                summary.organizationName,
                summary.jobLabel,
                summary.title,
            )
        } ?: getString(R.string.empty_create_todo_summary)
        createTodoExecutionTextView.text = when (createTodo.execution.status) {
            CreateTodoExecutionStatus.IDLE -> getString(R.string.empty_create_todo_execution)
            CreateTodoExecutionStatus.SENDING -> getString(R.string.create_todo_sending)
            CreateTodoExecutionStatus.SUCCESS -> createTodo.execution.createdTodo?.let { result ->
                buildString {
                    appendLine(getString(R.string.create_todo_success_header))
                    appendLine(getString(R.string.create_todo_success_id, result.id))
                    appendLine(getString(R.string.create_todo_success_title, result.title))
                    appendLine(getString(R.string.create_todo_success_job, result.jobLabel))
                    result.createdAtIso?.let { createdAt ->
                        append(getString(R.string.create_todo_success_created_at, createdAt))
                    }
                }.trim()
            } ?: getString(R.string.empty_create_todo_execution)

            CreateTodoExecutionStatus.FAILURE -> getString(
                R.string.create_todo_failure_format,
                createTodo.execution.failureMessage ?: getString(R.string.create_todo_failure_unknown),
            )
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
        return segments.joinToString(separator = " | ")
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
        if (!isJobTreadConfigured()) {
            lines += getString(R.string.status_jobtread_not_configured)
        }
        return lines.joinToString(separator = "\n")
    }

    private fun clearStatusNotice() {
        statusNotice = null
    }

    private fun setStatusNotice(
        message: String,
        isCritical: Boolean = false,
    ) {
        statusNotice = message
        if (isCritical) {
            // Notices now render in Dev Info only, but callers still distinguish critical cases.
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isOpenAiConfigured(): Boolean = BuildConfig.OPENAI_API_KEY.isNotBlank()

    private fun isJobTreadConfigured(): Boolean =
        BuildConfig.JOBTREAD_PAVE_URL.isNotBlank() && BuildConfig.JOBTREAD_GRANT_KEY.isNotBlank()

    private fun buildJobTreadLookupConfig(): JobTreadLookupConfig? {
        if (!isJobTreadConfigured()) {
            return null
        }
        return JobTreadLookupConfig(
            paveUrl = BuildConfig.JOBTREAD_PAVE_URL,
            grantKey = BuildConfig.JOBTREAD_GRANT_KEY,
        )
    }

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
