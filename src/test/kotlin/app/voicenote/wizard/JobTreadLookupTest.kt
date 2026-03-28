package app.voicenote.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobTreadLookupTest {
    @Test
    fun `missing config handling returns an unresolved config-missing state`() {
        val repository = ReadOnlyJobTreadLookupRepository(
            configProvider = { null },
            clock = FixedClock(5_000L),
        )

        val result = repository.resolveJobReference("Maple Street remodel")

        assertEquals(JobTreadSnapshotStatus.CONFIG_MISSING, result.snapshotStatus)
        assertEquals(JobTreadResolutionStatus.UNRESOLVED, result.resolutionStatus)
        assertEquals("Maple Street remodel", result.requestedReferenceText)
        assertEquals(5_000L, result.updatedAtEpochMillis)
    }

    @Test
    fun `lookup response parsing loads organization and jobs`() {
        val client = JobTreadLookupApiClient(
            config = JobTreadLookupConfig(
                paveUrl = "https://api.jobtread.test",
                grantKey = "grant-key",
            ),
            transport = StubJobTreadPaveTransport(
                """
                {
                  "currentGrant": {
                    "id": "grant-1",
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
                              "account": {
                                "name": "Alex Johnson"
                              }
                            }
                          },
                          {
                            "id": "job-1",
                            "name": "Garage Addition",
                            "location": {
                              "name": "55 Oak Avenue",
                              "account": {
                                "name": "Sam Rivera"
                              }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val snapshot = client.loadSnapshot()

        assertEquals(JobTreadOrganization(id = "org-1", name = "Northwind Builders"), snapshot.organization)
        assertEquals(
            listOf(
                JobTreadJobSummary(
                    id = "job-1",
                    name = "Garage Addition",
                    customerName = "Sam Rivera",
                    locationName = "55 Oak Avenue",
                ),
                JobTreadJobSummary(
                    id = "job-2",
                    name = "Kitchen Remodel",
                    customerName = "Alex Johnson",
                    locationName = "12 Main Street",
                ),
            ),
            snapshot.jobs,
        )
    }

    @Test
    fun `resolved ambiguous and unresolved matching stay deterministic`() {
        val jobs = listOf(
            JobTreadJobSummary(
                id = "job-1",
                name = "Kitchen Remodel",
                customerName = "Alex Johnson",
                locationName = "12 Main Street",
            ),
            JobTreadJobSummary(
                id = "job-2",
                name = "Kitchen Refresh",
                customerName = "Allie Jones",
                locationName = "77 Main Street",
            ),
            JobTreadJobSummary(
                id = "job-3",
                name = "Garage Addition",
                customerName = "Sam Rivera",
                locationName = "55 Oak Avenue",
            ),
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
        private val responseBody: String,
    ) : JobTreadPaveTransport {
        override fun execute(requestBody: String, config: JobTreadLookupConfig): String = responseBody
    }

    private class FixedClock(
        private val nowEpochMillis: Long,
    ) : EpochClock {
        override fun nowEpochMillis(): Long = nowEpochMillis
    }
}
