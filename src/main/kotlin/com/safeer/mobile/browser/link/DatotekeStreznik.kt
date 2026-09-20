package com.safeer.mobile.browser.link

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket

/**
 * Datoteke te naprave za druge naprave v Safeer Linku (ukaz `files.list`) - kot jih deli Safeer
 * Control na racunalniku, le da so tu videi, glasba in slike iz MediaStore.
 *
 * Po hubu gre samo seznam; datoteke same tecejo **neposredno** s te naprave po HTTPS s potrdilom
 * te naprave (isti kljuc kot za hub, odtis `fp` je v odgovoru) in z enkratnim zetonom na napravo
 * (`?t=`). Streznik se zazene sele, ko kdo prvic vprasa po datotekah, in tece, dokler tece proces.
 * Ista datoteka je v brskalniku za TV (si.safeer.tv.link); spremembe gredo v obe.
 */
object DatotekeStreznik {
    private const val TAG = "SafeerDatoteke"
    const val ZMOZNOST = "files"
    private const val NAJVEC = 500
    private const val PREDPONA = "media:"

    private val tece = AtomicBoolean(false)
    @Volatile private var vticnica: ServerSocket? = null
    @Volatile private var vrata = 0
    private val zetoni = ConcurrentHashMap<String, String>()
    private val nakljucje = SecureRandom()

    // ------------------------------------------------------------------ seznam

    fun dovoljenja(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES")
        else -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
    }

    fun imamoDovoljenje(context: Context): Boolean =
        dovoljenja().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private const val PREFS = "safeer_cast_prefs"
    private const val KLJUC_VKLOP = "link_datoteke"

    /** Uporabnikovo stikalo (stran Safeer Linka): privzeto vklopljeno, deli pa se sele z dovoljenjem. */
    fun vklopljeno(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_VKLOP, true)

    fun nastavi(context: Context, vklop: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_VKLOP, vklop).apply()
    }

    /** Stanje za stran Linka: {"vklopljeno", "dovoljenje", "deli"} - deli le, ko je oboje. */
    fun stanje(context: Context): JSONObject {
        val v = vklopljeno(context)
        val d = imamoDovoljenje(context)
        return JSONObject().put("vklopljeno", v).put("dovoljenje", d).put("deli", v && d)
    }

    /** Odgovor na `files.list`: {"items", "folder", "shared", "server"?} - ista oblika kot pri Controlu.
     *  Kadar naprava ne deli, pove zakaj: "reason" = "off" (stikalo) ali "permission" (brez dovoljenja). */
    fun seznam(context: Context, mapa: String, idNaprave: String): JSONObject {
        appContext = context.applicationContext
        val o = JSONObject().put("folder", if (mapa == "root") "" else mapa)
        if (!vklopljeno(context)) return o.put("items", JSONArray()).put("shared", false).put("reason", "off")
        if (!imamoDovoljenje(context)) return o.put("items", JSONArray()).put("shared", false).put("reason", "permission")
        val vnosi = JSONArray()
        var datotek = false
        if (mapa.isBlank() || mapa == "root") {
            for ((oznaka, ime) in listOf("video" to imeZbirke(context, "video"), "audio" to imeZbirke(context, "audio"), "image" to imeZbirke(context, "image"))) {
                vnosi.put(JSONObject().put("id", PREDPONA + oznaka).put("name", ime).put("type", "folder"))
            }
        } else {
            val zbirka = mapa.removePrefix(PREDPONA)
            val uri = zbirkaUri(zbirka) ?: return o.put("items", JSONArray()).put("shared", true)
            val stolpci = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
            try {
                context.contentResolver.query(uri, stolpci, null, null, MediaStore.MediaColumns.DISPLAY_NAME + " ASC")?.use { k ->
                    val ci = k.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val cn = k.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val cs = k.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val cm = k.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    while (k.moveToNext() && vnosi.length() < NAJVEC) {
                        val ime = k.getString(cn) ?: continue
                        vnosi.put(JSONObject().put("id", "$PREDPONA$zbirka:${k.getLong(ci)}").put("name", ime)
                            .put("type", zbirka).put("size", k.getLong(cs)).put("mime", k.getString(cm) ?: ""))
                        datotek = true
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Branje zbirke ni uspelo: ${e.message}")
            }
        }
        o.put("items", vnosi).put("shared", true)
        if (datotek) {
            val naslov = krajevniNaslov()
            if (zazeni() && naslov != null) {
                o.put("server", JSONObject().put("base_url", "https://$naslov:$vrata")
                    .put("fp", com.safeer.mobile.browser.cast.HubTls.lastniOdtis()).put("token", zetonZa(idNaprave)))
            }
        }
        return o
    }

    private fun imeZbirke(context: Context, zbirka: String): String {
        val ime = when (zbirka) { "audio" -> "os_krajevno_glasba"; "image" -> "os_krajevno_slike"; else -> "os_krajevno_videi" }
        val id = context.resources.getIdentifier(ime, "string", context.packageName)
        return if (id != 0) context.getString(id) else when (zbirka) { "audio" -> "Music"; "image" -> "Photos"; else -> "Videos" }
    }

    private fun zbirkaUri(zbirka: String): Uri? = when (zbirka) {
        "audio" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "image" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> null
    }

    /** Oznaka datoteke (media:video:123) -> content URI; null za vse, kar ni datoteka iz zbirke. */
    private fun uriIz(oznaka: String): Uri? {
        val deli = oznaka.removePrefix(PREDPONA).split(":")
        if (deli.size != 2) return null
        val id = deli[1].toLongOrNull() ?: return null
        return ContentUris.withAppendedId(zbirkaUri(deli[0]) ?: return null, id)
    }

    // ------------------------------------------------------------------ zetoni

    private fun zetonZa(idNaprave: String): String =
        zetoni.getOrPut(idNaprave.ifBlank { "naprava" }) {
            val b = ByteArray(24); nakljucje.nextBytes(b)
            android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

    private fun zetonVelja(z: String?): Boolean {
        if (z.isNullOrBlank()) return false
        val zb = z.toByteArray()
        return zetoni.values.any { MessageDigest.isEqual(it.toByteArray(), zb) }
    }

    // ------------------------------------------------------------------ streznik

    private var appContext: Context? = null

    @Synchronized
    private fun zazeni(): Boolean {
        if (tece.get() && vticnica?.isClosed == false) return true
        return try {
            val v = com.safeer.mobile.browser.cast.HubTls.streznik().createServerSocket(0, 8) as SSLServerSocket
            com.safeer.mobile.browser.cast.HubTls.nastaviStrezno(v)
            vticnica = v
            vrata = v.localPort
            tece.set(true)
            Thread({ zanka(v) }, "safeer-datoteke").apply { isDaemon = true; start() }
            Log.i(TAG, "Streznik datotek na vratih $vrata")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Streznika datotek ni bilo mogoce zagnati: ${e.message}")
            false
        }
    }

    fun pripravi(context: Context) { appContext = context.applicationContext }

    fun ustavi() {
        tece.set(false)
        try { vticnica?.close() } catch (_: Throwable) { }
        vticnica = null
    }

    private fun zanka(v: ServerSocket) {
        while (tece.get() && !v.isClosed) {
            val s = try { v.accept() } catch (_: Throwable) { break }
            Thread({ postrezi(s) }, "safeer-datoteke-zahteva").apply { isDaemon = true; start() }
        }
    }

    private fun postrezi(s: Socket) {
        try {
            s.soTimeout = 15_000
            val vhod = s.getInputStream()
            val izhod = s.getOutputStream()
            val prva = preberiVrstico(vhod) ?: return
            val glave = HashMap<String, String>()
            while (true) {
                val v = preberiVrstico(vhod) ?: break
                if (v.isEmpty()) break
                val i = v.indexOf(':')
                if (i > 0) glave[v.substring(0, i).trim().lowercase()] = v.substring(i + 1).trim()
            }
            val deli = prva.split(" ")
            if (deli.size < 2 || (deli[0] != "GET" && deli[0] != "HEAD")) { napaka(izhod, 405, "samo GET"); return }
            val cilj = deli[1]
            val pot = cilj.substringBefore('?')
            val poizvedba = cilj.substringAfter('?', "")
            // Zeton kot pri Controlu: glava X-Safeer-Token (predvajalnik, slike) ali ?t= (neposredna povezava).
            val zeton = glave["x-safeer-token"]
                ?: poizvedba.split("&").firstOrNull { it.startsWith("t=") }?.substring(2)?.let { URLDecoder.decode(it, "UTF-8") }
            if (!pot.startsWith("/d/")) { napaka(izhod, 404, "ni take poti"); return }
            if (!zetonVelja(zeton)) { napaka(izhod, 401, "manjka ali napacen zeton"); return }
            val ctx = appContext ?: run { napaka(izhod, 503, "ni pripravljeno"); return }
            val uri = uriIz(URLDecoder.decode(pot.substring(3), "UTF-8")) ?: run { napaka(izhod, 404, "datoteke ni"); return }
            val opis = try { ctx.contentResolver.openAssetFileDescriptor(uri, "r") } catch (_: Throwable) { null }
                ?: run { napaka(izhod, 404, "datoteke ni"); return }
            opis.use { o ->
                val velikost = o.length
                val vrsta = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                var zacetek = 0L
                var konec = velikost - 1
                var delni = false
                glave["range"]?.takeIf { it.startsWith("bytes=") }?.let { r ->
                    val ab = r.substring(6).split("-", limit = 2)
                    try {
                        if (ab[0].isEmpty() && ab.size > 1 && ab[1].isNotEmpty()) zacetek = maxOf(0L, velikost - ab[1].toLong())
                        else {
                            zacetek = ab[0].toLong()
                            if (ab.size > 1 && ab[1].isNotEmpty()) konec = minOf(velikost - 1, ab[1].toLong())
                        }
                        delni = true
                    } catch (_: Throwable) { delni = false }
                }
                if (delni && (zacetek > konec || zacetek >= velikost)) {
                    izhod.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$velikost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val dolzina = konec - zacetek + 1
                val sb = StringBuilder()
                sb.append(if (delni) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                sb.append("Content-Type: $vrsta\r\nAccept-Ranges: bytes\r\nContent-Length: $dolzina\r\n")
                if (delni) sb.append("Content-Range: bytes $zacetek-$konec/$velikost\r\n")
                sb.append("Cache-Control: private, max-age=0\r\nConnection: close\r\n\r\n")
                izhod.write(sb.toString().toByteArray())
                if (deli[0] == "HEAD") return
                o.createInputStream().use { vir ->
                    preskoci(vir, zacetek)
                    val b = ByteArray(64 * 1024)
                    var ostane = dolzina
                    while (ostane > 0) {
                        val n = vir.read(b, 0, minOf(b.size.toLong(), ostane).toInt())
                        if (n <= 0) break
                        izhod.write(b, 0, n)
                        ostane -= n
                    }
                }
                izhod.flush()
            }
        } catch (e: Throwable) {
            Log.i(TAG, "Zahteva: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { s.close() } catch (_: Throwable) { }
        }
    }

    private fun preskoci(vir: InputStream, koliko: Long) {
        var ostane = koliko
        while (ostane > 0) {
            val n = vir.skip(ostane)
            if (n <= 0) { if (vir.read() < 0) return; ostane-- } else ostane -= n
        }
    }

    private fun napaka(izhod: OutputStream, koda: Int, besedilo: String) {
        val telo = JSONObject().put("napaka", besedilo).toString().toByteArray()
        val beseda = when (koda) { 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        izhod.write("HTTP/1.1 $koda $beseda\r\nContent-Type: application/json\r\nContent-Length: ${telo.size}\r\nConnection: close\r\n\r\n".toByteArray())
        izhod.write(telo)
        izhod.flush()
    }

    private fun preberiVrstico(vhod: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val z = vhod.read()
            if (z < 0) return if (sb.isEmpty()) null else sb.toString()
            if (z == '\n'.code) break
            if (z != '\r'.code) sb.append(z.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    /** Naslov IPv4 te naprave v domacem omrezju (brez loopback in brez tun). */
    fun krajevniNaslov(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("dummy") }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) { null }
}
