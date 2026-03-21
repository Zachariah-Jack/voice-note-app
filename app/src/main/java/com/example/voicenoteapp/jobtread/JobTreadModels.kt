package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.settings.AssistantConfigField

sealed interface JobTreadApiResult<out T> {
    data class Success<T>(val value: T) : JobTreadApiResult<T>

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String
    ) : JobTreadApiResult<Nothing>

    data class Failure(val message: String) : JobTreadApiResult<Nothing>
}

data class JobTreadOrganization(
    val id: String,
    val name: String
)

data class JobTreadOrganizationSelection(
    val activeOrganization: JobTreadOrganization,
    val organizations: List<JobTreadOrganization>,
    val wasAutoSelected: Boolean,
    val shouldPersistSelection: Boolean
)

data class JobTreadAssignee(
    val membershipId: String,
    val userId: String?,
    val displayName: String,
    val emailAddress: String?,
    val roleName: String?
) {
    val summaryLabel: String
        get() = buildList {
            add(displayName)
            emailAddress?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" | ")
}

data class JobTreadJob(
    val id: String,
    val number: String?,
    val name: String,
    val description: String?,
    val locationName: String?,
    val locationAddress: String?,
    val accountName: String?
) {
    val summaryLabel: String
        get() = buildList {
            number?.takeIf { it.isNotBlank() }?.let(::add)
            add(name)
            locationName?.takeIf { it.isNotBlank() }?.let(::add)
            accountName?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" | ")
}

data class JobTreadTodoCreateInput(
    val title: String,
    val description: String?,
    val resolvedAssigneeMembershipId: String?,
    val resolvedJobId: String?,
    val dueDateIso: String?,
    val dueTimeLocal: String?
)

data class JobTreadCreatedTodo(
    val id: String,
    val name: String,
    val description: String?,
    val isToDo: Boolean,
    val targetType: String?,
    val dueDateIso: String?,
    val dueTimeLocal: String?
)

data class JobTreadLookupSnapshot(
    val organization: JobTreadOrganization,
    val assignees: List<JobTreadAssignee>,
    val jobs: List<JobTreadJob>,
    val warnings: List<String> = emptyList(),
    val availableOrganizations: List<JobTreadOrganization> = listOf(organization),
    val organizationWasAutoSelected: Boolean = false
)

sealed interface JobTreadOrganizationLoadResult {
    data class Success(val selection: JobTreadOrganizationSelection) : JobTreadOrganizationLoadResult

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String
    ) : JobTreadOrganizationLoadResult

    data class SelectionRequired(
        val organizations: List<JobTreadOrganization>,
        val message: String
    ) : JobTreadOrganizationLoadResult

    data class Failure(val message: String) : JobTreadOrganizationLoadResult
}

sealed interface JobTreadLookupLoadResult {
    data class Success(
        val snapshot: JobTreadLookupSnapshot,
        val shouldPersistSelection: Boolean = false
    ) : JobTreadLookupLoadResult

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String
    ) : JobTreadLookupLoadResult

    data class SelectionRequired(
        val organizations: List<JobTreadOrganization>,
        val message: String
    ) : JobTreadLookupLoadResult

    data class Failure(val message: String) : JobTreadLookupLoadResult
}

sealed interface JobTreadTodoCreateResult {
    data class Success(val todo: JobTreadCreatedTodo) : JobTreadTodoCreateResult

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String
    ) : JobTreadTodoCreateResult

    data class Failure(val message: String) : JobTreadTodoCreateResult
}
