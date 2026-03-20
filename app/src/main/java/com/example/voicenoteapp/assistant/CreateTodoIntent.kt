package com.example.voicenoteapp.assistant

enum class AssistantIntentType {
    CREATE_TODO
}

enum class TodoPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

enum class MissingCreateTodoField {
    TITLE
}

data class CreateTodoData(
    val title: String?,
    val description: String?,
    val jobReferenceText: String?,
    val assigneeReferenceText: String?,
    val dueDateIso: String?,
    val dueTimeLocal: String?,
    val priority: TodoPriority?,
    val tags: List<String>
)

data class CreateTodoIntent(
    val schemaVersion: String = "1.0",
    val intent: AssistantIntentType = AssistantIntentType.CREATE_TODO,
    val rawTranscript: String,
    val todo: CreateTodoData,
    val missingFields: List<MissingCreateTodoField>,
    val ambiguities: List<String>
)
