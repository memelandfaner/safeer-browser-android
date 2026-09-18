package com.safeer.mobile.browser.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Posiljanje datoteke prek Safeer Linka, v storitvi v ospredju.
 *
 * Zakaj storitev in ne navadna nit: novejsi Android aplikaciji, ki ni v ospredju, po nekaj
 * sekundah zapre omrezne vticnice. Uporabnik med posiljanjem vecje datoteke rad preklopi
 * drugam - prenos mora to preziveti. Obvestilo hkrati pove, da se nekaj posilja.
 *
 * Datoteka gre po eni sami zahtevi PUT v koscih; prstni odtis SHA-256 se racuna sproti in
 * primerja s tistim, ki ga vrne Hub. Napredek se javlja prek `naNapredek`, ki ga pripne
 * stran Safeer Linka, kadar je odprta.
 */
class PrenosDatotekeStoritev : Service() {

    companion object {
        private const val TAG = "SafeerPrenosDatoteke"
        private const val KANAL = "safeer_link_prenos"
        private const val ID_OBVESTILA = 4243

        const val ACTION_POSLJI = "com.safeer.mobile.browser.link.POSLJI_DATOTEKO"
        const val EXTRA_URI = "uri"
        const val EXTRA_CILJ = "cilj"
        const val EXTRA_HUB_HTTP = "hub_http"
        const val EXTRA_ZETON = "zeton"
        const val EXTRA_ID_NAPRAVE = "id_naprave"

        /** stanje: posiljam | poslano | napaka; odstotek -1, ce ni znan. */
        class Napredek(val cilj: String, val ime: String, val stanje: String, val odstotek: Int,
                       val sporocilo: String, val koda: String, val zasedenaOd: String)

        @Volatile var naNapredek: ((Napredek) -> Unit)? = null
        @Volatile var zadnji: Napredek? = null
            private set

        fun poslji(context: Context, uri: Uri, cilj: String, hubHttp: String, zeton: String, idNaprave: String) {
            val namera = Intent(context, PrenosDatotekeStoritev::class.java).apply {
                action = ACTION_POSLJI
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_CILJ, cilj)
                putExtra(EXTRA_HUB_HTTP, hubHttp)
                putExtra(EXTRA_ZETON, zeton)
                putExtra(EXTRA_ID_NAPRAVE, idNaprave)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(namera)
            else context.startService(namera)
        }

        private fun javi(n: Napredek) {
            zadnji = n
            try { naNapredek?.invoke(n) } catch (_: Exception) { }
        }
    }

    private val glavna = Handler(Looper.getMainLooper())
    private var vVrsti = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        val cilj = intent?.getStringExtra(EXTRA_CILJ) ?: ""
        val hubHttp = intent?.getStringExtra(EXTRA_HUB_HTTP) ?: ""
        val zeton = intent?.getStringExtra(EXTRA_ZETON) ?: ""
        val idNaprave = intent?.getStringExtra(EXTRA_ID_NAPRAVE) ?: ""
        if (intent?.action != ACTION_POSLJI || uri == null || cilj.isBlank() || hubHttp.isBlank()) {
            if (vVrsti == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            pripraviKanal()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_OBVESTILA, obvestilo("…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID_OBVESTILA, obvestilo("…", 0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Storitve v ospredju ni bilo mogoce zagnati: ${e.message}")
            javi(Napredek(cilj, "", "napaka", -1, e.message ?: "storitev ni stekla", "", ""))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        vVrsti++
        Thread({
            try {
                posljiDatoteko(uri, cilj, hubHttp, zeton, idNaprave)
            } finally {
                glavna.post {
                    vVrsti--
                    if (vVrsti <= 0) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                            else @Suppress("DEPRECATION") stopForeground(true)
                        } catch (_: Exception) { }
                        stopSelf()
                    }
                }
            }
        }, "safeer-prenos").start()
        return START_NOT_STICKY
    }

    private fun posljiDatoteko(uri: Uri, cilj: String, hubHttp: String, zeton: String, idNaprave: String) {
        var ime = "datoteka"
        try {
            val razresevalec = contentResolver
            var velikost = -1L
            razresevalec.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iIme = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val iVel = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (iIme >= 0) c.getString(iIme)?.let { if (it.isNotBlank()) ime = it }
                    if (iVel >= 0 && !c.isNull(iVel)) velikost = c.getLong(iVel)
                }
            }
            if (velikost < 0) razresevalec.openAssetFileDescriptor(uri, "r")?.use { velikost = it.length }
            if (velikost < 0) throw IOException("velikosti datoteke ni mogoce ugotoviti")
            javi(Napredek(cilj, ime, "posiljam", 0, "", "", ""))
            posodobiObvestilo(ime, 0)

            val pot = "/cast/file?name=" + URLEncoder.encode(ime, "UTF-8") +
                "&target=" + URLEncoder.encode(cilj, "UTF-8") +
                "&from=" + URLEncoder.encode(idNaprave, "UTF-8")
            val povezava = URL(hubHttp + pot).openConnection() as HttpURLConnection
            try {
                com.safeer.mobile.browser.cast.HubTls.zavaruj(povezava, applicationContext)
                povezava.requestMethod = "PUT"
                povezava.connectTimeout = 5000
                povezava.readTimeout = 0
                povezava.setRequestProperty("x-safeer-token", zeton)
                povezava.setRequestProperty("Content-Type", "application/octet-stream")
                povezava.doOutput = true
                povezava.setFixedLengthStreamingMode(velikost)
                val vhod = razresevalec.openInputStream(uri) ?: throw IOException("datoteke ni mogoce odpreti")
                var poslano = 0L
                var zadnjiOdstotek = 0
                // Prstni odtis racunamo sproti; Hub vrne svojega in mora biti isti - sicer je
                // datoteka po poti spremenjena ali okrnjena in je ne stejemo za poslano.
                val prstni = MessageDigest.getInstance("SHA-256")
                vhod.use { v ->
                    povezava.outputStream.use { izhod ->
                        val kos = ByteArray(64 * 1024)
                        while (true) {
                            val n = v.read(kos)
                            if (n < 0) break
                            izhod.write(kos, 0, n)
                            prstni.update(kos, 0, n)
                            poslano += n
                            val odstotek = if (velikost > 0) ((poslano * 100) / velikost).toInt() else 100
                            if (odstotek >= zadnjiOdstotek + 5) {
                                zadnjiOdstotek = odstotek
                                javi(Napredek(cilj, ime, "posiljam", odstotek, "", "", ""))
                                posodobiObvestilo(ime, odstotek)
                            }
                        }
                    }
                }
                val koda = povezava.responseCode
                val tok = if (koda >= 400) povezava.errorStream else povezava.inputStream
                val odgovor = tok?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (koda == 200) {
                    val nasOdtis = prstni.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
                    val hubovOdtis = try { JSONObject(odgovor).optString("sha256", "") } catch (_: Throwable) { "" }
                    if (hubovOdtis.isNotBlank() && hubovOdtis != nasOdtis) {
                        javi(Napredek(cilj, ime, "napaka", -1, "prstni odtis se ne ujema", "napacen_odtis", ""))
                    } else {
                        javi(Napredek(cilj, ime, "poslano", 100, "", "", ""))
                    }
                } else {
                    val o = try { JSONObject(odgovor) } catch (_: Throwable) { JSONObject() }
                    javi(Napredek(cilj, ime, "napaka", -1,
                        o.optString("napaka", "").ifBlank { "Hub je odgovoril $koda" },
                        o.optString("koda", ""),
                        o.optString("busy_by_name", "").ifBlank { o.optString("busy_by", "") }))
                }
            } finally {
                povezava.disconnect()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Datoteke ni bilo mogoce poslati: ${e.message}")
            javi(Napredek(cilj, ime, "napaka", -1, e.message ?: "posiljanje ni uspelo", "", ""))
        }
    }

    // ------------------------------------------------------------------ obvestilo

    private fun pripraviKanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val upravitelj = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (upravitelj.getNotificationChannel(KANAL) != null) return
        val kanal = NotificationChannel(KANAL, "Safeer Link – prenos datotek", NotificationManager.IMPORTANCE_LOW)
        kanal.description = "Pokaze napredek datoteke, ki se posilja na drugo napravo."
        kanal.setShowBadge(false)
        upravitelj.createNotificationChannel(kanal)
    }

    private fun obvestilo(ime: String, odstotek: Int): Notification {
        val gradnik = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, KANAL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return gradnik
            .setContentTitle(com.safeer.mobile.browser.I18n.t(this, "share_file_notif_title"))
            .setContentText(ime)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, odstotek.coerceIn(0, 100), false)
            .setOngoing(true)
            .build()
    }

    private fun posodobiObvestilo(ime: String, odstotek: Int) {
        try {
            val upravitelj = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            upravitelj.notify(ID_OBVESTILA, obvestilo(ime, odstotek))
        } catch (_: Exception) { }
    }
}
