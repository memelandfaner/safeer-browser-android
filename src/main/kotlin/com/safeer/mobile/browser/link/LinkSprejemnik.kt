package com.safeer.mobile.browser.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.safeer.mobile.browser.I18n
import com.safeer.mobile.browser.MainActivity
import com.safeer.mobile.browser.R
import com.safeer.mobile.browser.cast.CastSenderClient
import com.safeer.mobile.browser.cast.HubDiscovery
import com.safeer.mobile.browser.cast.HubKrmilnik
import com.safeer.mobile.browser.cast.HubTls
import com.safeer.mobile.browser.cast.HubTokovi
import org.json.JSONObject

/**
 * Sprejem prek Safeer Linka v ozadju.
 *
 * Telefon je za druge naprave dosegljiv tudi takrat, ko stran Safeer Linka ni odprta - kot
 * televizor (CastReceiverService) in racunalnik (povezava v ozadju). Storitev v ospredju drzi
 * eno povezavo s srediscem in prejeto pokaze takoj: stran odpre v brskalniku, besedilo pokaze
 * v oknu (ali obvestilu, ce brskalnik ni v ospredju), datoteko prenese v mapo prenosov, zaslon
 * odpre kot stran gledalca.
 *
 * Dokler je stran Safeer Linka odprta, ima povezavo ona (isti id naprave): storitev takrat
 * pocaka (premor) in se poveze nazaj, ko se stran zapre.
 */
class LinkSprejemnik : Service() {

    companion object {
        private const val TAG = "SafeerLinkSprejem"
        private const val KANAL = "safeer_link_sprejem"
        private const val KANAL_PREJETO = "safeer_link_prejeto"
        private const val OBVESTILO = 4143
        private const val PREFS = "safeer_cast_prefs"

        const val AKCIJA_ZACNI = "com.safeer.mobile.browser.link.SPREJEM_ZACNI"
        const val AKCIJA_KONCAJ = "com.safeer.mobile.browser.link.SPREJEM_KONCAJ"

        @Volatile
        var instance: LinkSprejemnik? = null
            private set

        /** Stran Linka je odprta in ima svojo povezavo; storitev takrat ne sme tekmovati z njo. */
        @Volatile
        private var premor = false

        /** Brskalnik v ospredju: prejeto besedilo pokaze v oknu, stran odpre v zavihku. */
        @Volatile
        var naBesedilo: ((String, String) -> Unit)? = null

        @Volatile
        var naStran: ((String) -> Unit)? = null
        /** Dejavnost v ospredju, ki zna izvesti tipke, drsenje in posnetek (Safeer Control). */
        var naUkaz: Daljinec.VOspredju? = null
        /** Domaca stran brskalnika; tipka Domov z daljinca jo odpre. */
        const val DOMACA_STRAN = "file:///android_asset/brave_home.html"

        private var stevecObvestil = 4200

        fun jeSeznanjen(context: Context): Boolean {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return !p.getString("hub_url", "").isNullOrBlank() &&
                !p.getString("control_token", null).isNullOrBlank() &&
                !p.getString(HubTls.KEY_HUB_FP, null).isNullOrBlank()
        }

        /** Ob zagonu brskalnika in po seznanitvi: ce je telefon seznanjen, naj sprejema. */
        fun zagotovi(context: Context) {
            val app = context.applicationContext
            if (!jeSeznanjen(app)) return
            zazeniStoritev(app, AKCIJA_ZACNI)
        }

        fun ustavi(context: Context) {
            zazeniStoritev(context.applicationContext, AKCIJA_KONCAJ)
        }

        /** Stran Linka se odpira: povezavo prepustimo njej. */
        fun premor() {
            premor = true
            instance?.odklopi()
        }

        /** Stran Linka se je zaprla: storitev spet prevzame povezavo. */
        fun nadaljuj(context: Context) {
            premor = false
            val app = context.applicationContext
            if (!jeSeznanjen(app)) return
            val i = instance
            if (i != null) i.povezi() else zazeniStoritev(app, AKCIJA_ZACNI)
        }

        private fun zazeniStoritev(app: Context, akcija: String) {
            val namera = Intent(app, LinkSprejemnik::class.java).apply { action = akcija }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(namera)
                else app.startService(namera)
            } catch (e: Throwable) {
                Log.w(TAG, "Storitve ni bilo mogoce zagnati: ${e.message}")
            }
        }
    }

    private val glavnaNit = Handler(Looper.getMainLooper())
    private var odjemalec: CastSenderClient? = null
    private var povezan = false
    private var zadnjeIskanjeSredisca = 0L

    private fun nastavitve() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hubUrl(): String = nastavitve().getString("hub_url", "") ?: ""
    private fun zeton(): String? = nastavitve().getString("control_token", null)
    private fun potVstopnice(): String = nastavitve().getString("hub_ticket_path", "/cast/ticket") ?: "/cast/ticket"
    private fun ime(): String = "phone-" + Build.MODEL.replace(Regex("\\s+"), "-").lowercase()
    private fun imeNaprave(): String = "Safeer (" + Build.MODEL + ")"
    private fun hubHttp(): String = hubUrl().replace(Regex("^wss"), "https").replace(Regex("^ws"), "http")
        .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws").trimEnd('/')

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AKCIJA_KONCAJ || !jeSeznanjen(this)) {
            odklopi()
            ustaviOspredje()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            pripraviKanale()
            startForeground(OBVESTILO, obvestilo())
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila ni bilo mogoce prikazati: ${e.message}")
        }
        if (!premor) povezi()
        return START_STICKY
    }

    override fun onDestroy() {
        odklopi()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ povezava

    @Synchronized
    fun povezi() {
        if (premor || odjemalec != null) return
        val naslov = hubUrl()
        val z = zeton()
        val odtis = HubTls.pripetiOdtis(this)
        if (naslov.isBlank() || z == null || odtis == null) return
        val nov = CastSenderClient(
            naslov, z, potVstopnice(),
            hubOdtis = odtis,
            senderId = ime(),
            sinhronizira = ZaznamkiSync.jeVklopljena(this),
            deviceName = imeNaprave(),
            zmoznosti = listOf("url", "text", "file", "screen", Daljinec.ZMOZNOST)
        )
        nov.onShare = { sporocilo -> prejmi(sporocilo) }
        nov.onControl = { sporocilo -> izvediUkaz(nov, sporocilo) }
        nov.onConnectedStateChanged = { p ->
            povezan = p
            posodobiObvestilo()
            if (!p) poisciDrugoSredisce()
        }
        nov.onSyncData = { kategorija, razlicica, _, vsebina ->
            if (kategorija == ZaznamkiSync.KATEGORIJA && ZaznamkiSync.jeVklopljena(this)) {
                Thread {
                    try {
                        val repo = com.safeer.mobile.browser.BrowserRepository(this)
                        ZaznamkiSync.zdruzi(repo, vsebina)
                        if (razlicica > ZaznamkiSync.razlicica(this)) ZaznamkiSync.shraniRazlicico(this, razlicica)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Zaznamkov ni bilo mogoce zdruziti: ${e.message}")
                    }
                }.start()
            }
        }
        odjemalec = nov
        try {
            nov.connect()
        } catch (e: Throwable) {
            Log.w(TAG, "Povezava ni uspela: ${e.message}")
        }
    }

    @Synchronized
    fun odklopi() {
        try { odjemalec?.disconnect() } catch (_: Throwable) { }
        odjemalec = null
        povezan = false
        posodobiObvestilo()
    }

    /**
     * Sredisce je ugasnilo ali dobilo nov naslov: cez nekaj sekund pogledamo, ali se Safeer
     * Link javlja kje drugje (HubDiscovery preklopi naslov in zeton, ce je bil telefon z njim
     * ze seznanjen). Najvec enkrat na pol minute, da ne trkamo po omrezju.
     */
    private fun poisciDrugoSredisce() {
        val zdaj = System.currentTimeMillis()
        if (zdaj - zadnjeIskanjeSredisca < 30_000L) return
        zadnjeIskanjeSredisca = zdaj
        val prej = hubUrl()
        val prejOdtis = HubTls.pripetiOdtis(this) ?: ""
        glavnaNit.postDelayed({
            if (premor || povezan) return@postDelayed
            try {
                HubDiscovery.discover(this) { naslov ->
                    if (naslov != null && (naslov != prej || (HubTls.pripetiOdtis(this) ?: "") != prejOdtis)) {
                        Log.i(TAG, "Sredisce se je preselilo: $prej -> $naslov")
                        odklopi()
                        povezi()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Iskanja drugega sredisca ni bilo mogoce zagnati: ${e.message}")
            }
        }, 6_000L)
    }

    // ------------------------------------------------------------------ prejeto

    private fun prejmi(sporocilo: JSONObject) {
        try {
            val tip = sporocilo.optString("type", "")
            val od = LinkVzdevki.ime(this, sporocilo.optString("sender", ""),
                sporocilo.optString("sender_name", "").ifBlank { sporocilo.optString("sender", "naprava") })
            val tovor = sporocilo.optJSONObject("payload") ?: JSONObject()
            when (tip) {
                "cast.url" -> {
                    val url = tovor.optString("url", "").trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) odpriStran(od, url, tovor.optString("title", ""))
                }
                "share.text" -> {
                    val besedilo = tovor.optString("text", "")
                    val v = naBesedilo
                    if (v != null) glavnaNit.post { v(od, besedilo) }
                    else obvestiBesedilo(od, besedilo)
                }
                "share.screen" -> {
                    if (tovor.optString("action", "") == "start") {
                        val pot = tovor.optString("path", "")
                        val url = if (pot.startsWith("/")) hubHttp() + pot else tovor.optString("url", "")
                        if (url.isNotBlank()) odpriStran(od, url, I18n.t(this, "share_screen_of").replace("{ime}", od))
                    }
                }
                "share.file" -> {
                    val imeDat = tovor.optString("name", "datoteka")
                    val pot = tovor.optString("path", "")
                    val odtis = tovor.optString("sha256", "")
                    val zaGostitelja = tovor.optBoolean("for_host", false)
                    if (zaGostitelja || pot.isBlank()) {
                        obvestiDatoteko(od, imeDat, true)
                    } else {
                        prevzemiDatoteko(hubHttp() + pot, imeDat, od, odtis)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Prejetega ni bilo mogoce obdelati: ${e.message}")
        }
    }

    /**
     * Ukaz Safeer Controla (control.command): izvede ga Daljinec na glavni niti - kar potrebuje
     * odprt brskalnik, prek dejavnosti v ospredju (naUkaz), ostalo storitev sama. Odgovor gre
     * nazaj posiljatelju kot control.result.
     */
    private fun izvediUkaz(odjemalec: CastSenderClient, sporocilo: JSONObject) {
        val tovor = sporocilo.optJSONObject("payload") ?: JSONObject()
        val posiljatelj = sporocilo.optString("sender", "")
        val dejanje = tovor.optString("action", "")
        val parametri = tovor.optJSONObject("params") ?: tovor
        val refId = sporocilo.optString("id", "")
        glavnaNit.post {
            val ospredje = naUkaz
            val izid = Daljinec.izvedi(this, dejanje, parametri, ospredje, DOMACA_STRAN) { url, naslov ->
                odpriStran("Safeer Control", url, naslov)
            }
            if (posiljatelj.isNotBlank()) {
                odjemalec.posljiSporocilo(Daljinec.sporociloIzida(posiljatelj, refId, dejanje, izid))
            }
        }
    }

    /** Stran (ali zaslon) odpremo takoj, ce je brskalnik v ospredju; sicer obvestilo, ki jo odpre. */
    private fun odpriStran(od: String, url: String, naslov: String) {
        val v = naStran
        if (v != null) {
            glavnaNit.post { v(url) }
            return
        }
        val namera = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val klik = PendingIntent.getActivity(this, stevecObvestil, namera, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        objaviObvestilo(
            I18n.t(this, "share_received_page").replace("{ime}", od),
            naslov.ifBlank { url },
            klik
        )
    }

    private fun obvestiBesedilo(od: String, besedilo: String) {
        val namera = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val klik = PendingIntent.getActivity(this, stevecObvestil, namera, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        objaviObvestilo("💬 " + I18n.t(this, "share_received_text").replace("{ime}", od), besedilo.take(1000), klik)
    }

    private fun obvestiDatoteko(od: String, imeDat: String, uspelo: Boolean) {
        val opisMape = com.safeer.mobile.browser.PrenosiMapa.opis(this)
        val besedilo = if (uspelo) I18n.t(this, "share_received_file").replace("{ime}", imeDat) + " (" + opisMape + ")"
        else I18n.t(this, "share_file_failed").replace("{ime}", imeDat)
        // Odpre seznam prenosov v Safeerju (ne sistemske aplikacije, iz katere se ni mogoce vrniti v brskalnik).
        val namera = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.ODPRI_PRENOSE, true)
        val klik = try {
            PendingIntent.getActivity(this, stevecObvestil, namera, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } catch (_: Throwable) { null }
        objaviObvestilo("📁 " + od, besedilo, klik)
    }

    /** Datoteko, ki caka na Hubu, prenesemo v mapo prenosov; ime ostane, ob trku dobi stevilko. */
    private fun prevzemiDatoteko(url: String, imeDat: String, od: String, pricakovanOdtis: String) {
        Thread {
            var zaBrisanje: java.io.File? = null
            try {
                val mapa = HubKrmilnik.mapaZaPrejete(this)
                val cilj = HubTokovi.enolicnaPot(mapa, HubTokovi.varnoIme(imeDat))
                zaBrisanje = cilj
                val povezava = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    HubTls.zavaruj(povezava, this)
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
                obvestiDatoteko(od, cilj.name, true)
            } catch (e: Throwable) {
                Log.w(TAG, "Datoteke $imeDat ni bilo mogoce prevzeti: ${e.message}")
                try { zaBrisanje?.delete() } catch (_: Throwable) { }
                obvestiDatoteko(od, imeDat, false)
            }
        }.start()
    }

    // ------------------------------------------------------------------ obvestila

    private fun pripraviKanale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val upravitelj = getSystemService(NotificationManager::class.java) ?: return
        if (upravitelj.getNotificationChannel(KANAL) == null) {
            val kanal = NotificationChannel(KANAL, getString(R.string.link_sprejem_naslov), NotificationManager.IMPORTANCE_MIN)
            kanal.description = getString(R.string.link_sprejem_besedilo)
            kanal.setShowBadge(false)
            upravitelj.createNotificationChannel(kanal)
        }
        if (upravitelj.getNotificationChannel(KANAL_PREJETO) == null) {
            val kanal = NotificationChannel(KANAL_PREJETO, getString(R.string.link_prejeto_naslov), NotificationManager.IMPORTANCE_HIGH)
            kanal.description = getString(R.string.link_prejeto_besedilo)
            upravitelj.createNotificationChannel(kanal)
        }
    }

    private fun gradnik(kanal: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, kanal)
        else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun obvestilo(): Notification {
        val namera = Intent(this, MainActivity::class.java)
        val klik = PendingIntent.getActivity(this, 0, namera, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val besedilo = if (povezan) getString(R.string.link_sprejem_povezan) else getString(R.string.link_sprejem_besedilo)
        return gradnik(KANAL)
            .setContentTitle(getString(R.string.link_sprejem_naslov))
            .setContentText(besedilo)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(klik)
            .setOngoing(true)
            .build()
    }

    private fun posodobiObvestilo() {
        try { getSystemService(NotificationManager::class.java)?.notify(OBVESTILO, obvestilo()) } catch (_: Throwable) { }
    }

    private fun objaviObvestilo(naslov: String, besedilo: String, klik: PendingIntent?) {
        try {
            val g = gradnik(KANAL_PREJETO)
                .setContentTitle(naslov)
                .setContentText(besedilo)
                .setStyle(Notification.BigTextStyle().bigText(besedilo))
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setAutoCancel(true)
            if (klik != null) g.setContentIntent(klik)
            getSystemService(NotificationManager::class.java)?.notify(stevecObvestil++, g.build())
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila ni bilo mogoce objaviti: ${e.message}")
        }
    }

    private fun ustaviOspredje() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Throwable) {
        }
    }
}
