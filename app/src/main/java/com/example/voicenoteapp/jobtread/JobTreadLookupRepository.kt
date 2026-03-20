package com.example.voicenoteapp.jobtread

import com.example.voicenoteapp.settings.AssistantSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JobTreadLookupRepository(
    private val apiClient: JobTreadApiClient
) {
    suspend fun loadLookupSnapshot(settings: AssistantSettings): JobTreadLookupLoadResult {
        return when (val organizationResult = resolveOrganization(settings)) {
            is JobTreadLookupLoadResult.Success -> loadSnapshotForOrganization(
                organization = organizationResult.snapshot.organization,
                settings = settings
            )

            is JobTreadLookupLoadResult.MissingConfiguration -> organizationResult
            is JobTreadLookupLoadResult.AmbiguousOrganization -> organizationResult
            is JobTreadLookupLoadResult.Failure -> organizationResult
        }
    }

    private suspend fun resolveOrganization(settings: AssistantSettings): JobTreadLookupLoadResult {
        return when (val result = apiClient.executeReadOnlyQuery(buildCurrentGrantQuery(), settings)) {
            is JobTreadApiResult.Success -> {
                val organizations = parseOrganizations(result.value)
                when {
                    organizations.isEmpty() -> JobTreadLookupLoadResult.Failure(
                        "The JobTread grant did not expose any organizations for lookup."
                    )

                    organizations.size == 1 -> JobTreadLookupLoadResult.Success(
                        JobTreadLookupSnapshot(
                            organization = organizations.single(),
                            assignees = emptyList(),
                            jobs = emptyList()
                        )
                    )

                    else -> JobTreadLookupLoadResult.AmbiguousOrganization(
                        organizations = organizations,
                        message = "The JobTread grant can access multiple organizations. Use a grant scoped to one organization for deterministic lookup."
                    )
                }
            }

            is JobTreadApiResult.MissingConfiguration -> JobTreadLookupLoadResult.MissingConfiguration(
                fields = result.fields,
                message = result.message
            )

            is JobTreadApiResult.Failure -> JobTreadLookupLoadResult.Failure(result.message)
        }
    }

    private suspend fun loadSnapshotForOrganization(
        organization: JobTreadOrganization,
        settings: AssistantSettings
    ): JobTreadLookupLoadResult {
        return when (val result = apiClient.executeReadOnlyQuery(buildLookupQuery(organization.id), settings)) {
            is JobTreadApiResult.Success -> {
                try {
                    JobTreadLookupLoadResult.Success(
                        snapshot = parseLookupSnapshot(
                            root = result.value,
                            organization = organization
                        )
                    )
                } catch (error: Exception) {
                    JobTreadLookupLoadResult.Failure(
                        "JobTread lookup response was not in the expected format."
                    )
                }
            }

            is JobTreadApiResult.MissingConfiguration -> JobTreadLookupLoadResult.MissingConfiguration(
                fields = result.fields,
                message = result.message
            )

            is JobTreadApiResult.Failure -> JobTreadLookupLoadResult.Failure(result.message)
        }
    }

    private fun buildCurrentGrantQuery(): JsonObject {
        return buildJsonObject {
            put(
                "currentGrantInfo",
                buildJsonObject {
                    put(
                        "currentGrant",
                        buildJsonObject {
                            put(
                                "organization",
                                buildJsonObject {
                                    put("id", field())
                                    put("name", field())
                                }
                            )
                            put(
                                "user",
                                buildJsonObject {
                                    put("id", field())
                                    put("name", field())
                                    put(
                                        "memberships",
                                        buildJsonObject {
                                            put("nextPage", field())
                                            put(
                                                "nodes",
                                                buildJsonObject {
                                                    put("id", field())
                                                    put(
                                                        "organization",
                                                        buildJsonObject {
                                                            put("id", field())
                                                            put("name", field())
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun buildLookupQuery(organizationId: String): JsonObject {
        return buildJsonObject {
            put(
                "organization",
                buildJsonObject {
                    put(
                        "$",
                        buildJsonObject {
                            put("id", organizationId)
                        }
                    )
                    put("id", field())
                    put("name", field())
                    put(
                        "memberships",
                        buildJsonObject {
                            put(
                                "$",
                                buildJsonObject {
                                    put("size", 200)
                                    put(
                                        "where",
                                        buildJsonObject {
                                            put(
                                                "and",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonArray {
                                                            add(stringValue("isInternal"))
                                                            add(booleanValue(true))
                                                        }
                                                    )
                                                    add(
                                                        buildJsonArray {
                                                            add(
                                                                buildJsonArray {
                                                                    add(stringValue("parentMembership"))
                                                                    add(stringValue("id"))
                                                                }
                                                            )
                                                            add(JsonNull)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                    put(
                                        "sortBy",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put(
                                                        "field",
                                                        buildJsonArray {
                                                            add(stringValue("user"))
                                                            add(stringValue("name"))
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                            put("nextPage", field())
                            put(
                                "nodes",
                                buildJsonObject {
                                    put("id", field())
                                    put("isInternal", field())
                                    put(
                                        "role",
                                        buildJsonObject {
                                            put("id", field())
                                            put("name", field())
                                        }
                                    )
                                    put(
                                        "user",
                                        buildJsonObject {
                                            put("id", field())
                                            put("name", field())
                                            put("emailAddress", field())
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put(
                        "jobs",
                        buildJsonObject {
                            put(
                                "$",
                                buildJsonObject {
                                    put("size", 200)
                                    put(
                                        "sortBy",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("field", "createdAt")
                                                    put("order", "desc")
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                            put("nextPage", field())
                            put(
                                "nodes",
                                buildJsonObject {
                                    put("id", field())
                                    put("number", field())
                                    put("name", field())
                                    put("description", field())
                                    put(
                                        "location",
                                        buildJsonObject {
                                            put("id", field())
                                            put("name", field())
                                            put("address", field())
                                            put(
                                                "account",
                                                buildJsonObject {
                                                    put("id", field())
                                                    put("name", field())
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun parseOrganizations(root: JsonObject): List<JobTreadOrganization> {
        val currentGrant = root["currentGrantInfo"]?.jsonObject?.get("currentGrant")?.jsonObject
            ?: return emptyList()

        val organizationsById = linkedMapOf<String, JobTreadOrganization>()

        currentGrant["organization"]
            ?.takeIf { it !is JsonNull }
            ?.jsonObject
            ?.toOrganization()
            ?.let { organizationsById[it.id] = it }

        currentGrant["user"]
            ?.jsonObject
            ?.get("memberships")
            ?.jsonObject
            ?.get("nodes")
            ?.jsonArray
            ?.mapNotNull { membership ->
                membership.jsonObject["organization"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonObject
                    ?.toOrganization()
            }
            ?.forEach { organizationsById[it.id] = it }

        return organizationsById.values.toList()
    }

    private fun parseLookupSnapshot(
        root: JsonObject,
        organization: JobTreadOrganization
    ): JobTreadLookupSnapshot {
        val organizationObject = root["organization"]?.jsonObject
            ?: throw IllegalStateException("Missing organization node.")

        val membershipsObject = organizationObject["memberships"]?.jsonObject
            ?: throw IllegalStateException("Missing memberships node.")
        val jobsObject = organizationObject["jobs"]?.jsonObject
            ?: throw IllegalStateException("Missing jobs node.")

        val assignees = membershipsObject["nodes"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toAssigneeOrNull() }
            ?.distinctBy { it.membershipId }
            ?: emptyList()

        val jobs = jobsObject["nodes"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toJobOrNull() }
            ?.distinctBy { it.id }
            ?: emptyList()

        val warnings = buildList {
            if (membershipsObject["nextPage"] != null && membershipsObject["nextPage"] !is JsonNull) {
                add("JobTread returned more assignees than the current lookup page loaded.")
            }
            if (jobsObject["nextPage"] != null && jobsObject["nextPage"] !is JsonNull) {
                add("JobTread returned more jobs than the current lookup page loaded.")
            }
        }

        return JobTreadLookupSnapshot(
            organization = organization,
            assignees = assignees,
            jobs = jobs,
            warnings = warnings
        )
    }

    private fun JsonObject.toOrganization(): JobTreadOrganization? {
        val id = string("id") ?: return null
        val name = string("name") ?: return null
        return JobTreadOrganization(id = id, name = name)
    }

    private fun JsonObject.toAssigneeOrNull(): JobTreadAssignee? {
        val membershipId = string("id") ?: return null
        val userObject = this["user"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val displayName = userObject.string("name")?.takeIf { it.isNotBlank() } ?: return null
        return JobTreadAssignee(
            membershipId = membershipId,
            userId = userObject.string("id"),
            displayName = displayName,
            emailAddress = userObject.string("emailAddress"),
            roleName = this["role"]?.takeIf { it !is JsonNull }?.jsonObject?.string("name")
        )
    }

    private fun JsonObject.toJobOrNull(): JobTreadJob? {
        val id = string("id") ?: return null
        val name = string("name")?.takeIf { it.isNotBlank() } ?: return null
        val locationObject = this["location"]?.takeIf { it !is JsonNull }?.jsonObject
        return JobTreadJob(
            id = id,
            number = string("number"),
            name = name,
            description = string("description"),
            locationName = locationObject?.string("name"),
            locationAddress = locationObject?.string("address"),
            accountName = locationObject
                ?.get("account")
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?.string("name")
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

    private fun field(): JsonObject = buildJsonObject {}

    private fun stringValue(value: String): JsonElement = kotlinx.serialization.json.JsonPrimitive(value)

    private fun booleanValue(value: Boolean): JsonElement = kotlinx.serialization.json.JsonPrimitive(value)
}
