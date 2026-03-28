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
    val organizationId: String? = null,
    val organizationName: String? = null,
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
        JobTreadSnapshotStatus.SELECTION_REQUIRED ->
            "Select a JobTread organization before lookup can run."
        JobTreadSnapshotStatus.FAILED ->
            "JobTread lookup failed${failureMessage?.let { ": $it" } ?: "."}"
        JobTreadSnapshotStatus.IDLE -> "No JobTread lookup requested yet."
        JobTreadSnapshotStatus.LOADED -> when (resolutionStatus) {
            JobTreadResolutionStatus.NOT_REQUESTED -> "No JobTread lookup requested yet."
            JobTreadResolutionStatus.RESOLVED ->
                "Resolved job: ${resolvedJob?.displayLabel() ?: "Unknown job"}"
            JobTreadResolutionStatus.AMBIGUOUS ->
                "Ambiguous job reference \"$requestedReferenceText\": ${
                    ambiguousJobs.joinToString(limit = 3) { it.displayLabel() }
                }"
            JobTreadResolutionStatus.UNRESOLVED ->
                "No JobTread job matched \"$requestedReferenceText\"."
        }
    }
}

@Serializable
data class JobTreadOrganizationSelectionState(
    val status: JobTreadOrganizationSelectionStatus = JobTreadOrganizationSelectionStatus.IDLE,
    val organizations: List<JobTreadOrganization> = emptyList(),
    val selectedOrganizationId: String? = null,
    val defaultOrganizationId: String? = null,
    val failureMessage: String? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    fun selectedOrganization(): JobTreadOrganization? =
        organizations.firstOrNull { it.id == selectedOrganizationId }

    fun summaryText(): String = when (status) {
        JobTreadOrganizationSelectionStatus.IDLE ->
            "Refresh JobTread organizations to choose a default organization."
        JobTreadOrganizationSelectionStatus.SELECTED_AUTOMATICALLY ->
            "Active organization: ${selectedOrganization()?.name ?: "Unknown"} (selected automatically)."
        JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT ->
            "Active organization: ${selectedOrganization()?.name ?: "Unknown"} (saved default)."
        JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED ->
            "Multiple JobTread organizations are available. Choose and save a default organization."
        JobTreadOrganizationSelectionStatus.MISSING_CONFIGURATION ->
            "JobTread organization selection is unavailable until Pave URL and grant key are configured."
        JobTreadOrganizationSelectionStatus.FAILURE ->
            "JobTread organization refresh failed${failureMessage?.let { ": $it" } ?: "."}"
    }

    fun saveDefaultOrganization(
        organizationId: String,
        updatedAtEpochMillis: Long,
    ): JobTreadOrganizationSelectionState {
        val organization = organizations.firstOrNull { it.id == organizationId }
            ?: return copy(
                status = JobTreadOrganizationSelectionStatus.FAILURE,
                failureMessage = "JobTread organization $organizationId was not found in the cached list.",
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        return copy(
            status = JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT,
            selectedOrganizationId = organization.id,
            defaultOrganizationId = organization.id,
            failureMessage = null,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    fun canRunLookup(): Boolean =
        status in setOf(
            JobTreadOrganizationSelectionStatus.SELECTED_AUTOMATICALLY,
            JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT,
        ) && selectedOrganization() != null
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
            .joinToString(" / ")
            .takeIf(String::isNotBlank)
        return listOfNotNull(name.takeIf(String::isNotBlank), suffix).joinToString(" - ")
    }

    internal fun searchText(): String = listOfNotNull(name, customerName, locationName).joinToString(" ")
}

enum class JobTreadSnapshotStatus {
    IDLE,
    LOADED,
    CONFIG_MISSING,
    SELECTION_REQUIRED,
    FAILED,
}

enum class JobTreadResolutionStatus {
    NOT_REQUESTED,
    RESOLVED,
    AMBIGUOUS,
    UNRESOLVED,
}

enum class JobTreadOrganizationSelectionStatus {
    IDLE,
    SELECTED_AUTOMATICALLY,
    SELECTED_FROM_SAVED_DEFAULT,
    SELECTION_REQUIRED,
    MISSING_CONFIGURATION,
    FAILURE,
}

data class JobTreadLookupExecution(
    val organizationSelection: JobTreadOrganizationSelectionState,
    val lookupState: JobTreadLookupState,
)

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
            return JobTreadLookupConfig(paveUrl = paveUrl, grantKey = grantKey)
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
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        } catch (exception: Exception) {
            connection.disconnect()
            throw JobTreadLookupException("JobTread lookup request failed before a response was received.", exception)
        }
        val responseBody = try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
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
    fun loadAccessibleOrganizations(): List<JobTreadOrganization> {
        val queries = listOf(
            buildCurrentGrantOrganizationsRequestBody(),
            buildRootOrganizationsRequestBody(),
            buildCurrentGrantOrganizationRequestBody(),
        )
        var lastException: JobTreadLookupException? = null
        queries.forEach { requestBody ->
            try {
                val organizations = parseOrganizations(executeQuery(requestBody))
                if (organizations.isNotEmpty()) {
                    return organizations.sortedWith(compareBy(JobTreadOrganization::name, JobTreadOrganization::id))
                }
            } catch (exception: JobTreadLookupException) {
                lastException = exception
            }
        }
        throw lastException ?: JobTreadLookupException("JobTread organization response did not include any accessible organizations.")
    }

    fun loadSnapshot(organizationId: String): JobTreadLookupSnapshot =
        parseSnapshot(executeQuery(buildSnapshotRequestBody(organizationId)))

    internal fun parseOrganizations(responseBody: String): List<JobTreadOrganization> {
        val root = parseResponseRoot(responseBody)
        val organizationsArray = listOfNotNull(
            root.findArray("currentGrant", "organizations", "nodes"),
            root.findArray("currentGrant", "organizations", "connection", "nodes"),
            root.findArray("organizations", "nodes"),
            root.findArray("organizations", "connection", "nodes"),
        ).firstOrNull()
        if (organizationsArray != null) {
            return organizationsArray.mapIndexed { index, element -> element.toOrganization(index) }
        }
        val singleOrganization = listOfNotNull(
            root.findObject("currentGrant", "organization"),
            root.findObject("organization"),
        ).firstOrNull()
        return listOfNotNull(singleOrganization?.toOrganization())
    }

    internal fun parseSnapshot(responseBody: String): JobTreadLookupSnapshot {
        val root = parseResponseRoot(responseBody)
        val organization = listOfNotNull(
            root.findObject("organization"),
            root.findObject("currentGrant", "organization"),
            root.findObject("scope"),
        ).firstOrNull()?.toOrganization()
            ?: throw JobTreadLookupException("JobTread lookup response did not include an organization snapshot.")
        val jobsArray = listOfNotNull(
            root.findArray("organization", "connection", "nodes"),
            root.findArray("organization", "jobs", "nodes"),
            root.findArray("currentGrant", "organization", "connection", "nodes"),
            root.findArray("scope", "connection", "nodes"),
        ).firstOrNull() ?: emptyList<JsonElement>()
        return JobTreadLookupSnapshot(
            organization = organization,
            jobs = jobsArray.mapIndexed { index, jobElement ->
                val jobObject = jobElement as? JsonObject
                    ?: throw JobTreadLookupException("Job snapshot at index $index must be a JSON object.")
                val id = jobObject["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw JobTreadLookupException("Job snapshot at index $index is missing id.")
                val name = jobObject["name"]?.jsonPrimitive?.contentOrNull
                    ?: throw JobTreadLookupException("Job snapshot at index $index is missing name.")
                JobTreadJobSummary(
                    id = id,
                    name = name,
                    customerName = jobObject.findObject("location", "account")?.get("name")?.jsonPrimitive?.contentOrNull,
                    locationName = jobObject.findObject("location")?.get("name")?.jsonPrimitive?.contentOrNull,
                )
            }.sortedWith(compareBy(JobTreadJobSummary::name, JobTreadJobSummary::id)),
        )
    }

    private fun executeQuery(requestBody: JsonObject): String = try {
        transport.execute(requestBody.toString(), config)
    } catch (exception: JobTreadLookupException) {
        throw exception
    } catch (exception: Exception) {
        throw JobTreadLookupException("JobTread lookup request failed.", exception)
    }

    private fun buildCurrentGrantOrganizationsRequestBody(): JsonObject = buildRequestBody(
        buildJsonObject {
            putJsonObject("currentGrant") {
                putJsonObject("id") {}
                putJsonObject("organizations") {
                    putJsonObject("nodes") {
                        putJsonObject("id") {}
                        putJsonObject("name") {}
                    }
                }
            }
        },
    )

    private fun buildRootOrganizationsRequestBody(): JsonObject = buildRequestBody(
        buildJsonObject {
            putJsonObject("organizations") {
                putJsonObject("nodes") {
                    putJsonObject("id") {}
                    putJsonObject("name") {}
                }
            }
        },
    )

    private fun buildCurrentGrantOrganizationRequestBody(): JsonObject = buildRequestBody(
        buildJsonObject {
            putJsonObject("currentGrant") {
                putJsonObject("id") {}
                putJsonObject("organization") {
                    putJsonObject("id") {}
                    putJsonObject("name") {}
                }
            }
        },
    )

    private fun buildSnapshotRequestBody(organizationId: String): JsonObject = buildRequestBody(
        buildJsonObject {
            putJsonObject("organization") {
                putJsonObject("$") {
                    put("id", organizationId)
                }
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
        },
    )

    private fun buildRequestBody(query: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("query") {
            putJsonObject("$") {
                put("grantKey", config.grantKey)
                put("timeZone", config.timeZoneId)
            }
            query.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun parseResponseRoot(responseBody: String): JsonObject {
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
        return root
    }

    companion object {
        internal val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}

interface JobTreadLookupRepository {
    fun refreshOrganizationSelection(
        currentSelection: JobTreadOrganizationSelectionState = JobTreadOrganizationSelectionState(),
    ): JobTreadOrganizationSelectionState

    fun resolveJobReference(
        referenceText: String?,
        currentSelection: JobTreadOrganizationSelectionState = JobTreadOrganizationSelectionState(),
    ): JobTreadLookupExecution
}

class ReadOnlyJobTreadLookupRepository(
    private val configProvider: () -> JobTreadLookupConfig?,
    private val clientFactory: (JobTreadLookupConfig) -> JobTreadLookupApiClient = ::JobTreadLookupApiClient,
    private val resolver: JobTreadJobReferenceResolver = JobTreadJobReferenceResolver,
    private val clock: EpochClock = SystemEpochClock,
) : JobTreadLookupRepository {
    override fun refreshOrganizationSelection(
        currentSelection: JobTreadOrganizationSelectionState,
    ): JobTreadOrganizationSelectionState {
        val now = clock.nowEpochMillis()
        val config = configProvider()
            ?: return currentSelection.copy(
                status = JobTreadOrganizationSelectionStatus.MISSING_CONFIGURATION,
                failureMessage = "Missing Pave URL or grant key.",
                updatedAtEpochMillis = now,
            )
        return try {
            val organizations = clientFactory(config).loadAccessibleOrganizations()
            JobTreadOrganizationSelectionResolver.resolve(
                organizations = organizations,
                savedDefaultOrganizationId = currentSelection.defaultOrganizationId,
                updatedAtEpochMillis = now,
            )
        } catch (exception: Exception) {
            currentSelection.copy(
                status = JobTreadOrganizationSelectionStatus.FAILURE,
                failureMessage = exception.message ?: exception::class.java.simpleName,
                updatedAtEpochMillis = now,
            )
        }
    }

    override fun resolveJobReference(
        referenceText: String?,
        currentSelection: JobTreadOrganizationSelectionState,
    ): JobTreadLookupExecution {
        val normalizedReference = referenceText?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return JobTreadLookupExecution(currentSelection, JobTreadLookupState())

        val selectionState = refreshOrganizationSelection(currentSelection)
        val selectedOrganization = selectionState.selectedOrganization()
        if (!selectionState.canRunLookup() || selectedOrganization == null) {
            return JobTreadLookupExecution(
                organizationSelection = selectionState,
                lookupState = selectionState.toBlockedLookupState(
                    requestedReferenceText = normalizedReference,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
            )
        }

        val config = configProvider()
            ?: return JobTreadLookupExecution(
                organizationSelection = selectionState.copy(
                    status = JobTreadOrganizationSelectionStatus.MISSING_CONFIGURATION,
                    failureMessage = "Missing Pave URL or grant key.",
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
                lookupState = JobTreadLookupState(
                    requestedReferenceText = normalizedReference,
                    organizationId = selectedOrganization.id,
                    organizationName = selectedOrganization.name,
                    snapshotStatus = JobTreadSnapshotStatus.CONFIG_MISSING,
                    resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                    failureMessage = "Missing Pave URL or grant key.",
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
            )

        return try {
            val snapshot = clientFactory(config).loadSnapshot(selectedOrganization.id)
            val result = resolver.resolve(normalizedReference, snapshot.jobs)
            JobTreadLookupExecution(
                organizationSelection = selectionState,
                lookupState = JobTreadLookupState(
                    requestedReferenceText = normalizedReference,
                    organizationId = selectedOrganization.id,
                    organizationName = selectedOrganization.name,
                    snapshotStatus = JobTreadSnapshotStatus.LOADED,
                    resolutionStatus = result.status,
                    snapshot = snapshot,
                    resolvedJob = result.resolvedJob,
                    ambiguousJobs = result.candidates,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
            )
        } catch (exception: Exception) {
            JobTreadLookupExecution(
                organizationSelection = selectionState,
                lookupState = JobTreadLookupState(
                    requestedReferenceText = normalizedReference,
                    organizationId = selectedOrganization.id,
                    organizationName = selectedOrganization.name,
                    snapshotStatus = JobTreadSnapshotStatus.FAILED,
                    resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                    failureMessage = exception.message ?: exception::class.java.simpleName,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
            )
        }
    }
}

data class JobTreadResolutionResult(
    val status: JobTreadResolutionStatus,
    val resolvedJob: JobTreadJobSummary? = null,
    val candidates: List<JobTreadJobSummary> = emptyList(),
)

object JobTreadOrganizationSelectionResolver {
    fun resolve(
        organizations: List<JobTreadOrganization>,
        savedDefaultOrganizationId: String?,
        updatedAtEpochMillis: Long,
    ): JobTreadOrganizationSelectionState {
        val sortedOrganizations = organizations.sortedWith(compareBy(JobTreadOrganization::name, JobTreadOrganization::id))
        if (sortedOrganizations.isEmpty()) {
            return JobTreadOrganizationSelectionState(
                status = JobTreadOrganizationSelectionStatus.FAILURE,
                defaultOrganizationId = savedDefaultOrganizationId,
                failureMessage = "No accessible JobTread organizations were returned.",
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }
        if (sortedOrganizations.size == 1) {
            val organization = sortedOrganizations.single()
            return JobTreadOrganizationSelectionState(
                status = JobTreadOrganizationSelectionStatus.SELECTED_AUTOMATICALLY,
                organizations = sortedOrganizations,
                selectedOrganizationId = organization.id,
                defaultOrganizationId = organization.id,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }
        val savedDefault = savedDefaultOrganizationId?.let { id ->
            sortedOrganizations.firstOrNull { it.id == id }
        }
        if (savedDefault != null) {
            return JobTreadOrganizationSelectionState(
                status = JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT,
                organizations = sortedOrganizations,
                selectedOrganizationId = savedDefault.id,
                defaultOrganizationId = savedDefault.id,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }
        return JobTreadOrganizationSelectionState(
            status = JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED,
            organizations = sortedOrganizations,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }
}

object JobTreadJobReferenceResolver {
    fun resolve(
        referenceText: String,
        jobs: List<JobTreadJobSummary>,
    ): JobTreadResolutionResult {
        val normalizedReference = referenceText.normalizeForLookup()
        if (normalizedReference.isBlank()) {
            return JobTreadResolutionResult(JobTreadResolutionStatus.NOT_REQUESTED)
        }
        val exactMatches = jobs.matching {
            it.searchText().normalizeForLookup() == normalizedReference ||
                it.name.normalizeForLookup() == normalizedReference
        }
        if (exactMatches.isResolved()) {
            return JobTreadResolutionResult(JobTreadResolutionStatus.RESOLVED, resolvedJob = exactMatches.single())
        }
        if (exactMatches.isAmbiguous()) {
            return JobTreadResolutionResult(JobTreadResolutionStatus.AMBIGUOUS, candidates = exactMatches)
        }
        val containsMatches = jobs.matching {
            val candidateSearchText = it.searchText().normalizeForLookup()
            candidateSearchText.contains(normalizedReference) ||
                normalizedReference.contains(it.name.normalizeForLookup())
        }
        if (containsMatches.isResolved()) {
            return JobTreadResolutionResult(JobTreadResolutionStatus.RESOLVED, resolvedJob = containsMatches.single())
        }
        if (containsMatches.isAmbiguous()) {
            return JobTreadResolutionResult(JobTreadResolutionStatus.AMBIGUOUS, candidates = containsMatches)
        }
        val tokenMatches = jobs.matching {
            val candidateTokens = it.searchText().normalizeForLookup()
            normalizedReference.split(' ').filter(String::isNotBlank).all(candidateTokens::contains)
        }
        return when {
            tokenMatches.isResolved() ->
                JobTreadResolutionResult(JobTreadResolutionStatus.RESOLVED, resolvedJob = tokenMatches.single())
            tokenMatches.isAmbiguous() ->
                JobTreadResolutionResult(JobTreadResolutionStatus.AMBIGUOUS, candidates = tokenMatches)
            else -> JobTreadResolutionResult(JobTreadResolutionStatus.UNRESOLVED)
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

private fun JsonObject.toOrganization(): JobTreadOrganization? {
    val id = get("id")?.jsonPrimitive?.contentOrNull ?: return null
    val name = get("name")?.jsonPrimitive?.contentOrNull ?: return null
    return JobTreadOrganization(id = id, name = name)
}

private fun JsonElement.toOrganization(index: Int): JobTreadOrganization {
    val organizationObject = this as? JsonObject
        ?: throw JobTreadLookupException("Organization snapshot at index $index must be a JSON object.")
    return organizationObject.toOrganization()
        ?: throw JobTreadLookupException("Organization snapshot at index $index is missing id or name.")
}

private fun JobTreadOrganizationSelectionState.toBlockedLookupState(
    requestedReferenceText: String,
    updatedAtEpochMillis: Long,
): JobTreadLookupState = when (status) {
    JobTreadOrganizationSelectionStatus.MISSING_CONFIGURATION ->
        JobTreadLookupState(
            requestedReferenceText = requestedReferenceText,
            snapshotStatus = JobTreadSnapshotStatus.CONFIG_MISSING,
            resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
            failureMessage = failureMessage ?: "Missing Pave URL or grant key.",
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED ->
        JobTreadLookupState(
            requestedReferenceText = requestedReferenceText,
            snapshotStatus = JobTreadSnapshotStatus.SELECTION_REQUIRED,
            resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
            failureMessage = "Select a JobTread organization before lookup can run.",
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    JobTreadOrganizationSelectionStatus.FAILURE ->
        JobTreadLookupState(
            requestedReferenceText = requestedReferenceText,
            snapshotStatus = JobTreadSnapshotStatus.FAILED,
            resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
            failureMessage = failureMessage ?: "JobTread organization refresh failed.",
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    JobTreadOrganizationSelectionStatus.IDLE,
    JobTreadOrganizationSelectionStatus.SELECTED_AUTOMATICALLY,
    JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT,
    -> JobTreadLookupState(
        requestedReferenceText = requestedReferenceText,
        snapshotStatus = JobTreadSnapshotStatus.SELECTION_REQUIRED,
        resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
        failureMessage = "Select a JobTread organization before lookup can run.",
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun List<JobTreadJobSummary>.matching(predicate: (JobTreadJobSummary) -> Boolean): List<JobTreadJobSummary> =
    filter(predicate).sortedWith(compareBy(JobTreadJobSummary::name, JobTreadJobSummary::id))

private fun List<JobTreadJobSummary>.isResolved(): Boolean = size == 1

private fun List<JobTreadJobSummary>.isAmbiguous(): Boolean = size > 1

private fun String.normalizeForLookup(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun String.truncate(maxLength: Int = 300): String =
    if (length <= maxLength) this else take(maxLength) + "..."
