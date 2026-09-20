package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import android.content.Context

/**
 * Krog zaupanja, kot ga hrani TA naprava (odjemalec). Isti zapis kot pri hubu, v zasebnih
 * nastavitvah aplikacije; ko hub ugasne in ga zamenja drug clan kroga, ima naprava vse, kar
 * potrebuje, da ga prepozna in se mu prijavi s podpisom.
 *
 * Kljuc naprave je kljuc iz AndroidKeyStore (HubTls): isti, s katerim bi ta naprava, ce bi bila
 * hub, podpisala svoje potrdilo TLS.
 */
object KrogNaprave {
    private const val PREFS = "safeer_cast_prefs"

    private class Shramba(private val context: Context) : HubUsmerjevalnik.Shramba {
        override fun beri(kljuc: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kljuc, null)
        override fun pisi(kljuc: String, vrednost: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kljuc, vrednost).apply()
        }
    }

    @Volatile private var krog: KrogZaupanja? = null

    @Synchronized
    fun krog(context: Context): KrogZaupanja =
        krog ?: KrogZaupanja(Shramba(context.applicationContext)).also { krog = it }

    /** Ali je ta naprava (s tem id) v krogu s SVOJIM trenutnim kljucem. */
    fun jeVpisana(context: Context, id: String): Boolean {
        val clan = krog(context).clan(id) ?: return false
        return try { clan.kljuc == HubTls.javniKljucB64() } catch (_: Throwable) { false }
    }

    /**
     * Ali se naprava s tem [id] lahko prijavi s podpisom: id je v krogu z nasim kljucem, ali pa je nas kljuc
     * v krogu pod drugim id-jem (stari id pred prehodom na id iz kljuca, sorodnik) - hub tak podpis sprejme
     * in nov id vpise kot alias.
     */
    fun lahkoSPodpisom(context: Context, id: String): Boolean =
        jeVpisana(context, id) || znaniIdZaNasKljuc(context, razen = id) != null

    /**
     * Id te naprave iz njenega kljuca (`n-<16 hex>`, KrogZaupanja.idIzKljuca) s pripono sorodnika
     * ("" za zaslon/hub, "-os" za Safeer OS ...). Isti kljuc -> isti id na vseh hubih; ce kljuca ni mogoce
     * dobiti, [nadomestni] (stari id po modelu naprave), da naprava ne ostane brez imena.
     */
    fun lastniId(pripona: String = "", nadomestni: () -> String): String =
        try { KrogZaupanja.idIzKljuca(HubTls.javniKljucB64()) + pripona } catch (_: Throwable) { nadomestni() + pripona }

    /** Kljuc huba [hub] iz kroga za pripenjanje potrdila (po id-ju ali, pri id-ju iz kljuca, po kljucu), ali null. */
    fun kljucHuba(context: Context, hubId: String): String? =
        if (hubId.isBlank()) null else krog(context).clanZaId(hubId)?.kljuc

    /** Kateri koli id (razen [razen]), pod katerim je nas kljuc ze v krogu (ista naprava, drug id), ali null. */
    fun znaniIdZaNasKljuc(context: Context, razen: String = ""): String? {
        val kljuc = try { HubTls.javniKljucB64() } catch (_: Throwable) { return null }
        return krog(context).clani().firstOrNull { it.kljuc == kljuc && it.id != razen }?.id
    }

    /** Zdruzi krog, ki ga je poslal hub (trust.update ali odgovor na prijavo). */
    fun sprejmi(context: Context, json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return krog(context).zdruzi(json)
    }

    /** Kaj naprava podpise ob prijavi na hub z odtisom [odtisHuba] - isto kot HubUsmerjevalnik.podatkiZaPodpis. */
    fun podpisPrijave(id: String, odtisHuba: String, nonce: String): String =
        HubTls.podpisi("safeer-link-auth\n${odtisHuba.lowercase()}\n$nonce\n$id".toByteArray(Charsets.UTF_8))
}
