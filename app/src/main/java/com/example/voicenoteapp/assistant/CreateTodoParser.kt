package com.example.voicenoteapp.assistant

import com.example.voicenoteapp.settings.AssistantConfigField
import com.example.voicenoteapp.settings.AssistantSettings

sealed interface CreateTodoParseResult {
    data class Success(
        val intent: CreateTodoIntent,
        val parserLabel: String
    ) : CreateTodoParseResult

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String,
        val parserLabel: String
    ) : CreateTodoParseResult

    data class Failure(
        val message: String,
        val parserLabel: String
    ) : CreateTodoParseResult
}

interface CreateTodoParser {
    val parserLabel: String

    suspend fun parse(
        transcript: String,
        settings: AssistantSettings
    ): CreateTodoParseResult
}
