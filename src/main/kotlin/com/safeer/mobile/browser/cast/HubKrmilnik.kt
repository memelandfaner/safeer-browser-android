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

    @Volatile
    var usmerjevalnik: HubUsmerjevalnik? = null
        private set

    @Volatile
    var tokovi: HubTokovi? = null
        private set

    fun tece(): Boolean = streznik?.teceZdaj() == true

    fun vrata(): Int = streznik?.vrata ?: 0

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

        HubObjava.objavi(app, s.vrata, imeHuba()) { uspelo ->
            if (!uspelo) {
                // Brez oglasa Hub se vedno dela; naprava, ki ga je ze videla, pozna naslov.
                Log.i(TAG, "Hub tece, oglas v omrezju pa ni uspel.")
            }
        }
        if (zapomni) zapomniZeljo(app, true)
        Log.i(TAG, "Safeer Hub na telefonu tece na ${naslov()}")
        return true
    }

    /** Ugasne Hub. `zapomni` naj bo true samo, kadar je tako odlocil uporabnik. */
    @Synchronized
    fun ustavi(context: Context?, zapomni: Boolean = true) {
        HubObjava.umakni()
        streznik?.ustavi()
        streznik = null
        usmerjevalnik = null
        tokovi = null
        if (zapomni && context != null) zapomniZeljo(context.applicationContext, false)
        Log.i(TAG, "Safeer Hub ustavljen.")
    }

    /** Isti id, s katerim se ta telefon prijavlja Hubu (LinkMost.ime()). */
    fun lastniId(): String = "phone-" + android.os.Build.MODEL.replace(Regex("\\s+"), "-").lowercase()

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
