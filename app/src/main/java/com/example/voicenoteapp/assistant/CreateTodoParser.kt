package com.example.voicenoteapp.assistant

import com.example.voicenoteapp.settings.AssistantConfigField
import com.example.voicenoteapp.settings.AssistantSettings

enum class CreateTodoParserMode(val label: String) {
    AI("AI parser"),
    FALLBACK("Fallback parser")
}

data class CreateTodoParserDescriptor(
    val mode: CreateTodoParserMode,
    val parserLabel: String
)

sealed interface CreateTodoParseResult {
    data class Success(
        val intent: CreateTodoIntent,
        val descriptor: CreateTodoParserDescriptor
    ) : CreateTodoParseResult

    data class MissingConfiguration(
        val fields: List<AssistantConfigField>,
        val message: String,
        val descriptor: CreateTodoParserDescriptor
    ) : CreateTodoParseResult

    data class Failure(
        val message: String,
        val descriptor: CreateTodoParserDescriptor
    ) : CreateTodoParseResult
}

interface CreateTodoParser {
    fun describe(settings: AssistantSettings): CreateTodoParserDescriptor

    suspend fun parse(
        transcript: String,
        settings: AssistantSettings
    ): CreateTodoParseResult
}
