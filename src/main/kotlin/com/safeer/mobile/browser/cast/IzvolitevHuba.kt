package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

/**
 * Izvolitev huba: kdo v hisi gosti Safeer Link.
 *
 * Najpreprostejse pravilo, ki ga vsi izracunajo enako brez pogovora: vsaka naprava v oglasu mDNS
 * pove svojo prioriteto (`prio`) in id (`id`). Hub je naprava z najvisjo prioriteto; pri enaki odloci
 * leksikografsko manjsi `device_id`. Ce se po izpadu omrezja pojavita dva huba, ostane boljsi, slabsi
 * se umakne in se poveze kot odjemalec. Kandidat steje samo, ce je clan kroga zaupanja - tuj oglas
 * nima glasu.
 *
 * Privzete prioritete po platformi (uporabnik jih lahko spremeni): streznik brez GUI 100, Linux
 * racunalnik 80, TV 60, tablica 40, telefon 20. Hub dela samo odkrivanje, katalog naprav in dogovor
 * o seji; mediji tecejo neposredno, zato seja prezivi menjavo huba.
 *
 * Razred ne pozna Androida in tece tudi v preizkusih na JVM.
 */
object IzvolitevHuba {

    /** Kljuca v oglasu mDNS (TXT). */
    const val TXT_PRIORITETA = "prio"
    const val TXT_ID = "id"

    const val PRIORITETA_STREZNIK = 100
    const val PRIORITETA_LINUX = 80
    const val PRIORITETA_TV = 60
    const val PRIORITETA_TABLICA = 40
    const val PRIORITETA_TELEFON = 20

    /** Privzeta prioriteta po platformi, kot je zapisana v krogu zaupanja (tv, tablet, phone, linux, server). */
    fun privzetaPrioriteta(platforma: String): Int = when (platforma.lowercase()) {
        "server", "core", "streznik" -> PRIORITETA_STREZNIK
        "linux", "pc", "windows" -> PRIORITETA_LINUX
        "tv" -> PRIORITETA_TV
        "tablet", "tablica" -> PRIORITETA_TABLICA
        "phone", "telefon" -> PRIORITETA_TELEFON
        else -> PRIORITETA_TELEFON
    }

    /** Hub, ki se oglasa (ali mi sami). */
    data class Kandidat(val id: String, val prioriteta: Int, val naslov: String = "", val odtis: String = "", val ime: String = "")

    /** Ali je [a] pred [b]: visja prioriteta, pri enaki manjsi id. Enaka id-ja: nihce ni pred drugim. */
    fun jePred(a: Kandidat, b: Kandidat): Boolean {
        if (a.id == b.id) return false
        if (a.prioriteta != b.prioriteta) return a.prioriteta > b.prioriteta
        return a.id < b.id
    }

    /** Najboljsi med kandidati (ali null, ce jih ni). Isti vhod da isti izid na vsaki napravi. */
    fun najboljsi(kandidati: Collection<Kandidat>): Kandidat? {
        var naj: Kandidat? = null
        for (k in kandidati) if (naj == null || jePred(k, naj)) naj = k
        return naj
    }

    /**
     * Odlocitev za napravo [jaz] ob videnih hubih [videni] (samo clani kroga; klicatelj jih prefiltrira).
     * Vrne null, ce naj gostim sam (nihce ni pred mano), sicer hub, ki se mu umaknem in se nanj povezem.
     */
    fun komuSeUmaknem(jaz: Kandidat, videni: Collection<Kandidat>): Kandidat? {
        val naj = najboljsi(videni.filter { it.id != jaz.id }) ?: return null
        return if (jePred(naj, jaz)) naj else null
    }

    /** Vrednost `prio` iz oglasa; oglas brez nje (starejsi hub) steje kot najnizja prioriteta. */
    fun prioritetaIzOglasa(vrednost: String?): Int = vrednost?.trim()?.toIntOrNull()?.coerceIn(0, 1000) ?: 0
}
