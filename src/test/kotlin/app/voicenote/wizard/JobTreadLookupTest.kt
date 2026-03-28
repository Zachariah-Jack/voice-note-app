package app.voicenote.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobTreadLookupTest {
    @Test
    fun `single organization auto-selection persists a deterministic active default`() {
        val repository = ReadOnlyJobTreadLookupRepository(
            configProvider = { JobTreadLookupConfig("https://api.jobtread.test", "grant-key") },
            clientFactory = {
                JobTreadLookupApiClient(
                    config = it,
                    transport = StubJobTreadPaveTransport(
                        """
                        {
                          "currentGrant": {
                            "id": "grant-1",
                            "organization": {
                              "id": "org-1",
                              "name": "Northwind Builders"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                )
            },
            clock = FixedClock(5_000L),
        )

        val selection = repository.refreshOrganizationSelection()

        assertEquals(JobTreadOrganizationSelectionStatus.SELECTED_AUTOMATICALLY, selection.status)
        assertEquals("org-1", selection.selectedOrganizationId)
        assertEquals("org-1", selection.defaultOrganizationId)
        assertEquals(5_000L, selection.updatedAtEpochMillis)
    }

    @Test
    fun `saved default organization reuse wins when multiple organizations are available`() {
        val repository = ReadOnlyJobTreadLookupRepository(
            configProvider = { JobTreadLookupConfig("https://api.jobtread.test", "grant-key") },
            clientFactory = {
                JobTreadLookupApiClient(
                    config = it,
                    transport = StubJobTreadPaveTransport(
                        """
                        {
                          "organizations": {
                            "nodes": [
                              { "id": "org-1", "name": "Northwind Builders" },
                              { "id": "org-2", "name": "Southwind Renovations" }
                            ]
                          }
                        }
                        """.trimIndent(),
                    ),
                )
            },
            clock = FixedClock(6_000L),
        )

        val selection = repository.refreshOrganizationSelection(
            JobTreadOrganizationSelectionState(
                defaultOrganizationId = "org-2",
            ),
        )

        assertEquals(JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT, selection.status)
        assertEquals("org-2", selection.selectedOrganizationId)
        assertEquals("org-2", selection.defaultOrganizationId)
        assertEquals("Southwind Renovations", selection.selectedOrganization()?.name)
    }

    @Test
    fun `selection required when multiple organizations exist and no saved default matches`() {
        val repository = ReadOnlyJobTreadLookupRepository(
            configProvider = { JobTreadLookupConfig("https://api.jobtread.test", "grant-key") },
            clientFactory = {
                JobTreadLookupApiClient(
                    config = it,
                    transport = StubJobTreadPaveTransport(
                        """
                        {
                          "currentGrant": {
                            "organizations": {
                              "nodes": [
                                { "id": "org-1", "name": "Northwind Builders" },
                                { "id": "org-2", "name": "Southwind Renovations" }
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                )
            },
            clock = FixedClock(7_000L),
        )

        val selection = repository.refreshOrganizationSelection()

        assertEquals(JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED, selection.status)
        assertNull(selection.selectedOrganizationId)
        assertNull(selection.defaultOrganizationId)
        assertEquals(listOf("org-1", "org-2"), selection.organizations.map(JobTreadOrganization::id))
    }

    @Test
    fun `lookup response parsing loads organization and jobs`() {
        val client = JobTreadLookupApiClient(
            config = JobTreadLookupConfig("https://api.jobtread.test", "grant-key"),
            transport = StubJobTreadPaveTransport(
                """
                {
                  "organization": {
                    "id": "org-1",
                    "name": "Northwind Builders",
                    "connection": {
                      "nodes": [
                        {
                          "id": "job-2",
                          "name": "Kitchen Remodel",
                          "location": {
                            "name": "12 Main Street",
                            "account": { "name": "Alex Johnson" }
                          }
                        },
                        {
                          "id": "job-1",
                          "name": "Garage Addition",
                          "location": {
                            "name": "55 Oak Avenue",
                            "account": { "name": "Sam Rivera" }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val snapshot = client.loadSnapshot("org-1")

        assertEquals(JobTreadOrganization(id = "org-1", name = "Northwind Builders"), snapshot.organization)
        assertEquals(listOf("job-1", "job-2"), snapshot.jobs.map(JobTreadJobSummary::id))
    }

    @Test
    fun `resolved ambiguous and unresolved matching stay deterministic`() {
        val jobs = listOf(
            JobTreadJobSummary("job-1", "Kitchen Remodel", "Alex Johnson", "12 Main Street"),
            JobTreadJobSummary("job-2", "Kitchen Refresh", "Allie Jones", "77 Main Street"),
            JobTreadJobSummary("job-3", "Garage Addition", "Sam Rivera", "55 Oak Avenue"),
        )

        val resolved = JobTreadJobReferenceResolver.resolve("garage addition", jobs)
        val ambiguous = JobTreadJobReferenceResolver.resolve("main street", jobs)
        val unresolved = JobTreadJobReferenceResolver.resolve("sunroom", jobs)

        assertEquals(JobTreadResolutionStatus.RESOLVED, resolved.status)
        assertEquals("job-3", resolved.resolvedJob?.id)
        assertEquals(JobTreadResolutionStatus.AMBIGUOUS, ambiguous.status)
        assertEquals(listOf("job-2", "job-1"), ambiguous.candidates.map(JobTreadJobSummary::id))
        assertEquals(JobTreadResolutionStatus.UNRESOLVED, unresolved.status)
        assertNull(unresolved.resolvedJob)
    }

    private class StubJobTreadPaveTransport(
        private vararg val responseBodies: String,
    ) : JobTreadPaveTransport {
        private var nextIndex = 0

        override fun execute(requestBody: String, config: JobTreadLookupConfig): String =
            responseBodies[nextIndex.coerceAtMost(responseBodies.lastIndex)].also { nextIndex++ }
    }

    private class FixedClock(
        private val nowEpochMillis: Long,
    ) : EpochClock {
        override fun nowEpochMillis(): Long = nowEpochMillis
    }
}
