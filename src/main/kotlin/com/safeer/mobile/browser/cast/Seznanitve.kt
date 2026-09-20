package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import android.content.Context
import org.json.JSONObject

/**
 * Seznanitve po Hubih: odtis potrdila -> zeton. V hisi je lahko vec sredisc (televizor,
 * telefon, racunalnik); ko eno ugasne, se naprava poveze na drugo, s katerim je bila ze
 * seznanjena, brez nove kode. Zetoni ostanejo v istih zasebnih nastavitvah kot doslej.
 */
object Seznanitve {

    private const val PREFS = "safeer_cast_prefs"
    private const val KLJUC = "seznanitve"

    private fun beri(context: Context): JSONObject = try {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC, "") ?: "")
    } catch (_: Throwable) {
        JSONObject()
    }

    /** Zapomni si zeton za Hub s tem odtisom. */
    fun zapomni(context: Context, odtis: String, zeton: String, hubUrl: String) {
        if (odtis.isBlank() || zeton.isBlank()) return
        val vse = beri(context)
        vse.put(odtis.lowercase(), JSONObject().put("token", zeton).put("hub_url", hubUrl))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC, vse.toString()).apply()
    }

    /** Zeton za Hub s tem odtisom ali null, ce se z njim nismo seznanili. */
    fun zeton(context: Context, odtis: String): String? {
        if (odtis.isBlank()) return null
        val z = beri(context).optJSONObject(odtis.lowercase())?.optString("token", "") ?: ""
        return z.ifBlank { null }
    }

    /** Trenutno seznanitev (zeton + odtis iz nastavitev) shrani v seznam, preden preklopimo. */
    fun zapomniTrenutno(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val zeton = p.getString("control_token", "") ?: ""
        val odtis = p.getString(HubTls.KEY_HUB_FP, "") ?: ""
        val hub = p.getString("hub_url", "") ?: ""
        if (zeton.isNotBlank() && odtis.isNotBlank()) zapomni(context, odtis, zeton, hub)
    }

    fun pozabiVse(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KLJUC).apply()
    }
}
