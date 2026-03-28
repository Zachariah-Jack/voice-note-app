package app.voicenote.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JobTreadCreateTodoTest {
    @Test
    fun `client parses a successful create_todo response`() {
        val client = JobTreadCreateTodoApiClient(
            config = JobTreadLookupConfig(
                paveUrl = "https://example.jobtread.test",
                grantKey = "grant-key",
            ),
            transport = FakeJobTreadPaveTransport(
                responseBody = """
                {
                  "createTask": {
                    "createdTask": {
                      "id": "task-123",
                      "name": "Call the supplier",
                      "isToDo": true,
                      "targetType": "job",
                      "createdAt": "2026-03-28T14:15:00Z",
                      "job": {
                        "id": "job-1",
                        "name": "Main Street Remodel"
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val createdTodo = client.createTodo(
            JobTreadCreateTodoInput(
                organizationId = "org-1",
                organizationName = "Northwind Builders",
                jobId = "job-1",
                jobLabel = "Main Street Remodel - Smith Family / 12 Main Street",
                title = "Call the supplier",
            ),
        )

        assertEquals("task-123", createdTodo.id)
        assertEquals("Call the supplier", createdTodo.title)
        assertEquals("job", createdTodo.targetType)
        assertEquals("Main Street Remodel", createdTodo.jobLabel)
        assertEquals("2026-03-28T14:15:00Z", createdTodo.createdAtIso)
    }

    @Test
    fun `client rejects malformed or missing required response fields`() {
        val client = JobTreadCreateTodoApiClient(
            config = JobTreadLookupConfig(
                paveUrl = "https://example.jobtread.test",
                grantKey = "grant-key",
            ),
            transport = FakeJobTreadPaveTransport(
                responseBody = """
                {
                  "createTask": {
                    "createdTask": {
                      "name": "Call the supplier",
                      "isToDo": true,
                      "targetType": "job",
                      "job": {
                        "id": "job-1"
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val exception = assertFailsWith<JobTreadCreateTodoException> {
            client.createTodo(
                JobTreadCreateTodoInput(
                    organizationId = "org-1",
                    organizationName = "Northwind Builders",
                    jobId = "job-1",
                    jobLabel = "Main Street Remodel - Smith Family / 12 Main Street",
                    title = "Call the supplier",
                ),
            )
        }

        assertEquals(
            "JobTread create_todo response is missing createdTask.id.",
            exception.message,
        )
    }

    @Test
    fun `repository rejects missing JobTread config`() {
        val repository = LiveJobTreadCreateTodoRepository(configProvider = { null })

        val exception = assertFailsWith<JobTreadCreateTodoException> {
            repository.createTodo(
                JobTreadCreateTodoInput(
                    organizationId = "org-1",
                    organizationName = "Northwind Builders",
                    jobId = "job-1",
                    jobLabel = "Main Street Remodel - Smith Family / 12 Main Street",
                    title = "Call the supplier",
                ),
            )
        }

        assertEquals("Missing Pave URL or grant key.", exception.message)
    }

    private class FakeJobTreadPaveTransport(
        private val responseBody: String,
    ) : JobTreadPaveTransport {
        override fun execute(requestBody: String, config: JobTreadLookupConfig): String = responseBody
    }
}
