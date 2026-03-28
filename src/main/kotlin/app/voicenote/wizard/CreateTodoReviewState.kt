package app.voicenote.wizard

import kotlinx.serialization.Serializable

@Serializable
data class CreateTodoDraftState(
    val title: String? = null,
    val executionRequested: Boolean = false,
    val readinessStatus: CreateTodoReadinessStatus = CreateTodoReadinessStatus.NOT_READY,
    val blockers: List<CreateTodoBlocker> = emptyList(),
    val confirmationSummary: CreateTodoConfirmationSummary? = null,
    val isConfirmed: Boolean = false,
    val updatedAtEpochMillis: Long = 0L,
)

@Serializable
data class CreateTodoBlocker(
    val code: CreateTodoBlockerCode,
    val message: String,
)

@Serializable
data class CreateTodoConfirmationSummary(
    val organizationId: String,
    val organizationName: String,
    val jobId: String,
    val jobLabel: String,
    val title: String,
)

enum class CreateTodoReadinessStatus {
    NOT_READY,
    BLOCKED,
    REVIEWABLE,
    READY_FOR_CONFIRMATION,
}

enum class CreateTodoBlockerCode {
    JOBTREAD_ORGANIZATION_UNRESOLVED,
    JOB_LOOKUP_UNRESOLVED,
    AMBIGUOUS_JOB_MATCH,
    LOOKUP_FAILURE,
    MISSING_TITLE,
}

object CreateTodoReviewStateCalculator {
    fun recalculate(
        draft: WizardDraft,
        organizationSelection: JobTreadOrganizationSelectionState,
        nowEpochMillis: Long,
    ): WizardDraft {
        val currentState = draft.createTodo
        val normalizedTitle = currentState.title?.trim().takeUnless { it.isNullOrEmpty() }
        val hasCreateTodoSignal = currentState.executionRequested ||
            normalizedTitle != null ||
            currentState.isConfirmed ||
            currentState.confirmationSummary != null

        val blockers = if (hasCreateTodoSignal) {
            computeBlockers(
                draft = draft,
                normalizedTitle = normalizedTitle,
                organizationSelection = organizationSelection,
            )
        } else {
            emptyList()
        }

        val summary = if (blockers.isEmpty()) {
            buildConfirmationSummary(
                draft = draft,
                normalizedTitle = normalizedTitle,
                organizationSelection = organizationSelection,
            )
        } else {
            null
        }

        val readinessStatus = when {
            !hasCreateTodoSignal -> CreateTodoReadinessStatus.NOT_READY
            blockers.isNotEmpty() -> CreateTodoReadinessStatus.BLOCKED
            currentState.executionRequested -> CreateTodoReadinessStatus.READY_FOR_CONFIRMATION
            else -> CreateTodoReadinessStatus.REVIEWABLE
        }

        val shouldRemainConfirmed = currentState.isConfirmed &&
            readinessStatus == CreateTodoReadinessStatus.READY_FOR_CONFIRMATION &&
            summary != null &&
            summary == currentState.confirmationSummary

        val updatedCreateTodo = currentState.copy(
            title = normalizedTitle,
            readinessStatus = readinessStatus,
            blockers = blockers,
            confirmationSummary = summary,
            isConfirmed = shouldRemainConfirmed,
            updatedAtEpochMillis = nowEpochMillis,
        )

        return if (updatedCreateTodo == currentState) {
            draft
        } else {
            draft.copy(
                createTodo = updatedCreateTodo,
                updatedAtEpochMillis = nowEpochMillis,
            )
        }
    }

    fun updateConfirmation(
        draft: WizardDraft,
        organizationSelection: JobTreadOrganizationSelectionState,
        confirmed: Boolean,
        nowEpochMillis: Long,
    ): WizardDraft {
        val recalculatedDraft = recalculate(
            draft = draft,
            organizationSelection = organizationSelection,
            nowEpochMillis = nowEpochMillis,
        )
        val currentState = recalculatedDraft.createTodo
        val canConfirm = currentState.readinessStatus == CreateTodoReadinessStatus.READY_FOR_CONFIRMATION &&
            currentState.confirmationSummary != null
        val nextConfirmed = confirmed && canConfirm
        val updatedCreateTodo = currentState.copy(
            isConfirmed = nextConfirmed,
            updatedAtEpochMillis = nowEpochMillis,
        )
        return if (updatedCreateTodo == currentState) {
            recalculatedDraft
        } else {
            recalculatedDraft.copy(
                createTodo = updatedCreateTodo,
                updatedAtEpochMillis = nowEpochMillis,
            )
        }
    }

    private fun computeBlockers(
        draft: WizardDraft,
        normalizedTitle: String?,
        organizationSelection: JobTreadOrganizationSelectionState,
    ): List<CreateTodoBlocker> {
        val blockers = mutableListOf<CreateTodoBlocker>()
        if (!organizationSelection.canRunLookup()) {
            blockers += CreateTodoBlocker(
                code = CreateTodoBlockerCode.JOBTREAD_ORGANIZATION_UNRESOLVED,
                message = "Select a JobTread organization before create_todo can be reviewed.",
            )
        }

        val selectedOrganizationId = organizationSelection.selectedOrganizationId
        val lookupState = draft.jobTreadLookup
        if (lookupState.snapshotStatus == JobTreadSnapshotStatus.FAILED) {
            blockers += CreateTodoBlocker(
                code = CreateTodoBlockerCode.LOOKUP_FAILURE,
                message = lookupState.failureMessage ?: "JobTread lookup failed.",
            )
        } else if (
            selectedOrganizationId != null &&
            lookupState.organizationId != null &&
            lookupState.organizationId != selectedOrganizationId
        ) {
            blockers += CreateTodoBlocker(
                code = CreateTodoBlockerCode.JOB_LOOKUP_UNRESOLVED,
                message = "Refresh JobTread lookup for the selected organization.",
            )
        } else {
            when (lookupState.resolutionStatus) {
                JobTreadResolutionStatus.RESOLVED -> Unit
                JobTreadResolutionStatus.AMBIGUOUS -> blockers += CreateTodoBlocker(
                    code = CreateTodoBlockerCode.AMBIGUOUS_JOB_MATCH,
                    message = "Choose a single JobTread job match before create_todo can be reviewed.",
                )
                JobTreadResolutionStatus.UNRESOLVED,
                JobTreadResolutionStatus.NOT_REQUESTED,
                -> blockers += CreateTodoBlocker(
                    code = CreateTodoBlockerCode.JOB_LOOKUP_UNRESOLVED,
                    message = "Resolve a JobTread job before create_todo can be reviewed.",
                )
            }
        }

        if (normalizedTitle == null) {
            blockers += CreateTodoBlocker(
                code = CreateTodoBlockerCode.MISSING_TITLE,
                message = "Add a local create_todo title before review or confirmation.",
            )
        }

        return blockers.distinctBy(CreateTodoBlocker::code)
    }

    private fun buildConfirmationSummary(
        draft: WizardDraft,
        normalizedTitle: String?,
        organizationSelection: JobTreadOrganizationSelectionState,
    ): CreateTodoConfirmationSummary? {
        val title = normalizedTitle ?: return null
        val organization = organizationSelection.selectedOrganization()
            ?.takeIf { organizationSelection.canRunLookup() }
            ?: return null
        val lookupState = draft.jobTreadLookup
        if (lookupState.snapshotStatus != JobTreadSnapshotStatus.LOADED ||
            lookupState.resolutionStatus != JobTreadResolutionStatus.RESOLVED ||
            lookupState.organizationId != organization.id
        ) {
            return null
        }

        val resolvedJob = lookupState.resolvedJob ?: return null
        return CreateTodoConfirmationSummary(
            organizationId = organization.id,
            organizationName = organization.name,
            jobId = resolvedJob.id,
            jobLabel = resolvedJob.displayLabel(),
            title = title,
        )
    }
}
