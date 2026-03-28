package app.voicenote.wizard

import java.net.HttpURLConnection
import java.net.URI
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class JobTreadLookupState(
    val requestedReferenceText: String? = null,
    val snapshotStatus: JobTreadSnapshotStatus = JobTreadSnapshotStatus.IDLE,
    val resolutionStatus: JobTreadResolutionStatus = JobTreadResolutionStatus.NOT_REQUESTED,
    val snapshot: JobTreadLookupSnapshot = JobTreadLookupSnapshot(),
    val resolvedJob: JobTreadJobSummary? = null,
    val ambiguousJobs: List<JobTreadJobSummary> = emptyList(),
    val failureMessage: String? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    fun summaryText(): String = when (snapshotStatus) {
        JobTreadSnapshotStatus.CONFIG_MISSING ->
            "JobTread lookup requested for \"$requestedReferenceText\", but Pave URL or grant key is missing."

        JobTreadSnapshotStatus.FAILED ->
            "JobTread lookup failed${failureMessage?.let { ": $it" } ?: "."}"

        JobTreadSnapshotStatus.IDLE -> when (resolutionStatus) {
            JobTreadResolutionStatus.NOT_REQUESTED -> "No JobTread lookup requested yet."
            JobTreadResolutionStatus.RESOLVED,
            JobTreadResolutionStatus.AMBIGUOUS,
            JobTreadResolutionStatus.UNRESOLVED,
            -> "No JobTread lookup requested yet."
        }

        JobTreadSnapshotStatus.LOADED -> when (resolutionStatus) {
            JobTreadResolutionStatus.NOT_REQUESTED -> "No JobTread lookup requested yet."
            JobTreadResolutionStatus.RESOLVED -> "Resolved job: ${resolvedJob?.displayLabel() ?: "Unknown job"}"
            JobTreadResolutionStatus.AMBIGUOUS ->
                "Ambiguous job reference \"$requestedReferenceText\": ${
                    ambiguousJobs.joinToString(limit = 3) { candidate -> candidate.displayLabel() }
                }"

            JobTreadResolutionStatus.UNRESOLVED ->
                "No JobTread job matched \"$requestedReferenceText\"."
        }
    }
}

@Serializable
data class JobTreadLookupSnapshot(
    val organization: JobTreadOrganization? = null,
    val jobs: List<JobTreadJobSummary> = emptyList(),
)

@Serializable
data class JobTreadOrganization(
    val id: String,
    val name: String,
)

@Serializable
data class JobTreadJobSummary(
    val id: String,
    val name: String,
    val customerName: String? = null,
    val locationName: String? = null,
) {
    fun displayLabel(): String {
        val suffix = listOfNotNull(customerName, locationName)
            .joinToString(separator = " / ")
            .takeIf { it.isNotBlank() }
        return listOfNotNull(name.takeIf { it.isNotBlank() }, suffix)
            .joinToString(separator = " - ")
    }

    internal fun searchText(): String = listOfNotNull(name, customerName, locationName)
        .joinToString(separator = " ")
}

enum class JobTreadSnapshotStatus {
    IDLE,
    LOADED,
    CONFIG_MISSING,
    FAILED,
}

enum class JobTreadResolutionStatus {
    NOT_REQUESTED,
    RESOLVED,
    AMBIGUOUS,
    UNRESOLVED,
}

data class JobTreadLookupConfig(
    val paveUrl: String,
    val grantKey: String,
    val timeZoneId: String = TimeZone.getDefault().id,
    val timeoutMillis: Long = 30_000L,
    val jobPageSize: Int = 100,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): JobTreadLookupConfig {
            val paveUrl = env["JOBTREAD_PAVE_URL"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: error("JOBTREAD_PAVE_URL is required to build the JobTread lookup client.")
            val grantKey = env["JOBTREAD_GRANT_KEY"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: error("JOBTREAD_GRANT_KEY is required to build the JobTread lookup client.")
            return JobTreadLookupConfig(
                paveUrl = paveUrl,
                grantKey = grantKey,
            )
        }
    }
}

open class JobTreadLookupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface JobTreadPaveTransport {
    fun execute(requestBody: String, config: JobTreadLookupConfig): String
}

class HttpJobTreadPaveTransport : JobTreadPaveTransport {
    override fun execute(requestBody: String, config: JobTreadLookupConfig): String {
        val timeoutMillis = config.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val connection = try {
            (URI.create("${config.paveUrl.trimEnd('/')}/pave").toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        } catch (exception: Exception) {
            throw JobTreadLookupException("JobTread lookup request could not be prepared.", exception)
        }

        try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }
        } catch (exception: Exception) {
            connection.disconnect()
            throw JobTreadLookupException("JobTread lookup request failed before a response was received.", exception)
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
            throw JobTreadLookupException("JobTread lookup response could not be read.", exception)
        }

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw JobTreadLookupException(
                "JobTread lookup request failed with status ${connection.responseCode}: ${responseBody.truncate()}",
            )
        }

        connection.disconnect()
        return responseBody
    }
}

class JobTreadLookupApiClient(
    private val config: JobTreadLookupConfig,
    private val transport: JobTreadPaveTransport = HttpJobTreadPaveTransport(),
    private val json: Json = defaultJson,
) {
    fun loadSnapshot(): JobTreadLookupSnapshot {
        val requestBody = buildRequestBody().toString()
        val responseBody = try {
            transport.execute(requestBody, config)
        } catch (exception: JobTreadLookupException) {
            throw exception
        } catch (exception: Exception) {
            throw JobTreadLookupException("JobTread lookup request failed.", exception)
        }
        return parseSnapshot(responseBody)
    }

    internal fun parseSnapshot(responseBody: String): JobTreadLookupSnapshot {
        val element = try {
            json.parseToJsonElement(responseBody)
        } catch (exception: Exception) {
            throw JobTreadLookupException("JobTread lookup response was not valid JSON.", exception)
        }
        val root = element as? JsonObject
            ?: throw JobTreadLookupException("JobTread lookup response must be a JSON object.")
        val errorMessage = root["error"]?.jsonPrimitive?.contentOrNull
        if (!errorMessage.isNullOrBlank()) {
            throw JobTreadLookupException("JobTread lookup failed: $errorMessage")
        }

        val organization = findOrganization(root)
            ?: throw JobTreadLookupException("JobTread lookup response did not include an organization snapshot.")

        return JobTreadLookupSnapshot(
            organization = organization,
            jobs = findJobs(root).sortedWith(compareBy(JobTreadJobSummary::name, JobTreadJobSummary::id)),
        )
    }

    private fun buildRequestBody(): JsonObject = buildJsonObject {
        putJsonObject("query") {
            putJsonObject("$") {
                put("grantKey", config.grantKey)
                put("timeZone", config.timeZoneId)
            }
            putJsonObject("currentGrant") {
                putJsonObject("id") {}
                putJsonObject("organization") {
                    putJsonObject("id") {}
                    putJsonObject("name") {}
                    putJsonObject("connection") {
                        put("_", "jobs")
                        putJsonObject("$") {
                            put("size", config.jobPageSize)
                            put(
                                "sortBy",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("field", "id")
                                            put("order", "desc")
                                        },
                                    )
                                },
                            )
                        }
                        putJsonObject("nodes") {
                            putJsonObject("id") {}
                            putJsonObject("name") {}
                            putJsonObject("location") {
                                putJsonObject("name") {}
                                putJsonObject("account") {
                                    putJsonObject("name") {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findOrganization(root: JsonObject): JobTreadOrganization? {
        val organizationObject = sequenceOf(
            root.findObject("currentGrant", "organization"),
            root.findObject("organization"),
            root.findObject("scope"),
        ).firstOrNull()
            ?: return null

        val id = organizationObject["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = organizationObject["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return JobTreadOrganization(
            id = id,
            name = name,
        )
    }

    private fun findJobs(root: JsonObject): List<JobTreadJobSummary> {
        val jobsArray = sequenceOf(
            root.findArray("currentGrant", "organization", "connection", "nodes"),
            root.findArray("organization", "connection", "nodes"),
            root.findArray("scope", "connection", "nodes"),
            root.findArray("currentGrant", "organization", "jobs", "nodes"),
            root.findArray("organization", "jobs", "nodes"),
        ).firstOrNull() ?: return emptyList()

        return jobsArray.mapIndexed { index, jobElement ->
            val jobObject = jobElement as? JsonObject
                ?: throw JobTreadLookupException("Job snapshot at index $index must be a JSON object.")
            val id = jobObject["id"]?.jsonPrimitive?.contentOrNull
                ?: throw JobTreadLookupException("Job snapshot at index $index is missing id.")
            val name = jobObject["name"]?.jsonPrimitive?.contentOrNull
                ?: throw JobTreadLookupException("Job snapshot at index $index is missing name.")
            JobTreadJobSummary(
                id = id,
                name = name,
                customerName = jobObject
                    .findObject("location", "account")
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull,
                locationName = jobObject
                    .findObject("location")
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            )
        }
    }

    companion object {
        internal val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}

interface JobTreadLookupRepository {
    fun resolveJobReference(referenceText: String?): JobTreadLookupState
}

class ReadOnlyJobTreadLookupRepository(
    private val configProvider: () -> JobTreadLookupConfig?,
    private val clientFactory: (JobTreadLookupConfig) -> JobTreadLookupApiClient = ::JobTreadLookupApiClient,
    private val resolver: JobTreadJobReferenceResolver = JobTreadJobReferenceResolver,
    private val clock: EpochClock = SystemEpochClock,
) : JobTreadLookupRepository {
    override fun resolveJobReference(referenceText: String?): JobTreadLookupState {
        val normalizedReference = referenceText?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return JobTreadLookupState()

        val config = configProvider()
            ?: return JobTreadLookupState(
                requestedReferenceText = normalizedReference,
                snapshotStatus = JobTreadSnapshotStatus.CONFIG_MISSING,
                resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                failureMessage = "Missing Pave URL or grant key.",
                updatedAtEpochMillis = clock.nowEpochMillis(),
            )

        return try {
            val snapshot = clientFactory(config).loadSnapshot()
            val result = resolver.resolve(
                referenceText = normalizedReference,
                jobs = snapshot.jobs,
            )
            JobTreadLookupState(
                requestedReferenceText = normalizedReference,
                snapshotStatus = JobTreadSnapshotStatus.LOADED,
                resolutionStatus = result.status,
                snapshot = snapshot,
                resolvedJob = result.resolvedJob,
                ambiguousJobs = result.candidates,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            )
        } catch (exception: Exception) {
            JobTreadLookupState(
                requestedReferenceText = normalizedReference,
                snapshotStatus = JobTreadSnapshotStatus.FAILED,
                resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                failureMessage = exception.message ?: exception::class.java.simpleName,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            )
        }
    }
}

data class JobTreadResolutionResult(
    val status: JobTreadResolutionStatus,
    val resolvedJob: JobTreadJobSummary? = null,
    val candidates: List<JobTreadJobSummary> = emptyList(),
)

object JobTreadJobReferenceResolver {
    fun resolve(
        referenceText: String,
        jobs: List<JobTreadJobSummary>,
    ): JobTreadResolutionResult {
        val normalizedReference = referenceText.normalizeForLookup()
        if (normalizedReference.isBlank()) {
            return JobTreadResolutionResult(status = JobTreadResolutionStatus.NOT_REQUESTED)
        }

        val exactMatches = jobs.matching { candidate ->
            candidate.searchText().normalizeForLookup() == normalizedReference ||
                candidate.name.normalizeForLookup() == normalizedReference
        }
        if (exactMatches.isResolved()) {
            return JobTreadResolutionResult(
                status = JobTreadResolutionStatus.RESOLVED,
                resolvedJob = exactMatches.single(),
            )
        }
        if (exactMatches.isAmbiguous()) {
            return JobTreadResolutionResult(
                status = JobTreadResolutionStatus.AMBIGUOUS,
                candidates = exactMatches,
            )
        }

        val containsMatches = jobs.matching { candidate ->
            val candidateSearchText = candidate.searchText().normalizeForLookup()
            candidateSearchText.contains(normalizedReference) ||
                normalizedReference.contains(candidate.name.normalizeForLookup())
        }
        if (containsMatches.isResolved()) {
            return JobTreadResolutionResult(
                status = JobTreadResolutionStatus.RESOLVED,
                resolvedJob = containsMatches.single(),
            )
        }
        if (containsMatches.isAmbiguous()) {
            return JobTreadResolutionResult(
                status = JobTreadResolutionStatus.AMBIGUOUS,
                candidates = containsMatches,
            )
        }

        val tokenMatches = jobs.matching { candidate ->
            val candidateTokens = candidate.searchText().normalizeForLookup()
            normalizedReference.split(' ')
                .filter(String::isNotBlank)
                .all(candidateTokens::contains)
        }
        return when {
            tokenMatches.isResolved() -> JobTreadResolutionResult(
                status = JobTreadResolutionStatus.RESOLVED,
                resolvedJob = tokenMatches.single(),
            )

            tokenMatches.isAmbiguous() -> JobTreadResolutionResult(
                status = JobTreadResolutionStatus.AMBIGUOUS,
                candidates = tokenMatches,
            )

            else -> JobTreadResolutionResult(
                status = JobTreadResolutionStatus.UNRESOLVED,
            )
        }
    }
}

private fun JsonObject.findObject(vararg path: String): JsonObject? =
    path.fold(this as JsonElement?) { current, segment ->
        (current as? JsonObject)?.get(segment)
    } as? JsonObject

private fun JsonObject.findArray(vararg path: String): JsonArray? =
    path.fold(this as JsonElement?) { current, segment ->
        (current as? JsonObject)?.get(segment)
    } as? JsonArray

private fun JsonObject.findObject(first: String, second: String): JsonObject? =
    findObject(*arrayOf(first, second))

private fun List<JobTreadJobSummary>.matching(predicate: (JobTreadJobSummary) -> Boolean): List<JobTreadJobSummary> =
    filter(predicate).sortedWith(compareBy(JobTreadJobSummary::name, JobTreadJobSummary::id))

private fun List<JobTreadJobSummary>.isResolved(): Boolean = size == 1

private fun List<JobTreadJobSummary>.isAmbiguous(): Boolean = size > 1

private fun String.normalizeForLookup(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun String.truncate(maxLength: Int = 300): String =
    if (length <= maxLength) {
        this
    } else {
        take(maxLength) + "..."
    }
