package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2.

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Seznanitev naprave s Safeer Hubom - s kodo, ki je nikoli ne posljemo po omrezju.
 *
 * Kodo pokaze gostitelj (naprava, na kateri Safeer Link tece). Uporabnik jo prebere z
 * njegovega zaslona in vtipka tukaj. Iz kode obe strani s SPAKE2 (RFC 9382) izpeljeta
 * skupni kljuc; po omrezju gredo samo tocke krivulje in potrditvi, iz katerih se brez kode
 * ne da nicesar. Kdor gostiteljevega zaslona ne vidi, se ne more prikljuciti, tudi ce je v
 * istem omrezju - in tudi ce prestreza promet.
 *
 * Povezava je TLS. Pred seznanitvijo Hubovega potrdila se ne poznamo: sprejmemo tisto, ki
 * ga vidimo, njegov prstni odtis pa vpletemo v seznanitev. Ce je vmes napadalec s svojim
 * potrdilom, vidita strani razlicna odtisa, potrditvi se ne ujemata in seznanitev pade.
 * Po uspehu si odtis zapomnimo in od takrat naprej sprejmemo samo se tega.
 *
 * Zeton naprave odpira samo Cast in Sync, ne celotnega Controla.
 */
object HubPairing {

    private const val TAG = "SafeerHubPairing"
    const val PREFS_NAME = "safeer_cast_prefs"
    const val KEY_CONTROL_TOKEN = "control_token"

    /** Kodo pokaze gostitelj; naprava jo vtipka (in jo s SPAKE2 dokaze, ne poslje). */
    const val NACIN_KODA_NA_GOSTITELJU = "koda_na_gostitelju"

    private val glavna = Handler(Looper.getMainLooper())

    @Volatile
    private var tece = false

    /** Odprta prijava: caka na vnos kode. */
    private class Prijava(val osnova: String, val pairId: String, val hubId: String, val odtis: String)

    @Volatile
    private var odprta: Prijava? = null

    /** Osnovni naslov Huba (https://...) iz naslova WebSocketa. */
    fun httpBase(wsUrl: String): String =
        wsUrl.replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
            .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws")
            .trimEnd('/')

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTROL_TOKEN, null)

    private fun shraniZeton(context: Context, zeton: String, odtis: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CONTROL_TOKEN, zeton)
            .putString(HubTls.KEY_HUB_FP, odtis)
            .apply()
        // Tudi v seznam seznanitev: ko ta Hub ugasne in se vrne drug znani, ni nove kode.
        Seznanitve.zapomni(context, odtis, zeton, prefs.getString("hub_url", "") ?: "")
    }

    /** Odjemalec, ki pred seznanitvijo sprejme katerokoli potrdilo in si zapomni njegov odtis. */
    private fun odjemalecTofu(): Pair<OkHttpClient, HubTls.Zaupnik> {
        val (g, z) = HubTls.okhttp(
            OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS), null
        )
        return g.build() to z
    }

    /** Odjemalec, ki zaupa samo Hubu z danim odtisom. */
    private fun odjemalecPripet(odtis: String): OkHttpClient =
        HubTls.okhttp(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS), odtis)
            .first.build()

    private fun post(odjemalec: OkHttpClient, url: String, telo: JSONObject): Pair<Int, JSONObject?> {
        val zahteva = Request.Builder().url(url)
            .post(telo.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
        odjemalec.newCall(zahteva).execute().use { odgovor ->
            val besedilo = odgovor.body?.string().orEmpty()
            val json = try { JSONObject(besedilo) } catch (e: Exception) { null }
            return odgovor.code to json
        }
    }

    /**
     * Zacne seznanitev. `nacinZnan` dobi nacin in prazno kodo (kodo pokaze gostitelj);
     * vmesnik nato ponudi vnos in ga poslje s [potrdiKodo]. `koncano(false)`, ce ne gre.
     */
    fun pair(
        context: Context, wsUrl: String, deviceId: String, deviceName: String,
        nacinZnan: (String, String) -> Unit,
        koncano: (Boolean) -> Unit
    ) {
        if (tece) {
            Log.i(TAG, "Seznanitev ze tece.")
            return
        }
        val osnova = httpBase(wsUrl)
        if (!osnova.startsWith("https://")) {
            // Hub brez TLS: zeton bi potoval v cistem besedilu. Tak Hub naj se posodobi.
            Log.w(TAG, "Hub brez TLS ($osnova); seznanitev zavrnjena.")
            glavna.post { koncano(false) }
            return
        }
        tece = true
        odprta = null
        Thread {
            try {
                val (odjemalec, zaupnik) = odjemalecTofu()
                val (koda, json) = post(odjemalec, "$osnova/cast/pair/start", JSONObject().apply {
                    put("device_id", deviceId)
                    put("name", deviceName)
                })
                val pairId = json?.optString("pair_id").orEmpty()
                val nacin = json?.optString("nacin").orEmpty()
                val hubId = json?.optString("hub_id").orEmpty().ifBlank { HubUsmerjevalnik.IDENTITETA_HUBA }
                val odtis = zaupnik.videni.orEmpty()
                if (koda != 200 || pairId.isBlank() || odtis.isBlank()) {
                    Log.w(TAG, "Prijave ni bilo mogoce zaceti (koda $koda).")
                    tece = false
                    glavna.post { koncano(false) }
                    return@Thread
                }
                if (nacin != HubUsmerjevalnik.NACIN_SPAKE2) {
                    // Starejsi Hub, ki bi kodo prejel po omrezju. Ne sodelujemo; Hub naj se posodobi.
                    Log.w(TAG, "Hub ne zna varnega seznanjanja (nacin '$nacin'); posodobi Safeer na njem.")
                    tece = false
                    glavna.post { koncano(false) }
                    return@Thread
                }
                odprta = Prijava(osnova, pairId, hubId, odtis)
                Log.i(TAG, "Cakam, da uporabnik vtipka kodo z gostitelja (odtis ${odtis.take(12)}…).")
                glavna.post { nacinZnan(NACIN_KODA_NA_GOSTITELJU, "") }
            } catch (e: Exception) {
                Log.w(TAG, "Prijave ni bilo mogoce zaceti: ${e.message}")
                tece = false
                glavna.post { koncano(false) }
            }
        }.start()
    }

    /**
     * Uporabnik je vtipkal kodo z gostiteljevega zaslona. `izid` dobi true ob uspehu, sicer
     * false in stabilno kodo napake ("napacna_koda", "prevec_poskusov", ...).
     */
    fun potrdiKodo(context: Context, koda: String, deviceId: String, izid: (Boolean, String?) -> Unit) {
        val p = odprta
        if (p == null) {
            glavna.post { izid(false, "seznanitev_ne_tece") }
            return
        }
        val app = context.applicationContext
        val vnos = koda.trim()
        if (vnos.length < 4) {
            glavna.post { izid(false, "napacna_koda") }
            return
        }
        Thread {
            try {
                // Ves cas seznanitve govorimo z natanko tistim potrdilom, ki smo ga videli na zacetku.
                val odjemalec = odjemalecPripet(p.odtis)
                val spake = Spake2.odjemalec(vnos, deviceId, p.hubId, p.odtis.toByteArray(Charsets.UTF_8), p.pairId.toByteArray(Charsets.UTF_8))
                val (k1, j1) = post(odjemalec, "${p.osnova}/cast/pair/spake", JSONObject().apply {
                    put("pair_id", p.pairId)
                    put("device_id", deviceId)
                    put("pb", HubUsmerjevalnik.bajteVHex(spake.sporocilo()))
                })
                if (k1 != 200) {
                    val razlog = j1?.optString("code").orEmpty().ifBlank { "povezava_ni_uspela" }
                    if (razlog == "prevec_poskusov" || razlog == "prijava_ne_obstaja") { tece = false; odprta = null }
                    glavna.post { izid(false, razlog) }
                    return@Thread
                }
                val pa = HubUsmerjevalnik.hexVBajte(j1?.optString("pa").orEmpty())
                val ca = HubUsmerjevalnik.hexVBajte(j1?.optString("ca").orEmpty())
                if (pa == null || ca == null) {
                    glavna.post { izid(false, "povezava_ni_uspela") }
                    return@Thread
                }
                val cb = try { spake.zakljuci(pa) } catch (e: IllegalArgumentException) {
                    glavna.post { izid(false, "povezava_ni_uspela") }
                    return@Thread
                }
                if (!spake.preveri(ca)) {
                    // Hub ne pozna iste kode ali pa je vmes kdo z drugim potrdilom. Hubu tega ne
                    // posljemo (ne dajemo mu materiala), poskus pa pri njem vseeno steje.
                    Log.w(TAG, "Hubova potrditev se ne ujema: napacna koda ali napadalec v sredini.")
                    glavna.post { izid(false, "napacna_koda") }
                    return@Thread
                }
                val (k2, j2) = post(odjemalec, "${p.osnova}/cast/pair/finish", JSONObject().apply {
                    put("pair_id", p.pairId)
                    put("device_id", deviceId)
                    put("cb", HubUsmerjevalnik.bajteVHex(cb))
                })
                val zeton = j2?.optString("token").orEmpty()
                if (k2 != 200 || zeton.isBlank()) {
                    val razlog = j2?.optString("code").orEmpty().ifBlank { "napacna_koda" }
                    if (razlog == "prevec_poskusov" || razlog == "prijava_ne_obstaja") { tece = false; odprta = null }
                    glavna.post { izid(false, razlog) }
                    return@Thread
                }
                shraniZeton(app, zeton, p.odtis)
                tece = false
                odprta = null
                Log.i(TAG, "Naprava je seznanjena s Safeer Hubom; odtis potrdila pripet.")
                glavna.post { izid(true, null) }
            } catch (e: Exception) {
                Log.w(TAG, "Kode ni bilo mogoce potrditi: ${e.message}")
                glavna.post { izid(false, "povezava_ni_uspela") }
            }
        }.start()
    }

    /** Uporabnik je vnos kode opustil. */
    fun prekini() {
        tece = false
        odprta = null
    }
}
