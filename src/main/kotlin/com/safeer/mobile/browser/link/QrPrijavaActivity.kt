package com.safeer.mobile.browser.link

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import com.safeer.mobile.browser.cast.HubPairing
import com.safeer.mobile.browser.cast.HubTls
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Prijava racunalnika s QR kodo: uporabnik s kamero poskenira kodo v prijavnem oknu Safeer OS /
 * Safeer Control. Koda odpre to dejavnost (safeer://link/qr?... ali https://safeer.si/p#...).
 *
 * Ta naprava je ze v Safeer Linku, zato sme dovoliti. Pred vprasanjem preverimo, da je koda za NAS
 * Safeer Link: odtis potrdila, ki ga je videl racunalnik (v kodi), se mora ujemati z odtisom huba,
 * ki mu ta naprava zaupa. Tako koda z drugega omrezja ali od vsiljivca v sredini ne gre skozi.
 * Nic se ne zgodi brez uporabnikovega »Dovoli«.
 */
class QrPrijavaActivity : Activity() {

    companion object {
        private const val TAG = "SafeerQrPrijava"

        /** Koda, ki jo pokaze sredisce (Safeer OS na televizorju): ta naprava se mu pridruzi. */
        data class Pridruzitev(val id: String, val skrivnost: String, val odtis: String, val naslov: String)

        /** Ali je povezava katera koli koda Safeer (prijava racunalnika ali pridruzitev srediscu). */
        fun jeSafeerKoda(uri: Uri?): Boolean = razcleni(uri) != null || razcleniPridruzitev(uri) != null

        private fun parametri(uri: Uri?): Map<String, String>? {
            if (uri == null) return null
            return when {
                uri.scheme == "safeer" && uri.host == "link" && uri.path == "/qr" ->
                    uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
                uri.scheme == "https" && uri.host == "safeer.si" && (uri.path == "/p" || uri.path == "/p/") ->
                    (uri.fragment ?: "").split("&").mapNotNull {
                        val k = it.substringBefore("=", ""); if (k.isEmpty()) null else k to Uri.decode(it.substringAfter("="))
                    }.toMap()
                // Koda sredisca s stranjo spletnega odjemalca: http://<zasebni naslov>:<vrata>/#j=...
                uri.scheme == "http" && jeZasebniNaslov(uri.host) && (uri.path.isNullOrEmpty() || uri.path == "/") && !uri.fragment.isNullOrEmpty() ->
                    (uri.fragment ?: "").split("&").mapNotNull {
                        val k = it.substringBefore("=", ""); if (k.isEmpty()) null else k to Uri.decode(it.substringAfter("="))
                    }.toMap()
                else -> null
            }
        }

        private fun jeZasebniNaslov(h: String?): Boolean =
            h != null && (h.startsWith("10.") || h.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(h))

        fun razcleniPridruzitev(uri: Uri?): Pridruzitev? {
            val p = parametri(uri) ?: return null
            val id = p["j"].orEmpty()
            val skrivnost = p["s"].orEmpty()
            val odtis = p["f"].orEmpty().lowercase()
            val naslov = p["a"].orEmpty()
            if (!Regex("^[0-9a-f]{8,64}$").matches(id) || skrivnost.length !in 16..128 ||
                !Regex("^[0-9a-f]{64}$").matches(odtis) || !Regex("^[0-9.]{7,15}:[0-9]{2,5}$").matches(naslov)) return null
            return Pridruzitev(id, skrivnost, odtis, naslov)
        }

        /** (qr_id, skrivnost, odtis16) iz povezave ali null, ce povezava ni prijava s QR. */
        fun razcleni(uri: Uri?): Triple<String, String, String>? {
            if (uri == null) return null
            val parametri: Map<String, String> = when {
                uri.scheme == "safeer" && uri.host == "link" && uri.path == "/qr" ->
                    uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
                uri.scheme == "https" && uri.host == "safeer.si" && (uri.path == "/p" || uri.path == "/p/") ->
                    (uri.fragment ?: "").split("&").mapNotNull {
                        val k = it.substringBefore("=", ""); if (k.isEmpty()) null else k to Uri.decode(it.substringAfter("="))
                    }.toMap()
                else -> return null
            }
            val id = parametri["i"].orEmpty()
            val skrivnost = parametri["s"].orEmpty()
            val odtis = parametri["f"].orEmpty().lowercase()
            if (!Regex("^[0-9a-f]{8,64}$").matches(id) || skrivnost.length !in 16..128 ||
                !Regex("^[0-9a-f]{16,64}$").matches(odtis)) return null
            return Triple(id, skrivnost, odtis)
        }
    }

    private val glavna = Handler(Looper.getMainLooper())
    private var qr: Triple<String, String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        razcleniPridruzitev(intent?.data)?.let { pridruzi(it); return }
        qr = razcleni(intent?.data)
        val koda = qr
        if (koda == null) { sporocilo(b("neveljavna")); return }
        if (HubPairing.token(this).isNullOrBlank() || naslovHuba().isBlank()) { sporocilo(b("niPovezana")); return }
        Thread {
            val (odgovor, videni) = klic("/cast/pair/qr/info", koda)
            glavna.post {
                if (isFinishing) return@post
                when {
                    odgovor == null -> sporocilo(b("niHuba"))
                    videni == null || !videni.lowercase().startsWith(koda.third) -> sporocilo(b("drugLink"))
                    odgovor.optString("device_id").isBlank() -> sporocilo(napaka(odgovor))
                    else -> vprasaj(odgovor.optString("name").ifBlank { odgovor.optString("device_id") })
                }
            }
        }.start()
    }

    /**
     * Ta naprava se pridruzi srediscu, ki je pokazalo kodo (televizor). Uporabnik je kodo namenoma
     * poskeniral, zato ni dodatnega vprasanja; odtis potrdila iz kode potrdi, da je to pravo sredisce.
     */
    private fun pridruzi(p: Pridruzitev) {
        if (!HubPairing.token(this).isNullOrBlank() && HubTls.pripetiOdtis(this) == p.odtis) {
            sporocilo(b("zePovezana")); return
        }
        val cakam = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(b("naslovPridruzi")).setMessage(b("povezujem")).setCancelable(false).show()
        HubPairing.pridruziSQr(this, p.naslov, p.odtis, p.id, p.skrivnost, com.safeer.mobile.browser.cast.HubKrmilnik.lastniId(),
            "Safeer (" + android.os.Build.MODEL + ")") { uspelo, razlog ->
            try { cakam.dismiss() } catch (_: Throwable) { }
            if (isFinishing) return@pridruziSQr
            if (uspelo) {
                LinkSprejemnik.zagotovi(this)
                sporocilo(b("pridruzena"), b("naslovPridruzi"))
            } else sporocilo(when (razlog) {
                "qr_ne_obstaja", "prevec_poskusov" -> b("poteklaTv")
                "prevec_naprav" -> b("prevecNaprav")
                else -> b("niHuba")
            }, b("naslovPridruzi"))
        }
    }

    private fun vprasaj(ime: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(b("naslov"))
            .setMessage(b("vprasanje").replace("{ime}", ime))
            .setPositiveButton(b("dovoli")) { _, _ -> dovoli(ime) }
            .setNegativeButton(b("ne")) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun dovoli(ime: String) {
        val koda = qr ?: return finish()
        Thread {
            val (odgovor, videni) = klic("/cast/pair/qr/approve", koda)
            glavna.post {
                if (isFinishing) return@post
                when {
                    odgovor == null -> sporocilo(b("niHuba"))
                    videni == null || !videni.lowercase().startsWith(koda.third) -> sporocilo(b("drugLink"))
                    odgovor.optBoolean("approved") -> sporocilo(b("uspeh").replace("{ime}", ime))
                    else -> sporocilo(napaka(odgovor))
                }
            }
        }.start()
    }

    private fun napaka(o: JSONObject): String = when (o.optString("code")) {
        "prevec_poskusov", "qr_ne_obstaja" -> b("potekla")
        "prevec_naprav" -> b("prevecNaprav")
        "naprava_ni_seznanjena" -> b("niPovezana")
        else -> o.optString("detail").ifBlank { b("niHuba") }
    }

    private fun sporocilo(besedilo: String, naslov: String = b("naslov")) {
        if (isFinishing) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(naslov)
            .setMessage(besedilo)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun naslovHuba(): String =
        getSharedPreferences(HubPairing.PREFS_NAME, Context.MODE_PRIVATE).getString("hub_url", "") ?: ""

    /**
     * POST na hub, ki mu ta naprava zaupa (pripeti odtis).
     * Vrne (odgovor ali null ob napaki povezave, odtis potrdila, ki smo ga videli).
     */
    private fun klic(pot: String, koda: Triple<String, String, String>): Pair<JSONObject?, String?> {
        var videni: String? = null
        return try {
            val osnova = HubPairing.httpBase(naslovHuba())
            val povezava = URL(osnova + pot).openConnection() as HttpURLConnection
            if (povezava !is HttpsURLConnection) return null to null
            val odtis = HubTls.pripetiOdtis(this) ?: return null to null
            val (tovarna, zaupnik) = HubTls.odjemalec(odtis)
            povezava.sslSocketFactory = tovarna
            povezava.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            try {
                povezava.requestMethod = "POST"
                povezava.connectTimeout = 5000
                povezava.readTimeout = 8000
                povezava.setRequestProperty("x-safeer-token", HubPairing.token(this).orEmpty())
                povezava.setRequestProperty("Content-Type", "application/json")
                povezava.doOutput = true
                val telo = JSONObject().put("qr_id", koda.first).put("secret", koda.second).toString()
                povezava.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
                val status = povezava.responseCode
                videni = zaupnik.videni
                val tok = if (status >= 400) povezava.errorStream else povezava.inputStream
                val vsebina = tok?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                (try { JSONObject(vsebina) } catch (_: Exception) { JSONObject() }) to videni
            } finally {
                povezava.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hub se ni odzval: ${e.message}")
            null to videni
        }
    }

    /** Besedila v jeziku naprave (slovenscina ali anglescina). */
    private fun b(kljuc: String): String {
        val sl = try { resources.configuration.locales[0].language == "sl" } catch (_: Throwable) { false }
        return when (kljuc) {
            "naslov" -> if (sl) "Prijava v Safeer OS" else "Sign in to Safeer OS"
            "vprasanje" -> if (sl) "Dovoliš računalniku »{ime}« dostop do tvojih naprav v Safeer Linku?"
                else "Allow the computer “{ime}” to access your devices in Safeer Link?"
            "dovoli" -> if (sl) "Dovoli" else "Allow"
            "ne" -> if (sl) "Ne" else "No"
            "uspeh" -> if (sl) "Računalnik »{ime}« je prijavljen." else "The computer “{ime}” is signed in."
            "naslovPridruzi" -> if (sl) "Safeer Link" else "Safeer Link"
            "povezujem" -> if (sl) "Povezujem s televizorjem …" else "Connecting to the TV …"
            "pridruzena" -> if (sl) "Povezano. Ta naprava je zdaj v tvojem Safeer Linku." else "Connected. This device is now in your Safeer Link."
            "zePovezana" -> if (sl) "Ta naprava je že povezana s tem Safeer Linkom." else "This device is already connected to this Safeer Link."
            "neveljavna" -> if (sl) "Ta koda ni koda za prijavo v Safeer." else "This is not a Safeer sign-in code."
            "niPovezana" -> if (sl) "Ta naprava še ni v Safeer Linku. Najprej jo poveži, nato poskeniraj kodo znova."
                else "This device is not in Safeer Link yet. Connect it first, then scan the code again."
            "niHuba" -> if (sl) "Safeer Link se ne oglasi. Preveri, da sta napravi v istem domačem omrežju."
                else "Safeer Link is not answering. Check that both devices are on the same home network."
            "drugLink" -> if (sl) "Ta koda ni za tvoj Safeer Link. Prijave nisem dovolil."
                else "This code is not for your Safeer Link. Sign-in was not allowed."
            "potekla" -> if (sl) "Koda je potekla. Na računalniku se je pokazala nova – poskeniraj jo."
                else "The code has expired. The computer is showing a new one – scan that."
            "poteklaTv" -> if (sl) "Koda je potekla. Televizor je že pokazal novo – poskeniraj jo."
                else "The code has expired. The TV is already showing a new one – scan that."
            "prevecNaprav" -> if (sl) "V Safeer Linku je že preveč naprav. Odstrani katero, ki je ne uporabljaš."
                else "Safeer Link already has too many devices. Remove one you no longer use."
            else -> kljuc
        }
    }
}
