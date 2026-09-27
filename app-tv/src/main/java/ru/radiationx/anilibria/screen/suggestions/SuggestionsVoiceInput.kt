package ru.radiationx.anilibria.screen.suggestions

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import timber.log.Timber

/**
 * Голосовой ввод страницы «Поиск» по кнопке микрофона.
 * Доступ к микрофону спрашивается только по нажатию; после отказа — подсказка про настройки ТВ.
 * Распознавание — [SpeechRecognizer] в приложении, а если его нет — системный экран
 * [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]. Нет ни того, ни другого — [isAvailable] = false.
 * Создавать до onCreate фрагмента (регистрирует ActivityResult).
 */
class SuggestionsVoiceInput(
    private val fragment: Fragment,
    private val storage: () -> SearchQueriesStorage,
    private val onListening: (Boolean) -> Unit,
    private val onResult: (String) -> Unit,
) {

    private companion object {
        const val DENIED_HINT = "Разрешите микрофон в настройках ТВ"
    }

    private val permissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        storage().micDenied = !granted
        if (granted) startRecognizer()
    }

    private val activityLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            onResult(text)
        }
    }

    private var recognizer: SpeechRecognizer? = null

    val isListening: Boolean
        get() = recognizer != null

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) || hasRecognizeActivity(context)

    fun onMicClick() {
        val context = fragment.context ?: return
        if (isListening) {
            stop()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            startRecognizeActivity()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        when {
            granted -> startRecognizer()
            storage().micDenied -> Toast.makeText(context, DENIED_HINT, Toast.LENGTH_SHORT).show()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stop() {
        val current = recognizer ?: return
        recognizer = null
        // destroy может бросить IllegalArgumentException: Service not registered.
        runCatching { current.cancel() }
        runCatching { current.destroy() }
        onListening(false)
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    private fun hasRecognizeActivity(context: Context): Boolean =
        recognizeIntent().resolveActivity(context.packageManager) != null

    private fun startRecognizeActivity() {
        try {
            activityLauncher.launch(recognizeIntent())
        } catch (e: ActivityNotFoundException) {
            Timber.e(e)
        }
    }

    private fun startRecognizer() {
        val context = fragment.context ?: return
        if (fragment.view == null) return
        stop()
        val newRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Timber.e(e)
            return
        }
        recognizer = newRecognizer
        newRecognizer.setRecognitionListener(Listener())
        onListening(true)
        try {
            newRecognizer.startListening(recognizeIntent())
        } catch (e: Exception) {
            Timber.e(e)
            stop()
        }
    }

    private inner class Listener : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            stop()
            if (!text.isNullOrBlank()) onResult(text)
        }

        override fun onError(error: Int) {
            stop()
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                fragment.context?.also { Toast.makeText(it, DENIED_HINT, Toast.LENGTH_SHORT).show() }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
