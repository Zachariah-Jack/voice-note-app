package com.example.voicenoteapp.assistant

import com.example.voicenoteapp.settings.AssistantSettings

class AutomaticCreateTodoParser(
    private val openAiParser: CreateTodoParser,
    private val fallbackParser: CreateTodoParser
) : CreateTodoParser {
    override fun describe(settings: AssistantSettings): CreateTodoParserDescriptor {
        return activeParser(settings).describe(settings)
    }

    override suspend fun parse(
        transcript: String,
        settings: AssistantSettings
    ): CreateTodoParseResult {
        return activeParser(settings).parse(transcript, settings)
    }

    private fun activeParser(settings: AssistantSettings): CreateTodoParser {
        return if (settings.hasOpenAiConfig) {
            openAiParser
        } else {
            fallbackParser
        }
    }
}
