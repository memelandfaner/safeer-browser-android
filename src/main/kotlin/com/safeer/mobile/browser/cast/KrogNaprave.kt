package com.safeer.mobile.browser.cast

import android.content.Context

/**
 * Krog zaupanja, kot ga hrani TA naprava (odjemalec). Isti zapis kot pri hubu, v zasebnih
 * nastavitvah aplikacije; ko hub ugasne in ga zamenja drug clan kroga, ima naprava vse, kar
 * potrebuje, da ga prepozna in se mu prijavi s podpisom.
 *
 * Kljuc naprave je kljuc iz AndroidKeyStore (HubTls): isti, s katerim bi ta naprava, ce bi bila
 * hub, podpisala svoje potrdilo TLS. (Kopija iz brskalnika TV; s korakom 3 ena sama.)
 */
object KrogNaprave {
    private const val PREFS = "safeer_link_krog"

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

    /** Zdruzi krog, ki ga je poslal hub (trust.update ali odgovor na prijavo). */
    fun sprejmi(context: Context, json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return krog(context).zdruzi(json)
    }

    /** Kaj naprava podpise ob prijavi na hub z odtisom [odtisHuba] - isto kot HubUsmerjevalnik.podatkiZaPodpis. */
    fun podpisPrijave(id: String, odtisHuba: String, nonce: String): String =
        HubTls.podpisi("safeer-link-auth\n${odtisHuba.lowercase()}\n$nonce\n$id".toByteArray(Charsets.UTF_8))
}
