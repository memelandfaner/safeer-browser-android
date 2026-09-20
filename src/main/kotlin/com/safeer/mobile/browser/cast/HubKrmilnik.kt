package com.safeer.mobile.browser.cast

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Vklop in izklop Safeer Huba na telefonu.
 *
 * Doslej je znal biti gostitelj samo televizor. To je pomenilo, da brez prizganega
 * televizorja ni bilo mogoce poslati nicesar nikamor - tudi s telefona na racunalnik ne.
 * Zdaj zna gostiti vsaka naprava: ce Huba v omrezju ni, ga zazene tista, ki hoce poslati.
 *
 * Koda gostitelja (HubStreznik, HubUsmerjevalnik, HubTokovi, HubObjava, JsonLahki) je ista
 * kot na televizorju; tu je le krmilnik, ki ve, kako se naprava imenuje in kje hrani nastavitve.
 *
 * Na telefonu Hub tece v HubStoritev (storitev v ospredju), da ga Android ne ubije takoj,
 * ko uporabnik zapusti brskalnik. Ugasne ga uporabnik v Safeer Linku.
 */
object HubKrmilnik {

    private const val TAG = "SafeerHubKrmilnik"
    const val PREFS = "safeer_cast_prefs"
    const val KLJUC_VKLOPLJEN = "hub_vklopljen"

    @Volatile
    private var streznik: HubStreznik? = null

    /** Spletni odjemalec (naprava brez Safeerja): goli HTTP na svojih vratih, samo domace omrezje. */
    @Volatile
    private var spletniStreznik: HubStreznik? = null

    @Volatile
    var usmerjevalnik: HubUsmerjevalnik? = null
        private set

    /** Stran Safeer Linka (ce je odprta) izve za nove ali potrjene prijave. */
    @Volatile
    var naSpremembePrijav: (() -> Unit)? = null

    /**
     * Zaslon (MainActivity) pokaze kodo za seznanitev tudi takrat, ko stran Linka ni odprta -
     * naprava, ki se povezuje, kodo potrebuje TAKOJ, uporabnik pa je morda sredi filma.
     */
    @Volatile
    var naPrijavoZaZaslon: (() -> Unit)? = null

    /** Storitev v ozadju osvezi obvestilo s kodo (telefon, ko brskalnik ni v ospredju). */
    @Volatile
    var naPrijavoZaObvestilo: (() -> Unit)? = null

    @Volatile
    var tokovi: HubTokovi? = null
        private set

    fun tece(): Boolean = streznik?.teceZdaj() == true

    fun vrata(): Int = streznik?.vrata ?: 0

    /** Vrata spletnega odjemalca (0, ce ne tece). */
    fun vrataSplet(): Int = spletniStreznik?.vrata ?: 0

    /** Ali je uporabnik Hub prizgal (tudi ce trenutno ne tece, npr. pred zagonom brskalnika). */
    fun jeZazelen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_VKLOPLJEN, false)

    private fun zapomniZeljo(context: Context, vklopljen: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KLJUC_VKLOPLJEN, vklopljen).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Nastavitve ni bilo mogoce zapisati: ${e.message}")
        }
    }

    /** Ob zagonu brskalnika: Hub stece samo, ce ga je uporabnik ze prej prizgal. */
    fun samodejniZagon(context: Context) {
        if (jeZazelen(context) && !tece()) zazeni(context, zapomni = false)
    }

    /**
     * Zazene Hub: streznik, usmerjevalnik in objavo v omrezju. Vrne true, ce tece.
     */
    @Synchronized
    fun zazeni(context: Context, zapomni: Boolean = true): Boolean {
        val app = context.applicationContext
        if (tece()) {
            if (zapomni) zapomniZeljo(app, true)
            return true
        }

        val u = HubUsmerjevalnik(NastavitveShramba(app))
        // TLS: kljuc Huba iz Android KeyStore; odtis potrdila je vpleten v seznanjanje.
        val tls = try { HubTls.streznik() } catch (e: Throwable) {
            Log.w(TAG, "TLS Huba ni bilo mogoce pripraviti: ${e.message}")
            return false
        }
        u.lastniOdtis = HubTls.lastniOdtis()
        // Hub je prvi clan kroga zaupanja: njegov kljuc je kljuc potrdila TLS.
        try { u.vpisiLastniKljuc(lastniId(), imeHuba(), HubTls.javniKljucB64(), "phone") } catch (e: Throwable) {
            Log.w(TAG, "Kljuca huba ni bilo mogoce vpisati v krog: ${e.message}")
        }
        u.naSpremembePrijav = {
            try { naSpremembePrijav?.invoke() } catch (_: Throwable) { }
            try { naPrijavoZaZaslon?.invoke() } catch (_: Throwable) { }
            try { naPrijavoZaObvestilo?.invoke() } catch (_: Throwable) { }
        }
        // Vsebina (zaslon, datoteke) gre mimo usmerjevalnika, po loceni zahtevi HTTP;
        // usmerjevalnik le pove ciljni napravi, kje jo dobi.
        val t = HubTokovi(
            mapaPrenosov = { mapaZaPrejete(app) },
            mapaZacasna = { File(app.cacheDir, "safeer-link").apply { mkdirs() } },
            jeVeljavenZeton = { zeton -> u.jeVeljavenZeton(zeton) },
            lastniId = { lastniId() },
            naPrejetoDatoteko = { ime, pot -> Log.i(TAG, "Prejeta datoteka $ime -> ${pot.parent}") }
        )
        u.tokovi = t
        val s = HubStreznik(
            naZahtevo = { zahteva -> u.odgovori(zahteva) },
            preveriVstopnico = { zahteva -> u.preveriVstopnico(zahteva) },
            naPovezavo = { povezava -> povezi(u, povezava) },
            naTok = { zahteva, vhod, izhod, vticnica -> t.obdelaj(zahteva, vhod, izhod, vticnica) },
            tlsTovarna = tls
        )
        if (!s.zazeni()) {
            Log.w(TAG, "Huba ni bilo mogoce zagnati.")
            return false
        }
        streznik = s
        usmerjevalnik = u
        tokovi = t

        // Spletni odjemalec: ista logika huba, goli HTTP na svojih vratih (brskalnik na telefonu brez
        // Safeerja ne sprejme nasega samopodpisanega potrdila); samo krajevno omrezje in ozek izbor poti.
        u.beriSredstvo = { ime ->
            try { app.assets.open("link-web/$ime").bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Throwable) { null }
        }
        val w = HubStreznik(
            zeljenaVrata = HubUsmerjevalnik.SPLETNA_VRATA,
            naZahtevo = { zahteva -> u.odgovoriSplet(zahteva) },
            preveriVstopnico = { zahteva -> u.preveriVstopnico(zahteva) },
            naPovezavo = { povezava -> povezi(u, povezava) },
            tlsTovarna = null
        )
        if (w.zazeni()) { spletniStreznik = w; u.spletnaVrata = w.vrata } else Log.w(TAG, "Spletnih vrat ni bilo mogoce odpreti; spletni odjemalec ni na voljo.")

        HubObjava.objavi(app, s.vrata, imeHuba(), IzvolitevHuba.privzetaPrioriteta("phone"), lastniId()) { uspelo ->
            if (!uspelo) {
                // Brez oglasa Hub se vedno dela; naprava, ki ga je ze videla, pozna naslov.
                Log.i(TAG, "Hub tece, oglas v omrezju pa ni uspel.")
            }
        }
        if (zapomni) zapomniZeljo(app, true)
        // Telefon, ki gosti, je hkrati navadna naprava: prikljuci se na lastni Hub, da ga
        // druge naprave vidijo in mu lahko posljejo, on pa njim. Brez tega bi bil samo posrednik.
        poveziLastnoNapravo(app, u, s.vrata)
        Log.i(TAG, "Safeer Hub na telefonu tece na ${naslov()}")
        return true
    }

    private const val LASTNI_NASLOV_PREDPONA = "wss://127.0.0.1:"
    private const val KLJUC_PREJSNJI_HUB = "prejsnji_hub_url"
    private const val KLJUC_PREJSNJI_ZETON = "prejsnji_control_token"
    private const val KLJUC_PREJSNJI_ODTIS = "prejsnji_hub_fp"
    private const val KLJUC_PREJSNJA_VSTOPNICA = "prejsnji_hub_ticket_path"

    /**
     * Telefon se prijavi lastnemu Hubu z zetonom, ki ga Hub izda sam sebi. Prejsnjo seznanitev
     * (npr. s televizorjem) shranimo in jo ob izklopu Huba vrnemo - uporabnik se ne sme znova
     * seznanjati samo zato, ker je vmes gostil.
     */
    private fun poveziLastnoNapravo(app: Context, u: HubUsmerjevalnik, vrata: Int) {
        try {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val trenutni = prefs.getString("hub_url", "") ?: ""
            val ur = prefs.edit()
            if (trenutni.isNotBlank() && !trenutni.startsWith(LASTNI_NASLOV_PREDPONA)) {
                ur.putString(KLJUC_PREJSNJI_HUB, trenutni)
                    .putString(KLJUC_PREJSNJI_ZETON, prefs.getString("control_token", "") ?: "")
                    .putString(KLJUC_PREJSNJI_ODTIS, prefs.getString(HubTls.KEY_HUB_FP, "") ?: "")
                    .putString(KLJUC_PREJSNJA_VSTOPNICA, prefs.getString("hub_ticket_path", "") ?: "")
            }
            val zeton = u.zagotoviLastniZeton(lastniId(), imeHuba())
            ur.putString("hub_url", LASTNI_NASLOV_PREDPONA + vrata + "/cast/ws")
                .putString("control_token", zeton)
                .putString("hub_ticket_path", "/cast/ticket")
                // Lastnemu Hubu zaupamo po istem pravilu kot vsakemu drugemu: po odtisu.
                .putString(HubTls.KEY_HUB_FP, HubTls.lastniOdtis())
                .apply()
            Log.i(TAG, "Telefon je prijavljen na lastni Hub kot naprava.")
        } catch (e: Throwable) {
            Log.w(TAG, "Lastne naprave ni bilo mogoce prijaviti: ${e.message}")
        }
    }

    /** Ob izklopu Huba vrnemo prejsnjo seznanitev (ce je bila) ali pocistimo, kar je kazalo nase. */
    private fun odklopiLastnoNapravo(app: Context) {
        try {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val naslov = prefs.getString("hub_url", "") ?: ""
            if (!naslov.startsWith(LASTNI_NASLOV_PREDPONA)) return
            val prejsnji = prefs.getString(KLJUC_PREJSNJI_HUB, "") ?: ""
            val ur = prefs.edit().remove("hub_url").remove("control_token").remove("hub_ticket_path").remove(HubTls.KEY_HUB_FP)
            if (prejsnji.isNotBlank()) {
                ur.putString("hub_url", prejsnji)
                val z = prefs.getString(KLJUC_PREJSNJI_ZETON, "") ?: ""
                val o = prefs.getString(KLJUC_PREJSNJI_ODTIS, "") ?: ""
                val v = prefs.getString(KLJUC_PREJSNJA_VSTOPNICA, "") ?: ""
                if (z.isNotBlank()) ur.putString("control_token", z)
                if (o.isNotBlank()) ur.putString(HubTls.KEY_HUB_FP, o)
                if (v.isNotBlank()) ur.putString("hub_ticket_path", v)
            }
            ur.remove(KLJUC_PREJSNJI_HUB).remove(KLJUC_PREJSNJI_ZETON).remove(KLJUC_PREJSNJI_ODTIS).remove(KLJUC_PREJSNJA_VSTOPNICA).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Lastne naprave ni bilo mogoce odklopiti: ${e.message}")
        }
    }

    /** Ugasne Hub. `zapomni` naj bo true samo, kadar je tako odlocil uporabnik. */
    @Synchronized
    fun ustavi(context: Context?, zapomni: Boolean = true) {
        HubObjava.umakni()
        streznik?.ustavi()
        streznik = null
        spletniStreznik?.ustavi()
        spletniStreznik = null
        usmerjevalnik = null
        tokovi = null
        if (zapomni && context != null) zapomniZeljo(context.applicationContext, false)
        if (context != null) odklopiLastnoNapravo(context.applicationContext)
        Log.i(TAG, "Safeer Hub ustavljen.")
    }

    /**
     * Id te naprave: iz njenega kljuca (`n-…`, KrogNaprave.lastniId) - isti na vseh hubih. Isti id uporabi
     * telefon kot posiljatelj (LinkMost/LinkSprejemnik) in kot hub (krog, oglas mDNS). Stari `phone-<model>`
     * ostane v krogih kot alias; hub ga ob prvi prijavi s podpisom sam poveze z novim.
     */
    fun lastniId(): String = KrogNaprave.lastniId(nadomestni = { stariId() })

    /** Id po modelu naprave, kot je veljal pred prehodom na id iz kljuca (nadomestek in alias). */
    fun stariId(): String = "phone-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase()

    /**
     * Mapa za datoteke, ki jih telefon prejme prek Safeer Linka: ista, kot jo je uporabnik
     * izbral za prenose. Ce vanjo ni mogoce pisati (novejsi Android brez dovoljenja), gre v
     * mapo aplikacije za prenose, ki je vedno na voljo.
     */
    fun mapaZaPrejete(app: Context): File {
        val izbrana = try { com.safeer.mobile.browser.PrenosiMapa.ciljnaMapa(app) } catch (_: Throwable) { null }
        if (izbrana != null && (izbrana.isDirectory || izbrana.mkdirs()) && izbrana.canWrite()) return izbrana
        val nadomestna = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "prenosi")
        nadomestna.mkdirs()
        return nadomestna
    }

    private fun povezi(u: HubUsmerjevalnik, povezava: HubStreznik.Povezava) {
        val odjemalec = object : HubUsmerjevalnik.Odjemalec {
            override val naslov: String = povezava.naslov
            override val vstopnica: String? = povezava.zahteva.poizvedba["ticket"]
            override fun poslji(besedilo: String) = povezava.poslji(besedilo)
            override fun zapri(koda: Int, razlog: String) = povezava.zapri(koda, razlog)
        }
        povezava.naSporocilo = { sporocilo -> u.obdelaj(odjemalec, sporocilo) }
        povezava.naZaprtje = { u.odklopi(odjemalec) }
    }

    private fun imeHuba(): String = "Safeer telefon (" + android.os.Build.MODEL + ")"

    /** Naslov, na katerem je Hub dosegljiv; prazen, ce ne tece ali ce ni omrezja. */
    fun naslov(): String {
        val vrata = vrata()
        if (vrata == 0) return ""
        val ip = krajevniNaslov() ?: return ""
        return "https://$ip:$vrata"
    }

    /**
     * Prvi krajevni naslov IPv4 te naprave. Televizor ima navadno enega samega, a na
     * napravah z vec vmesniki izberemo tistega, ki je res v hisnem omrezju.
     */
    fun krajevniNaslov(): String? = try {
        val vmesniki = NetworkInterface.getNetworkInterfaces()
        var najdeno: String? = null
        while (vmesniki.hasMoreElements() && najdeno == null) {
            val vmesnik = vmesniki.nextElement()
            if (!vmesnik.isUp || vmesnik.isLoopback) continue
            val naslovi = vmesnik.inetAddresses
            while (naslovi.hasMoreElements()) {
                val naslov = naslovi.nextElement()
                if (naslov is Inet4Address && naslov.isSiteLocalAddress) {
                    najdeno = naslov.hostAddress
                    break
                }
            }
        }
        najdeno
    } catch (e: Exception) {
        Log.w(TAG, "Naslova ni bilo mogoce ugotoviti: ${e.message}")
        null
    }

    /** Stanje za stran Safeer Linka. */
    fun stanjeJson(context: Context): String {
        val u = usmerjevalnik
        return JsonLahki.Zapis()
            .logicno("tece", tece())
            .logicno("zazelen", jeZazelen(context))
            .niz("naslov", naslov())
            .niz("ime", if (HubObjava.objavljenoIme.isNotBlank()) HubObjava.objavljenoIme else imeHuba())
            .logicno("objavljen", HubObjava.jeObjavljen())
            .stevilo("naprav", (u?.steviloNaprav() ?: 0).toDouble())
            .stevilo("cakajocih", (u?.cakajocePrijave()?.size ?: 0).toDouble())
            .stevilo("seznanjenih", (u?.seznanjeneNaprave()?.size ?: 0).toDouble())
            .toString()
    }

    /** Zetoni seznanjenih naprav v zasebnih nastavitvah brskalnika. */
    private class NastavitveShramba(private val context: Context) : HubUsmerjevalnik.Shramba {
        override fun beri(kljuc: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kljuc, null)

        override fun pisi(kljuc: String, vrednost: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(kljuc, vrednost).apply()
        }
    }
}
