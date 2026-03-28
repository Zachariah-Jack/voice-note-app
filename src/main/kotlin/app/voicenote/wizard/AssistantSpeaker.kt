package app.voicenote.wizard

data class AssistantSpeechRequest(
    val utteranceId: String,
    val text: String,
)

data class AssistantSpeakerEvent(
    val utteranceId: String,
    val type: AssistantSpeakerEventType,
    val errorCode: Int? = null,
)

enum class AssistantSpeakerEventType {
    STARTED,
    DONE,
    STOPPED,
    ERROR,
}

fun interface AssistantSpeakerEventListener {
    fun onEvent(event: AssistantSpeakerEvent)
}

interface AssistantSpeaker {
    fun setEventListener(listener: AssistantSpeakerEventListener?)

    fun speak(request: AssistantSpeechRequest)

    fun stop()

    fun release()
}

object NoOpAssistantSpeaker : AssistantSpeaker {
    override fun setEventListener(listener: AssistantSpeakerEventListener?) = Unit

    override fun speak(request: AssistantSpeechRequest) = Unit

    override fun stop() = Unit

    override fun release() = Unit
}
