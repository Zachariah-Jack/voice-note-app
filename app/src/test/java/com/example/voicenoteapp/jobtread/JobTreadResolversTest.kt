package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.assistant.AssistantIntentType
import com.example.voicenoteapp.assistant.CreateTodoData
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.MissingCreateTodoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTreadResolversTest {
    private val organization = JobTreadOrganization(
        id = "org_1",
        name = "Acme Homes"
    )

    private val assignees = listOf(
        JobTreadAssignee(
            membershipId = "mem_1",
            userId = "user_1",
            displayName = "Jane Smith",
            emailAddress = "jane@example.com",
            roleName = "Project Manager"
        ),
        JobTreadAssignee(
            membershipId = "mem_2",
            userId = "user_2",
            displayName = "John Carter",
            emailAddress = "john@example.com",
            roleName = "Superintendent"
        )
    )

    private val jobs = listOf(
        JobTreadJob(
            id = "job_1",
            number = "JT-1001",
            name = "Kitchen Remodel",
            description = "Main floor remodel",
            locationName = "Smith Residence",
            locationAddress = "123 Main St",
            accountName = "Smith Family"
        ),
        JobTreadJob(
            id = "job_2",
            number = "JT-1002",
            name = "Kitchen Refresh",
            description = "Cabinet update",
            locationName = "Jones Residence",
            locationAddress = "456 Oak Ave",
            accountName = "Jones Family"
        )
    )

    private val snapshot = JobTreadLookupSnapshot(
        organization = organization,
        assignees = assignees,
        jobs = jobs
    )

    @Test
    fun resolvesExactAssigneeMatchCaseInsensitively() {
        val summary = JobTreadResolvers.resolve(
            intent = intent(
                title = "Call customer",
                assigneeReference = "jane smith"
            ),
            snapshot = snapshot
        )

        val resolution = summary.assigneeResolution
        assertTrue(resolution is JobTreadLookupResolution.Resolved)
        assertEquals(
            "Jane Smith",
            (resolution as JobTreadLookupResolution.Resolved).match.displayName
        )
    }

    @Test
    fun marksAmbiguousJobMatchWhenMultiplePlausibleJobsRemain() {
        val summary = JobTreadResolvers.resolve(
            intent = intent(
                title = "Follow up",
                jobReference = "kitchen"
            ),
            snapshot = snapshot
        )

        val resolution = summary.jobResolution
        assertTrue(resolution is JobTreadLookupResolution.Ambiguous)
        assertEquals(
            2,
            (resolution as JobTreadLookupResolution.Ambiguous).candidates.size
        )
    }

    @Test
    fun blocksCreateWhenJobReferenceIsUnresolved() {
        val summary = JobTreadResolvers.resolve(
            intent = intent(
                title = "Order materials",
                jobReference = "garage addition"
            ),
            snapshot = snapshot
        )

        val readiness = JobTreadResolvers.determineCreateReadiness(
            intent = intent(
                title = "Order materials",
                jobReference = "garage addition"
            ),
            hasJobTreadConfig = true,
            lookupInFlight = false,
            lookupErrorMessage = null,
            summary = summary
        )

        assertEquals(JobTreadCreateReadiness.BLOCKED_UNRESOLVED_JOB, readiness)
    }

    @Test
    fun blocksCreateWhenTitleIsMissingEvenIfLookupResolves() {
        val intent = intent(
            title = null,
            assigneeReference = "John Carter"
        )
        val summary = JobTreadResolvers.resolve(intent, snapshot)

        val readiness = JobTreadResolvers.determineCreateReadiness(
            intent = intent,
            hasJobTreadConfig = true,
            lookupInFlight = false,
            lookupErrorMessage = null,
            summary = summary
        )

        assertEquals(JobTreadCreateReadiness.BLOCKED_MISSING_TITLE, readiness)
    }

    private fun intent(
        title: String? = "Call customer",
        assigneeReference: String? = null,
        jobReference: String? = null
    ): CreateTodoIntent {
        return CreateTodoIntent(
            schemaVersion = "1.0",
            intent = AssistantIntentType.CREATE_TODO,
            rawTranscript = "create a todo",
            todo = CreateTodoData(
                title = title,
                description = "test description",
                jobReferenceText = jobReference,
                assigneeReferenceText = assigneeReference,
                dueDateIso = null,
                dueTimeLocal = null,
                priority = null,
                tags = emptyList()
            ),
            missingFields = if (title == null) listOf(MissingCreateTodoField.TITLE) else emptyList(),
            ambiguities = emptyList()
        )
    }
}
