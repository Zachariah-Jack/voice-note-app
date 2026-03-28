package app.voicenote.android.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.voicenote.wizard.AssistantSpeaker
import app.voicenote.wizard.AssistantSpeakerEvent
import app.voicenote.wizard.AssistantSpeakerEventListener
import app.voicenote.wizard.AssistantSpeakerEventType
import app.voicenote.wizard.AssistantSpeechRequest
import java.util.Locale

class AndroidTextToSpeechAssistantSpeaker internal constructor(
    private val engineFactory: AndroidTextToSpeechEngineFactory,
    private val locale: Locale = Locale.getDefault(),
) : AssistantSpeaker {
    private val lock = Any()
    private var eventListener: AssistantSpeakerEventListener? = null
    private var initialized = false
    private var released = false
    private var pendingRequest: AssistantSpeechRequest? = null
    private val engine: AndroidTextToSpeechEngine

    constructor(
        context: Context,
        locale: Locale = Locale.getDefault(),
    ) : this(
        engineFactory = RealAndroidTextToSpeechEngineFactory(context.applicationContext),
        locale = locale,
    )

    init {
        engine = engineFactory.create(
            callbacks = object : AndroidTextToSpeechEngineCallbacks {
                override fun onInit(status: Int) {
                    handleInitialization(status)
                }

                override fun onStart(utteranceId: String) {
                    emitEvent(
                        AssistantSpeakerEvent(
                            utteranceId = utteranceId,
                            type = AssistantSpeakerEventType.STARTED,
                        ),
                    )
                }

                override fun onDone(utteranceId: String) {
                    emitEvent(
                        AssistantSpeakerEvent(
                            utteranceId = utteranceId,
                            type = AssistantSpeakerEventType.DONE,
                        ),
                    )
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    emitEvent(
                        AssistantSpeakerEvent(
                            utteranceId = utteranceId,
                            type = AssistantSpeakerEventType.STOPPED,
                        ),
                    )
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    emitEvent(
                        AssistantSpeakerEvent(
                            utteranceId = utteranceId,
                            type = AssistantSpeakerEventType.ERROR,
                            errorCode = errorCode,
                        ),
                    )
                }
            },
        )
    }

    override fun setEventListener(listener: AssistantSpeakerEventListener?) {
        synchronized(lock) {
            eventListener = listener
        }
    }

    override fun speak(request: AssistantSpeechRequest) {
        val shouldSpeakImmediately = synchronized(lock) {
            check(!released) { "Assistant speaker has already been released." }
            pendingRequest = request
            initialized
        }

        if (shouldSpeakImmediately) {
            speakPendingRequest()
        }
    }

    override fun stop() {
        val pendingRequestToStop = synchronized(lock) {
            if (released) {
                return
            }

            if (!initialized) {
                pendingRequest.also { pendingRequest = null }
            } else {
                null
            }
        }

        if (pendingRequestToStop != null) {
            emitEvent(
                AssistantSpeakerEvent(
                    utteranceId = pendingRequestToStop.utteranceId,
                    type = AssistantSpeakerEventType.STOPPED,
                ),
            )
            return
        }

        engine.stop()
    }

    override fun release() {
        synchronized(lock) {
            if (released) {
                return
            }
            released = true
            pendingRequest = null
            eventListener = null
        }
        engine.shutdown()
    }

    private fun handleInitialization(status: Int) {
        val pendingAfterInit = synchronized(lock) {
            if (released) {
                return
            }

            if (status != TextToSpeech.SUCCESS) {
                val failedRequest = pendingRequest
                pendingRequest = null
                failedRequest
            } else {
                val languageStatus = engine.setLanguage(locale)
                initialized = languageStatus >= TextToSpeech.LANG_AVAILABLE
                if (!initialized) {
                    val failedRequest = pendingRequest
                    pendingRequest = null
                    failedRequest
                } else {
                    pendingRequest
                }
            }
        }

        if (!initialized) {
            pendingAfterInit?.let { request ->
                emitEvent(
                    AssistantSpeakerEvent(
                        utteranceId = request.utteranceId,
                        type = AssistantSpeakerEventType.ERROR,
                    ),
                )
            }
            return
        }

        if (pendingAfterInit != null) {
            speakPendingRequest()
        }
    }

    private fun speakPendingRequest() {
        val request = synchronized(lock) {
            pendingRequest.also { pendingRequest = null }
        } ?: return

        val result = engine.speak(
            text = request.text,
            utteranceId = request.utteranceId,
        )
        if (result != TextToSpeech.SUCCESS) {
            emitEvent(
                AssistantSpeakerEvent(
                    utteranceId = request.utteranceId,
                    type = AssistantSpeakerEventType.ERROR,
                    errorCode = result,
                ),
            )
        }
    }

    private fun emitEvent(event: AssistantSpeakerEvent) {
        val listener = synchronized(lock) { eventListener }
        listener?.onEvent(event)
    }
}

internal fun interface AndroidTextToSpeechEngineFactory {
    fun create(callbacks: AndroidTextToSpeechEngineCallbacks): AndroidTextToSpeechEngine
}

internal interface AndroidTextToSpeechEngineCallbacks {
    fun onInit(status: Int)

    fun onStart(utteranceId: String)

    fun onDone(utteranceId: String)

    fun onStop(utteranceId: String, interrupted: Boolean)

    fun onError(utteranceId: String, errorCode: Int)
}

internal interface AndroidTextToSpeechEngine {
    fun setLanguage(locale: Locale): Int

    fun speak(text: String, utteranceId: String): Int

    fun stop(): Int

    fun shutdown()
}

internal class RealAndroidTextToSpeechEngineFactory(
    private val context: Context,
) : AndroidTextToSpeechEngineFactory {
    override fun create(callbacks: AndroidTextToSpeechEngineCallbacks): AndroidTextToSpeechEngine {
        lateinit var textToSpeech: TextToSpeech
        textToSpeech = TextToSpeech(context) { status ->
            callbacks.onInit(status)
        }
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    callbacks.onStart(utteranceId)
                }

                override fun onDone(utteranceId: String) {
                    callbacks.onDone(utteranceId)
                }

                override fun onError(utteranceId: String) {
                    callbacks.onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    callbacks.onError(utteranceId, errorCode)
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    callbacks.onStop(utteranceId, interrupted)
                }
            },
        )
        return RealAndroidTextToSpeechEngine(textToSpeech)
    }
}

internal class RealAndroidTextToSpeechEngine(
    private val textToSpeech: TextToSpeech,
) : AndroidTextToSpeechEngine {
    override fun setLanguage(locale: Locale): Int = textToSpeech.setLanguage(locale)

    override fun speak(text: String, utteranceId: String): Int =
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)

    override fun stop(): Int = textToSpeech.stop()

    override fun shutdown() {
        textToSpeech.shutdown()
    }
}
