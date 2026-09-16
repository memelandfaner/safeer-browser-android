package com.safeer.mobile.browser

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Vratar pred pojavnimi okni. Pravilo je preprosto: **nic se ne odpre, cesar uporabnik ni
 * zahteval** - ne okno, ne zavihek, in tudi nobena vrstica z vprasanjem.
 *
 * Dosedanja zascita je preprecila samo okna brez uporabnikovega dotika (`isUserGesture`).
 * Strani s filmi pa uporabijo prav prvi klik na predvajalnik: isti klik sprozi `window.open`
 * z oglasom, zato je za Android to "uporabnik je kliknil" in okno je slo skozi. Brskalnik je
 * zavihek ustvaril takoj, se preden je vedel, kam vodi.
 *
 * Zdaj: okno dobi zacasen, skrit pogled, ki ni zavihek in ga uporabnik nikoli ne vidi.
 * Takoj ko izvemo naslov, pogled unicimo. Stevec zavihkov se ne premakne, predvajalnik pa
 * klik dobi normalno in film stece. Uporabnik ne vidi nicesar - ne okna in ne obvestila o
 * njem; obvestilo bi bilo le se ena stvar, ki skoci predenj. Kdor novo okno res potrebuje,
 * preprecevanje izklopi v meniju.
 *
 * Izjeme iz seznamov (pravila `@@`) tu ne veljajo: namenjene so temu, da se ne pokvarijo
 * trgovine in banke, ne pa temu, da spustijo pojavno okno.
 */
object PopUpVratar {

    private const val TAG = "SafeerPopUp"
    private const val CAKANJE_MS = 6_000L

    /**
     * Vrne true, kadar je okno prevzel vratar (stran dobi veljaven odgovor, uporabnik pa ne
     * vidi nicesar). Klicano iz onCreateWindow na glavni niti.
     */
    fun prestrezi(dejavnost: Activity, resultMsg: android.os.Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val glavna = Handler(Looper.getMainLooper())

        val skriti = WebView(dejavnost)
        skriti.settings.javaScriptEnabled = false
        skriti.settings.loadsImagesAutomatically = false
        skriti.settings.blockNetworkLoads = true      // naslov izvemo iz zahteve, nalagati ni treba nicesar

        var odloceno = false

        fun unici() {
            try {
                skriti.stopLoading()
                skriti.webViewClient = WebViewClient()
                skriti.destroy()
            } catch (e: Throwable) {
                Log.w(TAG, "skritega pogleda ni bilo mogoce pospraviti: ${e.message}")
            }
        }

        fun odloci(naslov: String?) {
            if (odloceno) return
            if (naslov.isNullOrBlank() || !naslov.startsWith("http", ignoreCase = true)) return
            odloceno = true
            glavna.post {
                unici()
                Log.i(TAG, "Pojavno okno preprečeno: $naslov")
            }
        }

        skriti.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                odloci(request?.url?.toString())
                return true
            }

            @Deprecated("Za starejse sisteme", ReplaceWith(""))
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                odloci(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                odloci(url)
            }
        }

        transport.webView = skriti
        resultMsg.sendToTarget()

        // Ce okno nikamor ne odnavigira (prazna lupina za kasneje), ga po tiho pospravimo.
        glavna.postDelayed({
            if (!odloceno) {
                odloceno = true
                unici()
            }
        }, CAKANJE_MS)
        return true
    }
}
