package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.assistant.CreateTodoData
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.MissingCreateTodoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JobTreadTodoRepositoryTest {
    @Test
    fun `toJobTreadTodoCreateInput maps only resolved ids and trimmed supported fields`() {
        val input = createIntent(
            title = "  Call roofer  ",
            description = "  Confirm revised estimate  ",
            jobReference = "Maple Street",
            assigneeReference = "Sam",
            dueDateIso = "2026-03-22",
            dueTimeLocal = "14:30"
        ).toJobTreadTodoCreateInput(
            summary = JobTreadResolutionSummary(
                organization = JobTreadOrganization(id = "org-1", name = "Acme Builders"),
                assigneeResolution = JobTreadLookupResolution.Resolved(
                    JobTreadAssignee(
                        membershipId = "membership-9",
                        userId = "user-9",
                        displayName = "Sam Rivera",
                        emailAddress = "sam@example.com",
                        roleName = "PM"
                    )
                ),
                jobResolution = JobTreadLookupResolution.Resolved(
                    JobTreadJob(
                        id = "job-77",
                        number = "J-77",
                        name = "Maple Street Remodel",
                        description = null,
                        locationName = null,
                        locationAddress = null,
                        accountName = null
                    )
                ),
                messages = emptyList()
            )
        )

        assertNotNull(input)
        assertEquals("Call roofer", input?.title)
        assertEquals("Confirm revised estimate", input?.description)
        assertEquals("membership-9", input?.resolvedAssigneeMembershipId)
        assertEquals("job-77", input?.resolvedJobId)
        assertEquals("2026-03-22", input?.dueDateIso)
        assertEquals("14:30", input?.dueTimeLocal)
    }

    @Test
    fun `toJobTreadTodoCreateInput omits unresolved ids and blank description`() {
        val input = createIntent(
            title = "Order cabinet pulls",
            description = "   ",
            jobReference = "Unknown job",
            assigneeReference = "Unknown assignee",
            dueDateIso = null,
            dueTimeLocal = "09:00"
        ).toJobTreadTodoCreateInput(
            summary = JobTreadResolutionSummary(
                organization = JobTreadOrganization(id = "org-1", name = "Acme Builders"),
                assigneeResolution = JobTreadLookupResolution.Unresolved("Unknown assignee"),
                jobResolution = JobTreadLookupResolution.Ambiguous(
                    referenceText = "Unknown job",
                    candidates = listOf(
                        JobTreadJob(
                            id = "job-1",
                            number = null,
                            name = "Unknown job north",
                            description = null,
                            locationName = null,
                            locationAddress = null,
                            accountName = null
                        ),
                        JobTreadJob(
                            id = "job-2",
                            number = null,
                            name = "Unknown job south",
                            description = null,
                            locationName = null,
                            locationAddress = null,
                            accountName = null
                        )
                    )
                ),
                messages = emptyList()
            )
        )

        assertNotNull(input)
        assertEquals("Order cabinet pulls", input?.title)
        assertNull(input?.description)
        assertNull(input?.resolvedAssigneeMembershipId)
        assertNull(input?.resolvedJobId)
        assertNull(input?.dueDateIso)
        assertEquals("09:00", input?.dueTimeLocal)
    }

    @Test
    fun `toJobTreadTodoCreateInput returns null when title is missing`() {
        val input = createIntent(title = null).toJobTreadTodoCreateInput(summary = null)

        assertNull(input)
    }

    private fun createIntent(
        title: String?,
        description: String? = null,
        jobReference: String? = null,
        assigneeReference: String? = null,
        dueDateIso: String? = null,
        dueTimeLocal: String? = null
    ): CreateTodoIntent {
        return CreateTodoIntent(
            rawTranscript = "Create a todo",
            todo = CreateTodoData(
                title = title,
                description = description,
                jobReferenceText = jobReference,
                assigneeReferenceText = assigneeReference,
                dueDateIso = dueDateIso,
                dueTimeLocal = dueTimeLocal,
                priority = null,
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
}
