package com.safeer.mobile.browser.link

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Sistemsko vprasanje, kadar Android za brisanje ali spreminjanje fotografije zahteva privolitev.
 *
 * Uporabnik ukaz sprozi na televizorju, Android pa odloca na napravi, kjer fotografija je. Zato tu
 * ni nobenega svojega vmesnika: okno je prazno in prosojno, pokaze se samo sistemsko vprasanje, in
 * ko uporabnik odgovori, se zapre. Po privolitvi dejanje dokoncamo, da mu ni treba ponoviti ukaza
 * na televizorju.
 *
 * Zakaj obvestilo in ne kar okno: Android od 10 naprej programu v ozadju ne dovoli odpreti okna
 * (»background activity launch«, v dnevniku BAL_BLOCK). Streznik datotek tece v ozadju, zato okna
 * ne more odpreti sam - pokaze obvestilo, uporabnikov dotik nanj pa okno odpre. Kadar je program
 * v ospredju, okno odpremo takoj in obvestilo umaknemo.
 */
class PotrditevActivity : Activity() {

    private var uri: Uri? = null
    private var dejanje = ""
    private var stopinje = 0

    override fun onCreate(shranjeno: Bundle?) {
        super.onCreate(shranjeno)
        uri = intent?.getParcelableExtra(KLJUC_URI)
        dejanje = intent?.getStringExtra(KLJUC_DEJANJE).orEmpty()
        stopinje = intent?.getIntExtra(KLJUC_STOPINJE, 90) ?: 90
        umakniObvestilo(this)
        val cilj = uri
        if (cilj == null || dejanje.isBlank()) { finish(); return }
        val vprasanje = when (dejanje) {
            DEJANJE_BRISANJE -> UrejanjeMedijev.vprasanjeZaBrisanje(this, cilj)
            else -> UrejanjeMedijev.vprasanjeZaPisanje(this, cilj)
        }
        if (vprasanje == null) { finish(); return }
        try {
            startIntentSenderForResult(vprasanje.intentSender, ZAHTEVA, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            Log.w(TAG, "Vprasanja ni bilo mogoce pokazati: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(zahteva: Int, izid: Int, podatki: Intent?) {
        super.onActivityResult(zahteva, izid, podatki)
        val cilj = uri
        if (zahteva == ZAHTEVA && izid == RESULT_OK && cilj != null) {
            // Privolitev je dana: dejanje dokoncamo tu, da ga uporabniku ni treba ponoviti.
            // Brisanje je Android ob potrditvi ze opravil (Smeti), vrtenje pa moramo se mi.
            if (dejanje != DEJANJE_BRISANJE) UrejanjeMedijev.zavrti(this, cilj, stopinje)
        }
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "SafeerPotrditev"
        private const val ZAHTEVA = 4711
        private const val KANAL = "safeer_potrditev"
        private const val OBVESTILO = 4711

        const val DEJANJE_BRISANJE = "delete"
        const val DEJANJE_VRTENJE = "rotate"

        private const val KLJUC_URI = "uri"
        private const val KLJUC_DEJANJE = "dejanje"
        private const val KLJUC_STOPINJE = "stopinje"

        private fun namera(context: Context, uri: Uri, dejanje: String, stopinje: Int) =
            Intent(context, PotrditevActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(KLJUC_URI, uri)
                .putExtra(KLJUC_DEJANJE, dejanje)
                .putExtra(KLJUC_STOPINJE, stopinje)

        /**
         * Prosi uporabnika za privolitev na tej napravi. Klicemo iz streznika, ki tece v ozadju,
         * zato gre prek obvestila; ce je program v ospredju, se okno odpre takoj.
         */
        fun pokazi(context: Context, uri: Uri, dejanje: String, stopinje: Int = 90) {
            val n = namera(context, uri, dejanje, stopinje)
            pokaziObvestilo(context, n, dejanje)
            // Poskus neposrednega odprtja: uspe, kadar je program v ospredju, sicer ga Android
            // mirno zavrne (BAL_BLOCK) in ostane obvestilo.
            try {
                context.startActivity(n)
            } catch (e: Throwable) {
                Log.i(TAG, "Okna iz ozadja ni mogoce odpreti; ostane obvestilo: ${e.message}")
            }
        }

        private fun pokaziObvestilo(context: Context, n: Intent, dejanje: String) {
            val upravitelj = context.getSystemService(NotificationManager::class.java) ?: return
            if (!Obvestila.dovoljena(context)) Log.w(TAG, "Obvestila so izklopljena: vprasanja ne bo videti.")
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    val kanal = NotificationChannel(KANAL, besedilo(context, "os_ur_kanal", "Safeer: potrditev"),
                        NotificationManager.IMPORTANCE_HIGH)
                    kanal.description = besedilo(context, "os_ur_kanal_opis",
                        "Vprašanja, ki jih mora uporabnik potrditi na tej napravi.")
                    upravitelj.createNotificationChannel(kanal)
                }
                val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val dotik = PendingIntent.getActivity(context, 0, n, zastavice)
                val naslov = if (dejanje == DEJANJE_BRISANJE)
                    besedilo(context, "os_ur_obv_brisanje", "Potrdi izbris slike")
                else besedilo(context, "os_ur_obv_vrtenje", "Potrdi vrtenje slike")
                val opis = besedilo(context, "os_ur_obv_opis", "Ukaz prihaja iz Safeer Linka. Dotakni se za potrditev.")
                val obvestilo = android.app.Notification.Builder(context, KANAL)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(naslov)
                    .setContentText(opis)
                    .setContentIntent(dotik)
                    .setAutoCancel(true)
                    .setCategory(android.app.Notification.CATEGORY_RECOMMENDATION)
                    // Vprasanje naj skoci v ospredje, ne caka v predalu: uporabnik ga je
                    // sprozil na drugi napravi in ga tam ze caka. Ce naprava tega ne
                    // dovoli, ostane navadno obvestilo.
                    .setFullScreenIntent(dotik, true)
                    .build()
                upravitelj.notify(OBVESTILO, obvestilo)
            } catch (e: Throwable) {
                Log.w(TAG, "Obvestila ni bilo mogoce pokazati: ${e.message}")
            }
        }

        fun umakniObvestilo(context: Context) {
            try {
                context.getSystemService(NotificationManager::class.java)?.cancel(OBVESTILO)
            } catch (_: Throwable) { }
        }

        /** Prevod, ce obstaja; sicer rezerva, da obvestilo nikoli ni prazno. */
        private fun besedilo(context: Context, ime: String, rezerva: String): String = try {
            val id = context.resources.getIdentifier(ime, "string", context.packageName)
            if (id != 0) context.getString(id) else rezerva
        } catch (_: Throwable) { rezerva }
    }
}
