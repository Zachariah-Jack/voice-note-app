package app.voicenote.wizard

enum class SpeechRecognizerAvailability {
    AVAILABLE,
    PERMISSION_NEEDED,
    UNAVAILABLE,
}

data class SpeechRecognitionEvent(
    val type: SpeechRecognitionEventType,
    val transcript: String? = null,
    val errorCode: Int? = null,
)

enum class SpeechRecognitionEventType {
    LISTENING_STARTED,
    PARTIAL_TRANSCRIPT,
    FINAL_TRANSCRIPT,
    NO_MATCH,
    TIMEOUT,
    BUSY,
    PERMISSION_REQUIRED,
    ERROR,
}

fun interface SpeechRecognitionEventListener {
    fun onEvent(event: SpeechRecognitionEvent)
}

interface SpeechRecognizerGateway {
    fun setEventListener(listener: SpeechRecognitionEventListener?)

    fun checkAvailability(): SpeechRecognizerAvailability

    fun startListening()

    fun stopListening()

    fun cancel()

    fun release()
}

object NoOpSpeechRecognizerGateway : SpeechRecognizerGateway {
    override fun setEventListener(listener: SpeechRecognitionEventListener?) = Unit

    override fun checkAvailability(): SpeechRecognizerAvailability = SpeechRecognizerAvailability.UNAVAILABLE

    override fun startListening() = Unit

    override fun stopListening() = Unit

    override fun cancel() = Unit

    override fun release() = Unit
}
