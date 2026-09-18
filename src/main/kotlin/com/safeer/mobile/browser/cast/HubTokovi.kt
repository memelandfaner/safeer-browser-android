package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2.

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tokovi Safeer Huba: deljenje zaslona in prenos datotek.
 *
 * Nadzor (kdo komu kaj deli) gre po WebSocketu prek usmerjevalnika; VSEBINA gre tu, po
 * loceni zahtevi HTTP. Tako deljenje zaslona tece gladko, medtem ko se prenasa datoteka -
 * nista v isti vrsti - in nobena od njiju ne more zamasiti sporocil.
 *
 * Pravila, ki jih ta razred uveljavlja (glej SAFEER-ZNANJE.md):
 *  - vsak gledalec zaslona ima omejeno vrsto; ce ne dohaja, izgubi okvirje, ne pomnilnika;
 *  - datoteke se pisejo na disk v koscih, nikoli ne sestavljajo v pomnilniku;
 *  - hkrati tece omejeno stevilo prenosov in en sam deljen zaslon na posiljatelja;
 *  - ime cilja je enolicno; prostor na disku se preveri pred sprejemom.
 *
 * Razred ne pozna Androida; mapo dobi od klicatelja, da ga je mogoce preizkusiti v JVM.
 */
class HubTokovi(
    /** Kam se shranjujejo prejete datoteke, kadar je cilj ta naprava (mapa za prenose). */
    private val mapaPrenosov: () -> File,
    /** Zacasna mapa za datoteke, ki cakajo, da jih prevzame druga naprava. */
    private val mapaZacasna: () -> File,
    /** Ali je zeton v glavi veljaven (seznanjena naprava). */
    private val jeVeljavenZeton: (String?) -> Boolean,
    /** Id naprave, na kateri Hub tece (da vemo, kdaj je cilj gostitelj sam). */
    private val lastniId: () -> String,
    /** Klice se, ko je datoteka za gostitelja shranjena: ime in pot. */
    private val naPrejetoDatoteko: (String, File) -> Unit = { _, _ -> },
    private val ura: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Usmerjevalnik se pripne sem, da o vsebini obvesti ciljno napravo po WebSocketu:
     * ko je datoteka cela (share.file) in ko se deljenje zaslona konca (share.screen stop).
     * Posiljatelju tako ni treba vzdrzevati lastne povezave: vsebino odda po HTTP, Hub pove naprej.
     */
    @Volatile var naDatoteko: ((Datoteka) -> Unit)? = null
    @Volatile var naKonecZaslona: ((String, String) -> Unit)? = null
    /** Ali je ciljna naprava povezana; brez tega datoteke za tujo napravo ne sprejmemo. */
    @Volatile var jeCiljPovezan: ((String) -> Boolean)? = null
    /** Naprava, ki ji zeton pripada; posiljatelj se doloci iz zetona, ne iz poizvedbe. */
    @Volatile var napravaZeZetona: ((String?) -> String?)? = null
    /** Z eno napravo deli naenkrat ena naprava: vrne id naprave, ki cilj ze ima, ali null. */
    @Volatile var zasediCilj: ((String, String) -> String?)? = null
    @Volatile var sprostiCilj: ((String, String) -> Unit)? = null

    // ------------------------------------------------------------------ zaslon

    private class Gledalec {
        val vrsta = ArrayBlockingQueue<ByteArray>(VRSTA_GLEDALCA)
        @Volatile var konec = false
        fun ponudi(okvir: ByteArray) {
            // Kdor ne dohaja, izgubi najstarejsi okvir. Nikoli ne cakamo in nikoli ne kopicimo.
            while (!vrsta.offer(okvir)) vrsta.poll()
        }
    }

    private class Zaslon(val id: String, val kljuc: String, val posiljatelj: String) {
        @Volatile var zadnji: ByteArray? = null
        @Volatile var tece = true
        val gledalci = CopyOnWriteArrayList<Gledalec>()
        val zacetek: Long = System.currentTimeMillis()
    }

    private val zasloni = ConcurrentHashMap<String, Zaslon>()

    /** Deljenje se zacne z navadno zahtevo (usmerjevalnik jo poklice); vrne id in kljuc gledalca. */
    fun zacniZaslon(posiljatelj: String): Pair<String, String>? {
        // En deljen zaslon na posiljatelja: prejsnjega koncamo.
        zasloni.values.filter { it.posiljatelj == posiljatelj }.forEach { koncajZaslon(it.id) }
        if (zasloni.size >= NAJVEC_ZASLONOV) return null
        val id = nakljucno(8)
        val kljuc = nakljucno(16)
        zasloni[id] = Zaslon(id, kljuc, posiljatelj)
        return id to kljuc
    }

    fun koncajZaslon(id: String) {
        val z = zasloni.remove(id) ?: return
        z.tece = false
        for (g in z.gledalci) g.konec = true
        try { naKonecZaslona?.invoke(id, z.posiljatelj) } catch (_: Exception) { }
    }

    fun zaslonTece(id: String): Boolean = zasloni[id]?.tece == true

    // ------------------------------------------------------------------ datoteke

    class Datoteka(val id: String, val ime: String, val velikost: Long, val pot: File, val kljuc: String,
                   val cilj: String, val posiljatelj: String, val zaGostitelja: Boolean, val nastala: Long,
                   val sha256: String = "") {
        /** Pot, po kateri ciljna naprava datoteko prevzame (samo, ce ni za gostitelja). */
        fun potPrevzema(): String = "/cast/file/$id?k=$kljuc"
    }

    private val datoteke = ConcurrentHashMap<String, Datoteka>()
    private val prenosovZdaj = AtomicInteger(0)

    fun datoteka(id: String): Datoteka? = datoteke[id]

    /** Datoteke, ki jih nihce ni prevzel, pospravimo; klice se obcasno. */
    fun pocistiDatoteke() {
        val zdaj = ura()
        for ((id, d) in datoteke) {
            if (zdaj - d.nastala > DATOTEKA_VELJA_MS) {
                datoteke.remove(id)
                try { if (d.pot.parentFile == mapaZacasna()) d.pot.delete() } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP tokovi

    /**
     * Prevzame tokovne zahteve. Vrne true, ce je odgovoril sam (tudi z napako).
     *   PUT  /cast/file?name=&target=&from=      telo = datoteka; zeton v glavi
     *   GET  /cast/file/{id}?k=                  prevzem datoteke (kljuc iz share.file)
     *   POST /cast/screen/{id}?k=                tok okvirjev: 4 bajti dolzine + JPEG, ponavljaj
     *   GET  /cast/screen/{id}/stream?k=         MJPEG za gledalca
     *   GET  /cast/screen/{id}/view?k=           HTML stran gledalca (za televizor/brskalnik)
     */
    fun obdelaj(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream, vticnica: Socket): Boolean {
        val pot = zahteva.pot
        val krajevni = HubUsmerjevalnik.jeKrajevni(zahteva.odjemalec)
        if (!krajevni) {
            odgovori(izhod, 403, "{\"napaka\":\"samo v krajevnem omrežju\"}")
            return true
        }
        return when {
            pot == "/cast/file" && zahteva.metoda == "PUT" -> { sprejmiDatoteko(zahteva, vhod, izhod); true }
            pot.startsWith("/cast/file/") && zahteva.metoda == "GET" -> { posljiDatoteko(zahteva, izhod); true }
            pot.startsWith("/cast/screen/") && pot.endsWith("/stream") && zahteva.metoda == "GET" -> { gledajZaslon(zahteva, izhod, vticnica); true }
            pot.startsWith("/cast/screen/") && pot.endsWith("/view") && zahteva.metoda == "GET" -> { stranGledalca(zahteva, izhod); true }
            pot.startsWith("/cast/screen/") && zahteva.metoda == "POST" -> { sprejmiZaslon(zahteva, vhod, izhod, vticnica); true }
            else -> false
        }
    }

    // ---- zaslon: posiljatelj ----

    private fun sprejmiZaslon(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream, vticnica: Socket) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        if (z == null || z.kljuc != zahteva.poizvedba["k"]) {
            odgovori(izhod, 404, "{\"napaka\":\"tega deljenja ni\"}")
            return
        }
        val zeton = zahteva.glave["x-safeer-token"]
        if (!jeVeljavenZeton(zeton)) {
            odgovori(izhod, 401, "{\"napaka\":\"naprava ni seznanjena\"}")
            return
        }
        val lastnik = napravaZeZetona?.invoke(zeton)
        if (lastnik != null && lastnik != z.posiljatelj) {
            // Okvirje sme potiskati samo naprava, ki je deljenje zacela.
            odgovori(izhod, 403, "{\"napaka\":\"to deljenje pripada drugi napravi\"}")
            return
        }
        // Okvirji prihajajo, dokler posiljatelj deli; brez okvirja 20 s pomeni, da je odsel.
        vticnica.soTimeout = 20_000
        val glava = ByteArray(4)
        try {
            while (z.tece) {
                if (!preberiTocno(vhod, glava, 4)) break
                val dolzina = ((glava[0].toInt() and 0xFF) shl 24) or ((glava[1].toInt() and 0xFF) shl 16) or
                    ((glava[2].toInt() and 0xFF) shl 8) or (glava[3].toInt() and 0xFF)
                if (dolzina <= 0 || dolzina > NAJVECJI_OKVIR) break
                val okvir = ByteArray(dolzina)
                if (!preberiTocno(vhod, okvir, dolzina)) break
                z.zadnji = okvir
                for (g in z.gledalci) g.ponudi(okvir)
            }
        } catch (_: IOException) {
        } finally {
            koncajZaslon(id)
        }
        try { odgovori(izhod, 200, "{\"koncano\":true}") } catch (_: Exception) { }
    }

    // ---- zaslon: gledalec ----

    private fun gledajZaslon(zahteva: HubStreznik.Zahteva, izhod: OutputStream, vticnica: Socket) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        if (z == null || z.kljuc != zahteva.poizvedba["k"] || !z.tece) {
            odgovori(izhod, 404, "{\"napaka\":\"tega deljenja ni\"}")
            return
        }
        if (z.gledalci.size >= NAJVEC_GLEDALCEV) {
            odgovori(izhod, 503, "{\"napaka\":\"preveč gledalcev\"}")
            return
        }
        val g = Gledalec()
        z.gledalci.add(g)
        z.zadnji?.let { g.ponudi(it) }
        vticnica.soTimeout = 0
        try {
            izhod.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$MEJA\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            izhod.flush()
            while (z.tece && !g.konec) {
                val okvir = g.vrsta.poll(1, TimeUnit.SECONDS) ?: continue
                izhod.write(("--$MEJA\r\nContent-Type: image/jpeg\r\nContent-Length: ${okvir.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                izhod.write(okvir)
                izhod.write("\r\n".toByteArray(Charsets.US_ASCII))
                izhod.flush()
            }
            izhod.write("--$MEJA--\r\n".toByteArray(Charsets.US_ASCII))
            izhod.flush()
        } catch (_: Exception) {
        } finally {
            z.gledalci.remove(g)
        }
    }

    private fun stranGledalca(zahteva: HubStreznik.Zahteva, izhod: OutputStream) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        val kljuc = zahteva.poizvedba["k"] ?: ""
        if (z == null || z.kljuc != kljuc) {
            odgovori(izhod, 404, STRAN_KONEC, "text/html; charset=utf-8")
            return
        }
        val html = STRAN_GLEDALCA.replace("%%TOK%%", "/cast/screen/$id/stream?k=$kljuc")
        odgovori(izhod, 200, html, "text/html; charset=utf-8")
    }

    // ---- datoteke ----

    private fun sprejmiDatoteko(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream) {
        val zeton = zahteva.glave["x-safeer-token"]
        if (!jeVeljavenZeton(zeton)) {
            odgovori(izhod, 401, "{\"napaka\":\"naprava ni seznanjena\"}")
            return
        }
        val ime = varnoIme(zahteva.poizvedba["name"] ?: "")
        val cilj = (zahteva.poizvedba["target"] ?: "").take(64)
        // Posiljatelj je lastnik zetona; "from" v poizvedbi velja le, ce Hub zetonov ne veze.
        val posiljatelj = (napravaZeZetona?.invoke(zeton) ?: zahteva.poizvedba["from"] ?: "").take(64)
        val dolzina = zahteva.glave["content-length"]?.toLongOrNull() ?: -1L
        if (ime.isEmpty() || cilj.isEmpty() || dolzina < 0) {
            odgovori(izhod, 400, "{\"napaka\":\"manjka ime, cilj ali dolžina\"}")
            return
        }
        if (dolzina > NAJVECJA_DATOTEKA) {
            odgovori(izhod, 413, "{\"napaka\":\"datoteka je prevelika\"}")
            return
        }
        if (prenosovZdaj.get() >= NAJVEC_PRENOSOV) {
            odgovori(izhod, 503, "{\"napaka\":\"preveč hkratnih prenosov\"}")
            return
        }
        val zaGostitelja = cilj == lastniId()
        if (!zaGostitelja && jeCiljPovezan?.invoke(cilj) == false) {
            odgovori(izhod, 404, "{\"napaka\":\"ciljna naprava ni povezana\",\"koda\":\"naprava_ni_povezana\"}")
            return
        }
        val mapa = if (zaGostitelja) mapaPrenosov() else mapaZacasna()
        try { mapa.mkdirs() } catch (_: Exception) { }
        if (mapa.usableSpace in 1 until dolzina + REZERVA_PROSTORA) {
            odgovori(izhod, 507, "{\"napaka\":\"ni dovolj prostora\"}")
            return
        }
        // Med prenosom je cilj zaseden za to napravo: nihce drug mu medtem ne poslje nicesar.
        zasediCilj?.invoke(cilj, posiljatelj)?.let { kdo ->
            odgovori(izhod, 409, "{\"napaka\":\"z napravo trenutno deli druga naprava\",\"koda\":\"naprava_zasedena\",\"busy_by\":\"${ubezi(kdo)}\"}")
            return
        }
        val cilja = enolicnaPot(mapa, if (zaGostitelja) ime else nakljucno(6) + "-" + ime)
        val id = nakljucno(8)
        val kljuc = nakljucno(16)
        prenosovZdaj.incrementAndGet()
        var prejeto = 0L
        val prstni = java.security.MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(cilja).use { out ->
                val kos = ByteArray(KOS)
                while (prejeto < dolzina) {
                    val n = vhod.read(kos, 0, minOf(kos.size.toLong(), dolzina - prejeto).toInt())
                    if (n < 0) break
                    out.write(kos, 0, n)
                    prstni.update(kos, 0, n)
                    prejeto += n
                }
            }
        } catch (e: IOException) {
            try { cilja.delete() } catch (_: Exception) { }
            prenosovZdaj.decrementAndGet()
            sprostiCilj?.invoke(cilj, posiljatelj)
            odgovori(izhod, 500, "{\"napaka\":\"prenos prekinjen\"}")
            return
        }
        prenosovZdaj.decrementAndGet()
        sprostiCilj?.invoke(cilj, posiljatelj)
        if (prejeto != dolzina) {
            try { cilja.delete() } catch (_: Exception) { }
            odgovori(izhod, 400, "{\"napaka\":\"datoteka ni prišla cela\"}")
            return
        }
        val sha256 = prstni.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        // Ce je posiljatelj prstni odtis napovedal, se mora ujemati; sicer datoteke ne obdrzimo.
        val napovedan = zahteva.glave["x-safeer-sha256"]?.trim()?.lowercase()
        if (!napovedan.isNullOrEmpty() && napovedan != sha256) {
            try { cilja.delete() } catch (_: Exception) { }
            odgovori(izhod, 400, "{\"napaka\":\"prstni odtis se ne ujema\",\"koda\":\"napacen_odtis\"}")
            return
        }
        val d = Datoteka(id, cilja.name, prejeto, cilja, kljuc, cilj, posiljatelj, zaGostitelja, ura(), sha256)
        datoteke[id] = d
        if (zaGostitelja) {
            try { naPrejetoDatoteko(d.ime, cilja) } catch (_: Exception) { }
        }
        // Ciljni napravi pove Hub (share.file), posiljatelj je s tem opravil.
        try { naDatoteko?.invoke(d) } catch (_: Exception) { }
        odgovori(izhod, 200, "{\"id\":\"$id\",\"name\":\"${ubezi(d.ime)}\",\"size\":$prejeto,\"key\":\"$kljuc\",\"for_host\":$zaGostitelja,\"sha256\":\"$sha256\"}")
    }

    private fun posljiDatoteko(zahteva: HubStreznik.Zahteva, izhod: OutputStream) {
        val id = zahteva.pot.removePrefix("/cast/file/").substringBefore('/')
        val d = datoteke[id]
        if (d == null || d.kljuc != zahteva.poizvedba["k"] || !d.pot.isFile) {
            odgovori(izhod, 404, "{\"napaka\":\"te datoteke ni\"}")
            return
        }
        if (prenosovZdaj.get() >= NAJVEC_PRENOSOV) {
            odgovori(izhod, 503, "{\"napaka\":\"preveč hkratnih prenosov\"}")
            return
        }
        prenosovZdaj.incrementAndGet()
        try {
            izhod.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${d.pot.length()}\r\n" +
                "x-safeer-sha256: ${d.sha256}\r\n" +
                "Content-Disposition: attachment; filename=\"${ubezi(d.ime)}\"\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            FileInputStream(d.pot).use { vhodDat ->
                val kos = ByteArray(KOS)
                while (true) {
                    val n = vhodDat.read(kos)
                    if (n < 0) break
                    izhod.write(kos, 0, n)
                }
            }
            izhod.flush()
            // Prevzeta zacasna datoteka je opravila svoje.
            if (d.pot.parentFile == mapaZacasna()) {
                datoteke.remove(id)
                try { d.pot.delete() } catch (_: Exception) { }
            }
        } catch (_: IOException) {
        } finally {
            prenosovZdaj.decrementAndGet()
        }
    }

    // ------------------------------------------------------------------ pomozno

    private fun preberiTocno(vhod: InputStream, kam: ByteArray, koliko: Int): Boolean {
        var prebrano = 0
        while (prebrano < koliko) {
            val n = vhod.read(kam, prebrano, koliko - prebrano)
            if (n < 0) return false
            prebrano += n
        }
        return true
    }

    private fun odgovori(izhod: OutputStream, koda: Int, telo: String, vrsta: String = "application/json; charset=utf-8") {
        val bajti = telo.toByteArray(Charsets.UTF_8)
        val opis = when (koda) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
            503 -> "Service Unavailable"; 507 -> "Insufficient Storage"; else -> "OK"
        }
        izhod.write(("HTTP/1.1 $koda $opis\r\nContent-Type: $vrsta\r\nContent-Length: ${bajti.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        izhod.write(bajti)
        izhod.flush()
    }

    private fun ubezi(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val NAJVEC_ZASLONOV = 4
        const val NAJVEC_GLEDALCEV = 4
        const val VRSTA_GLEDALCA = 2
        const val NAJVECJI_OKVIR = 2 * 1024 * 1024
        const val NAJVEC_PRENOSOV = 3
        const val NAJVECJA_DATOTEKA = 4L * 1024 * 1024 * 1024
        const val REZERVA_PROSTORA = 200L * 1024 * 1024
        const val DATOTEKA_VELJA_MS = 60 * 60 * 1000L
        const val KOS = 64 * 1024
        private const val MEJA = "safeerokvir"

        private val nakljucniStevec = SecureRandom()

        fun nakljucno(bajtov: Int): String {
            val b = ByteArray(bajtov)
            nakljucniStevec.nextBytes(b)
            return b.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        }

        /** Ime brez poti in brez znakov, ki bi jih sistem razumel kot ukaz. */
        fun varnoIme(ime: String): String {
            val golo = ime.substringAfterLast('/').substringAfterLast('\\').trim()
            val ocisceno = golo.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").take(120)
            return if (ocisceno.isEmpty() || ocisceno == "." || ocisceno == "..") "datoteka" else ocisceno
        }

        /** Kot na namizju: ime (1).ext, ce datoteka ze obstaja - dve hkratni ne pisieta ena cez drugo. */
        @Synchronized
        fun enolicnaPot(mapa: File, ime: String): File {
            var kandidat = File(mapa, ime)
            if (!kandidat.exists()) return kandidat
            val pika = ime.lastIndexOf('.')
            val osnova = if (pika > 0) ime.substring(0, pika) else ime
            val koncnica = if (pika > 0) ime.substring(pika) else ""
            var i = 1
            while (kandidat.exists()) {
                kandidat = File(mapa, "$osnova ($i)$koncnica")
                i++
            }
            return kandidat
        }

        /** Gledalec: crno ozadje, slika umerjena po krajsi stranici, razmerje vedno ohranjeno. */
        private const val STRAN_GLEDALCA = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Safeer Link – zaslon</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
#konec{display:none;position:absolute;inset:0;color:#cbd5e1;font:20px sans-serif;align-items:center;justify-content:center;text-align:center;padding:24px}
</style></head><body>
<img id="zaslon" src="%%TOK%%" alt="">
<div id="konec">Deljenje zaslona je končano.</div>
<script>
var s=document.getElementById('zaslon');
s.onerror=function(){s.style.display='none';document.getElementById('konec').style.display='flex';};
</script></body></html>"""

        private const val STRAN_KONEC = """<!doctype html><html><head><meta charset="utf-8"><title>Safeer Link</title>
<style>html,body{margin:0;height:100%;background:#000;color:#cbd5e1;font:20px sans-serif;display:flex;align-items:center;justify-content:center}</style>
</head><body>Deljenje zaslona je končano.</body></html>"""
    }
}
