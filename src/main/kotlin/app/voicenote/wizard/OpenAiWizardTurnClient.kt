package app.voicenote.wizard

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OpenAiWizardClientConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val organization: String? = null,
    val timeoutMillis: Long = 30_000L,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-5.4-nano"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiWizardClientConfig {
            val apiKey = env["OPENAI_API_KEY"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: error("OPENAI_API_KEY is required to build the OpenAI wizard client.")
            val model = env["OPENAI_WIZARD_MODEL"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: DEFAULT_MODEL
            val baseUrl = env["OPENAI_BASE_URL"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: DEFAULT_BASE_URL
            val organization = env["OPENAI_ORGANIZATION"]?.trim().takeUnless { it.isNullOrEmpty() }
            return OpenAiWizardClientConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                organization = organization,
            )
        }
    }
}

data class WizardTurnContractAssets(
    val systemPrompt: String,
    val responseSchema: JsonObject,
    val responseFormatName: String = "wizard_turn_response",
) {
    companion object {
        private const val PROMPT_RESOURCE = "app/voicenote/wizard/wizard-turn-system-prompt.md"
        private const val SCHEMA_RESOURCE = "app/voicenote/wizard/wizard-turn-response.schema.json"

        fun loadDefault(
            classLoader: ClassLoader = WizardTurnContractAssets::class.java.classLoader,
            json: Json = OpenAiWizardTurnClient.defaultJson,
        ): WizardTurnContractAssets {
            val systemPrompt = classLoader.readTextResource(PROMPT_RESOURCE)
            val responseSchema = json.parseToJsonElement(
                classLoader.readTextResource(SCHEMA_RESOURCE),
            ).jsonObject
            return WizardTurnContractAssets(
                systemPrompt = systemPrompt,
                responseSchema = responseSchema,
            )
        }

        private fun ClassLoader.readTextResource(path: String): String =
            getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
                ?: error("Missing classpath resource: $path")
    }
}

interface OpenAiResponsesTransport {
    fun createResponse(requestBody: String, config: OpenAiWizardClientConfig): String
}

class HttpOpenAiResponsesTransport(
) : OpenAiResponsesTransport {
    override fun createResponse(requestBody: String, config: OpenAiWizardClientConfig): String {
        val timeoutMillis = config.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val connection = try {
            (URI.create("${config.baseUrl.trimEnd('/')}/responses").toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                config.organization?.let { setRequestProperty("OpenAI-Organization", it) }
            }
        } catch (exception: Exception) {
            throw WizardTurnClientException("OpenAI request could not be prepared.", exception)
        }

        try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }
        } catch (exception: Exception) {
            connection.disconnect()
            throw WizardTurnClientException("OpenAI request failed before a response was received.", exception)
        }

        val responseBody = try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (exception: Exception) {
            connection.disconnect()
            throw WizardTurnClientException("OpenAI response could not be read.", exception)
        }

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw WizardTurnClientException(
                "OpenAI request failed with status ${connection.responseCode}: ${responseBody.truncate()}",
            )
        }

        connection.disconnect()
        return responseBody
    }
}

class OpenAiWizardTurnClient(
    private val config: OpenAiWizardClientConfig,
    private val transport: OpenAiResponsesTransport = HttpOpenAiResponsesTransport(),
    private val assets: WizardTurnContractAssets = WizardTurnContractAssets.loadDefault(),
    private val json: Json = defaultJson,
) : WizardTurnClient {
    private val parser = WizardTurnStructuredResponseParser(
        responseSchema = assets.responseSchema,
        json = json,
    )

    override fun runTurn(request: WizardTurnRequest): WizardTurnResponse {
        val requestBody = json.encodeToString(buildApiRequest(request))
        val responseBody = try {
            transport.createResponse(requestBody, config)
        } catch (exception: WizardTurnClientException) {
            throw exception
        } catch (exception: Exception) {
            throw WizardTurnClientException("OpenAI request failed.", exception)
        }
        val structuredOutput = extractStructuredOutput(responseBody)
        return parser.parse(structuredOutput)
    }

    internal fun extractStructuredOutput(responseBody: String): String {
        val apiResponse = try {
            json.decodeFromString<OpenAiResponsesCreateResponse>(responseBody)
        } catch (exception: Exception) {
            throw WizardTurnClientException("OpenAI response body was not valid JSON.", exception)
        }

        val outputText = apiResponse.outputText?.trim().takeUnless { it.isNullOrEmpty() }
            ?: apiResponse.output
                .asSequence()
                .flatMap { outputItem -> outputItem.content.asSequence() }
                .firstNotNullOfOrNull { content ->
                    content.text
                        ?.takeIf { content.type == "output_text" && it.isNotBlank() }
                        ?.trim()
                }

        return outputText
            ?: throw WizardTurnClientException(
                "OpenAI response did not include structured output text.",
            )
    }

    private fun buildApiRequest(request: WizardTurnRequest): OpenAiResponsesCreateRequest =
        OpenAiResponsesCreateRequest(
            model = config.model,
            input = listOf(
                OpenAiInputMessage(
                    role = "system",
                    content = listOf(OpenAiInputText(text = assets.systemPrompt.trim())),
                ),
                OpenAiInputMessage(
                    role = "user",
                    content = listOf(OpenAiInputText(text = renderUserPrompt(request))),
                ),
            ),
            text = OpenAiTextConfiguration(
                format = OpenAiJsonSchemaFormat(
                    name = assets.responseFormatName,
                    schema = assets.responseSchema,
                ),
            ),
        )

    private fun renderUserPrompt(request: WizardTurnRequest): String = buildString {
        appendLine("Apply the wizard contract to the current local draft.")
        appendLine("Draft id: ${request.draft.id}")
        appendLine("Draft status: ${request.draft.status}")
        appendLine("Session phase: ${request.session.phase}")
        appendLine("Current JobTread lookup: ${request.draft.jobTreadLookup.summaryText()}")
        appendLine("Latest user turn: ${request.userTurn.text}")
        appendLine()
        appendLine("Transcript so far:")
        if (request.draft.transcript.isEmpty()) {
            appendLine("(empty)")
        } else {
            request.draft.transcript.forEachIndexed { index, turn ->
                appendLine("${index + 1}. ${turn.speaker}: ${turn.text}")
            }
        }
    }.trim()

    companion object {
        internal val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            transport: OpenAiResponsesTransport = HttpOpenAiResponsesTransport(),
            assets: WizardTurnContractAssets = WizardTurnContractAssets.loadDefault(),
            json: Json = defaultJson,
        ): OpenAiWizardTurnClient = OpenAiWizardTurnClient(
            config = OpenAiWizardClientConfig.fromEnvironment(env),
            transport = transport,
            assets = assets,
            json = json,
        )
    }
}

internal class WizardTurnStructuredResponseParser(
    responseSchema: JsonObject,
    private val json: Json,
) {
    private val schemaProperties = responseSchema["properties"]?.jsonObject
        ?: error("Wizard response schema is missing properties.")
    private val requiredFields = responseSchema["required"]?.jsonArray
        ?.map { requiredField -> requiredField.jsonPrimitive.content }
        ?.toSet()
        ?: emptySet()
    private val allowAdditionalProperties = responseSchema["additionalProperties"]
        ?.jsonPrimitive
        ?.booleanOrNull
        ?: true

    fun parse(structuredOutput: String): WizardTurnResponse {
        val element = try {
            json.parseToJsonElement(structuredOutput)
        } catch (exception: Exception) {
            throw WizardTurnClientException(
                "Structured output was not valid JSON: ${exception.message}",
                exception,
            )
        }
        val responseObject = element as? JsonObject
            ?: throw WizardTurnClientException("Structured output must be a JSON object.")

        validateShape(responseObject)

        return try {
            json.decodeFromJsonElement(responseObject)
        } catch (exception: Exception) {
            throw WizardTurnClientException(
                "Structured output could not be decoded as WizardTurnResponse.",
                exception,
            )
        }
    }

    private fun validateShape(responseObject: JsonObject) {
        val missingFields = requiredFields
            .filterNot { requiredField ->
                responseObject[requiredField] != null && responseObject[requiredField] !is JsonNull
            }
            .sorted()
        if (missingFields.isNotEmpty()) {
            throw WizardTurnClientException(
                "Structured output is missing required fields: ${missingFields.joinToString(", ")}",
            )
        }

        if (!allowAdditionalProperties) {
            val unsupportedFields = responseObject.keys
                .filterNot(schemaProperties::containsKey)
                .sorted()
            if (unsupportedFields.isNotEmpty()) {
                throw WizardTurnClientException(
                    "Structured output included unsupported fields: ${unsupportedFields.joinToString(", ")}",
                )
            }
        }

        schemaProperties.forEach { (propertyName, propertySchemaElement) ->
            val value = responseObject[propertyName] ?: return@forEach
            validateProperty(
                propertyName = propertyName,
                value = value,
                propertySchema = propertySchemaElement.jsonObject,
            )
        }
    }

    private fun validateProperty(
        propertyName: String,
        value: JsonElement,
        propertySchema: JsonObject,
    ) {
        val expectedType = propertySchema["type"]?.jsonPrimitive?.contentOrNull
        if (expectedType == "string") {
            val primitive = value as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                throw WizardTurnClientException(
                    "Structured output field '$propertyName' must be a string.",
                )
            }

            val stringValue = primitive.content
            val minLength = propertySchema["minLength"]?.jsonPrimitive?.intOrNull
            if (minLength != null && stringValue.length < minLength) {
                throw WizardTurnClientException(
                    "Structured output field '$propertyName' must be at least $minLength characters.",
                )
            }

            val allowedValues = propertySchema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            if (allowedValues != null && stringValue !in allowedValues) {
                throw WizardTurnClientException(
                    "Structured output field '$propertyName' must be one of ${allowedValues.sorted().joinToString(", ")}.",
                )
            }
        }
    }
}

@Serializable
private data class OpenAiResponsesCreateRequest(
    val model: String,
    val input: List<OpenAiInputMessage>,
    val text: OpenAiTextConfiguration,
)

@Serializable
private data class OpenAiInputMessage(
    val role: String,
    val content: List<OpenAiInputText>,
)

@Serializable
private data class OpenAiInputText(
    val type: String = "input_text",
    val text: String,
)

@Serializable
private data class OpenAiTextConfiguration(
    val format: OpenAiJsonSchemaFormat,
)

@Serializable
private data class OpenAiJsonSchemaFormat(
    val type: String = "json_schema",
    val name: String,
    val schema: JsonObject,
    val strict: Boolean = true,
)

@Serializable
private data class OpenAiResponsesCreateResponse(
    @SerialName("output_text")
    val outputText: String? = null,
    val output: List<OpenAiOutputItem> = emptyList(),
)

@Serializable
private data class OpenAiOutputItem(
    val type: String? = null,
    val content: List<OpenAiOutputContent> = emptyList(),
)

@Serializable
private data class OpenAiOutputContent(
    val type: String? = null,
    val text: String? = null,
)

private fun String.truncate(maxLength: Int = 300): String =
    if (length <= maxLength) {
        this
    } else {
        take(maxLength) + "..."
    }
