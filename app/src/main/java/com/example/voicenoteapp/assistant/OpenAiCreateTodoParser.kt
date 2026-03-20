package com.example.voicenoteapp.assistant

import com.example.voicenoteapp.settings.AssistantSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenAiCreateTodoParser(
    private val json: Json = Json { prettyPrint = false }
) : CreateTodoParser {
    override val parserLabel: String = "OpenAI parser scaffold"

    override suspend fun parse(
        transcript: String,
        settings: AssistantSettings
    ): CreateTodoParseResult {
        if (!settings.hasOpenAiConfig) {
            return CreateTodoParseResult.MissingConfiguration(
                fields = settings.missingOpenAiFields,
                message = "OpenAI parser is selected but OpenAI settings are incomplete.",
                parserLabel = parserLabel
            )
        }

        val preview = buildRequestPreview(transcript, settings)
        return CreateTodoParseResult.Failure(
            message = "OpenAI parser scaffold is configured but not connected yet. Request preview prepared (${preview.length} chars).",
            parserLabel = parserLabel
        )
    }

    private fun buildRequestPreview(transcript: String, settings: AssistantSettings): String {
        val payload = buildJsonObject {
            put("model", settings.openAiModel)
            put("transcript", transcript)
            put("instruction", "Return strict JSON for create_todo only.")
            put("required_intent", "create_todo")
            put("expected_fields", buildJsonArray {
                add(JsonPrimitive("title"))
                add(JsonPrimitive("description"))
                add(JsonPrimitive("job_reference_text"))
                add(JsonPrimitive("assignee_reference_text"))
                add(JsonPrimitive("due_date_iso"))
                add(JsonPrimitive("due_time_local"))
                add(JsonPrimitive("priority"))
                add(JsonPrimitive("tags"))
            })
        }
        return json.encodeToString(payload)
    }
}
