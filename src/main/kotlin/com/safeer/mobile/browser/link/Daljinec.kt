package com.safeer.mobile.browser.link

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ukazi Safeer Controla po Safeer Linku (sporocilo `control.command`).
 *
 * Seznanjena naprava (racunalnik s Safeer Controlom, telefon) sme tej napravi narocati samo
 * to, kar aplikacija sme narediti sama: tipke in drsenje v brskalniku, glasnost, odpiranje
 * strani, zagon aplikacij, ponovni zagon in ciscenje lastnega predpomnilnika. Nic od tega
 * ne potrebuje Shizukuja ali ADB - zato tudi ni skrite moci, ki bi jo lahko kdo zlorabil.
 *
 * Kar potrebuje odprt brskalnik (tipke, drsenje, posnetek), izvede dejavnost v ospredju
 * prek [VOspredju]; ce brskalnika ni na zaslonu, ukaz vrne razumljivo napako (razen
 * `home` in `open_url`, ki brskalnik pripeljeta v ospredje).
 *
 * Ista datoteka je v brskalniku za televizor (si.safeer.tv.link); spremembe
 * gredo v obe.
 */
object Daljinec {
    private const val TAG = "SafeerDaljinec"

    /** Zmoznost, s katero se naprava prijavi sredi scu: "to napravo je mogoce upravljati". */
    const val ZMOZNOST = "remote"

    /** Vsa dejanja, ki jih ta naprava razume; Control jih dobi v odgovoru na `status`. */
    val DEJANJA = listOf(
        "key", "scroll", "open_url", "volume", "launch_app", "open_in_app", "apps",
        "restart", "clear_cache", "status", "screenshot",
        // Protocol v1: ista imena kot pri ponudniku na racunalniku (Safeer Control), da odjemalec
        // (Safeer OS, Control) aplikacije katere koli naprave nasteje in zazene na en nacin.
        "apps.list", "apps.launch",
        // Zvok racunalnika na tej napravi (Safeer OS za racunalnik: Zvok -> Predvajaj tukaj).
        "audio.play", "audio.stop",
        // Datoteke te naprave (videi, glasba, slike) za druge naprave - kot jih deli Safeer Control.
        "files.list"
    )

    /** Zmoznost, s katero se naprava javi, da zna predvajati zvok racunalnika ([ZvokSprejemnik]). */
    const val ZMOZNOST_ZVOK = "audio"

    /** Izid ukaza: `ok`, kratko sporocilo za uporabnika in neobvezni podatki. */
    class Izid(val ok: Boolean, val sporocilo: String, val podatki: JSONObject? = null, val koda: String = "") {
        fun json(): JSONObject = JSONObject().apply {
            put("ok", ok)
            put("message", sporocilo)
            if (koda.isNotBlank()) put("code", koda)
            if (podatki != null) put("data", podatki)
        }
    }

    /** Kar zna izvesti samo odprti brskalnik. Vrne null, ce dejanja ne pozna. */
    interface VOspredju {
        fun izvediUkaz(dejanje: String, parametri: JSONObject): Izid?
    }

    /** Dejanja, za katera mora biti brskalnik odprt. */
    private val ZAHTEVA_OSPREDJE = setOf("key", "scroll", "screenshot")

    /** Tipke daljinca, ki jih dejavnost dobi kot navadne KeyEvent-e (kot s pravega daljinca). */
    val TIPKE: Map<String, Int> = mapOf(
        "up" to android.view.KeyEvent.KEYCODE_DPAD_UP,
        "down" to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        "center" to android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        "back" to android.view.KeyEvent.KEYCODE_BACK,
        "menu" to android.view.KeyEvent.KEYCODE_MENU,
        "play_pause" to android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "play" to android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
        "stop" to android.view.KeyEvent.KEYCODE_MEDIA_STOP,
        "next" to android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "rewind" to android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
        "fast_forward" to android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "channel_up" to android.view.KeyEvent.KEYCODE_CHANNEL_UP,
        "channel_down" to android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
        "page_up" to android.view.KeyEvent.KEYCODE_PAGE_UP,
        "page_down" to android.view.KeyEvent.KEYCODE_PAGE_DOWN,
        // Stevke: program po stevilki (Xplore TV v brskalniku jih razume kot tipke daljinca).
        "0" to android.view.KeyEvent.KEYCODE_0, "1" to android.view.KeyEvent.KEYCODE_1,
        "2" to android.view.KeyEvent.KEYCODE_2, "3" to android.view.KeyEvent.KEYCODE_3,
        "4" to android.view.KeyEvent.KEYCODE_4, "5" to android.view.KeyEvent.KEYCODE_5,
        "6" to android.view.KeyEvent.KEYCODE_6, "7" to android.view.KeyEvent.KEYCODE_7,
        "8" to android.view.KeyEvent.KEYCODE_8, "9" to android.view.KeyEvent.KEYCODE_9
    )

    /**
     * Izvede ukaz. Klice se na glavni niti (dejavnost in WebView to zahtevata).
     * @param ospredje dejavnost v ospredju ali null, ce brskalnik ni odprt
     * @param odpriStran kako odpreti stran, ko brskalnika ni v ospredju (storitev to zna)
     */
    fun izvedi(
        context: Context,
        dejanje: String,
        parametri: JSONObject,
        ospredje: VOspredju?,
        domacaStran: String,
        odpriStran: (String, String) -> Unit
    ): Izid {
        val d = dejanje.trim().lowercase()
        if (d !in DEJANJA) return Izid(false, "Neznano dejanje: $d", koda = "neznano_dejanje")
        // Protocol v1: apps.list / apps.launch sta enotni imeni; `app` je id iz kataloga (tu ime paketa).
        if (d == "apps.list") return seznamV1(context, parametri)
        if (d == "apps.launch") {
            return zazeniAplikacijo(context, parametri.optString("app", "").ifBlank { parametri.optString("package", "") })
        }
        // Zvok z racunalnika igra ne glede na to, kaj je na zaslonu.
        if (d == "audio.play") return ZvokSprejemnik.zacni(context, parametri)
        if (d == "audio.stop") return ZvokSprejemnik.ustavi()
        if (d == "files.list") {
            val podatki = DatotekeStreznik.seznam(context, parametri.optString("folder", ""), parametri.optString("_posiljatelj", ""))
            return Izid(true, if (podatki.optBoolean("shared")) "Datoteke" else "Naprava datotek ne deli", podatki)
        }
        try {
            // Najprej dejavnost: tipke, drsenje, posnetek in tudi status z odprto stranjo.
            if (ospredje != null) {
                val izid = ospredje.izvediUkaz(d, parametri)
                if (izid != null) return izid
            } else if (d in ZAHTEVA_OSPREDJE) {
                // "home" brskalnik odpre; vse druge tipke potrebujejo odprt brskalnik.
                if (d == "key" && parametri.optString("key") == "home") {
                    odpriStran(domacaStran, "")
                    return Izid(true, "Safeer se odpira")
                }
                return Izid(false, "Safeer ni odprt na zaslonu. Najprej odpri stran ali Domov.", koda = "ni_v_ospredju")
            }
            return when (d) {
                "open_url" -> odpriUrl(parametri, domacaStran, odpriStran)
                "volume" -> glasnost(context, parametri)
                "launch_app" -> zazeniAplikacijo(context, parametri.optString("package", ""))
                "open_in_app" -> odpriVAplikaciji(context, parametri.optString("package", ""), parametri.optString("url", ""))
                "apps" -> Izid(true, "Seznam aplikacij", JSONObject().put("apps", aplikacije(context, parametri.optBoolean("icons", false))))
                "restart" -> znovaZazeni(context)
                "clear_cache" -> pocistiPredpomnilnik(context)
                "status" -> Izid(true, "Stanje", stanje(context, null))
                else -> Izid(false, "Dejanje $d tu ni na voljo")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Ukaz $d ni uspel: ${e.message}")
            return Izid(false, "Ukaz ni uspel: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun odpriUrl(parametri: JSONObject, domacaStran: String, odpriStran: (String, String) -> Unit): Izid {
        var url = parametri.optString("url", "").trim()
        if (url == "home" || url == "safeer://home") url = domacaStran
        if (!(url.startsWith("http://") || url.startsWith("https://") || url == domacaStran)) {
            return Izid(false, "Dovoljeni so samo naslovi http(s)")
        }
        odpriStran(url, parametri.optString("title", ""))
        return Izid(true, "Stran se odpira")
    }

    /** Glasnost naprave (STREAM_MUSIC): gor/dol/utisaj ali raven 0-100. */
    fun glasnost(context: Context, parametri: JSONObject): Izid {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val najvec = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val smer = parametri.optString("direction", "").lowercase()
        val raven = if (parametri.has("level")) parametri.optInt("level", -1) else -1
        when {
            raven in 0..100 -> {
                val indeks = Math.round(raven / 100.0 * najvec).toInt().coerceIn(0, najvec)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, indeks, AudioManager.FLAG_SHOW_UI)
            }
            smer == "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            smer == "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            smer == "mute" -> utisaj(am, true)
            smer == "unmute" -> utisaj(am, false)
            smer == "toggle_mute" -> utisaj(am, !jeUtisano(am))
            smer.isEmpty() -> { /* samo branje */ }
            else -> return Izid(false, "Neznana smer glasnosti: $smer")
        }
        val trenutno = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val podatki = JSONObject()
            .put("level", Math.round(trenutno * 100.0 / najvec).toInt())
            .put("muted", jeUtisano(am))
        return Izid(true, "Glasnost ${podatki.getInt("level")} %", podatki)
    }

    private fun jeUtisano(am: AudioManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.isStreamMute(AudioManager.STREAM_MUSIC)
        else am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    private fun utisaj(am: AudioManager, utisano: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (utisano) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI
            )
        } else {
            @Suppress("DEPRECATION")
            am.setStreamMute(AudioManager.STREAM_MUSIC, utisano)
        }
    }

    /** Namera za zagon aplikacije po imenu paketa (na televizorju najprej Leanback). */
    fun nameraZaZagon(context: Context, paket: String): Intent? {
        val pm = context.packageManager
        val namera = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) pm.getLeanbackLaunchIntentForPackage(paket) else null)
            ?: pm.getLaunchIntentForPackage(paket) ?: return null
        namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return namera
    }

    /**
     * Zazene drugo aplikacijo. Android 10+ zagon iz ozadja pogosto tiho zavrne, zato poleg
     * neposrednega poskusa objavimo obvestilo s celozaslonsko namero - enako, kot brskalnik
     * odpre sam sebe, ko pride stran s telefona.
     */
    private fun zazeniAplikacijo(context: Context, paket: String): Izid {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(paket)) return Izid(false, "Neveljavno ime paketa")
        val namera = nameraZaZagon(context, paket) ?: return Izid(false, "Aplikacija $paket ni namescena", koda = "ni_namescena")
        val ime = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(paket, 0)).toString()
        } catch (_: Throwable) { paket }
        try {
            context.startActivity(namera)
        } catch (e: Throwable) {
            Log.w(TAG, "Neposredni zagon $paket ni uspel: ${e.message}")
        }
        prebudiZNamero(context, namera, ime)
        return Izid(true, "Odpiram $ime", JSONObject().put("package", paket).put("label", ime))
    }

    private const val KANAL_ZAGON = "safeer_link_zagon"
    private const val OBVESTILO_ZAGON = 4046

    private fun prebudiZNamero(context: Context, namera: Intent, ime: String) {
        try {
            val upravitelj = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && upravitelj.getNotificationChannel(KANAL_ZAGON) == null) {
                val kanal = android.app.NotificationChannel(KANAL_ZAGON, "Safeer Link - zagon aplikacij",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                kanal.description = "Odpre aplikacijo, ki jo izbere seznanjena naprava."
                kanal.setShowBadge(false)
                upravitelj.createNotificationChannel(kanal)
            }
            var zastavice = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) zastavice = zastavice or PendingIntent.FLAG_IMMUTABLE
            val cakajoca = PendingIntent.getActivity(context, OBVESTILO_ZAGON, namera, zastavice)
            val gradnik = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(context, KANAL_ZAGON)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            val obvestilo = gradnik
                .setContentTitle("Safeer Link")
                .setContentText(ime)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentIntent(cakajoca)
                .setFullScreenIntent(cakajoca, true)
                .setAutoCancel(true)
                .build()
            upravitelj.notify(OBVESTILO_ZAGON, obvestilo)
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila za zagon ni bilo mogoce objaviti: ${e.message}")
        }
    }

    /**
     * Povezavo odpre v izbrani aplikaciji (npr. iskanje v aplikaciji YouTube). Ce je
     * aplikacija ne zna sprejeti, vrne neuspeh in klicatelj jo odpre v Safeerju.
     */
    private fun odpriVAplikaciji(context: Context, paket: String, url: String): Izid {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(paket)) return Izid(false, "Neveljavno ime paketa")
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return Izid(false, "Dovoljeni so samo naslovi http(s)")
        val namera = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(paket)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val zna = try { context.packageManager.queryIntentActivities(namera, 0).isNotEmpty() } catch (_: Throwable) { false }
        if (!zna) return Izid(false, "Aplikacija $paket te povezave ne zna odpreti", koda = "ne_zna_povezave")
        val ime = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(paket, 0)).toString()
        } catch (_: Throwable) { paket }
        try { context.startActivity(namera) } catch (e: Throwable) { Log.w(TAG, "Zagon $paket z naslovom ni uspel: ${e.message}") }
        prebudiZNamero(context, namera, ime)
        return Izid(true, "Odpiram $ime", JSONObject().put("package", paket).put("label", ime))
    }

    /**
     * Aplikacije, ki jih je mogoce zagnati (Leanback ali navadni zaganjalnik), po imenu.
     * Z ikonami (48 px, WebP) za mrezo v daljincu; celoten seznam mora ostati pod mejo
     * sporocila sredisca (256 KiB), zato jih je najvec 60.
     */
    fun aplikacije(context: Context, zIkonami: Boolean = false): JSONArray {
        val pm = context.packageManager
        val najdene = LinkedHashMap<String, String>()
        val kategorije = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        else listOf(Intent.CATEGORY_LAUNCHER)
        for (kategorija in kategorije) {
            val namera = Intent(Intent.ACTION_MAIN).addCategory(kategorija)
            val seznam = try { pm.queryIntentActivities(namera, 0) } catch (_: Throwable) { emptyList() }
            for (info in seznam) {
                val paket = info.activityInfo?.packageName ?: continue
                if (paket == context.packageName || najdene.containsKey(paket)) continue
                val ime = try { info.loadLabel(pm).toString().trim() } catch (_: Throwable) { "" }
                // Brez cloveskega imena (samo ime paketa) je vnos za uporabnika neuporaben.
                if (ime.isBlank() || ime == paket || Regex("^[a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(ime)) continue
                najdene[paket] = ime
            }
        }
        val polje = JSONArray()
        for ((paket, ime) in najdene.entries.sortedBy { it.value.lowercase() }.take(60)) {
            val zapis = JSONObject().put("package", paket).put("label", ime)
            if (zIkonami) ikonaAplikacije(pm, paket)?.let { zapis.put("icon", it) }
            polje.put(zapis)
        }
        return polje
    }

    /**
     * Odgovor na `apps.list` v obliki, ki jo pozna tudi ponudnik na racunalniku:
     * {"enabled": true, "items": [{"id", "name", "icon"?}], "total", "offset"}. `icon` je data URL.
     */
    private fun seznamV1(context: Context, parametri: JSONObject): Izid {
        val zIkonami = parametri.optBoolean("icons", false)
        val polje = aplikacije(context, zIkonami)
        val elementi = JSONArray()
        for (i in 0 until polje.length()) {
            val z = polje.optJSONObject(i) ?: continue
            val e = JSONObject().put("id", z.optString("package")).put("name", z.optString("label"))
            if (z.has("icon")) e.put("icon", z.optString("icon"))
            elementi.put(e)
        }
        val podatki = JSONObject().put("enabled", true).put("items", elementi)
            .put("total", elementi.length()).put("offset", 0)
        return Izid(true, "Seznam aplikacij", podatki)
    }

    /** Ikona aplikacije kot data URL (WebP, 48 px); null, ce je ni mogoce narisati. */
    private fun ikonaAplikacije(pm: PackageManager, paket: String): String? = try {
        val risba = pm.getApplicationIcon(paket)
        val velikost = 48
        val slika = android.graphics.Bitmap.createBitmap(velikost, velikost, android.graphics.Bitmap.Config.ARGB_8888)
        val platno = android.graphics.Canvas(slika)
        risba.setBounds(0, 0, velikost, velikost)
        risba.draw(platno)
        val izhod = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        slika.compress(android.graphics.Bitmap.CompressFormat.WEBP, 70, izhod)
        slika.recycle()
        "data:image/webp;base64," + android.util.Base64.encodeToString(izhod.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (_: Throwable) { null }

    /** Ponovni zagon aplikacije: cez pol sekunde jo sistem odpre znova, potem se koncamo. */
    private fun znovaZazeni(context: Context): Izid {
        val namera = nameraZaZagon(context, context.packageName)
            ?: return Izid(false, "Ponovni zagon ni mogoc")
        try {
            var zastavice = PendingIntent.FLAG_CANCEL_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) zastavice = zastavice or PendingIntent.FLAG_IMMUTABLE
            val cakajoca = PendingIntent.getActivity(context, 4047, namera, zastavice)
            val budilka = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            budilka.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 700, cakajoca)
        } catch (e: Throwable) {
            return Izid(false, "Ponovnega zagona ni bilo mogoce nacrtovati: ${e.message}")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (_: Throwable) { }
        }, 400)
        return Izid(true, "Safeer se znova zaganja")
    }

    /** Pocisti lasten predpomnilnik (mapi cache in code_cache; WebView pocisti dejavnost). */
    private fun pocistiPredpomnilnik(context: Context): Izid {
        var bajtov = 0L
        for (mapa in listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir)) {
            bajtov += pobrisiVsebino(mapa)
        }
        val mb = bajtov / (1024.0 * 1024.0)
        return Izid(true, String.format(java.util.Locale.ROOT, "Predpomnilnik pociscen (%.1f MB)", mb),
            JSONObject().put("bytes", bajtov))
    }

    private fun pobrisiVsebino(mapa: java.io.File): Long {
        var skupaj = 0L
        val vnosi = mapa.listFiles() ?: return 0L
        for (v in vnosi) {
            try {
                if (v.isDirectory) {
                    skupaj += pobrisiVsebino(v)
                    v.delete()
                } else {
                    skupaj += v.length()
                    v.delete()
                }
            } catch (_: Throwable) { }
        }
        return skupaj
    }

    /** Osnovno stanje naprave; dejavnost doda odprto stran. */
    fun stanje(context: Context, dodatno: JSONObject?): JSONObject {
        val razlicica = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Throwable) { "" }
        val s = JSONObject()
            .put("app", context.packageName)
            .put("version", razlicica)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("foreground", dodatno != null)
            .put("can_wake", lahkoVOspredje(context))
            .put("actions", JSONArray(DEJANJA))
            .put("keys", JSONArray(TIPKE.keys.toList()))
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val najvec = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            s.put("volume", Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / najvec).toInt())
            s.put("muted", jeUtisano(am))
        } catch (_: Throwable) { }
        if (dodatno != null) {
            val kljuci = dodatno.keys()
            while (kljuci.hasNext()) {
                val k = kljuci.next()
                s.put(k, dodatno.get(k))
            }
        }
        return s
    }

    /**
     * Ali se aplikacija sme sama postaviti v ospredje (Android 10+: dovoljenje za prekrivanje).
     * Na telefonu tega ne zahtevamo - tam stran odpre obvestilo, ki ga uporabnik tapne.
     */
    fun lahkoVOspredje(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        // Telefon in tablica: stran ali ukaz odpre obvestilo, ki ga uporabnik tapne - dovoljenja ni treba.
        val televizor = try { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } catch (_: Throwable) { false }
        if (!televizor) return true
        return try { android.provider.Settings.canDrawOverlays(context) } catch (_: Throwable) { true }
    }

    /** Sporocilo `control.result`, ki gre prek sredisca nazaj posiljatelju ukaza. */
    fun sporociloIzida(cilj: String, refId: String, dejanje: String, izid: Izid): JSONObject =
        JSONObject().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("type", "control.result")
            put("target", cilj)
            put("ref_id", refId)
            put("payload", izid.json().put("action", dejanje))
        }
}
