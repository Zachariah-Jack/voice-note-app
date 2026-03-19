package com.example.voicenoteapp.voice

object TagSuggestionEngine {
    private val dictionary = listOf(
        "dumpster",
        "jobsite cleanup",
        "scheduling",
        "damage",
        "safety",
        "delivery",
        "material",
        "inspection",
        "permit",
        "equipment",
        "crew",
        "delay",
        "weather",
        "follow up"
    )

    private val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "have", "will",
        "about", "there", "were", "been", "when", "where", "your", "their", "them",
        "just", "need", "note", "subject", "title", "job", "project", "location"
    )

    fun buildSuggestions(title: String, body: String): List<String> {
        val joined = "$title $body".lowercase()
        val selected = mutableListOf<String>()

        dictionary.forEach { tag ->
            if (joined.contains(tag.lowercase())) selected += tag
        }

        val extracted = joined
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 4 && it !in stopWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)

        selected += extracted
        return selected.distinct().take(6)
    }
}
