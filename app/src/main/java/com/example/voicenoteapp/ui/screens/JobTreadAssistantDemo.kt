package com.example.voicenoteapp.ui.screens

import com.example.voicenoteapp.assistant.AssistantIntentType
import com.example.voicenoteapp.assistant.CreateTodoData
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.MissingCreateTodoField
import com.example.voicenoteapp.assistant.TodoPriority
import com.example.voicenoteapp.jobtread.JobTreadAssignee
import com.example.voicenoteapp.jobtread.JobTreadJob
import com.example.voicenoteapp.jobtread.JobTreadLookupResolution
import com.example.voicenoteapp.jobtread.JobTreadOrganization
import com.example.voicenoteapp.jobtread.JobTreadResolutionSummary

enum class JobTreadAssistantDemoCreateOutcome {
    NONE,
    SUCCESS,
    ERROR
}

data class JobTreadAssistantDemoScenario(
    val id: String,
    val title: String,
    val description: String,
    val transcript: String,
    val parsedIntent: CreateTodoIntent,
    val activeOrganization: JobTreadOrganization,
    val lookupSummary: JobTreadResolutionSummary,
    val createOutcome: JobTreadAssistantDemoCreateOutcome
)

internal val jobTreadAssistantDemoScenarios: List<JobTreadAssistantDemoScenario> by lazy {
    val org = JobTreadOrganization(
        id = "demo-org-1",
        name = "Acme Homes"
    )
    val assigneeJane = JobTreadAssignee(
        membershipId = "demo-mem-jane",
        userId = "demo-user-jane",
        displayName = "Jane Smith",
        emailAddress = "jane@acme.test",
        roleName = "Project Manager"
    )
    val assigneeJohn = JobTreadAssignee(
        membershipId = "demo-mem-john",
        userId = "demo-user-john",
        displayName = "John Carter",
        emailAddress = "john@acme.test",
        roleName = "Superintendent"
    )
    val jobKitchen = JobTreadJob(
        id = "demo-job-kitchen",
        number = "JT-1001",
        name = "Kitchen Remodel",
        description = "Main floor kitchen remodel",
        locationName = "Smith Residence",
        locationAddress = "123 Main St",
        accountName = "Smith Family"
    )
    val jobBath = JobTreadJob(
        id = "demo-job-bath",
        number = "JT-1002",
        name = "Bathroom Refresh",
        description = "Upstairs bathroom refresh",
        locationName = "Jones Residence",
        locationAddress = "456 Oak Ave",
        accountName = "Jones Family"
    )

    listOf(
        JobTreadAssistantDemoScenario(
            id = "simple_create",
            title = "Simple create todo",
            description = "Shows a clean parse with no assignee or job lookup required.",
            transcript = "Create a todo to call the supplier tomorrow morning",
            parsedIntent = createIntent(
                transcript = "Create a todo to call the supplier tomorrow morning",
                title = "Call the supplier",
                description = "Follow up tomorrow morning",
                dueDateIso = "2026-03-22",
                dueTimeLocal = "09:00",
                priority = TodoPriority.NORMAL
            ),
            activeOrganization = org,
            lookupSummary = summary(
                organization = org,
                assigneeResolution = JobTreadLookupResolution.NotRequested,
                jobResolution = JobTreadLookupResolution.NotRequested
            ),
            createOutcome = JobTreadAssistantDemoCreateOutcome.SUCCESS
        ),
        JobTreadAssistantDemoScenario(
            id = "with_assignee",
            title = "Create with assignee",
            description = "Shows the assistant resolving an assignee deterministically.",
            transcript = "Create a todo for Jane Smith to confirm cabinet delivery Friday",
            parsedIntent = createIntent(
                transcript = "Create a todo for Jane Smith to confirm cabinet delivery Friday",
                title = "Confirm cabinet delivery",
                assigneeReferenceText = "Jane Smith",
                dueDateIso = "2026-03-27",
                priority = TodoPriority.HIGH
            ),
            activeOrganization = org,
            lookupSummary = summary(
                organization = org,
                assigneeResolution = JobTreadLookupResolution.Resolved(assigneeJane),
                jobResolution = JobTreadLookupResolution.NotRequested
            ),
            createOutcome = JobTreadAssistantDemoCreateOutcome.SUCCESS
        ),
        JobTreadAssistantDemoScenario(
            id = "with_job",
            title = "Create with job",
            description = "Shows the assistant resolving a JobTread job target.",
            transcript = "Create a todo on Kitchen Remodel to order faucet samples",
            parsedIntent = createIntent(
                transcript = "Create a todo on Kitchen Remodel to order faucet samples",
                title = "Order faucet samples",
                jobReferenceText = "Kitchen Remodel",
                priority = TodoPriority.NORMAL
            ),
            activeOrganization = org,
            lookupSummary = summary(
                organization = org,
                assigneeResolution = JobTreadLookupResolution.NotRequested,
                jobResolution = JobTreadLookupResolution.Resolved(jobKitchen)
            ),
            createOutcome = JobTreadAssistantDemoCreateOutcome.SUCCESS
        ),
        JobTreadAssistantDemoScenario(
            id = "ambiguous_lookup",
            title = "Ambiguous lookup case",
            description = "Shows safety blocking create when the assignee and job remain ambiguous.",
            transcript = "Create a todo for John on the remodel to send the updated finish schedule",
            parsedIntent = createIntent(
                transcript = "Create a todo for John on the remodel to send the updated finish schedule",
                title = "Send the updated finish schedule",
                assigneeReferenceText = "John",
                jobReferenceText = "remodel"
            ),
            activeOrganization = org,
            lookupSummary = summary(
                organization = org,
                assigneeResolution = JobTreadLookupResolution.Ambiguous(
                    referenceText = "John",
                    candidates = listOf(assigneeJohn, assigneeJane)
                ),
                jobResolution = JobTreadLookupResolution.Ambiguous(
                    referenceText = "remodel",
                    candidates = listOf(jobKitchen, jobBath)
                )
            ),
            createOutcome = JobTreadAssistantDemoCreateOutcome.NONE
        ),
        JobTreadAssistantDemoScenario(
            id = "resolved_ready",
            title = "Fully resolved success-ready",
            description = "Shows a strong end-to-end ready state before create.",
            transcript = "Create a todo for Jane on Kitchen Remodel to confirm countertop template by Tuesday at 2 PM",
            parsedIntent = createIntent(
                transcript = "Create a todo for Jane on Kitchen Remodel to confirm countertop template by Tuesday at 2 PM",
                title = "Confirm countertop template",
                assigneeReferenceText = "Jane Smith",
                jobReferenceText = "Kitchen Remodel",
                dueDateIso = "2026-03-24",
                dueTimeLocal = "14:00",
                priority = TodoPriority.HIGH
            ),
            activeOrganization = org,
            lookupSummary = summary(
                organization = org,
                assigneeResolution = JobTreadLookupResolution.Resolved(assigneeJane),
                jobResolution = JobTreadLookupResolution.Resolved(jobKitchen)
            ),
            createOutcome = JobTreadAssistantDemoCreateOutcome.SUCCESS
        ),
        JobTreadAssistantDemoScenario(
            id = "retryable_error",
            title = "Retryable create error",
            description = "Shows a safe create failure while preserving the parsed request and lookup results.",
            transcript = "Create a todo for Jane on Kitchen Remodel to request permit update",
            parsedIntent = createIntent(
                transcript = "Create a todo for Jane on Kitchen Remodel to request permit update",
                title = "Request permit update",
                assigneeReferenceText = "Jane Smith",
                jobReferenceText = "Kitchen Remodel",
                priority = TodoPriority.NORMAL
            ),
            activeOrganization = org,
            lookupSummary = summary(
                organization = org,
                assigneeResolution = JobTreadLookupResolution.Resolved(assigneeJane),
                jobResolution = JobTreadLookupResolution.Resolved(jobKitchen)
            ),
            createOutcome = JobTreadAssistantDemoCreateOutcome.ERROR
        )
    )
}

private fun createIntent(
    transcript: String,
    title: String?,
    description: String? = null,
    assigneeReferenceText: String? = null,
    jobReferenceText: String? = null,
    dueDateIso: String? = null,
    dueTimeLocal: String? = null,
    priority: TodoPriority? = null
): CreateTodoIntent {
    return CreateTodoIntent(
        schemaVersion = "1.0",
        intent = AssistantIntentType.CREATE_TODO,
        rawTranscript = transcript,
        todo = CreateTodoData(
            title = title,
            description = description,
            jobReferenceText = jobReferenceText,
            assigneeReferenceText = assigneeReferenceText,
            dueDateIso = dueDateIso,
            dueTimeLocal = dueTimeLocal,
            priority = priority,
            tags = emptyList()
        ),
        missingFields = if (title.isNullOrBlank()) {
            listOf(MissingCreateTodoField.TITLE)
        } else {
            emptyList()
        },
        ambiguities = emptyList()
    )
}

private fun summary(
    organization: JobTreadOrganization,
    assigneeResolution: JobTreadLookupResolution<JobTreadAssignee>,
    jobResolution: JobTreadLookupResolution<JobTreadJob>
): JobTreadResolutionSummary {
    return JobTreadResolutionSummary(
        organization = organization,
        assigneeResolution = assigneeResolution,
        jobResolution = jobResolution,
        messages = emptyList()
    )
}
