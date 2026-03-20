package com.example.voicenoteapp.assistant

import com.example.voicenoteapp.settings.AssistantSettings
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAiCreateTodoParser(
    private val json: Json = Json { ignoreUnknownKeys = false }
) : CreateTodoParser {
    private val descriptor = CreateTodoParserDescriptor(
        mode = CreateTodoParserMode.AI,
        parserLabel = "OpenAI structured parser"
    )

    override fun describe(settings: AssistantSettings): CreateTodoParserDescriptor = descriptor

    override suspend fun parse(
        transcript: String,
        settings: AssistantSettings
    ): CreateTodoParseResult {
        if (!settings.hasOpenAiConfig) {
            return CreateTodoParseResult.MissingConfiguration(
                fields = settings.missingOpenAiFields,
                message = "OpenAI settings are incomplete. Add the API key and model name to enable AI parsing.",
                descriptor = descriptor
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = buildRequestBody(transcript, settings)
                val responseBody = executeRequest(requestBody, settings.openAiApiKey.trim())
                val parsedIntent = parseResponseBody(responseBody, transcript)
                CreateTodoParseResult.Success(
                    intent = parsedIntent,
                    descriptor = descriptor
                )
            } catch (error: OpenAiParserException) {
                CreateTodoParseResult.Failure(
                    message = error.message ?: "OpenAI parsing failed.",
                    descriptor = descriptor
                )
            } catch (error: Exception) {
                CreateTodoParseResult.Failure(
                    message = "OpenAI parsing failed: ${error.message ?: "unknown error"}.",
                    descriptor = descriptor
                )
            }
        }
    }

    private fun buildRequestBody(
        transcript: String,
        settings: AssistantSettings
    ): String {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val payload = buildJsonObject {
            put("model", settings.openAiModel.trim())
            put("instructions", buildInstructions())
            put("input", buildUserInput(today, zoneId, transcript))
            put("max_output_tokens", 300)
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put("name", "create_todo_intent")
                            put("strict", true)
                            put("schema", createTodoIntentSchema())
                        }
                    )
                }
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun executeRequest(
        requestBody: String,
        apiKey: String
    ): String {
        val connection = (URL(OPENAI_RESPONSES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }

            val responseBody = readStream(
                if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
            )

            if (connection.responseCode !in 200..299) {
                throw OpenAiParserException(parseOpenAiError(responseBody, connection.responseMessage))
            }

            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponseBody(
        responseBody: String,
        transcript: String
    ): CreateTodoIntent {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val outputText = extractOutputText(root)
        return parseIntentJson(outputText, transcript)
    }

    private fun extractOutputText(root: JsonObject): String {
        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { outputText ->
            if (outputText.isNotBlank()) return outputText
        }

        val output = root["output"]?.jsonArray ?: throw OpenAiParserException(
            "OpenAI response did not contain any output content."
        )

        for (messageElement in output) {
            val messageObject = messageElement.jsonObject
            val content = messageObject["content"]?.jsonArray ?: continue
            for (contentElement in content) {
                val contentObject = contentElement.jsonObject
                contentObject["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotBlank()) return text
                }
                contentObject["refusal"]?.jsonPrimitive?.contentOrNull?.let { refusal ->
                    if (refusal.isNotBlank()) {
                        throw OpenAiParserException("OpenAI refused the parsing request: $refusal")
                    }
                }
            }
        }

        throw OpenAiParserException("OpenAI response did not contain structured JSON text.")
    }

    private fun parseIntentJson(
        outputText: String,
        transcript: String
    ): CreateTodoIntent {
        val root = try {
            json.parseToJsonElement(outputText).jsonObject
        } catch (error: Exception) {
            throw OpenAiParserException("OpenAI returned malformed JSON for create_todo parsing.")
        }

        ensureOnlyKeys(
            root = root,
            allowedKeys = setOf(
                "schema_version",
                "intent",
                "raw_transcript",
                "todo",
                "missing_fields",
                "ambiguities"
            ),
            context = "create_todo payload"
        )

        val schemaVersion = root.requireString("schema_version", "schema version")
        if (schemaVersion != "1.0") {
            throw OpenAiParserException("OpenAI returned an unsupported schema version.")
        }

        val intentValue = root.requireString("intent", "intent")
        if (intentValue != "create_todo") {
            throw OpenAiParserException("OpenAI returned an unsupported intent.")
        }

        root.requireString("raw_transcript", "raw transcript")

        val todoObject = root.requireObject("todo", "todo")
        ensureOnlyKeys(
            root = todoObject,
            allowedKeys = setOf(
                "title",
                "description",
                "job_reference_text",
                "assignee_reference_text",
                "due_date_iso",
                "due_time_local",
                "priority",
                "tags"
            ),
            context = "todo"
        )

        val title = todoObject.optionalString("title", "title")
        val description = todoObject.optionalString("description", "description")
        val jobReference = todoObject.optionalString("job_reference_text", "job reference")
        val assigneeReference = todoObject.optionalString("assignee_reference_text", "assignee reference")
        val dueDateIso = todoObject.optionalString("due_date_iso", "due date")?.also(::validateIsoDate)
        val dueTimeLocal = todoObject.optionalString("due_time_local", "due time")?.also(::validateLocalTime)
        val priority = parsePriority(todoObject["priority"])
        val tags = parseTags(todoObject["tags"])
        val ambiguities = parseAmbiguities(root["ambiguities"])

        val missingFieldNames = parseMissingFieldNames(root["missing_fields"])
        if (title.isNullOrBlank() && "title" !in missingFieldNames) {
            throw OpenAiParserException("OpenAI parsing response omitted the required missing title marker.")
        }
        if (!title.isNullOrBlank() && missingFieldNames.isNotEmpty()) {
            throw OpenAiParserException("OpenAI parsing response marked title missing even though a title was returned.")
        }

        val normalizedMissingFields = if (title.isNullOrBlank()) {
            listOf(MissingCreateTodoField.TITLE)
        } else {
            emptyList()
        }

        return CreateTodoIntent(
            rawTranscript = transcript,
            todo = CreateTodoData(
                title = title,
                description = description,
                jobReferenceText = jobReference,
                assigneeReferenceText = assigneeReference,
                dueDateIso = dueDateIso,
                dueTimeLocal = dueTimeLocal,
                priority = priority,
                tags = tags
            ),
            missingFields = normalizedMissingFields,
            ambiguities = ambiguities
        )
    }

    private fun parsePriority(element: JsonElement?): TodoPriority? {
        if (element == null || element is JsonNull) return null
        val rawPriority = requireStringValue(element, "priority")
        return when (rawPriority) {
            "low" -> TodoPriority.LOW
            "normal" -> TodoPriority.NORMAL
            "high" -> TodoPriority.HIGH
            "urgent" -> TodoPriority.URGENT
            else -> throw OpenAiParserException("OpenAI returned an invalid priority value.")
        }
    }

    private fun parseTags(element: JsonElement?): List<String> {
        val array = element?.jsonArray ?: throw OpenAiParserException("OpenAI response is missing tags.")
        if (array.size > 5) {
            throw OpenAiParserException("OpenAI returned too many tags.")
        }

        val tags = array.map { tagElement ->
            val tag = requireStringValue(tagElement, "tag").trim()
            if (tag.isBlank()) {
                throw OpenAiParserException("OpenAI returned an empty tag.")
            }
            tag
        }

        if (tags.distinct().size != tags.size) {
            throw OpenAiParserException("OpenAI returned duplicate tags.")
        }

        return tags
    }

    private fun parseAmbiguities(element: JsonElement?): List<String> {
        val array = element?.jsonArray ?: throw OpenAiParserException("OpenAI response is missing ambiguities.")
        if (array.size > 5) {
            throw OpenAiParserException("OpenAI returned too many ambiguities.")
        }

        return array.map { ambiguityElement ->
            val ambiguity = requireStringValue(ambiguityElement, "ambiguity").trim()
            if (ambiguity.isBlank()) {
                throw OpenAiParserException("OpenAI returned an empty ambiguity.")
            }
            ambiguity
        }
    }

    private fun parseMissingFieldNames(element: JsonElement?): List<String> {
        val array = element?.jsonArray ?: throw OpenAiParserException("OpenAI response is missing missing_fields.")
        val fieldNames = array.map { fieldElement ->
            val name = requireStringValue(fieldElement, "missing field").trim()
            if (name != "title") {
                throw OpenAiParserException("OpenAI returned an unsupported missing field marker.")
            }
            name
        }

        if (fieldNames.distinct().size != fieldNames.size) {
            throw OpenAiParserException("OpenAI returned duplicate missing field markers.")
        }

        return fieldNames
    }

    private fun buildInstructions(): String {
        return """
            Extract only a JobTread create_todo command into the provided JSON schema.
            Return schema-compliant JSON only.
            Do not invent IDs, job IDs, assignee IDs, or unknown values.
            Use null when a field is not clearly stated.
            Keep raw_transcript as the user's request.
            If the request is not clearly a create_todo command, keep title null, keep other unknown fields null, and explain the uncertainty in ambiguities.
            Priority must be one of: low, normal, high, urgent, or null.
            due_date_iso must be YYYY-MM-DD or null.
            due_time_local must be HH:MM in 24-hour local time or null.
            tags must be a short list and may be empty.
            missing_fields must contain "title" when title is null or blank, otherwise it must be empty.
            additional properties are not allowed anywhere.
        """.trimIndent()
    }

    private fun buildUserInput(
        today: LocalDate,
        zoneId: ZoneId,
        transcript: String
    ): String {
        return """
            Current local date: $today
            Current timezone: ${zoneId.id}
            Raw transcript: $transcript
        """.trimIndent()
    }

    private fun createTodoIntentSchema(): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("schema_version"))
                    add(JsonPrimitive("intent"))
                    add(JsonPrimitive("raw_transcript"))
                    add(JsonPrimitive("todo"))
                    add(JsonPrimitive("missing_fields"))
                    add(JsonPrimitive("ambiguities"))
                }
            )
            put(
                "properties",
                buildJsonObject {
                    put(
                        "schema_version",
                        buildJsonObject {
                            put("type", "string")
                            put("const", "1.0")
                        }
                    )
                    put(
                        "intent",
                        buildJsonObject {
                            put("type", "string")
                            put("const", "create_todo")
                        }
                    )
                    put(
                        "raw_transcript",
                        buildJsonObject {
                            put("type", "string")
                            put("minLength", 1)
                        }
                    )
                    put("todo", createTodoSchema())
                    put(
                        "missing_fields",
                        buildJsonObject {
                            put("type", "array")
                            put("uniqueItems", true)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("title"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put(
                        "ambiguities",
                        buildJsonObject {
                            put("type", "array")
                            put("maxItems", 5)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("minLength", 1)
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun createTodoSchema(): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("description"))
                    add(JsonPrimitive("job_reference_text"))
                    add(JsonPrimitive("assignee_reference_text"))
                    add(JsonPrimitive("due_date_iso"))
                    add(JsonPrimitive("due_time_local"))
                    add(JsonPrimitive("priority"))
                    add(JsonPrimitive("tags"))
                }
            )
            put(
                "properties",
                buildJsonObject {
                    put("title", nullableStringSchema())
                    put("description", nullableStringSchema())
                    put("job_reference_text", nullableStringSchema())
                    put("assignee_reference_text", nullableStringSchema())
                    put(
                        "due_date_iso",
                        buildNullableUnion(
                            buildJsonObject {
                                put("type", "string")
                                put("pattern", "^\\\\d{4}-\\\\d{2}-\\\\d{2}$")
                            }
                        )
                    )
                    put(
                        "due_time_local",
                        buildNullableUnion(
                            buildJsonObject {
                                put("type", "string")
                                put("pattern", "^([01]\\\\d|2[0-3]):[0-5]\\\\d$")
                            }
                        )
                    )
                    put(
                        "priority",
                        buildNullableUnion(
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("low"))
                                        add(JsonPrimitive("normal"))
                                        add(JsonPrimitive("high"))
                                        add(JsonPrimitive("urgent"))
                                    }
                                )
                            }
                        )
                    )
                    put(
                        "tags",
                        buildJsonObject {
                            put("type", "array")
                            put("maxItems", 5)
                            put("uniqueItems", true)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("minLength", 1)
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun nullableStringSchema(): JsonObject {
        return buildNullableUnion(
            buildJsonObject {
                put("type", "string")
                put("minLength", 1)
            }
        )
    }

    private fun buildNullableUnion(nonNullSchema: JsonObject): JsonObject {
        return buildJsonObject {
            put(
                "anyOf",
                buildJsonArray {
                    add(nonNullSchema)
                    add(
                        buildJsonObject {
                            put("type", "null")
                        }
                    )
                }
            )
        }
    }

    private fun ensureOnlyKeys(
        root: JsonObject,
        allowedKeys: Set<String>,
        context: String
    ) {
        val extraKeys = root.keys - allowedKeys
        if (extraKeys.isNotEmpty()) {
            throw OpenAiParserException("OpenAI returned unexpected fields for $context.")
        }
    }

    private fun JsonObject.requireObject(
        key: String,
        label: String
    ): JsonObject {
        val element = this[key] ?: throw OpenAiParserException("OpenAI response is missing $label.")
        return try {
            element.jsonObject
        } catch (error: Exception) {
            throw OpenAiParserException("OpenAI returned an invalid $label object.")
        }
    }

    private fun JsonObject.requireString(
        key: String,
        label: String
    ): String {
        val element = this[key] ?: throw OpenAiParserException("OpenAI response is missing $label.")
        return requireStringValue(element, label)
    }

    private fun JsonObject.optionalString(
        key: String,
        label: String
    ): String? {
        val element = this[key] ?: throw OpenAiParserException("OpenAI response is missing $label.")
        if (element is JsonNull) return null
        val value = requireStringValue(element, label).trim()
        return value.ifBlank { null }
    }

    private fun requireStringValue(
        element: JsonElement,
        label: String
    ): String {
        val primitive = element as? JsonPrimitive
            ?: throw OpenAiParserException("OpenAI returned an invalid $label value.")
        return primitive.contentOrNull
            ?: throw OpenAiParserException("OpenAI returned a non-string $label value.")
    }

    private fun validateIsoDate(value: String) {
        if (!ISO_DATE_REGEX.matches(value)) {
            throw OpenAiParserException("OpenAI returned an invalid due date format.")
        }
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw OpenAiParserException("OpenAI returned an invalid due date.")
        }
    }

    private fun validateLocalTime(value: String) {
        if (!LOCAL_TIME_REGEX.matches(value)) {
            throw OpenAiParserException("OpenAI returned an invalid due time.")
        }
    }

    private fun parseOpenAiError(
        responseBody: String,
        responseMessage: String?
    ): String {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val errorObject = root["error"]?.jsonObject
            val message = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
            if (message.isNullOrBlank()) {
                "OpenAI request failed: ${responseMessage ?: "unknown error"}."
            } else {
                "OpenAI request failed: $message"
            }
        } catch (_: Exception) {
            "OpenAI request failed: ${responseMessage ?: "unknown error"}."
        }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                    if (line != null) {
                        append('\n')
                    }
                }
            }
        }
    }

    private class OpenAiParserException(message: String) : Exception(message)

    private companion object {
        private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
        private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val LOCAL_TIME_REGEX = Regex("""([01]\d|2[0-3]):[0-5]\d""")
    }
}
