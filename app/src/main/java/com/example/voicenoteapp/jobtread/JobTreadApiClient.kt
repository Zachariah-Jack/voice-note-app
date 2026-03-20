package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.settings.AssistantSettings
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JobTreadApiClient(
    private val json: Json = Json { ignoreUnknownKeys = false }
) {
    suspend fun executeReadOnlyQuery(
        queryRoot: JsonObject,
        settings: AssistantSettings
    ): JobTreadApiResult<JsonObject> {
        return executePaveQuery(
            queryRoot = queryRoot,
            settings = settings,
            missingConfigMessage = "JobTread lookup needs a Pave URL and grant key in Settings."
        )
    }

    suspend fun executeMutation(
        queryRoot: JsonObject,
        settings: AssistantSettings
    ): JobTreadApiResult<JsonObject> {
        return executePaveQuery(
            queryRoot = queryRoot,
            settings = settings,
            missingConfigMessage = "JobTread create needs a Pave URL and grant key in Settings."
        )
    }

    private suspend fun executePaveQuery(
        queryRoot: JsonObject,
        settings: AssistantSettings,
        missingConfigMessage: String
    ): JobTreadApiResult<JsonObject> {
        if (!settings.hasJobTreadConfig) {
            return JobTreadApiResult.MissingConfiguration(
                fields = settings.missingJobTreadFields,
                message = missingConfigMessage
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val payload = buildPayload(
                    grantKey = settings.jobTreadApiKey.trim(),
                    queryRoot = queryRoot
                )
                val responseBody = executeRequest(
                    paveUrl = normalizePaveUrl(settings.jobTreadBaseUrl),
                    payload = payload
                )
                val root = json.parseToJsonElement(responseBody).jsonObject
                val apiError = extractApiError(root)
                if (apiError != null) {
                    JobTreadApiResult.Failure(apiError)
                } else {
                    JobTreadApiResult.Success(root)
                }
            } catch (error: JobTreadApiException) {
                JobTreadApiResult.Failure(error.message ?: "JobTread request failed.")
            } catch (error: Exception) {
                JobTreadApiResult.Failure(
                    "JobTread request failed: ${error.message ?: "unknown error"}."
                )
            }
        }
    }

    private fun buildPayload(
        grantKey: String,
        queryRoot: JsonObject
    ): String {
        val payload = buildJsonObject {
            put(
                "query",
                buildJsonObject {
                    put(
                        "$",
                        buildJsonObject {
                            put("grantKey", grantKey)
                        }
                    )
                    queryRoot.forEach { (key, value) -> put(key, value) }
                }
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun executeRequest(
        paveUrl: String,
        payload: String
    ): String {
        val connection = (URL(paveUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                output.write(payload.toByteArray(StandardCharsets.UTF_8))
            }

            val responseBody = readStream(
                if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
            )

            if (connection.responseCode !in 200..299) {
                throw JobTreadApiException(parseApiError(responseBody, connection.responseMessage))
            }

            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizePaveUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            throw JobTreadApiException("JobTread request needs a Pave URL in Settings.")
        }
        return if (trimmed.endsWith("/pave", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/pave"
        }
    }

    private fun extractApiError(root: JsonObject): String? {
        val errorMessage = root["error"]
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: root["errors"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull

        return errorMessage
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "JobTread request failed: $it" }
    }

    private fun parseApiError(
        responseBody: String,
        responseMessage: String?
    ): String {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            extractApiError(root)
                ?: "JobTread request failed: ${responseMessage ?: "unknown error"}."
        } catch (_: Exception) {
            "JobTread request failed: ${responseMessage ?: "unknown error"}."
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

    private class JobTreadApiException(message: String) : Exception(message)
}
