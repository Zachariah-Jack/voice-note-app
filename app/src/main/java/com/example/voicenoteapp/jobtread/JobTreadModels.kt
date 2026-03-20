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
        }.joinToString(" • ")
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
        }.joinToString(" • ")
}

data class JobTreadLookupSnapshot(
    val organization: JobTreadOrganization,
    val assignees: List<JobTreadAssignee>,
    val jobs: List<JobTreadJob>,
    val warnings: List<String> = emptyList()
)

sealed interface JobTreadLookupLoadResult {
    data class Success(val snapshot: JobTreadLookupSnapshot) : JobTreadLookupLoadResult

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String
    ) : JobTreadLookupLoadResult

    data class AmbiguousOrganization(
        val organizations: List<JobTreadOrganization>,
        val message: String
    ) : JobTreadLookupLoadResult

    data class Failure(val message: String) : JobTreadLookupLoadResult
}
