package app.voicenote.wizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class JobTreadCreateTodoInput(
    val organizationId: String,
    val organizationName: String,
    val jobId: String,
    val jobLabel: String,
    val title: String,
)

open class JobTreadCreateTodoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface JobTreadCreateTodoRepository {
    fun createTodo(input: JobTreadCreateTodoInput): JobTreadCreatedTodo
}

class LiveJobTreadCreateTodoRepository(
    private val configProvider: () -> JobTreadLookupConfig?,
    private val clientFactory: (JobTreadLookupConfig) -> JobTreadCreateTodoApiClient = ::JobTreadCreateTodoApiClient,
) : JobTreadCreateTodoRepository {
    override fun createTodo(input: JobTreadCreateTodoInput): JobTreadCreatedTodo {
        val config = configProvider()
            ?: throw JobTreadCreateTodoException("Missing Pave URL or grant key.")
        return clientFactory(config).createTodo(input)
    }
}

class JobTreadCreateTodoApiClient(
    private val config: JobTreadLookupConfig,
    private val transport: JobTreadPaveTransport = HttpJobTreadPaveTransport(),
    private val json: Json = JobTreadLookupApiClient.defaultJson,
) {
    fun createTodo(input: JobTreadCreateTodoInput): JobTreadCreatedTodo =
        parseCreatedTodo(
            responseBody = executeMutation(buildCreateTodoRequestBody(input)),
            input = input,
        )

    internal fun parseCreatedTodo(
        responseBody: String,
        input: JobTreadCreateTodoInput,
    ): JobTreadCreatedTodo {
        val root = parseResponseRoot(responseBody)
        val createTask = root["createTask"] as? JsonObject
            ?: throw JobTreadCreateTodoException("JobTread create_todo response did not include createTask.")
        val createdTask = createTask["createdTask"] as? JsonObject
            ?: throw JobTreadCreateTodoException("JobTread create_todo response did not include createdTask.")

        val id = createdTask.requiredString("id", "createdTask.id")
        val title = createdTask.requiredString("name", "createdTask.name")
        val isToDo = createdTask["isToDo"]?.jsonPrimitive?.booleanOrNull
            ?: throw JobTreadCreateTodoException("JobTread create_todo response is missing createdTask.isToDo.")
        if (!isToDo) {
            throw JobTreadCreateTodoException("JobTread create_todo response returned a task that is not marked as a To-Do.")
        }

        val targetType = createdTask.requiredString("targetType", "createdTask.targetType")
        if (targetType != "job") {
            throw JobTreadCreateTodoException("JobTread create_todo response returned unexpected targetType $targetType.")
        }

        val jobObject = createdTask["job"] as? JsonObject
            ?: throw JobTreadCreateTodoException("JobTread create_todo response is missing createdTask.job.")
        val jobId = jobObject.requiredString("id", "createdTask.job.id")
        if (jobId != input.jobId) {
            throw JobTreadCreateTodoException(
                "JobTread create_todo response returned unexpected job id $jobId.",
            )
        }
        val jobLabel = jobObject["name"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: input.jobLabel

        return JobTreadCreatedTodo(
            id = id,
            title = title,
            organizationId = input.organizationId,
            organizationName = input.organizationName,
            jobId = input.jobId,
            jobLabel = jobLabel,
            targetType = targetType,
            createdAtIso = createdTask["createdAt"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun executeMutation(requestBody: JsonObject): String = try {
        transport.execute(requestBody.toString(), config)
    } catch (exception: JobTreadCreateTodoException) {
        throw exception
    } catch (exception: JobTreadLookupException) {
        throw JobTreadCreateTodoException(exception.message ?: "JobTread create_todo request failed.", exception)
    } catch (exception: Exception) {
        throw JobTreadCreateTodoException("JobTread create_todo request failed.", exception)
    }

    private fun buildCreateTodoRequestBody(input: JobTreadCreateTodoInput): JsonObject = buildJsonObject {
        putJsonObject("query") {
            putJsonObject("$") {
                put("grantKey", config.grantKey)
                put("timeZone", config.timeZoneId)
            }
            putJsonObject("createTask") {
                putJsonObject("$") {
                    put("isToDo", true)
                    put("name", input.title)
                    put("targetId", input.jobId)
                    put("targetType", "job")
                }
                putJsonObject("createdTask") {
                    putJsonObject("id") {}
                    putJsonObject("name") {}
                    putJsonObject("isToDo") {}
                    putJsonObject("targetType") {}
                    putJsonObject("createdAt") {}
                    putJsonObject("job") {
                        putJsonObject("id") {}
                        putJsonObject("name") {}
                    }
                }
            }
        }
    }

    private fun parseResponseRoot(responseBody: String): JsonObject {
        val element = try {
            json.parseToJsonElement(responseBody)
        } catch (exception: Exception) {
            throw JobTreadCreateTodoException("JobTread create_todo response was not valid JSON.", exception)
        }
        val root = element as? JsonObject
            ?: throw JobTreadCreateTodoException("JobTread create_todo response must be a JSON object.")
        val errorMessage = root["error"]?.jsonPrimitive?.contentOrNull
        if (!errorMessage.isNullOrBlank()) {
            throw JobTreadCreateTodoException("JobTread create_todo failed: $errorMessage")
        }
        return root
    }
}

object CreateTodoExecutionInputFactory {
    fun build(draft: WizardDraft): JobTreadCreateTodoInput? {
        val createTodo = draft.createTodo
        val summary = createTodo.confirmationSummary ?: return null
        if (createTodo.readinessStatus != CreateTodoReadinessStatus.READY_FOR_CONFIRMATION) {
            return null
        }
        if (!createTodo.isConfirmed || createTodo.blockers.isNotEmpty()) {
            return null
        }
        return JobTreadCreateTodoInput(
            organizationId = summary.organizationId,
            organizationName = summary.organizationName,
            jobId = summary.jobId,
            jobLabel = summary.jobLabel,
            title = summary.title,
        )
    }
}

private fun JsonObject.requiredString(
    key: String,
    description: String,
): String = get(key)?.jsonPrimitive?.contentOrNull
    ?.trim()
    .takeUnless { it.isNullOrEmpty() }
    ?: throw JobTreadCreateTodoException("JobTread create_todo response is missing $description.")
