package com.example.voicenoteapp.voice

enum class BinaryResponse {
    YES,
    NO,
    UNKNOWN
}

enum class EditChoice {
    JOB,
    TITLE,
    NOTE,
    NONE,
    CANCEL,
    UNKNOWN
}

object SpeechParsing {
    private val jobMarkers = listOf(" job ", " project ", " location ")
    private val titleMarkers = listOf("title", "heading", "regarding", "in reference to", "subject")
    private val noteMarkers = listOf("note", "details", "message")
    private val positiveWords = setOf("yes", "yep", "yeah", "sure", "ok", "okay", "affirmative")
    private val negativeWords = setOf("no", "nope", "nah", "negative")

    fun parseJobName(input: String): String {
        val cleaned = clean(input)
        val lower = " ${cleaned.lowercase()} "
        val regex = Regex("""(?:this is|for|on)?\s*(?:the\s+)?(.+?)\s+(job|project|location)\b""")
        val regexMatch = regex.find(cleaned.lowercase())
        if (regexMatch != null) {
            return clean(regexMatch.groupValues[1])
        }
        for (marker in jobMarkers) {
            val idx = lower.indexOf(marker)
            if (idx > 0) {
                return clean(cleaned.substring(0, idx).ifBlank { cleaned.substring(idx + marker.length) })
            }
        }
        return cleaned
    }

    fun parseTitle(input: String): String {
        return extractByMarkers(input, titleMarkers, listOf("note", "details", "message", "job", "project", "location"))
    }

    fun parseBody(input: String): String {
        return extractByMarkers(input, noteMarkers, emptyList())
    }

    fun parseBinaryResponse(input: String): BinaryResponse {
        val normalized = normalize(input)
        if (normalized.isBlank()) return BinaryResponse.UNKNOWN
        if (positiveWords.any { normalized.contains(it) }) return BinaryResponse.YES
        if (negativeWords.any { normalized.contains(it) }) return BinaryResponse.NO
        return BinaryResponse.UNKNOWN
    }

    fun parseEditChoice(input: String): EditChoice {
        val normalized = normalize(input)
        return when {
            normalized.contains("cancel") -> EditChoice.CANCEL
            normalized.contains("no edit") || normalized.contains("done") || normalized.contains("save") -> EditChoice.NONE
            normalized.contains("job") -> EditChoice.JOB
            normalized.contains("title") || normalized.contains("subject") || normalized.contains("heading") -> EditChoice.TITLE
            normalized.contains("note") || normalized.contains("body") || normalized.contains("details") -> EditChoice.NOTE
            else -> EditChoice.UNKNOWN
        }
    }

    fun extractTags(
        spoken: String,
        suggestions: List<String>
    ): List<String> {
        val normalized = normalize(spoken)
        if (normalized.contains("skip")) return emptyList()

        val selected = mutableListOf<String>()

        val numbers = Regex("""\b(\d+)\b""").findAll(normalized).mapNotNull {
            it.groupValues.getOrNull(1)?.toIntOrNull()
        }.toList()
        numbers.forEach { num ->
            val idx = num - 1
            if (idx in suggestions.indices) selected += suggestions[idx]
        }

        val suggestionMap = suggestions.associateBy { normalize(it) }
        suggestionMap.forEach { (normalizedTag, originalTag) ->
            if (normalized.contains(normalizedTag)) selected += originalTag
        }

        if (selected.isNotEmpty()) return selected.distinct()

        val freeform = spoken
            .replace(Regex("(?i)^(add|use|tags?)\\s*"), "")
            .split(",", " and ")
            .map { clean(it) }
            .filter { it.isNotBlank() }
        return freeform.distinct().take(5)
    }

    fun clean(input: String): String {
        return input
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$"), "")
            .trim()
    }

    private fun normalize(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractByMarkers(
        input: String,
        markers: List<String>,
        stopMarkers: List<String>
    ): String {
        val cleaned = clean(input)
        if (cleaned.isBlank()) return ""
        val lower = cleaned.lowercase()

        val markerMatch = markers
            .map { marker ->
                val idx = lower.indexOf(marker)
                marker to idx
            }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }

        if (markerMatch == null) return cleaned

        val start = markerMatch.second + markerMatch.first.length
        var segment = cleaned.substring(start).trim()
        if (stopMarkers.isNotEmpty()) {
            val segmentLower = segment.lowercase()
            val stopIndex = stopMarkers
                .map { marker -> segmentLower.indexOf(marker) }
                .filter { it >= 0 }
                .minOrNull()
            if (stopIndex != null && stopIndex > 0) {
                segment = segment.substring(0, stopIndex).trim()
            }
        }
        return clean(segment)
    }
}
