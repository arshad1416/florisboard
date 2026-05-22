package dev.patrickgold.florisboard.gemma

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.annotation.MainThread

/**
 * Modern real-time speech-to-text wrapper for Android.
 * Optimized for the system's on-device speech engine.
 */
class StreamingTranscriber(private val context: Context) {
    interface Callback {
        fun onReadyForSpeech()
        fun onPartial(text: String)
        fun onSegment(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onEndOfSpeech()
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    private var pendingPartial: String = ""
    private val accumulated = java.lang.StringBuilder()
    
    @Volatile
    private var isListening = false
    @Volatile
    private var isFallback = false
    private var currentLanguageTag: String = "en-US"

    @MainThread
    fun start(callback: Callback, languageTag: String = "en-US") {
        this.callback = callback
        this.currentLanguageTag = languageTag
        this.pendingPartial = ""
        this.accumulated.setLength(0)
        this.isListening = true
        this.isFallback = false
        startListeningInternal()
    }

    @MainThread
    private fun startListeningInternal() {
        destroyRecognizer()

        if (callback == null) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            callback?.onError("Microphone permission not granted")
            return
        }

        val appContext = context.applicationContext
        
        // Use createOnDeviceSpeechRecognizer for privacy first, fallback to standard if unavailable or fails
        val useOnDevice = !isFallback && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        val rec = if (useOnDevice) {
            Log.d("StreamingTranscriber", "Attempting on-device SpeechRecognizer")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            Log.d("StreamingTranscriber", "Attempting standard SpeechRecognizer (isFallback=$isFallback)")
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        recognizer = rec
        
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                callback?.onReadyForSpeech()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                callback?.onEndOfSpeech()
            }
            
            override fun onError(error: Int) {
                Log.w("StreamingTranscriber", "SpeechRecognizer error: $error (isListening=$isListening)")
                if (isListening && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                    Log.i("StreamingTranscriber", "Recoverable error $error. Restarting listening.")
                    startListeningInternal()
                } else if (!isFallback && isListening) {
                    Log.i("StreamingTranscriber", "Error $error is recoverable. Falling back to standard SpeechRecognizer.")
                    isFallback = true
                    startListeningInternal()
                } else {
                    if (isListening) {
                        callback?.onError(getErrorText(error))
                        stop()
                    } else {
                        // Manual finish was requested, deliver what we have
                        callback?.onFinal(accumulated.toString())
                        stop()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                val segmentText = text.ifBlank { pendingPartial }
                if (segmentText.isNotBlank()) {
                    if (accumulated.isNotEmpty() && !accumulated.endsWith(" ")) {
                        accumulated.append(" ")
                    }
                    accumulated.append(segmentText)
                    callback?.onSegment(segmentText)
                }
                pendingPartial = ""
                
                if (isListening) {
                    startListeningInternal()
                } else {
                    callback?.onFinal(accumulated.toString())
                    stop()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    pendingPartial = text
                    val currentPartial = if (accumulated.isNotEmpty()) {
                        if (accumulated.endsWith(" ")) "$accumulated$text" else "$accumulated $text"
                    } else {
                        text
                    }
                    callback?.onPartial(currentPartial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (!isFallback) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            
            // Prevent auto-stop on brief pauses — user controls start/stop via mic toggle.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
        rec.startListening(intent)
    }

    @MainThread
    fun finishListening() {
        if (!isListening) return
        isListening = false
        val rec = recognizer
        if (rec == null) {
            callback?.onFinal(accumulated.toString())
            stop()
        } else {
            runCatching { rec.stopListening() }
            // 800ms safety timeout to force complete if SpeechRecognizer service is unresponsive
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (callback != null) {
                    Log.w("StreamingTranscriber", "Safety timeout triggered in finishListening")
                    callback?.onFinal(accumulated.toString())
                    stop()
                }
            }, 800)
        }
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    @MainThread
    fun stop() {
        isListening = false
        destroyRecognizer()
        callback = null
    }

    private fun getErrorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions missing"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Engine busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Error: $code"
    }
}
