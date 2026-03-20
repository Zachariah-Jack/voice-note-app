package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.settings.AssistantSettings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JobTreadTodoRepository(
    private val apiClient: JobTreadApiClient
) {
    suspend fun createTodo(
        input: JobTreadTodoCreateInput,
        settings: AssistantSettings
    ): JobTreadTodoCreateResult {
        if (input.title.isBlank()) {
            return JobTreadTodoCreateResult.Failure(
                "JobTread create needs a title before sending the request."
            )
        }

        return when (val result = apiClient.executeMutation(buildCreateTodoMutation(input), settings)) {
            is JobTreadApiResult.Success -> {
                try {
                    val createdTodo = parseCreatedTodo(result.value)
                    JobTreadTodoCreateResult.Success(createdTodo)
                } catch (_: Exception) {
                    JobTreadTodoCreateResult.Failure(
                        "JobTread created the To-Do, but the response was missing the expected fields."
                    )
                }
            }

            is JobTreadApiResult.MissingConfiguration -> JobTreadTodoCreateResult.MissingConfiguration(
                fields = result.fields,
                message = result.message
            )

            is JobTreadApiResult.Failure -> JobTreadTodoCreateResult.Failure(result.message)
        }
    }

    private fun buildCreateTodoMutation(input: JobTreadTodoCreateInput): JsonObject {
        return buildJsonObject {
            put(
                "createTask",
                buildJsonObject {
                    put("$", buildCreateTaskArguments(input))
                    put("id", field())
                    put("name", field())
                    put("description", field())
                    put("isToDo", field())
                    put("targetType", field())
                    put("endDate", field())
                    put("endTime", field())
                }
            )
        }
    }

    private fun buildCreateTaskArguments(input: JobTreadTodoCreateInput): JsonObject {
        return buildJsonObject {
            put("name", input.title)
            put("isToDo", true)

            input.description
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("description", it) }

            input.resolvedJobId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { resolvedJobId ->
                    put("targetId", resolvedJobId)
                    put("targetType", "job")
                }

            input.resolvedAssigneeMembershipId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { membershipId ->
                    put(
                        "assignees",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("membershipId", membershipId)
                                }
                            )
                        }
                    )
                }

            input.dueDateIso
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("endDate", it) }

            input.dueTimeLocal
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("endTime", it) }
        }
    }

    private fun parseCreatedTodo(root: JsonObject): JobTreadCreatedTodo {
        val taskObject = root["createTask"]?.jsonObject
            ?: throw IllegalStateException("Missing createTask node.")

        val id = taskObject.string("id")
            ?: throw IllegalStateException("Missing created task id.")
        val name = taskObject.string("name")
            ?: throw IllegalStateException("Missing created task name.")
        val isToDo = taskObject.boolean("isToDo")
            ?: throw IllegalStateException("Missing created task isToDo flag.")

        if (!isToDo) {
            throw IllegalStateException("Unexpected non-To-Do task response.")
        }

        return JobTreadCreatedTodo(
            id = id,
            name = name,
            description = taskObject.string("description"),
            isToDo = isToDo,
            targetType = taskObject.string("targetType"),
            dueDateIso = taskObject.string("endDate"),
            dueTimeLocal = taskObject.string("endTime")
        )
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        return this[key]
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
    }

    private fun field(): JsonObject = buildJsonObject {}
}

internal fun CreateTodoIntent.toJobTreadTodoCreateInput(
    summary: JobTreadResolutionSummary?
): JobTreadTodoCreateInput? {
    val title = todo.title
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val resolvedAssigneeMembershipId = when (val resolution = summary?.assigneeResolution) {
        is JobTreadLookupResolution.Resolved -> resolution.match.membershipId
        else -> null
    }

    val resolvedJobId = when (val resolution = summary?.jobResolution) {
        is JobTreadLookupResolution.Resolved -> resolution.match.id
        else -> null
    }

    return JobTreadTodoCreateInput(
        title = title,
        description = todo.description?.trim()?.takeIf { it.isNotBlank() },
        resolvedAssigneeMembershipId = resolvedAssigneeMembershipId,
        resolvedJobId = resolvedJobId,
        dueDateIso = todo.dueDateIso?.trim()?.takeIf { it.isNotBlank() },
        dueTimeLocal = todo.dueTimeLocal?.trim()?.takeIf { it.isNotBlank() }
    )
}
