package com.safeer.mobile.browser

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * PDF v brskalniku: Android WebView PDF-ja ne zna prikazati (ponudi le prenos), zato ga odpremo
 * z vgrajenim PDF.js (assets/pdfjs, isti pregledovalnik kot v Firefoxu) - z branjem, iskanjem in
 * urejanjem (oznacevanje, besedilo, risanje, slika). Vse ostane v aplikaciji, nic ne gre v oblak.
 *
 * Kako: pregledovalnik in dokument strezemo z izmisljenega gostitelja https://pdf.safeer.internal/
 * prek shouldInterceptRequest - pregledovalnik iz assets, dokument pa pretocimo z izvornega naslova
 * (s piskotki in User-Agentom seje, da delujejo tudi PDF-ji za prijavo). Isti izvor => PDF.js sme
 * brati dokument brez CORS. Shranjevanje (tudi urejenega) gre skozi most SafeerPdf v mapo prenosov,
 * ker WebView blob: naslova ne zna prenesti. Zeton v naslovu pregledovalnika prepreci, da bi tuja
 * stran prek mostu pisala datoteke.
 */
object PdfPregledovalnik {

    private const val TAG = "SafeerPdf"
    const val GOSTITELJ = "pdf.safeer.internal"
    private const val OSNOVA = "https://$GOSTITELJ"
    private const val NAJVEC_DOKUMENTOV = 8

    private class Dokument(val id: String, val url: String, val ime: String, val zeton: String, val userAgent: String?)

    private val dokumenti = LinkedHashMap<String, Dokument>()
    private val nakljucje = SecureRandom()

    /** Ali odziv (klic DownloadListenerja) nosi PDF, ki ga prikazemo namesto prenesemo. */
    fun jePdf(url: String, mimeType: String?, contentDisposition: String?): Boolean {
        val vrsta = (mimeType ?: "").lowercase().substringBefore(';').trim()
        if (vrsta == "application/pdf" || vrsta == "application/x-pdf") return true
        if (vrsta.isNotEmpty() && vrsta != "application/octet-stream" && vrsta != "binary/octet-stream") return false
        val ime = try { URLUtil.guessFileName(url, contentDisposition, mimeType) } catch (_: Throwable) { "" }
        return ime.lowercase().endsWith(".pdf") || Uri.parse(url).path?.lowercase()?.endsWith(".pdf") == true
    }

    /** Odpre PDF v pregledovalniku v istem pogledu (zavihku). */
    fun odpri(context: Context, webView: WebView, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val ime = varnoIme(try { URLUtil.guessFileName(url, contentDisposition, mimeType) } catch (_: Throwable) { "dokument.pdf" })
        val id = nakljucni(12)
        val zeton = nakljucni(24)
        synchronized(dokumenti) {
            while (dokumenti.size >= NAJVEC_DOKUMENTOV) dokumenti.remove(dokumenti.keys.first())
            dokumenti[id] = Dokument(id, url, ime, zeton, userAgent)
        }
        val jezik = jezik(context)
        val naslov = "$OSNOVA/pdfjs/web/viewer.html?file=" + Uri.encode("/doc/$id/$ime") +
            "&lang=" + Uri.encode(jezik) + "&z=" + zeton
        webView.post { webView.loadUrl(naslov) }
    }

    /** Lokalni dokument (iz upravitelja datotek, prenosov, e-poste): content:// ali file://. */
    fun jeLokalni(url: String?): Boolean {
        if (url == null) return false
        if (url.startsWith("content://")) return true
        // Samo PDF z diska; nase strani iz assets (domaca stran, Link) so tudi file:// in NISO dokumenti.
        if (!url.startsWith("file://") || url.startsWith("file:///android_asset/")) return false
        return url.substringBefore('?').substringBefore('#').lowercase().endsWith(".pdf")
    }

    /**
     * Odpre PDF z naprave (namera VIEW z application/pdf). Vsebino beremo prek ContentResolverja
     * z dovoljenjem, ki ga je dala namera; WebView sam do content:// ne sme (allowContentAccess=false).
     */
    fun odpriLokalno(context: Context, webView: WebView, uri: Uri, mimeType: String?) {
        var ime = ""
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) ime = c.getString(0) ?: ""
                }
            }
        } catch (_: Throwable) { }
        if (ime.isBlank()) ime = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        ime = varnoIme(ime.ifBlank { "dokument.pdf" })
        val id = nakljucni(12)
        val zeton = nakljucni(24)
        synchronized(dokumenti) {
            while (dokumenti.size >= NAJVEC_DOKUMENTOV) dokumenti.remove(dokumenti.keys.first())
            dokumenti[id] = Dokument(id, uri.toString(), ime, zeton, null)
        }
        val jezik = jezik(context)
        val naslov = "$OSNOVA/pdfjs/web/viewer.html?file=" + Uri.encode("/doc/$id/$ime") +
            "&lang=" + Uri.encode(jezik) + "&z=" + zeton
        webView.post { webView.loadUrl(naslov) }
    }

    /** Ali je naslov nas pregledovalnik. */
    fun jePregledovalnik(url: String?): Boolean = url != null && url.startsWith("$OSNOVA/")

    /** Naslov, ki ga vidi uporabnik (in zgodovina, seja): izvorni naslov PDF-ja, ne notranji. */
    fun javniNaslov(url: String): String {
        if (!jePregledovalnik(url)) return url
        return try {
            val datoteka = Uri.parse(url).getQueryParameter("file") ?: return url
            val id = datoteka.removePrefix("/doc/").substringBefore('/')
            synchronized(dokumenti) { dokumenti[id]?.url } ?: url
        } catch (_: Throwable) { url }
    }

    /** Odgovor za zahteve na nasega gostitelja (klice se z niti shouldInterceptRequest). */
    fun odgovor(context: Context, url: String): WebResourceResponse? {
        val u = Uri.parse(url)
        if (u.host != GOSTITELJ) return null
        val pot = u.path ?: "/"
        return when {
            pot.startsWith("/pdfjs/") -> izAssets(context, pot.removePrefix("/pdfjs/"))
            pot.startsWith("/doc/") -> dokument(context, pot.removePrefix("/doc/").substringBefore('/'))
            else -> napaka(404, "Ni najdeno")
        }
    }

    private fun izAssets(context: Context, relativna: String): WebResourceResponse {
        val cista = relativna.substringBefore('?').substringBefore('#')
        if (cista.contains("..")) return napaka(403, "Prepovedano")
        return try {
            val tok = context.assets.open("pdfjs/$cista")
            WebResourceResponse(vrsta(cista), if (vrsta(cista).startsWith("text/") || cista.endsWith(".mjs") || cista.endsWith(".json")) "utf-8" else null, 200, "OK", glave(), tok)
        } catch (_: Throwable) {
            napaka(404, "Ni najdeno")
        }
    }

    private fun dokument(context: Context, id: String): WebResourceResponse {
        val d = synchronized(dokumenti) { dokumenti[id] } ?: return napaka(404, "Dokumenta ni vec")
        if (jeLokalni(d.url)) {
            return try {
                val tok = context.contentResolver.openInputStream(Uri.parse(d.url)) ?: return napaka(404, "Datoteke ni")
                val g = glave().toMutableMap()
                g["Accept-Ranges"] = "none"
                WebResourceResponse("application/pdf", null, 200, "OK", g, tok)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "Lokalnega PDF-ja ni bilo mogoce odpreti: ${e.message}")
                napaka(403, "Dostop do datoteke ni dovoljen")
            }
        }
        return try {
            val povezava = URL(d.url).openConnection() as HttpURLConnection
            povezava.instanceFollowRedirects = true
            povezava.connectTimeout = 15000
            povezava.readTimeout = 60000
            d.userAgent?.let { povezava.setRequestProperty("User-Agent", it) }
            povezava.setRequestProperty("Accept", "application/pdf,*/*")
            try {
                CookieManager.getInstance().getCookie(d.url)?.takeIf { it.isNotBlank() }?.let { povezava.setRequestProperty("Cookie", it) }
            } catch (_: Throwable) { }
            val koda = povezava.responseCode
            if (koda !in 200..299) {
                povezava.disconnect()
                return napaka(koda, "Streznik je odgovoril $koda")
            }
            val dolzina = povezava.contentLengthLong
            val g = glave().toMutableMap()
            if (dolzina > 0) g["Content-Length"] = dolzina.toString()
            g["Accept-Ranges"] = "none"
            WebResourceResponse("application/pdf", null, 200, "OK", g, povezava.inputStream)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "PDF-ja ni bilo mogoce pretociti: ${e.message}")
            napaka(502, "Dokumenta ni bilo mogoce prenesti")
        }
    }

    private fun glave(): Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer"
    )

    private fun napaka(koda: Int, razlog: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", koda, razlog, glave(), ByteArrayInputStream(razlog.toByteArray()))

    private fun vrsta(pot: String): String = when (pot.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "mjs", "js" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "ftl" -> "text/plain"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "wasm" -> "application/wasm"
        "pfb" -> "application/x-font-type1"
        "ttf" -> "font/ttf"
        "bcmap" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private fun nakljucni(znakov: Int): String {
        val abeceda = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..znakov).map { abeceda[nakljucje.nextInt(abeceda.length)] }.joinToString("")
    }

    private fun varnoIme(ime: String): String {
        var cisto = ime.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().ifBlank { "dokument.pdf" }
        if (!cisto.lowercase().endsWith(".pdf")) cisto += ".pdf"
        return cisto.take(120)
    }

    private fun jezik(context: Context): String = when (I18n.getEffectiveLanguage(context)) {
        "sl" -> "sl"
        "de" -> "de"
        "es" -> "es-ES"
        "fr" -> "fr"
        "it" -> "it"
        else -> "en-US"
    }

    /**
     * Most za stran pregledovalnika (window.SafeerPdf). Vsak klic mora prinesti zeton dokumenta iz
     * naslova pregledovalnika: tuja stran ga ne pozna, zato mostu ne more zlorabiti.
     */
    class Most(private val context: Context, private val prenesi: (url: String, userAgent: String?) -> Unit) {

        private fun dokumentZaZeton(zeton: String): Dokument? =
            synchronized(dokumenti) { dokumenti.values.firstOrNull { it.zeton == zeton } }

        /** Shrani (urejen) PDF v mapo prenosov. base64 = vsebina datoteke. */
        @JavascriptInterface
        fun shrani(ime: String, base64: String, zeton: String) {
            val d = dokumentZaZeton(zeton) ?: return
            Thread {
                try {
                    val podatki = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val mapa = com.safeer.mobile.browser.cast.HubKrmilnik.mapaZaPrejete(context)
                    val cilj = com.safeer.mobile.browser.cast.HubTokovi.enolicnaPot(mapa, varnoIme(ime.ifBlank { d.ime }))
                    cilj.outputStream().use { it.write(podatki) }
                    obvesti(I18n.t(context, "pdf_saved").replace("{ime}", cilj.name).replace("{mapa}", PrenosiMapa.opis(context)))
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "PDF-ja ni bilo mogoce shraniti: ${e.message}")
                    obvesti(I18n.t(context, "pdf_save_failed"))
                }
            }.start()
        }

        /** Prenese izvirnik (brez sprememb) kot navaden prenos. */
        @JavascriptInterface
        fun prenesiIzvirnik(ime: String, zeton: String) {
            val d = dokumentZaZeton(zeton) ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { prenesi(d.url, d.userAgent) }
        }

        private fun obvesti(besedilo: String) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { Toast.makeText(context, besedilo, Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
            }
        }
    }
}
