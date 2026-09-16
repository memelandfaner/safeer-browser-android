package com.safeer.mobile.browser.link

import android.content.Context
import org.json.JSONObject

/**
 * Krajevna imena naprav v Safeer Linku: uporabnik te naprave lahko druge naprave poimenuje po
 * svoje (stran Linka -> Preimenuj). Imena ostanejo na tej napravi; tu jih beremo za obvestila
 * in seznam, da je ime povsod isto.
 */
object LinkVzdevki {
    private const val KLJUC = "link_vzdevki"

    fun ime(context: Context, idNaprave: String, privzeto: String): String {
        if (idNaprave.isBlank()) return privzeto
        return try {
            val vsi = JSONObject(context.getSharedPreferences(LinkMost.PREFS, Context.MODE_PRIVATE).getString(KLJUC, "{}") ?: "{}")
            vsi.optString(idNaprave, "").ifBlank { privzeto }
        } catch (_: Throwable) {
            privzeto
        }
    }
}
