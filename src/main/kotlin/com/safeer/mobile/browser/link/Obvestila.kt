package com.safeer.mobile.browser.link

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Dovoljenje za obvestila (Android 13+).
 *
 * Brez njega naprava ne more vprasati lastnika, kadar Android za brisanje ali vrtenje fotografije
 * zahteva njegovo privolitev ([PotrditevActivity]): streznik datotek tece v ozadju in okna iz
 * ozadja ne sme odpreti (BAL_BLOCK), obvestilo pa sistem brez dovoljenja zavrze. Uporabnik bi tako
 * na drugi napravi videl »potrdi na napravi, kjer je slika«, na sami napravi pa ne bi bilo nicesar.
 *
 * Vprasamo enkrat, ob prvem odprtju domacega zaslona. Ce uporabnik zavrne, ga ne nadlegujemo vec -
 * sistem drugic vprasanja tako ali tako ne pokaze.
 */
object Obvestila {

    private const val DOVOLJENJE = "android.permission.POST_NOTIFICATIONS"
    private const val NASTAVITVE = "safeer_obvestila"
    private const val VPRASANO = "vprasano"
    private const val ZAHTEVA = 7411

    /** Ali naprava obvestila sploh pokaze? (Uporabnik jih lahko izklopi tudi v nastavitvah.) */
    fun dovoljena(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 24) true
        else context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true
    } catch (_: Throwable) { true }

    /** Ob prvem zagonu vprasa za dovoljenje; pozneje ne stori nicesar. */
    fun zaprosiEnkrat(a: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (a.checkSelfPermission(DOVOLJENJE) == PackageManager.PERMISSION_GRANTED) return
        val p = a.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        if (p.getBoolean(VPRASANO, false)) return
        p.edit().putBoolean(VPRASANO, true).apply()
        try {
            a.requestPermissions(arrayOf(DOVOLJENJE), ZAHTEVA)
        } catch (_: Throwable) { }
    }
}
