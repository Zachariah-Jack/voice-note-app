package com.example.voicenoteapp.settings

enum class AssistantConfigField(val label: String) {
    OPENAI_API_KEY("OpenAI API key"),
    OPENAI_MODEL("OpenAI model"),
    JOBTREAD_BASE_URL("JobTread Pave URL"),
    JOBTREAD_API_KEY("JobTread grant key")
}

data class AssistantSettings(
    val openAiApiKey: String = "",
    val openAiModel: String = "",
    val jobTreadBaseUrl: String = "",
    val jobTreadApiKey: String = ""
) {
    val missingOpenAiFields: List<AssistantConfigField>
        get() = buildList {
            if (openAiApiKey.isBlank()) add(AssistantConfigField.OPENAI_API_KEY)
            if (openAiModel.isBlank()) add(AssistantConfigField.OPENAI_MODEL)
        }

    val missingJobTreadFields: List<AssistantConfigField>
        get() = buildList {
            if (jobTreadBaseUrl.isBlank()) add(AssistantConfigField.JOBTREAD_BASE_URL)
            if (jobTreadApiKey.isBlank()) add(AssistantConfigField.JOBTREAD_API_KEY)
        }

    val missingFields: List<AssistantConfigField>
        get() = missingOpenAiFields + missingJobTreadFields

    val hasOpenAiConfig: Boolean
        get() = missingOpenAiFields.isEmpty()

    val hasJobTreadConfig: Boolean
        get() = missingJobTreadFields.isEmpty()
}
