package com.safeer.mobile.browser.link

import android.app.Activity
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.safeer.mobile.browser.cast.CastSenderClient
import com.safeer.mobile.browser.cast.HubDiscovery
import com.safeer.mobile.browser.cast.HubPairing
import org.json.JSONArray
import org.json.JSONObject

/**
 * Most med stranjo Safeer Linka in aplikacijo.
 *
 * Stran Safeer Linka je del aplikacije (assets/link/), ne prihaja z omrezja. Most je
 * pripet SAMO na njen pogled -- nikoli na zavihek, v katerem se odpirajo spletne strani --
 * zato do teh metod nobena spletna stran ne more.
 *
 * Zeton naprave ostane v zasebnih nastavitvah aplikacije: stran ga nikoli ne vidi in ga
 * ne more prebrati. Vsak klic proti Hubu opravi most, stran pove samo, kaj zeli.
 *
 * Odgovori se vracajo z `window.safeerLinkOdziv(vrsta, podatki)`, ker klici mostu pridejo
 * z druge niti in ne smejo cakati na omrezje.
 */
class LinkMost(
    private val dejavnost: Activity,
    private val pogled: WebView,
    private val trenutnaStran: () -> Pair<String, String?>,
    private val zapriZaslon: () -> Unit,
    private val odpriVBrskalniku: (String) -> Unit,
    /** Odpre sistemski izbirnik datotek; izbrano vrne dejavnost prek posljiDatotekoUri. */
    private val izberiDatoteko: ((String) -> Unit)? = null,
    /** Vprasa za dovoljenje za zajem zaslona; dejavnost nato zazene DeljenjeZaslonaStoritev. */
    private val zahtevajZajemZaslona: ((String, String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "SafeerLink"
        const val PREFS = "safeer_cast_prefs"
    }

    private var odjemalec: CastSenderClient? = null
    private var zadnjeNaprave: JSONArray = JSONArray()

    // ------------------------------------------------------------------
    // Pomozno
    // ------------------------------------------------------------------

    private fun nastavitve() = dejavnost.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hubUrl(): String = nastavitve().getString("hub_url", "") ?: ""

    private fun zeton(): String? = nastavitve().getString("control_token", null)

    /** Odtis Hubovega potrdila, pripet ob seznanitvi. */
    private fun odtisHuba(): String? = com.safeer.mobile.browser.cast.HubTls.pripetiOdtis(dejavnost)

    private fun potVstopnice(): String =
        nastavitve().getString("hub_ticket_path", "/cast/ticket") ?: "/cast/ticket"

    /** Odgovor strani. Vedno na glavni niti in vedno kot JSON, da stran ne sestavlja nizov. */
    private fun odziv(vrsta: String, podatki: Any) {
        val telo = when (podatki) {
            is JSONObject, is JSONArray -> podatki.toString()
            is Boolean -> podatki.toString()
            else -> JSONObject.quote(podatki.toString())
        }
        pogled.post {
            try {
                pogled.evaluateJavascript(
                    "window.safeerLinkOdziv && window.safeerLinkOdziv(${JSONObject.quote(vrsta)}, $telo)",
                    null
                )
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "Odziva ni bilo mogoce dostaviti: ${e.message}")
            }
        }
    }

    /**
     * Napaka za stran. [koda] je stabilna oznaka, ki jo stran prevede v jezik naprave;
     * [sporocilo] ostane zraven kot rezerva in za diagnostiko, ce kode ne pozna.
     */
    private fun napaka(koda: String, sporocilo: String) = odziv(
        "napaka",
        JSONObject().put("koda", koda).put("sporocilo", sporocilo)
    )

    // ------------------------------------------------------------------
    // Stanje
    // ------------------------------------------------------------------

    /**
     * Stanje brez omrezja: ali Hub poznamo, ali nas ze pozna in kako se ta naprava imenuje.
     * Stran tako nariše pravi zaslon takoj, brez cakanja.
     */

    /**
     * Jezik, ki ga ima uporabnik na napravi -- stran govori v njem.
     * Vrnemo samo dvocrkovno oznako; stran zna slovensko in anglesko.
     */
    @JavascriptInterface
    fun jezik(): String = try {
        val jeziki = dejavnost.resources.configuration.locales
        val prvi = if (jeziki.size() > 0) jeziki.get(0) else java.util.Locale.getDefault()
        (prvi.language ?: "").lowercase().take(2)
    } catch (e: Throwable) {
        ""
    }

    /**
     * Odklopi TO napravo od Safeer Linka: pozabi zeton in naslov.
     *
     * Namenoma ne posegamo v druge naprave -- to je odlocitev za napravo, ki jo ima
     * uporabnik v roki. Ostale se odstrani v Safeer Controlu.
     */
    @JavascriptInterface
    fun pozabiNapravo() {
        try {
            odjemalec?.disconnect()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Zapiranja povezave ni bilo mogoce dokoncati: ${e.message}")
        }
        odjemalec = null
        try {
            nastavitve().edit()
                .remove("control_token")
                .remove("hub_url")
                .remove("hub_ticket_path")
                .remove("hub_last_seen")
                .remove(com.safeer.mobile.browser.cast.HubTls.KEY_HUB_FP)
                .remove("seznanitve")
                .apply()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Nastavitev ni bilo mogoce pocistiti: ${e.message}")
        }
        try { LinkSprejemnik.ustavi(dejavnost) } catch (_: Throwable) {}
        odziv("pozabljeno", true)
    }

    @JavascriptInterface
    fun stanje(): String {
        return try {
            JSONObject().apply {
                put("hub", hubUrl())
                put("znan", hubUrl().isNotBlank())
                // Seznanjena je naprava, ki ima zeton IN odtis Hubovega potrdila; stara seznanitev
                // brez odtisa (pred TLS) ne velja vec - stran ponudi novo.
                put("seznanjen", zeton() != null && odtisHuba() != null)
                put("naprava", "Safeer (" + android.os.Build.MODEL + ")")
                put("id", ime())
                put("videnZadnjic", nastavitve().getLong("hub_last_seen", 0L))
            }.toString()
        } catch (e: Throwable) {
            "{\"znan\":false,\"seznanjen\":false}"
        }
    }

    private fun ime(): String =
        "phone-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase()

    private fun imeNaprave(): String = "Safeer (" + android.os.Build.MODEL + ")"

    /** Naslov Huba za navadne zahteve HTTP (ws://x:y/cast/ws -> http://x:y). */
    private fun hubHttp(): String = hubUrl().replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
        .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws").trimEnd('/')

    // ------------------------------------------------------------------
    // Hub: iskanje in seznanitev
    // ------------------------------------------------------------------

    /** Poisce Hub v krajevnem omrezju. Ce ga ni, stran to pove mirno -- brskalnik dela naprej. */
    @JavascriptInterface
    fun poisciHub() {
        try {
            HubDiscovery.discover(dejavnost) { naslov ->
                odziv("hub", JSONObject().apply {
                    put("najden", naslov != null)
                    put("naslov", naslov ?: "")
                })
            }
        } catch (e: Throwable) {
            napaka("iskanje_ni_steklo", "Iskanja ni bilo mogoce zagnati: ${e.message}")
        }
    }

    /**
     * Zacne seznanitev.
     *
     * Pri novem Hubu kodo pokaze gostitelj, uporabnik pa jo vtipka tu; strani to povemo z
     * odzivom "nacin". Pri starejsem Hubu ostane stari postopek: kodo pokazemo mi, potrdi
     * se na gostitelju.
     */
    @JavascriptInterface
    fun seznani() {
        val naslov = hubUrl()
        if (naslov.isBlank()) {
            napaka("hub_ni_znan", "Hub ni znan. Najprej ga poisci.")
            return
        }
        try {
            HubPairing.pair(
                dejavnost, naslov, ime(), "Safeer (" + android.os.Build.MODEL + ")",
                { nacin, koda ->
                    odziv("nacin", JSONObject().apply {
                        put("nacin", nacin)
                        put("koda", koda)
                    })
                },
                { uspelo -> odziv("seznanitev", uspelo) }
            )
        } catch (e: Throwable) {
            napaka("seznanitev_ni_stekla", "Seznanitve ni bilo mogoce zaceti: ${e.message}")
        }
    }

    /** Uporabnik je vtipkal sestmestno kodo, ki jo pokaze gostitelj. */
    @JavascriptInterface
    fun potrdiKodo(koda: String) {
        try {
            HubPairing.potrdiKodo(dejavnost, koda, ime()) { uspelo, razlog ->
                if (uspelo) {
                    odziv("seznanitev", true)
                } else {
                    odziv("kodaNiSprejeta", JSONObject().apply {
                        put("razlog", razlog ?: "napacna_koda")
                    })
                }
            }
        } catch (e: Throwable) {
            napaka("seznanitev_ni_stekla", "Kode ni bilo mogoce poslati: ${e.message}")
        }
    }

    /** Uporabnik je vnos kode opustil. */
    @JavascriptInterface
    fun prekiniSeznanitev() {
        try {
            HubPairing.prekini()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Prekinitve ni bilo mogoce izvesti: ${e.message}")
        }
        odziv("seznanitevPrekinjena", true)
    }

    // ------------------------------------------------------------------
    // Naprave in Cast
    // ------------------------------------------------------------------

    private fun odjemalec(): CastSenderClient? {
        val naslov = hubUrl()
        if (naslov.isBlank()) return null
        odjemalec?.let { return it }
        // Stran ima svojo povezavo z istim id-jem naprave: sprejem v ozadju medtem pocaka.
        LinkSprejemnik.premor()
        val nov = CastSenderClient(
            naslov, zeton(), potVstopnice(),
            hubOdtis = odtisHuba(),
            senderId = ime(),
            sinhronizira = ZaznamkiSync.jeVklopljena(dejavnost),
            deviceName = imeNaprave(),
            zmoznosti = listOf("url", "text", "file", "screen", Daljinec.ZMOZNOST)
        )
        nov.onShare = { sporocilo -> prejmiDeljenje(sporocilo) }
        nov.onControlOdziv = { json -> ukazOdziv(json) }
        // Dokler je stran Linka odprta, ima povezavo ona: ukazi Safeer Controla gredo sem.
        // Tipk in drsenja tu ni (odprt je Link, ne stran); glasnost, aplikacije in ostalo delujejo.
        nov.onControl = { sporocilo ->
            val tovor = sporocilo.optJSONObject("payload") ?: JSONObject()
            val dejanje = tovor.optString("action", "")
            val izid = Daljinec.izvedi(dejavnost, dejanje, tovor.optJSONObject("params") ?: tovor, null,
                LinkSprejemnik.DOMACA_STRAN) { url, _ -> zapriZaslon(); odpriVBrskalniku(url) }
            val posiljatelj = sporocilo.optString("sender", "")
            if (posiljatelj.isNotBlank()) nov.posljiSporocilo(Daljinec.sporociloIzida(posiljatelj, sporocilo.optString("id", ""), dejanje, izid))
        }
        nov.onSyncData = { kategorija, razlicica, _, vsebina ->
            if (kategorija == ZaznamkiSync.KATEGORIJA && ZaznamkiSync.jeVklopljena(dejavnost)) {
                // Zdruzevanje odpre bazo, zato ne na glavni niti.
                Thread {
                    try {
                        val repo = com.safeer.mobile.browser.BrowserRepository(dejavnost)
                        val dodanih = ZaznamkiSync.zdruzi(repo, vsebina)
                        if (razlicica > ZaznamkiSync.razlicica(dejavnost)) {
                            ZaznamkiSync.shraniRazlicico(dejavnost, razlicica)
                        }
                        odziv("sinhronizacija", JSONObject().apply {
                            put("kategorija", kategorija)
                            put("dodanih", dodanih)
                            put("skupaj", ZaznamkiSync.stevilo(repo))
                            put("vklopljena", true)
                        })
                    } catch (e: Throwable) {
                        napaka("zdruzevanje_ni_koncano", "Zdruzevanja zaznamkov ni bilo mogoce koncati: ${e.message}")
                    }
                }.start()
            }
        }
        nov.onDevicesChanged = { seznam ->
            val polje = JSONArray()
            seznam.forEach { n ->
                polje.put(JSONObject().apply {
                    put("id", n.id)
                    put("ime", n.name)
                    put("vloga", n.role)
                    put("zmoznosti", JSONArray(n.capabilities))
                    put("zasedenaOd", n.busyBy)
                    put("zasedenaOdIme", n.busyByName)
                    put("naslov", n.address)
                })
            }
            zadnjeNaprave = polje
            odziv("naprave", polje)
        }
        nov.onPlaybackStatus = { s ->
            odziv("predvajanje", JSONObject().apply {
                put("naprava", s.deviceId)
                put("stanje", s.state)
                put("naslov", s.title ?: "")
                put("url", s.currentUrl ?: "")
                put("polozaj", s.position)
                put("trajanje", s.duration)
            })
        }
        nov.onConnectedStateChanged = { povezan ->
            odziv("povezava", povezan)
            if (!povezan) poisciDrugoSredisce()
        }
        odjemalec = nov
        return nov
    }

    private var zadnjeIskanjeSredisca = 0L

    /**
     * Povezave s sredisce ni. Sredisce je morda ugasnilo (televizor) ali dobilo nov naslov;
     * cez nekaj sekund pogledamo, ali se Safeer Link javlja kje drugje. Ce je bila ta naprava
     * z njim ze seznanjena, se poveze brez nove kode (HubDiscovery preklopi in vrne zeton).
     */
    private fun poisciDrugoSredisce() {
        val zdaj = System.currentTimeMillis()
        if (zdaj - zadnjeIskanjeSredisca < 30_000L) return
        zadnjeIskanjeSredisca = zdaj
        val prej = hubUrl()
        val prejOdtis = odtisHuba() ?: ""
        pogled.postDelayed({
            val o = odjemalec
            if (o != null && o.jePovezan()) return@postDelayed
            try {
                HubDiscovery.discover(dejavnost) { naslov ->
                    if (naslov != null && (naslov != prej || (odtisHuba() ?: "") != prejOdtis)) {
                        android.util.Log.i(TAG, "Sredisce se je preselilo: $prej -> $naslov")
                        prevezi()
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "Iskanja drugega sredisca ni bilo mogoce zagnati: ${e.message}")
            }
        }, 6_000L)
    }

    /** Povezi se s Hubom in vrni seznam naprav. */
    @JavascriptInterface
    fun poveziSe() {
        val o = odjemalec()
        if (o == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        try {
            o.connect()
        } catch (e: Throwable) {
            napaka("povezava_ni_uspela", "Povezava ni uspela: ${e.message}")
        }
    }

    /** Zadnji znani seznam naprav brez novega klica (za takojsen izris). */
    @JavascriptInterface
    fun naprave(): String = zadnjeNaprave.toString()

    /** Naslov in ime strani, ki je odprta v brskalniku -- to Link ponudi za posiljanje. */
    @JavascriptInterface
    fun trenutnaStranJson(): String {
        return try {
            val (url, naslov) = trenutnaStran()
            JSONObject().apply {
                put("url", url)
                put("naslov", naslov ?: "")
                put("posljiva", url.isNotBlank() && !url.startsWith("file:///android_asset/"))
            }.toString()
        } catch (e: Throwable) {
            "{\"url\":\"\",\"naslov\":\"\",\"posljiva\":false}"
        }
    }

    /** Poslje trenutno odprto stran na izbrano napravo. */
    @JavascriptInterface
    fun posljiTrenutno(idNaprave: String) {
        val (url, naslov) = trenutnaStran()
        if (url.isBlank() || url.startsWith("file:///android_asset/")) {
            napaka("stran_ni_primerna", "Ta stran ni primerna za posiljanje.")
            return
        }
        poslji(idNaprave, url, naslov ?: "")
    }

    /** Poslje poljuben naslov. Stran ga sme podati, ker jo je napisal uporabnik sam. */
    @JavascriptInterface
    fun poslji(idNaprave: String, url: String, naslov: String) {
        val o = odjemalec()
        if (o == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        val cist = url.trim()
        if (!cist.startsWith("http://") && !cist.startsWith("https://")) {
            napaka("samo_http", "Poslati je mogoce samo naslove http in https.")
            return
        }
        try {
            o.sendUrl(idNaprave, cist, naslov.ifBlank { null })
            odziv("poslano", JSONObject().apply {
                put("naprava", idNaprave)
                put("url", cist)
            })
        } catch (e: Throwable) {
            napaka("posiljanje_ni_uspelo", "Posiljanje ni uspelo: ${e.message}")
        }
    }

    /** Predvajanje: pause, play, seek, volume, stop -- kar Hub dovoli. */
    @JavascriptInterface
    fun nadzor(idNaprave: String, ukaz: String, vrednost: Double) {
        val o = odjemalec()
        if (o == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        try {
            when (ukaz) {
                "seek" -> o.sendControl(idNaprave, "seek", position = vrednost)
                "volume" -> o.sendControl(idNaprave, "volume", volume = vrednost)
                else -> o.sendControl(idNaprave, ukaz)
            }
        } catch (e: Throwable) {
            napaka("ukaz_ni_uspel", "Ukaz ni uspel: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ daljinec (Safeer Control)

    /**
     * Ukaz daljinca drugi napravi prek sredisca (control.command). Odgovor pride kot odziv
     * "ukaz" z istim `ref`, ki ga je dala stran; ce sredisce ukaz zavrne, prav tako.
     */
    @JavascriptInterface
    fun ukaz(idNaprave: String, dejanje: String, parametriJson: String, ref: String) {
        val o = odjemalec()
        if (o == null || !o.jePovezan()) {
            odziv("ukaz", JSONObject().put("ref", ref).put("ok", false).put("message", "Ni povezave s Safeer Linkom."))
            return
        }
        val parametri = try { JSONObject(parametriJson) } catch (_: Throwable) { JSONObject() }
        val sporocilo = JSONObject().apply {
            put("id", ref.ifBlank { java.util.UUID.randomUUID().toString() })
            put("type", "control.command")
            put("target", idNaprave)
            put("payload", JSONObject().put("action", dejanje).put("params", parametri))
        }
        if (!o.posljiSporocilo(sporocilo)) {
            odziv("ukaz", JSONObject().put("ref", ref).put("ok", false).put("message", "Ukaza ni bilo mogoce poslati."))
        }
    }

    /** Odgovor naprave (control.result) ali zavrnitev sredisca (control.ack) -> stran. */
    private fun ukazOdziv(json: JSONObject) {
        val tovor = json.optJSONObject("payload") ?: JSONObject()
        val o = JSONObject()
            .put("ref", json.optString("ref_id", ""))
            .put("naprava", json.optString("sender", ""))
        if (json.optString("type") == "control.ack") {
            o.put("ok", false).put("message", json.optString("error", "").ifBlank { "Sredisce je ukaz zavrnilo." })
                .put("koda", json.optString("error_code", ""))
        } else {
            o.put("ok", tovor.optBoolean("ok", false)).put("message", tovor.optString("message", ""))
                .put("action", tovor.optString("action", "")).put("koda", tovor.optString("code", ""))
            tovor.optJSONObject("data")?.let { o.put("data", it) }
        }
        odziv("ukaz", o)
    }

    /** Telefon strani in ukaze odpre prek obvestila; dovoljenja za prekrivanje ne zahteva. */
    @JavascriptInterface
    fun lahkoVOspredje(): Boolean = true

    @JavascriptInterface
    fun dovoliOspredje() {}

    private var govor: Govor? = null

    /** Ali naprava zna prepoznavati govor. */
    @JavascriptInterface
    fun znaGovor(): Boolean = try { Govor(dejavnost) { }.jeNaVoljo() } catch (_: Throwable) { false }

    /** Zacne poslusati; odzivi "govor" nosijo stanje (poslusam, delno, koncno, napaka) in besedilo. */
    @JavascriptInterface
    fun poslusaj(jezik: String) {
        dejavnost.runOnUiThread {
            val g = govor ?: Govor(dejavnost) { podatki -> odziv("govor", podatki) }.also { govor = it }
            g.zacni(jezik)
        }
    }

    @JavascriptInterface
    fun nehajPoslusati() {
        dejavnost.runOnUiThread { govor?.ustavi() }
    }

    // ------------------------------------------------------------------
    // Deljenje: besedilo, datoteka, zaslon
    //
    // Vsebina gre na Hub po HTTP (Hub jo posreduje cilju), zato posiljanje ne rabi odprte
    // povezave WebSocket in tece naprej, tudi ce uporabnik zapre ta zaslon. Stran o
    // poteku izve z odzivom "deljenje": {vrsta, stanje, cilj, ime, sporocilo, odstotek}.
    // ------------------------------------------------------------------

    private fun deljenje(vrsta: String, stanje: String, cilj: String, ime: String = "", sporocilo: String = "", odstotek: Int = -1,
                         koda: String = "", zasedenaOd: String = "") {
        odziv("deljenje", JSONObject().apply {
            put("vrsta", vrsta)
            put("stanje", stanje)
            put("cilj", cilj)
            put("ime", ime)
            put("sporocilo", sporocilo)
            put("koda", koda)
            put("zasedenaOd", zasedenaOd)
            if (odstotek >= 0) put("odstotek", odstotek)
        })
    }

    /** Napaka Huba: besedilo (rezerva), stabilna koda in - ce je naprava zasedena - kdo z njo deli. */
    private class NapakaHuba(val sporocilo: String, val koda: String, val zasedenaOd: String)

    private fun napakaHuba(koda: Int, odgovor: String): NapakaHuba = try {
        val o = JSONObject(odgovor)
        NapakaHuba(
            o.optString("napaka", "").ifBlank { o.optString("error", "") }.ifBlank { "Hub je odgovoril $koda" },
            o.optString("koda", "").ifBlank { o.optString("error_code", "") },
            o.optString("busy_by_name", "").ifBlank { o.optString("busy_by", "") }
        )
    } catch (_: Throwable) { NapakaHuba("Hub je odgovoril $koda", "", "") }

    private fun httpJson(metoda: String, pot: String, telo: String): Pair<Int, String> {
        val povezava = java.net.URL(hubHttp() + pot).openConnection() as java.net.HttpURLConnection
        try {
            com.safeer.mobile.browser.cast.HubTls.zavaruj(povezava, dejavnost)
            povezava.requestMethod = metoda
            povezava.connectTimeout = 5000
            povezava.readTimeout = 10000
            zeton()?.let { povezava.setRequestProperty("x-safeer-token", it) }
            povezava.setRequestProperty("Content-Type", "application/json")
            povezava.doOutput = true
            povezava.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            val koda = povezava.responseCode
            val tok = if (koda >= 400) povezava.errorStream else povezava.inputStream
            return koda to (tok?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            povezava.disconnect()
        }
    }

    /** Besedilo na izbrano napravo. */
    @JavascriptInterface
    fun posljiBesedilo(idNaprave: String, besedilo: String) {
        val cisto = besedilo.trim()
        if (cisto.isEmpty()) return
        if (hubUrl().isBlank() || zeton() == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        deljenje("besedilo", "posiljam", idNaprave)
        Thread {
            try {
                val telo = JSONObject().put("device_id", ime()).put("target", idNaprave).put("text", cisto).toString()
                val (koda, odgovor) = httpJson("POST", "/cast/share/text", telo)
                if (koda == 200) deljenje("besedilo", "poslano", idNaprave)
                else napakaHuba(koda, odgovor).let { n -> deljenje("besedilo", "napaka", idNaprave, sporocilo = n.sporocilo, koda = n.koda, zasedenaOd = n.zasedenaOd) }
            } catch (e: Throwable) {
                deljenje("besedilo", "napaka", idNaprave, sporocilo = e.message ?: "posiljanje ni uspelo")
            }
        }.start()
    }

    /** Odpre izbirnik datotek; izbrana datoteka se poslje z posljiDatotekoUri. */
    @JavascriptInterface
    fun izberiDatoteko(idNaprave: String) {
        val izbira = izberiDatoteko
        if (izbira == null) {
            napaka("ni_izbirnika", "Izbira datoteke tu ni na voljo.")
            return
        }
        dejavnost.runOnUiThread { izbira(idNaprave) }
    }

    /**
     * Poslje datoteko iz sistemskega izbirnika. Prenos tece v storitvi v ospredju
     * (PrenosDatotekeStoritev), da prezivi preklop v drugo aplikacijo; sem pride le napredek.
     */
    fun posljiDatotekoUri(uri: android.net.Uri, idNaprave: String) {
        if (hubUrl().isBlank() || zeton() == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        pripniNapredekPrenosa()
        try {
            PrenosDatotekeStoritev.poslji(dejavnost, uri, idNaprave, hubHttp(), zeton() ?: "", ime())
            deljenje("datoteka", "posiljam", idNaprave, "", odstotek = 0)
        } catch (e: Throwable) {
            deljenje("datoteka", "napaka", idNaprave, "", e.message ?: "posiljanje ni uspelo")
        }
    }

    private fun pripniNapredekPrenosa() {
        PrenosDatotekeStoritev.naNapredek = { n ->
            deljenje("datoteka", n.stanje, n.cilj, n.ime, n.sporocilo, n.odstotek, n.koda, n.zasedenaOd)
        }
    }

    /** Zacne deljenje zaslona: dejavnost vprasa za dovoljenje in zazene storitev. */
    @JavascriptInterface
    fun zacniDeljenjeZaslona(idNaprave: String, imeNaprave: String) {
        val zahteva = zahtevajZajemZaslona
        if (zahteva == null) {
            napaka("ni_zajema", "Deljenje zaslona tu ni na voljo.")
            return
        }
        if (hubUrl().isBlank() || zeton() == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        DeljenjeZaslonaStoritev.naSpremembo = { javiZaslon() }
        dejavnost.runOnUiThread { zahteva(idNaprave, imeNaprave) }
    }

    /** Dovoljenje je dano: zazene storitev, ki deli zaslon, dokler je uporabnik ne prekine. */
    fun zajemDovoljen(resultCode: Int, data: android.content.Intent, idNaprave: String, imeNaprave: String) {
        DeljenjeZaslonaStoritev.naSpremembo = { javiZaslon() }
        DeljenjeZaslonaStoritev.zazeni(dejavnost, resultCode, data, idNaprave, imeNaprave, hubHttp(), zeton() ?: "", ime())
        deljenje("zaslon", "zaganjam", idNaprave, imeNaprave)
    }

    fun zajemZavrnjen(idNaprave: String) {
        deljenje("zaslon", "koncano", idNaprave, sporocilo = "dovoljenje ni bilo dano", koda = "dovoljenje_zavrnjeno")
    }

    @JavascriptInterface
    fun koncajDeljenjeZaslona() {
        // Stran je lahko nova (Link je bil vmes zaprt): poslusalca pripnemo znova, da izve za konec.
        DeljenjeZaslonaStoritev.naSpremembo = { javiZaslon() }
        DeljenjeZaslonaStoritev.ustavi(dejavnost)
    }

    /** Stanje deljenja zaslona za izris (tudi ce je bil zaslon Linka vmes zaprt). */
    @JavascriptInterface
    fun deljenjeZaslonaStanje(): String = JSONObject().apply {
        DeljenjeZaslonaStoritev.naSpremembo = { javiZaslon() }
        put("tece", DeljenjeZaslonaStoritev.tece)
        put("cilj", DeljenjeZaslonaStoritev.cilj)
        put("ime", DeljenjeZaslonaStoritev.imeCilja)
        put("napaka", DeljenjeZaslonaStoritev.zadnjaNapaka)
    }.toString()

    private fun javiZaslon() {
        val tece = DeljenjeZaslonaStoritev.tece
        deljenje("zaslon", if (tece) "tece" else "koncano", DeljenjeZaslonaStoritev.cilj,
            DeljenjeZaslonaStoritev.imeCilja, DeljenjeZaslonaStoritev.zadnjaNapaka,
            koda = DeljenjeZaslonaStoritev.zadnjaKoda, zasedenaOd = DeljenjeZaslonaStoritev.zadnjaZasedenaOd)
    }

    /** Poimenuje napravo (tudi to) za vse naprave v hisi; ime hrani Hub. Prazno ime vrne prvotnega. */
    @JavascriptInterface
    fun preimenujNapravo(idNaprave: String, ime: String) {
        if (hubUrl().isBlank() || zeton() == null) {
            napaka("hub_ni_znan", "Hub ni znan.")
            return
        }
        Thread {
            try {
                val telo = JSONObject().put("device_id", idNaprave).put("name", ime.trim()).toString()
                val (koda, odgovor) = httpJson("POST", "/cast/devices/rename", telo)
                if (koda == 200) {
                    val novo = try { JSONObject(odgovor).optString("name", "") } catch (_: Throwable) { "" }
                    odziv("preimenovano", JSONObject().put("id", idNaprave).put("ime", novo))
                } else napaka("preimenovanje_ni_uspelo", napakaHuba(koda, odgovor).sporocilo)
            } catch (e: Throwable) {
                napaka("preimenovanje_ni_uspelo", "Preimenovanje ni uspelo: ${e.message}")
            }
        }.start()
    }

    /** Druga naprava nam je nekaj poslala: besedilo, datoteko ali zaslon. */
    private fun prejmiDeljenje(sporocilo: JSONObject) {
        try {
            val tip = sporocilo.optString("type", "")
            val od = LinkVzdevki.ime(dejavnost, sporocilo.optString("sender", ""),
                sporocilo.optString("sender_name", "").ifBlank { sporocilo.optString("sender", "naprava") })
            val tovor = sporocilo.optJSONObject("payload") ?: JSONObject()
            when (tip) {
                "cast.url" -> {
                    // Stran s televizorja (ali druge naprave): odpremo jo v brskalniku.
                    val url = tovor.optString("url", "").trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        odziv("prejeto", JSONObject().put("vrsta", "stran").put("od", od).put("url", url).put("naslov", tovor.optString("title", "")))
                        dejavnost.runOnUiThread { zapriZaslon(); odpriVBrskalniku(url) }
                    }
                }
                "share.text" -> {
                    val besedilo = tovor.optString("text", "")
                    odziv("prejeto", JSONObject().put("vrsta", "besedilo").put("od", od).put("besedilo", besedilo))
                    pokaziBesedilo(od, besedilo)
                }
                "share.screen" -> {
                    val dejanje = tovor.optString("action", "")
                    if (dejanje == "start") {
                        val pot = tovor.optString("path", "")
                        val url = if (pot.startsWith("/")) hubHttp() + pot else tovor.optString("url", "")
                        if (url.isNotBlank()) dejavnost.runOnUiThread { zapriZaslon(); odpriVBrskalniku(url) }
                    }
                    odziv("prejeto", JSONObject().put("vrsta", "zaslon").put("od", od).put("dejanje", dejanje))
                }
                "share.file" -> {
                    val imeDat = tovor.optString("name", "datoteka")
                    val pot = tovor.optString("path", "")
                    val odtis = tovor.optString("sha256", "")
                    val zaGostitelja = tovor.optBoolean("for_host", false)
                    if (zaGostitelja || pot.isBlank()) {
                        val mapa = com.safeer.mobile.browser.PrenosiMapa.opis(dejavnost)
                        odziv("prejeto", JSONObject().put("vrsta", "datoteka").put("od", od).put("ime", imeDat).put("mapa", mapa))
                        obvesti(com.safeer.mobile.browser.I18n.t(dejavnost, "share_received_file").replace("{ime}", imeDat) + " (" + mapa + ")")
                    } else {
                        prevzemiDatoteko(hubHttp() + pot, imeDat, od, odtis)
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Prejetega deljenja ni bilo mogoce obdelati: ${e.message}")
        }
    }

    private fun obvesti(besedilo: String) {
        dejavnost.runOnUiThread {
            try { android.widget.Toast.makeText(dejavnost, besedilo, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
        }
    }

    private fun pokaziBesedilo(od: String, besedilo: String) {
        dejavnost.runOnUiThread {
            try {
                val cisto = besedilo.trim()
                val jePovezava = (cisto.startsWith("http://") || cisto.startsWith("https://")) && !cisto.contains(Regex("\\s"))
                val okno = android.app.AlertDialog.Builder(dejavnost)
                    .setTitle("💬 " + com.safeer.mobile.browser.I18n.t(dejavnost, "share_received_text").replace("{ime}", od))
                    .setMessage(besedilo.take(4000))
                    .setNegativeButton(android.R.string.ok, null)
                    .setNeutralButton(com.safeer.mobile.browser.I18n.t(dejavnost, "share_copy")) { _, _ ->
                        try {
                            val odlozisce = dejavnost.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            odlozisce.setPrimaryClip(android.content.ClipData.newPlainText("Safeer Link", besedilo))
                            obvesti(com.safeer.mobile.browser.I18n.t(dejavnost, "share_copied"))
                        } catch (_: Throwable) { }
                    }
                if (jePovezava) {
                    okno.setPositiveButton(com.safeer.mobile.browser.I18n.t(dejavnost, "share_open_link")) { _, _ ->
                        zapriZaslon(); odpriVBrskalniku(cisto)
                    }
                }
                okno.show()
            } catch (e: Throwable) {
                obvesti("💬 $od: " + besedilo.take(200))
            }
        }
    }

    /** Datoteko, ki caka na Hubu, prenesemo v mapo prenosov; ime ostane, ob trku dobi stevilko. */
    private fun prevzemiDatoteko(url: String, imeDat: String, od: String, pricakovanOdtis: String) {
        Thread {
            var zaBrisanje: java.io.File? = null
            try {
                val mapa = com.safeer.mobile.browser.cast.HubKrmilnik.mapaZaPrejete(dejavnost)
                val cilj = com.safeer.mobile.browser.cast.HubTokovi.enolicnaPot(mapa, com.safeer.mobile.browser.cast.HubTokovi.varnoIme(imeDat))
                zaBrisanje = cilj
                val povezava = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    com.safeer.mobile.browser.cast.HubTls.zavaruj(povezava, dejavnost)
                    povezava.connectTimeout = 5000
                    povezava.readTimeout = 30000
                    if (povezava.responseCode != 200) throw java.io.IOException("Hub je odgovoril ${povezava.responseCode}")
                    val prstni = java.security.MessageDigest.getInstance("SHA-256")
                    povezava.inputStream.use { vhod ->
                        java.io.FileOutputStream(cilj).use { izhod ->
                            val kos = ByteArray(64 * 1024)
                            while (true) {
                                val n = vhod.read(kos)
                                if (n < 0) break
                                izhod.write(kos, 0, n)
                                prstni.update(kos, 0, n)
                            }
                        }
                    }
                    val odtis = prstni.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                    val pricakovan = pricakovanOdtis.ifBlank { povezava.getHeaderField("x-safeer-sha256") ?: "" }
                    if (pricakovan.isNotBlank() && pricakovan != odtis) throw java.io.IOException("prstni odtis se ne ujema")
                } finally {
                    povezava.disconnect()
                }
                val opisMape = com.safeer.mobile.browser.PrenosiMapa.opis(dejavnost)
                odziv("prejeto", JSONObject().put("vrsta", "datoteka").put("od", od).put("ime", cilj.name).put("mapa", opisMape))
                obvesti(com.safeer.mobile.browser.I18n.t(dejavnost, "share_received_file").replace("{ime}", cilj.name) + " (" + opisMape + ")")
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "Datoteke $imeDat ni bilo mogoce prevzeti: ${e.message}")
                try { zaBrisanje?.delete() } catch (_: Throwable) { }
                obvesti(com.safeer.mobile.browser.I18n.t(dejavnost, "share_file_failed").replace("{ime}", imeDat))
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Okno
    // ------------------------------------------------------------------

    /** Zapre Safeer Link in prekine povezavo -- brez odprte povezave ni prometa. */
    @JavascriptInterface
    fun zapri() {
        try { odjemalec?.disconnect() } catch (_: Throwable) {}
        odjemalec = null
        dejavnost.runOnUiThread { zapriZaslon() }
    }

    /** Odpre naslov v navadnem zavihku (npr. konzolo Safeer Controla). */
    @JavascriptInterface
    fun odpri(url: String) {
        val cist = url.trim()
        if (!cist.startsWith("http://") && !cist.startsWith("https://")) return
        dejavnost.runOnUiThread {
            zapriZaslon()
            odpriVBrskalniku(cist)
        }
    }

    // ------------------------------------------------------------------
    // Sinhronizacija zaznamkov
    // ------------------------------------------------------------------

    /** Stanje sinhronizacije za izris; brez omrezja. */
    @JavascriptInterface
    fun sinhronizacijaStanje(): String {
        return try {
            val repo = com.safeer.mobile.browser.BrowserRepository(dejavnost)
            JSONObject().apply {
                put("zaznamki", JSONObject().apply {
                    put("vklopljena", ZaznamkiSync.jeVklopljena(dejavnost))
                    put("stevilo", ZaznamkiSync.stevilo(repo))
                })
            }.toString()
        } catch (e: Throwable) {
            "{\"zaznamki\":{\"vklopljena\":false,\"stevilo\":0}}"
        }
    }

    /**
     * Vklopi ali izklopi sinhronizacijo zaznamkov.
     *
     * Ob vklopu naprava najprej vprasa Hub, kaj ze hrani, in sele nato poslje svoje --
     * tako se stanji zdruzita in nihce ne izgubi zaznamka. Ker se zmoznost "sync"
     * prijavi ob povezavi, se moramo povezati znova.
     */
    @JavascriptInterface
    fun nastaviSinhronizacijo(vklopljena: Boolean) {
        try {
            ZaznamkiSync.nastavi(dejavnost, vklopljena)
            try { odjemalec?.disconnect() } catch (_: Throwable) {}
            odjemalec = null
            if (!vklopljena) {
                odziv("sinhronizacija", JSONObject().apply {
                    put("kategorija", ZaznamkiSync.KATEGORIJA)
                    put("vklopljena", false)
                    put("dodanih", 0)
                })
                poveziSe()
                return
            }
            val o = odjemalec() ?: run { napaka("hub_ni_znan", "Hub ni znan."); return }
            o.connect()
            // Povezava potrebuje trenutek; sele nato ima smisel karkoli poslati.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    o.zahtevajSinhronizacijo(ZaznamkiSync.KATEGORIJA)
                    Thread {
                        try {
                            val repo = com.safeer.mobile.browser.BrowserRepository(dejavnost)
                            val vsebina = ZaznamkiSync.izvozi(repo)
                            val nova = ZaznamkiSync.razlicica(dejavnost) + 1
                            ZaznamkiSync.shraniRazlicico(dejavnost, nova)
                            o.posljiSinhronizacijo(ZaznamkiSync.KATEGORIJA, nova, vsebina)
                            odziv("sinhronizacija", JSONObject().apply {
                                put("kategorija", ZaznamkiSync.KATEGORIJA)
                                put("vklopljena", true)
                                put("dodanih", 0)
                                put("skupaj", ZaznamkiSync.stevilo(repo))
                            })
                        } catch (e: Throwable) {
                            napaka("zaznamki_niso_poslani", "Zaznamkov ni bilo mogoce poslati: ${e.message}")
                        }
                    }.start()
                } catch (e: Throwable) {
                    napaka("sync_ni_stekla", "Sinhronizacije ni bilo mogoce zaceti: ${e.message}")
                }
            }, 1500L)
        } catch (e: Throwable) {
            napaka("sync_ni_nastavljena", "Sinhronizacije ni bilo mogoce nastaviti: ${e.message}")
        }
    }

    /** Ali tece na televizorju. Televizor je zaslon in nima komu posiljati. */
    @JavascriptInterface
    fun jeTelevizor(): Boolean = false

    /** Naslov konzole Safeer Controla, izpeljan iz naslova Huba. */

    // ------------------------------------------------------------------
    // Ta telefon kot sredisce (Hub)
    //
    // Doslej je znal gostiti samo televizor. Ce ga ni bilo prizganega, telefon ni imel kam
    // poslati - niti na racunalnik. Zdaj lahko gosti tudi telefon: takrat se druge naprave
    // povezejo nanj. Koda gostitelja je ista kot na televizorju.
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun hubStanje(): String {
        pripniPoslusalce()
        return try {
            com.safeer.mobile.browser.cast.HubKrmilnik.stanjeJson(dejavnost)
        } catch (e: Throwable) {
            "{\"tece\":false,\"zazelen\":false}"
        }
    }

    @JavascriptInterface
    fun hubVklopi() {
        try {
            val uspelo = com.safeer.mobile.browser.cast.HubStoritev.vklopi(dejavnost)
            pripniPoslusalce()
            if (!uspelo) napaka("hub_ni_zagnan", "Huba ni bilo mogoce zagnati.")
            odziv("hub-tu", JSONObject(hubStanje()))
            // Gostitelj je hkrati naprava: povezemo se na lastni Hub (nastavitve je prepisal
            // HubKrmilnik), da stran vidi ostale naprave in lahko posilja.
            if (uspelo) prevezi()
        } catch (e: Throwable) {
            napaka("hub_ni_zagnan", "Huba ni bilo mogoce zagnati: ${e.message}")
        }
    }

    @JavascriptInterface
    fun hubIzklopi() {
        try {
            com.safeer.mobile.browser.cast.HubStoritev.izklopi(dejavnost)
            odziv("hub-tu", JSONObject(hubStanje()))
            // Nazaj na prejsnji Hub (ce je bil), sicer na "ni seznanjen".
            prevezi()
        } catch (e: Throwable) {
            napaka("hub_ni_ustavljen", "Huba ni bilo mogoce ustaviti: ${e.message}")
        }
    }

    /** Naprave, ki cakajo na potrditev: ime in sestmestna koda, ki jo kazejo na svojem zaslonu. */
    @JavascriptInterface
    fun hubPrijave(): String = try {
        val u = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik
        JSONArray().apply {
            u?.cakajocePrijave()?.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.pairId)
                    put("ime", p.ime)
                    put("koda", p.pin)
                    put("naslov", p.naslov)
                    put("starost", p.starostSekund)
                })
            }
        }.toString()
    } catch (e: Throwable) {
        "[]"
    }

    @JavascriptInterface
    fun hubPotrdi(idPrijave: String) {
        val u = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik
        if (u == null) {
            napaka("hub_ne_tece", "Hub ne tece.")
            return
        }
        if (!u.potrdiPrijavo(idPrijave)) {
            napaka("prijava_potekla", "Prijave ni vec ali pa je poteklo.")
        }
        odziv("hub-prijave", JSONArray(hubPrijave()))
    }

    @JavascriptInterface
    fun hubZavrni(idPrijave: String) {
        com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik?.zavrniPrijavo(idPrijave)
        odziv("hub-prijave", JSONArray(hubPrijave()))
    }

    /** Naprave, ki jim je uporabnik ze dovolil. */
    @JavascriptInterface
    fun hubSeznanjene(): String = try {
        val u = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik
        JSONArray().apply {
            u?.seznanjeneNaprave()?.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.deviceId)
                    put("ime", n.ime)
                    put("od", n.seznanjenaOb)
                })
            }
        }.toString()
    } catch (e: Throwable) {
        "[]"
    }

    /** Odvzame dostop napravi in jo, ce je povezana, odklopi. */
    // ------------------------------------------------------------------ krajevna imena naprav

    /** Imena, ki jih je uporabnik te naprave dal drugim napravam (JSON {id: ime}); ostanejo na tej napravi. */
    @JavascriptInterface
    fun vzdevki(): String = nastavitve().getString("link_vzdevki", "{}") ?: "{}"

    /** Prazno ime vzdevek odstrani (naprava se spet kaze s svojim imenom). */
    @JavascriptInterface
    fun shraniVzdevek(idNaprave: String, ime: String) {
        try {
            val vsi = JSONObject(nastavitve().getString("link_vzdevki", "{}") ?: "{}")
            val cisto = ime.trim().take(64)
            if (cisto.isEmpty()) vsi.remove(idNaprave) else vsi.put(idNaprave, cisto)
            nastavitve().edit().putString("link_vzdevki", vsi.toString()).apply()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Vzdevka ni bilo mogoce shraniti: ${e.message}")
        }
    }

    @JavascriptInterface
    fun hubPreklici(idNaprave: String) {
        val u = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik
        if (u == null) {
            napaka("hub_ne_tece", "Hub ne tece.")
            return
        }
        u.prekliciNapravo(idNaprave)
        odziv("hub-seznanjene", JSONArray(hubSeznanjene()))
    }

    /** Zapre trenutno povezavo in odpre novo po (spremenjenih) nastavitvah Huba. */
    private fun prevezi() {
        try { odjemalec?.disconnect() } catch (_: Throwable) {}
        odjemalec = null
        // Stran ob odzivu "stanje" znova prebere stanje() in se sama poveze (poveziSe).
        odziv("stanje", JSONObject(stanje()))
    }

    /** Usmerjevalnik javi spremembe strani, da se nova prijava pokaze takoj. */
    private fun pripniPoslusalce() {
        val u = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik ?: return
        com.safeer.mobile.browser.cast.HubKrmilnik.naSpremembePrijav = { odziv("hub-prijave", JSONArray(hubPrijave())) }
        u.naSpremembeNaprav = {
            odziv("hub-tu", JSONObject(com.safeer.mobile.browser.cast.HubKrmilnik.stanjeJson(dejavnost)))
        }
    }

    /** Ob zaprtju zaslona pospravi povezavo. */
    fun pospravi() {
        try { odjemalec?.disconnect() } catch (_: Throwable) {}
        odjemalec = null
        try { govor?.ustavi() } catch (_: Throwable) {}
        govor = null
        // Stran se zapira: sprejem v ozadju spet prevzame povezavo (ce je telefon seznanjen).
        try { LinkSprejemnik.nadaljuj(dejavnost) } catch (_: Throwable) {}
        DeljenjeZaslonaStoritev.naSpremembo = null
        PrenosDatotekeStoritev.naNapredek = null
        // Hub namenoma tece naprej, ce ga je uporabnik prizgal: telefon je takrat sredisce
        // za druge naprave tudi, ko ta zaslon ni odprt. Odklopimo samo poslusalca.
        try {
            val u = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik
            com.safeer.mobile.browser.cast.HubKrmilnik.naSpremembePrijav = null
            u?.naSpremembeNaprav = null
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Poslusalcev ni bilo mogoce odkljuciti: ${e.message}")
        }
    }
}
