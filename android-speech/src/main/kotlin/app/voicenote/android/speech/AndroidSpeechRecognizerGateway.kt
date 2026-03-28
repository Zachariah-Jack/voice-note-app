package app.voicenote.android.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.voicenote.wizard.SpeechRecognitionEvent
import app.voicenote.wizard.SpeechRecognitionEventListener
import app.voicenote.wizard.SpeechRecognitionEventType
import app.voicenote.wizard.SpeechRecognizerAvailability
import app.voicenote.wizard.SpeechRecognizerGateway
import java.util.Locale

class AndroidSpeechRecognizerGateway internal constructor(
    private val environment: AndroidSpeechRecognizerEnvironment,
    private val mainThreadChecker: MainThreadChecker = AndroidMainThreadChecker,
) : SpeechRecognizerGateway {
    private var eventListener: SpeechRecognitionEventListener? = null
    private var released = false
    private var recognizer: AndroidSpeechRecognizer? = null
    private val preferOnDevice = environment.isOnDeviceRecognitionAvailable()

    constructor(
        context: Context,
        locale: Locale = Locale.getDefault(),
    ) : this(
        environment = RealAndroidSpeechRecognizerEnvironment(
            context = context.applicationContext,
            locale = locale,
        ),
    )

    override fun setEventListener(listener: SpeechRecognitionEventListener?) {
        mainThreadChecker.checkMainThread()
        eventListener = listener
    }

    override fun checkAvailability(): SpeechRecognizerAvailability {
        mainThreadChecker.checkMainThread()
        if (!environment.isRecognitionAvailable()) {
            return SpeechRecognizerAvailability.UNAVAILABLE
        }
        if (!environment.hasRecordAudioPermission()) {
            return SpeechRecognizerAvailability.PERMISSION_NEEDED
        }
        return SpeechRecognizerAvailability.AVAILABLE
    }

    override fun startListening() {
        mainThreadChecker.checkMainThread()
        check(!released) { "Speech recognizer gateway has already been released." }

        when (checkAvailability()) {
            SpeechRecognizerAvailability.AVAILABLE -> {
                ensureRecognizer().startListening(preferOnDevice)
            }

            SpeechRecognizerAvailability.PERMISSION_NEEDED -> emitEvent(
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.PERMISSION_REQUIRED),
            )

            SpeechRecognizerAvailability.UNAVAILABLE -> emitEvent(
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.ERROR),
            )
        }
    }

    override fun stopListening() {
        mainThreadChecker.checkMainThread()
        if (released) {
            return
        }
        recognizer?.stopListening()
    }

    override fun cancel() {
        mainThreadChecker.checkMainThread()
        if (released) {
            return
        }
        recognizer?.cancel()
    }

    override fun release() {
        mainThreadChecker.checkMainThread()
        if (released) {
            return
        }
        released = true
        recognizer?.destroy()
        recognizer = null
        eventListener = null
    }

    private fun ensureRecognizer(): AndroidSpeechRecognizer {
        val existingRecognizer = recognizer
        if (existingRecognizer != null) {
            return existingRecognizer
        }

        val newRecognizer = environment.createRecognizer(
            callbacks = object : AndroidSpeechRecognizerCallbacks {
                override fun onListeningStarted() {
                    emitEvent(SpeechRecognitionEvent(type = SpeechRecognitionEventType.LISTENING_STARTED))
                }

                override fun onPartialTranscript(transcript: String) {
                    emitEvent(
                        SpeechRecognitionEvent(
                            type = SpeechRecognitionEventType.PARTIAL_TRANSCRIPT,
                            transcript = transcript,
                        ),
                    )
                }

                override fun onFinalTranscript(transcript: String) {
                    emitEvent(
                        SpeechRecognitionEvent(
                            type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                            transcript = transcript,
                        ),
                    )
                }

                override fun onError(errorCode: Int) {
                    emitEvent(
                        SpeechRecognitionEvent(
                            type = errorCode.toGatewayEventType(),
                            errorCode = errorCode.takeUnless {
                                errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            },
                        ),
                    )
                }
            },
            preferOnDevice = preferOnDevice,
        )
        recognizer = newRecognizer
        return newRecognizer
    }

    private fun emitEvent(event: SpeechRecognitionEvent) {
        eventListener?.onEvent(event)
    }
}

internal interface MainThreadChecker {
    fun checkMainThread()
}

internal object AndroidMainThreadChecker : MainThreadChecker {
    override fun checkMainThread() {
        check(Looper.getMainLooper() == Looper.myLooper()) {
            "SpeechRecognizerGateway methods must be called from the main thread."
        }
    }
}

internal interface AndroidSpeechRecognizerEnvironment {
    fun hasRecordAudioPermission(): Boolean

    fun isRecognitionAvailable(): Boolean

    fun isOnDeviceRecognitionAvailable(): Boolean

    fun createRecognizer(
        callbacks: AndroidSpeechRecognizerCallbacks,
        preferOnDevice: Boolean,
    ): AndroidSpeechRecognizer
}

internal interface AndroidSpeechRecognizerCallbacks {
    fun onListeningStarted()

    fun onPartialTranscript(transcript: String)

    fun onFinalTranscript(transcript: String)

    fun onError(errorCode: Int)
}

internal interface AndroidSpeechRecognizer {
    fun startListening(preferOnDevice: Boolean)

    fun stopListening()

    fun cancel()

    fun destroy()
}

internal class RealAndroidSpeechRecognizerEnvironment(
    private val context: Context,
    private val locale: Locale,
) : AndroidSpeechRecognizerEnvironment {
    override fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun isOnDeviceRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun createRecognizer(
        callbacks: AndroidSpeechRecognizerCallbacks,
        preferOnDevice: Boolean,
    ): AndroidSpeechRecognizer {
        val speechRecognizer = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        speechRecognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    callbacks.onListeningStarted()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    callbacks.onError(error)
                }

                override fun onResults(results: Bundle?) {
                    results.firstTranscript()?.let(callbacks::onFinalTranscript)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.firstTranscript()?.let(callbacks::onPartialTranscript)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        return RealAndroidSpeechRecognizer(
            speechRecognizer = speechRecognizer,
            context = context,
            locale = locale,
        )
    }
}

internal class RealAndroidSpeechRecognizer(
    private val speechRecognizer: SpeechRecognizer,
    private val context: Context,
    private val locale: Locale,
) : AndroidSpeechRecognizer {
    override fun startListening(preferOnDevice: Boolean) {
        speechRecognizer.startListening(
            android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            },
        )
    }

    override fun stopListening() {
        speechRecognizer.stopListening()
    }

    override fun cancel() {
        speechRecognizer.cancel()
    }

    override fun destroy() {
        speechRecognizer.destroy()
    }
}

private fun Bundle?.firstTranscript(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }

private fun Int.toGatewayEventType(): SpeechRecognitionEventType = when (this) {
    SpeechRecognizer.ERROR_NO_MATCH -> SpeechRecognitionEventType.NO_MATCH
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechRecognitionEventType.TIMEOUT
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechRecognitionEventType.BUSY
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionEventType.PERMISSION_REQUIRED
    else -> SpeechRecognitionEventType.ERROR
}
