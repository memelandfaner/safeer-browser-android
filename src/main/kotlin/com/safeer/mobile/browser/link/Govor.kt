package com.safeer.mobile.browser.link

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject

/**
 * Glasovno upravljanje za daljinec Safeer Linka: uporabnik govori v telefon (ali daljinec
 * televizorja), sistemski prepoznavalnik govora vrne besedilo, stran daljinca ga prevede v
 * ukaz (npr. "glasneje", "odpri YouTube"). Besedilo se ne shranjuje in ne posilja nikamor
 * razen v stran daljinca; prepoznavanje opravi sistem naprave.
 *
 * Ista datoteka je v brskalniku za televizor (si.safeer.tv.link).
 */
class Govor(private val dejavnost: Activity, private val odziv: (JSONObject) -> Unit) {

    companion object {
        private const val TAG = "SafeerGovor"
        const val ZAHTEVA_DOVOLJENJA = 4611

        /** Jezik strani (sl, en ...) v oznako, ki jo razume prepoznavalnik. */
        fun oznakaJezika(jezik: String): String = when (jezik.lowercase().take(2)) {
            "sl" -> "sl-SI"
            "de" -> "de-DE"
            "es" -> "es-ES"
            "fr" -> "fr-FR"
            "it" -> "it-IT"
            else -> "en-US"
        }
    }

    private var prepoznavalnik: SpeechRecognizer? = null

    fun jeNaVoljo(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(dejavnost)
    } catch (_: Throwable) { false }

    private fun imaDovoljenje(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            dejavnost.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Zacne poslusati; klice se na glavni niti. Odzivi: {stanje: poslusam|delno|koncno|napaka, besedilo, koda}. */
    fun zacni(jezik: String) {
        if (!jeNaVoljo()) {
            odziv(JSONObject().put("stanje", "napaka").put("koda", "ni_prepoznavalnika"))
            return
        }
        if (!imaDovoljenje()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dejavnost.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), ZAHTEVA_DOVOLJENJA)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Dovoljenja za mikrofon ni bilo mogoce zahtevati: ${e.message}")
            }
            odziv(JSONObject().put("stanje", "napaka").put("koda", "dovoljenje"))
            return
        }
        ustavi()
        val p = try { SpeechRecognizer.createSpeechRecognizer(dejavnost) } catch (e: Throwable) {
            odziv(JSONObject().put("stanje", "napaka").put("koda", "ni_prepoznavalnika"))
            return
        }
        prepoznavalnik = p
        p.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { odziv(JSONObject().put("stanje", "poslusam")) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                odziv(JSONObject().put("stanje", "glasnost").put("vrednost", rmsdB.toDouble()))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { odziv(JSONObject().put("stanje", "obdelujem")) }
            override fun onError(error: Int) {
                val koda = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nic_slisano"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "dovoljenje"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "omrezje"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "zaseden"
                    else -> "napaka_$error"
                }
                odziv(JSONObject().put("stanje", "napaka").put("koda", koda))
                ustavi()
            }
            override fun onResults(results: Bundle?) {
                val besedilo = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                odziv(JSONObject().put("stanje", "koncno").put("besedilo", besedilo))
                ustavi()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val besedilo = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (besedilo.isNotBlank()) odziv(JSONObject().put("stanje", "delno").put("besedilo", besedilo))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val namera = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, oznakaJezika(jezik))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, dejavnost.packageName)
        }
        try {
            p.startListening(namera)
        } catch (e: Throwable) {
            Log.w(TAG, "Poslusanja ni bilo mogoce zaceti: ${e.message}")
            odziv(JSONObject().put("stanje", "napaka").put("koda", "ni_prepoznavalnika"))
            ustavi()
        }
    }

    fun ustavi() {
        val p = prepoznavalnik ?: return
        prepoznavalnik = null
        try { p.stopListening() } catch (_: Throwable) { }
        try { p.destroy() } catch (_: Throwable) { }
    }
}
