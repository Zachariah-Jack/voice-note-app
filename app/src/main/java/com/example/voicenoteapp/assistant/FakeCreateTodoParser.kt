package com.example.voicenoteapp.assistant

import com.example.voicenoteapp.settings.AssistantSettings
import com.example.voicenoteapp.voice.SpeechParsing
import java.time.LocalDate

class FakeCreateTodoParser : CreateTodoParser {
    private val descriptor = CreateTodoParserDescriptor(
        mode = CreateTodoParserMode.FALLBACK,
        parserLabel = "Local deterministic fallback parser"
    )

    override fun describe(settings: AssistantSettings): CreateTodoParserDescriptor = descriptor

    override suspend fun parse(
        transcript: String,
        settings: AssistantSettings
    ): CreateTodoParseResult {
        return CreateTodoParseResult.Success(
            intent = parseLocally(transcript),
            descriptor = descriptor
        )
    }

    private fun parseLocally(transcript: String, today: LocalDate = LocalDate.now()): CreateTodoIntent {
        val cleaned = SpeechParsing.clean(transcript)
        val normalized = cleaned.lowercase()
        val title = cleaned.take(80).trim().ifBlank { null }
        val description = cleaned.ifBlank { null }
        val dueDateIso = when {
            Regex("\\btomorrow\\b").containsMatchIn(normalized) -> today.plusDays(1).toString()
            Regex("\\btoday\\b").containsMatchIn(normalized) -> today.toString()
            else -> null
        }
        val priority = when {
            normalized.contains("urgent") || normalized.contains("asap") || normalized.contains("right away") -> TodoPriority.URGENT
            normalized.contains("high priority") || normalized.contains("important") -> TodoPriority.HIGH
            normalized.contains("low priority") -> TodoPriority.LOW
            normalized.contains("normal priority") -> TodoPriority.NORMAL
            else -> null
        }

        return CreateTodoIntent(
            rawTranscript = cleaned,
            todo = CreateTodoData(
                title = title,
                description = description,
                jobReferenceText = extractJobReference(cleaned),
                assigneeReferenceText = extractAssigneeReference(cleaned),
                dueDateIso = dueDateIso,
                dueTimeLocal = null,
                priority = priority,
                tags = emptyList()
            ),
            missingFields = if (title == null) listOf(MissingCreateTodoField.TITLE) else emptyList(),
            ambiguities = buildList {
                if (cleaned.isBlank()) add("No usable transcript was captured.")
                if (dueDateIso == null && Regex("\\b(next|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").containsMatchIn(normalized)) {
                    add("Date sounded relative or calendar-based, but this fake parser did not resolve it.")
                }
            }
        )
    }

    private fun extractJobReference(input: String): String? {
        val match = Regex(
            pattern = """(?:for|on)\s+(.+?)(?=\s+(?:assign(?:ed)?\s+to|today|tomorrow|asap|urgent|high priority|low priority)\b|$)""",
            option = RegexOption.IGNORE_CASE
        ).find(input)
        return match?.groupValues?.getOrNull(1)?.let(SpeechParsing::clean)?.ifBlank { null }
    }

    private fun extractAssigneeReference(input: String): String? {
        val match = Regex(
            pattern = """assign(?:ed)?\s+to\s+(.+?)(?=\s+(?:today|tomorrow|asap|urgent|high priority|low priority)\b|$)""",
            option = RegexOption.IGNORE_CASE
        ).find(input)
        return match?.groupValues?.getOrNull(1)?.let(SpeechParsing::clean)?.ifBlank { null }
    }
}
