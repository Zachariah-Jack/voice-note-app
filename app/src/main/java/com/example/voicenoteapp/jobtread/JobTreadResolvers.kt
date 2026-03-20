package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.MissingCreateTodoField

sealed interface JobTreadLookupResolution<out T> {
    data object NotRequested : JobTreadLookupResolution<Nothing>

    data class Resolved<T>(val match: T) : JobTreadLookupResolution<T>

    data class Unresolved(val referenceText: String) : JobTreadLookupResolution<Nothing>

    data class Ambiguous<T>(
        val referenceText: String,
        val candidates: List<T>
    ) : JobTreadLookupResolution<T>
}

data class JobTreadResolutionSummary(
    val organization: JobTreadOrganization,
    val assigneeResolution: JobTreadLookupResolution<JobTreadAssignee>,
    val jobResolution: JobTreadLookupResolution<JobTreadJob>,
    val messages: List<String>
)

enum class JobTreadCreateReadiness(val label: String) {
    READY("Ready for create"),
    BLOCKED_MISSING_TITLE("Blocked by missing title"),
    BLOCKED_MISSING_JOBTREAD_CONFIG("Blocked by missing JobTread config"),
    BLOCKED_LOOKUP_LOADING("Blocked while JobTread lookup is running"),
    BLOCKED_LOOKUP_ERROR("Blocked by JobTread lookup error"),
    BLOCKED_UNRESOLVED_ASSIGNEE("Blocked by unresolved assignee"),
    BLOCKED_UNRESOLVED_JOB("Blocked by unresolved job"),
    BLOCKED_AMBIGUOUS_LOOKUP("Blocked by ambiguous lookup")
}

object JobTreadResolvers {
    fun resolve(
        intent: CreateTodoIntent,
        snapshot: JobTreadLookupSnapshot
    ): JobTreadResolutionSummary {
        val assigneeResolution = resolveEntity(
            referenceText = intent.todo.assigneeReferenceText,
            candidates = snapshot.assignees,
            idSelector = { it.membershipId },
            searchTerms = { assignee ->
                buildList {
                    add(assignee.displayName)
                    assignee.emailAddress?.let(::add)
                }
            }
        )
        val jobResolution = resolveEntity(
            referenceText = intent.todo.jobReferenceText,
            candidates = snapshot.jobs,
            idSelector = { it.id },
            searchTerms = { job ->
                buildList {
                    add(job.name)
                    job.number?.let(::add)
                    job.locationName?.let(::add)
                    job.accountName?.let(::add)
                    add(job.summaryLabel)
                }
            }
        )

        return JobTreadResolutionSummary(
            organization = snapshot.organization,
            assigneeResolution = assigneeResolution,
            jobResolution = jobResolution,
            messages = buildList {
                addAll(snapshot.warnings)
                appendResolutionMessage("assignee", assigneeResolution)
                appendResolutionMessage("job", jobResolution)
            }
        )
    }

    fun determineCreateReadiness(
        intent: CreateTodoIntent?,
        hasJobTreadConfig: Boolean,
        lookupInFlight: Boolean,
        lookupErrorMessage: String?,
        summary: JobTreadResolutionSummary?
    ): JobTreadCreateReadiness {
        if (intent == null || MissingCreateTodoField.TITLE in intent.missingFields) {
            return JobTreadCreateReadiness.BLOCKED_MISSING_TITLE
        }
        if (!hasJobTreadConfig) {
            return JobTreadCreateReadiness.BLOCKED_MISSING_JOBTREAD_CONFIG
        }
        if (lookupInFlight) {
            return JobTreadCreateReadiness.BLOCKED_LOOKUP_LOADING
        }
        if (lookupErrorMessage != null) {
            return JobTreadCreateReadiness.BLOCKED_LOOKUP_ERROR
        }

        val assigneeNeedsLookup = !intent.todo.assigneeReferenceText.isNullOrBlank()
        val jobNeedsLookup = !intent.todo.jobReferenceText.isNullOrBlank()

        if (assigneeNeedsLookup) {
            when (summary?.assigneeResolution) {
                is JobTreadLookupResolution.Ambiguous -> return JobTreadCreateReadiness.BLOCKED_AMBIGUOUS_LOOKUP
                is JobTreadLookupResolution.Unresolved,
                JobTreadLookupResolution.NotRequested,
                null -> return JobTreadCreateReadiness.BLOCKED_UNRESOLVED_ASSIGNEE
                is JobTreadLookupResolution.Resolved -> Unit
            }
        }

        if (jobNeedsLookup) {
            when (summary?.jobResolution) {
                is JobTreadLookupResolution.Ambiguous -> return JobTreadCreateReadiness.BLOCKED_AMBIGUOUS_LOOKUP
                is JobTreadLookupResolution.Unresolved,
                JobTreadLookupResolution.NotRequested,
                null -> return JobTreadCreateReadiness.BLOCKED_UNRESOLVED_JOB
                is JobTreadLookupResolution.Resolved -> Unit
            }
        }

        return JobTreadCreateReadiness.READY
    }

    private fun <T> resolveEntity(
        referenceText: String?,
        candidates: List<T>,
        idSelector: (T) -> String,
        searchTerms: (T) -> List<String>
    ): JobTreadLookupResolution<T> {
        val query = referenceText?.trim().orEmpty()
        if (query.isBlank()) {
            return JobTreadLookupResolution.NotRequested
        }

        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return JobTreadLookupResolution.Unresolved(query)
        }

        val exactMatches = candidates.matching(idSelector) { candidate ->
            searchTerms(candidate).any { candidateValue ->
                candidateValue.equals(query, ignoreCase = true) || normalize(candidateValue) == normalizedQuery
            }
        }
        if (exactMatches.size == 1) {
            return JobTreadLookupResolution.Resolved(exactMatches.single())
        }
        if (exactMatches.size > 1) {
            return JobTreadLookupResolution.Ambiguous(query, exactMatches)
        }

        val startsWithMatches = candidates.matching(idSelector) { candidate ->
            searchTerms(candidate).any { candidateValue ->
                normalize(candidateValue).startsWith(normalizedQuery)
            }
        }
        if (startsWithMatches.size == 1) {
            return JobTreadLookupResolution.Resolved(startsWithMatches.single())
        }
        if (startsWithMatches.size > 1) {
            return JobTreadLookupResolution.Ambiguous(query, startsWithMatches)
        }

        val containsMatches = candidates.matching(idSelector) { candidate ->
            searchTerms(candidate).any { candidateValue ->
                val normalizedCandidate = normalize(candidateValue)
                normalizedQuery.length >= 2 && normalizedCandidate.contains(normalizedQuery)
            }
        }
        if (containsMatches.size == 1) {
            return JobTreadLookupResolution.Resolved(containsMatches.single())
        }
        if (containsMatches.size > 1) {
            return JobTreadLookupResolution.Ambiguous(query, containsMatches)
        }

        return JobTreadLookupResolution.Unresolved(query)
    }

    private fun <T> List<T>.matching(
        idSelector: (T) -> String,
        predicate: (T) -> Boolean
    ): List<T> {
        return filter(predicate).distinctBy(idSelector).take(MAX_AMBIGUOUS_MATCHES)
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(MULTISPACE_REGEX, " ")
            .trim()
    }

    private fun MutableList<String>.appendResolutionMessage(
        label: String,
        resolution: JobTreadLookupResolution<*>
    ) {
        when (resolution) {
            is JobTreadLookupResolution.Ambiguous<*> -> add(
                "Multiple JobTread $label matches were found for \"${resolution.referenceText}\"."
            )

            is JobTreadLookupResolution.Unresolved -> add(
                "No JobTread $label matched \"${resolution.referenceText}\"."
            )

            JobTreadLookupResolution.NotRequested,
            is JobTreadLookupResolution.Resolved<*> -> Unit
        }
    }

    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
    private val MULTISPACE_REGEX = Regex("\\s+")
    private const val MAX_AMBIGUOUS_MATCHES = 5
}
