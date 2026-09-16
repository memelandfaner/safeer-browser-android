package com.safeer.mobile.browser.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Deljenje zaslona te naprave prek Safeer Linka.
 *
 * Zaslon se zajema z MediaProjection (uporabnik ga dovoli v sistemskem oknu), vsak okvir se
 * pomanjsa in stisne v JPEG ter poslje Hubu po eni sami odprti zahtevi HTTP; Hub ga prikaze
 * ciljni napravi. Vse gre po hisnem omrezju, nic v oblak.
 *
 * Storitev tece v ospredju (obvestilo z gumbom "Prekini deljenje zaslona"), da deljenje ne
 * ugasne, ko uporabnik zapusti brskalnik - saj je smisel prav v tem, da na televizorju
 * vidi, kar pocne na tablici.
 *
 * Razmerje slike se ohrani na vsakem koraku: pomanjsava je enakomerna po obeh oseh, gledalec
 * sliko umeri z object-fit: contain. Krog ostane krog.
 */
class DeljenjeZaslonaStoritev : Service() {

    companion object {
        private const val TAG = "SafeerDeljenjeZaslona"
        private const val KANAL = "safeer_link_zaslon"
        private const val ID_OBVESTILA = 4242

        const val ACTION_START = "com.safeer.mobile.browser.link.ZASLON_START"
        const val ACTION_STOP = "com.safeer.mobile.browser.link.ZASLON_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CILJ = "cilj"
        const val EXTRA_IME_CILJA = "ime_cilja"
        const val EXTRA_HUB_HTTP = "hub_http"
        const val EXTRA_ZETON = "zeton"
        const val EXTRA_ID_NAPRAVE = "id_naprave"

        /** Najdaljsa stranica poslane slike; dovolj za televizor, dovolj malo za Wi-Fi. */
        const val NAJVECJA_STRANICA = 1280
        const val KAKOVOST_JPEG = 60
        /** Ciljni razmik med okvirji (ms): ~8 na sekundo. */
        const val RAZMIK_MS = 120L
        /** Ce se slika ne spremeni, vseeno posljemo okvir vsakih toliko, da Hub ve, da smo tu. */
        const val UTRIP_MS = 4000L

        @Volatile var tece: Boolean = false
            private set
        @Volatile var cilj: String = ""
            private set
        @Volatile var imeCilja: String = ""
            private set
        @Volatile var zadnjaNapaka: String = ""
            private set
        @Volatile var zadnjaKoda: String = ""
            private set
        @Volatile var zadnjaZasedenaOd: String = ""
            private set

        /** Stran Safeer Linka se pripne sem, da izve za zacetek, konec in napako. */
        @Volatile var naSpremembo: (() -> Unit)? = null

        fun zazeni(
            context: Context, resultCode: Int, data: Intent, cilj: String, imeCilja: String,
            hubHttp: String, zeton: String, idNaprave: String
        ) {
            val namera = Intent(context, DeljenjeZaslonaStoritev::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_CILJ, cilj)
                putExtra(EXTRA_IME_CILJA, imeCilja)
                putExtra(EXTRA_HUB_HTTP, hubHttp)
                putExtra(EXTRA_ZETON, zeton)
                putExtra(EXTRA_ID_NAPRAVE, idNaprave)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(namera)
            else context.startService(namera)
        }

        fun ustavi(context: Context) {
            try {
                context.startService(Intent(context, DeljenjeZaslonaStoritev::class.java).apply { action = ACTION_STOP })
            } catch (e: Exception) {
                Log.w(TAG, "Ustavitve ni bilo mogoce zahtevati: ${e.message}")
            }
        }

        private fun javi() {
            try { naSpremembo?.invoke() } catch (_: Exception) { }
        }
    }

    private val glavna = Handler(Looper.getMainLooper())
    private var projekcija: MediaProjection? = null
    private var navidezniZaslon: VirtualDisplay? = null
    private var bralec: ImageReader? = null
    private var nit: Thread? = null
    @Volatile private var ustavljam = false
    private var idDeljenja = ""
    private var hubHttp = ""
    private var zeton = ""
    private var idNaprave = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            koncaj("")
            return START_NOT_STICKY
        }
        if (tece) {
            // Novo deljenje med tekocim: starega koncamo, novega zacne naslednji klic.
            koncaj("")
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        cilj = intent.getStringExtra(EXTRA_CILJ) ?: ""
        imeCilja = intent.getStringExtra(EXTRA_IME_CILJA) ?: cilj
        hubHttp = intent.getStringExtra(EXTRA_HUB_HTTP) ?: ""
        zeton = intent.getStringExtra(EXTRA_ZETON) ?: ""
        idNaprave = intent.getStringExtra(EXTRA_ID_NAPRAVE) ?: ""
        if (data == null || cilj.isBlank() || hubHttp.isBlank()) {
            koncaj("Manjkajo podatki za deljenje.")
            return START_NOT_STICKY
        }

        try {
            pripraviKanal()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_OBVESTILA, obvestilo(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(ID_OBVESTILA, obvestilo())
            }
        } catch (e: Exception) {
            koncaj("Storitve v ospredju ni bilo mogoce zagnati: ${e.message}")
            return START_NOT_STICKY
        }

        try {
            val upravitelj = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = upravitelj.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("dovoljenje za zajem zaslona ni bilo dano")
            // Od Androida 14 mora biti povratni klic registriran pred navideznim zaslonom.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { koncaj("") }
            }, glavna)
            projekcija = p
        } catch (e: Exception) {
            koncaj("Zajema zaslona ni bilo mogoce zaceti: ${e.message}")
            return START_NOT_STICKY
        }

        tece = true
        zadnjaNapaka = ""
        zadnjaKoda = ""
        zadnjaZasedenaOd = ""
        ustavljam = false
        javi()
        nit = Thread({ tokZaslona() }, "safeer-zaslon").also { it.start() }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ tok

    private fun tokZaslona() {
        var vticnica: Socket? = null
        var napaka = ""
        try {
            val zacetek = zacniNaHubu()
            idDeljenja = zacetek.optString("id", "")
            val potPotiskanja = zacetek.optString("push_path", "")
            if (idDeljenja.isBlank() || potPotiskanja.isBlank()) throw IllegalStateException("Hub ni vrnil poti za deljenje")

            val url = URL(hubHttp)
            if (url.protocol != "https") throw IllegalStateException("Hub brez TLS - posodobi Safeer na gostitelju")
            val vrata = if (url.port > 0) url.port else 443
            // Tok zaslona gre po isti TLS povezavi z istim pripetim odtisom kot vse drugo.
            val odtis = com.safeer.mobile.browser.cast.HubTls.pripetiOdtis(applicationContext)
                ?: throw IllegalStateException("naprava s tem Hubom ni seznanjena")
            val (tovarna, _) = com.safeer.mobile.browser.cast.HubTls.odjemalec(odtis)
            vticnica = (tovarna.createSocket(url.host, vrata) as javax.net.ssl.SSLSocket).apply {
                tcpNoDelay = true
                soTimeout = 0
                com.safeer.mobile.browser.cast.HubTls.nastaviOdjemalsko(this)
                startHandshake()
            }
            val izhod = DataOutputStream(vticnica.getOutputStream().buffered(256 * 1024))
            izhod.write(("POST $potPotiskanja HTTP/1.1\r\nHost: ${url.host}\r\n" +
                "x-safeer-token: $zeton\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII))
            izhod.flush()

            zajemaj(izhod)
        } catch (e: Exception) {
            if (!ustavljam) {
                napaka = e.message ?: "napaka pri deljenju"
                Log.w(TAG, "Deljenje zaslona se je prekinilo: $napaka")
            }
        } finally {
            try { vticnica?.close() } catch (_: Exception) { }
            glavna.post { koncaj(napaka) }
        }
    }

    /** Hub odpre deljenje in sam obvesti ciljno napravo, kje naj gleda. */
    private fun zacniNaHubu(): JSONObject {
        val telo = JSONObject().put("device_id", idNaprave).put("target", cilj).toString()
        val (koda, odgovor) = http("POST", "$hubHttp/cast/share/screen/start", telo)
        if (koda != 200) {
            val o = try { JSONObject(odgovor) } catch (_: Exception) { JSONObject() }
            val sporocilo = o.optString("napaka", "")
            zadnjaKoda = o.optString("koda", "")
            zadnjaZasedenaOd = o.optString("busy_by_name", "").ifBlank { o.optString("busy_by", "") }
            throw IllegalStateException(if (sporocilo.isNotBlank()) sporocilo else "Hub je zavrnil deljenje ($koda)")
        }
        return JSONObject(odgovor)
    }

    private fun http(metoda: String, naslov: String, telo: String): Pair<Int, String> {
        val povezava = URL(naslov).openConnection() as HttpURLConnection
        try {
            com.safeer.mobile.browser.cast.HubTls.zavaruj(povezava, applicationContext)
            povezava.requestMethod = metoda
            povezava.connectTimeout = 5000
            povezava.readTimeout = 8000
            povezava.setRequestProperty("x-safeer-token", zeton)
            povezava.setRequestProperty("Content-Type", "application/json")
            povezava.doOutput = true
            povezava.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            val koda = povezava.responseCode
            val tok = if (koda >= 400) povezava.errorStream else povezava.inputStream
            val vsebina = tok?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return koda to vsebina
        } finally {
            povezava.disconnect()
        }
    }

    // ------------------------------------------------------------------ zajem

    private class Zajem(val sirina: Int, val visina: Int, val gostota: Int)

    private fun meritveZaslona(): Zajem {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Zajem(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun postaviZajem(z: Zajem) {
        val p = projekcija ?: throw IllegalStateException("projekcije ni")
        val novi = ImageReader.newInstance(z.sirina, z.visina, PixelFormat.RGBA_8888, 2)
        val stari = bralec
        val vd = navidezniZaslon
        if (vd == null) {
            navidezniZaslon = p.createVirtualDisplay(
                "SafeerLink", z.sirina, z.visina, z.gostota,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, novi.surface, null, null
            )
        } else {
            // Obrat naprave: isti navidezni zaslon, nova velikost in nova povrsina.
            vd.resize(z.sirina, z.visina, z.gostota)
            vd.surface = novi.surface
        }
        bralec = novi
        try { stari?.close() } catch (_: Exception) { }
    }

    private fun zajemaj(izhod: OutputStream) {
        var trenutna = meritveZaslona()
        postaviZajem(trenutna)
        val jpeg = ByteArrayOutputStream(256 * 1024)
        var zadnjiOkvir: ByteArray? = null
        var zadnjiPoslan = 0L
        var platno: Bitmap? = null
        var pomanjsano: Bitmap? = null
        var preverjanje = 0L

        while (!ustavljam && tece) {
            val zdaj = System.currentTimeMillis()
            if (zdaj - preverjanje > 700) {
                preverjanje = zdaj
                val nova = meritveZaslona()
                if (nova.sirina != trenutna.sirina || nova.visina != trenutna.visina) {
                    trenutna = nova
                    postaviZajem(nova)
                    platno = null
                    pomanjsano = null
                }
            }
            val slika = try { bralec?.acquireLatestImage() } catch (_: Exception) { null }
            if (slika == null) {
                if (zadnjiOkvir != null && zdaj - zadnjiPoslan > UTRIP_MS) {
                    posljiOkvir(izhod, zadnjiOkvir)
                    zadnjiPoslan = zdaj
                }
                Thread.sleep(25)
                continue
            }
            try {
                val ravnina = slika.planes[0]
                val korakSlikovne = ravnina.pixelStride
                val korakVrstice = ravnina.rowStride
                val sirinaZRobom = korakVrstice / korakSlikovne
                val p: Bitmap = platno?.takeIf { it.width == sirinaZRobom && it.height == slika.height }
                    ?: Bitmap.createBitmap(sirinaZRobom, slika.height, Bitmap.Config.ARGB_8888).also { platno = it }
                p.copyPixelsFromBuffer(ravnina.buffer)
                val sirina = slika.width
                val visina = slika.height
                // Enakomerna pomanjsava po obeh oseh: razmerje ostane natanko tako, kot je na zaslonu.
                val merilo = minOf(1f, NAJVECJA_STRANICA.toFloat() / maxOf(sirina, visina))
                val cs = maxOf(1, Math.round(sirina * merilo))
                val cv = maxOf(1, Math.round(visina * merilo))
                val m: Bitmap = pomanjsano?.takeIf { it.width == cs && it.height == cv }
                    ?: Bitmap.createBitmap(cs, cv, Bitmap.Config.ARGB_8888).also { pomanjsano = it }
                val risalo = android.graphics.Canvas(m)
                val izvor = android.graphics.Rect(0, 0, sirina, visina)
                val cilj = android.graphics.Rect(0, 0, cs, cv)
                risalo.drawBitmap(p, izvor, cilj, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                jpeg.reset()
                m.compress(Bitmap.CompressFormat.JPEG, KAKOVOST_JPEG, jpeg)
                val okvir = jpeg.toByteArray()
                zadnjiOkvir = okvir
                posljiOkvir(izhod, okvir)
                zadnjiPoslan = System.currentTimeMillis()
            } finally {
                slika.close()
            }
            val porabljeno = System.currentTimeMillis() - zdaj
            if (porabljeno < RAZMIK_MS) Thread.sleep(RAZMIK_MS - porabljeno)
        }
    }

    private fun posljiOkvir(izhod: OutputStream, okvir: ByteArray) {
        val d = okvir.size
        izhod.write(byteArrayOf((d ushr 24).toByte(), (d ushr 16).toByte(), (d ushr 8).toByte(), d.toByte()))
        izhod.write(okvir)
        izhod.flush()
    }

    // ------------------------------------------------------------------ konec

    private fun koncaj(napaka: String) {
        val jeTeklo = tece
        ustavljam = true
        tece = false
        if (napaka.isNotBlank()) zadnjaNapaka = napaka
        try { navidezniZaslon?.release() } catch (_: Exception) { }
        navidezniZaslon = null
        try { bralec?.close() } catch (_: Exception) { }
        bralec = null
        try { projekcija?.stop() } catch (_: Exception) { }
        projekcija = null
        if (jeTeklo && idDeljenja.isNotBlank()) {
            // Hub ob zaprti vticnici konca sam; izrecen stop pove cilju takoj, ne sele po premoru.
            val id = idDeljenja
            idDeljenja = ""
            Thread {
                try { http("POST", "$hubHttp/cast/share/screen/stop", JSONObject().put("id", id).toString()) }
                catch (e: Exception) { Log.i(TAG, "Stop Hubu ni bil sporocen: ${e.message}") }
            }.start()
        }
        javi()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (tece) koncaj("")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ obvestilo

    private fun pripraviKanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val upravitelj = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (upravitelj.getNotificationChannel(KANAL) != null) return
        val kanal = NotificationChannel(KANAL, "Safeer Link – deljenje zaslona", NotificationManager.IMPORTANCE_LOW)
        kanal.description = "Pokaze, da se zaslon deli na drugo napravo, in ga pusti prekiniti."
        kanal.setShowBadge(false)
        upravitelj.createNotificationChannel(kanal)
    }

    private fun obvestilo(): Notification {
        var zastavice = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) zastavice = zastavice or PendingIntent.FLAG_IMMUTABLE
        val ustavi = PendingIntent.getService(
            this, 1, Intent(this, DeljenjeZaslonaStoritev::class.java).apply { action = ACTION_STOP }, zastavice)
        val gradnik = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, KANAL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val ikona = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        return gradnik
            .setContentTitle(com.safeer.mobile.browser.I18n.t(this, "share_screen_notif_title"))
            .setContentText(com.safeer.mobile.browser.I18n.t(this, "share_screen_notif_text").replace("{ime}", imeCilja))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(ikona, com.safeer.mobile.browser.I18n.t(this, "share_screen_stop"), ustavi).build())
            .build()
    }
}
