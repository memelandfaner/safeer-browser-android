package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Iskanje Safeer Huba v krajevnem omrežju (mDNS / NSD).
 *
 * Hub se objavlja kot `_safeercast._tcp` in v lastnostih pove pot do WebSocketa (`ws`), pot do
 * vstopnice (`ticket`) in ali je potrebna avtentikacija (`auth`). Naslova zato ne ugibamo —
 * sestavimo ga iz tega, kar Hub sam objavi.
 *
 * Iskanje je kratko in se v vsakem primeru ustavi: mDNS, ki teče ves čas, je davek na baterijo.
 * Če Huba ni, uporabnik o njem ne izve ničesar — brskalnik je uporaben sam.
 */
object HubDiscovery {

    private const val TAG = "SafeerHubDiscovery"
    const val SERVICE_TYPE = "_safeercast._tcp."
    const val PREFS_NAME = "safeer_cast_prefs"
    const val KEY_HUB_URL = "hub_url"
    const val KEY_HUB_TICKET_PATH = "hub_ticket_path"
    const val KEY_HUB_SEEN = "hub_last_seen"


    private val preverjalniki = HashMap<String, OkHttpClient>()

    /**
     * Odjemalec za preverjanje naslova. Ce je naprava ze seznanjena, zaupa samo odtisu iz
     * seznanitve; pred seznanitvijo sprejme katerokoli potrdilo (zetona takrat ne posilja).
     */
    private fun preverjalnik(pripeti: String?): OkHttpClient = synchronized(preverjalniki) {
        preverjalniki.getOrPut(pripeti ?: "") {
            val g = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
            HubTls.okhttp(g, pripeti).first.build()
        }
    }

    /** Odzivna koda naslova; 0 pomeni, da se ni oglasil nihce (ali da potrdilo ni pravo). */
    private fun koda(url: String, pripeti: String?, odgovor: (Int) -> Unit) {
        try {
            preverjalnik(pripeti).newCall(Request.Builder().url(url).get().build())
                .enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        odgovor(0)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val koda = response.code
                        response.close()
                        odgovor(koda)
                    }
                })
        } catch (e: Exception) {
            Log.w(TAG, "Preverjanja ni bilo mogoce izvesti: ${e.message}")
            odgovor(0)
        }
    }

    /**
     * Ali na tem naslovu res odgovarja Safeer Hub?
     *
     * Ne zadosca, da se nekaj oglasi: na istih vratih je lahko cisto drug streznik, oglas
     * `_safeercast._tcp` pa lahko odda kdorkoli v omrezju. Preverimo dvoje, kar zna samo
     * Hub: `/cast/health` odgovori (200 ali 401, nikoli 404), `/cast/ticket` pa obstaja,
     * a ne kot GET (405 ali 401). Zetona pri tem ne posljemo -- to je preverba PRED
     * zaupanjem, ne po njem.
     */
    private fun preveriHub(osnova: String, pripeti: String?, odgovor: (Boolean) -> Unit) {
        koda("$osnova/cast/health", pripeti) { zdravje ->
            if (zdravje == 0 || zdravje == 404) {
                odgovor(false)
            } else {
                koda("$osnova/cast/ticket", pripeti) { vstopnica ->
                    odgovor(vstopnica != 0 && vstopnica != 404)
                }
            }
        }
    }

    /** Iz wss://gostitelj:vrata/pot naredi https://gostitelj:vrata (ws -> http). */
    private fun osnovaIz(naslov: String): String {
        val brezSheme = naslov.substringAfter("://", naslov)
        val gostitelj = brezSheme.substringBefore("/")
        val shema = if (naslov.startsWith("wss://") || naslov.startsWith("https://")) "https" else "http"
        return "$shema://$gostitelj"
    }

    /** Ali gre za isti Hub? Primerjamo gostitelja in vrata, ne sheme ne poti. */
    private fun istiHub(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return osnovaIz(a).substringAfter("://").equals(osnovaIz(b).substringAfter("://"), ignoreCase = true)
    }

    /** Ali je naprava ze seznanjena (ima zeton)? Potem je previdnost vecja. */
    private fun jeSeznanjena(context: Context): Boolean =
        !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("control_token", null).isNullOrBlank()

    /** Hub, ki se oglasa v omrezju, kot ga vidi izvolitev: naslov, odtis, id in prioriteta iz oglasa. */
    data class NajdeniHub(val naslov: String, val odtis: String, val id: String, val prioriteta: Int, val ime: String)

    /**
     * Zbere VSE hube, ki se oglasajo in so zivi (mDNS, [timeoutMs]), brez spreminjanja nastavitev.
     * Za izvolitev huba: klicatelj kandidate prefiltrira po krogu zaupanja in izbere najboljsega.
     * Klic na glavni niti, natanko enkrat.
     */
    fun poisciVse(context: Context, timeoutMs: Long = 2500L, naprej: (List<NajdeniHub>) -> Unit) {
        val app = context.applicationContext
        val glavna = Handler(Looper.getMainLooper())
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { glavna.post { naprej(emptyList()) }; return }
        val najdeni = java.util.Collections.synchronizedList(ArrayList<NajdeniHub>())
        val koncano = AtomicBoolean(false)
        val multicast = try {
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("safeer-hub-izvolitev")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { null }
        var listener: NsdManager.DiscoveryListener? = null
        fun zakljuci() {
            if (!koncano.compareAndSet(false, true)) return
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
            try { multicast?.release() } catch (_: Exception) {}
            val kopija = synchronized(najdeni) { najdeni.distinctBy { it.naslov } }
            glavna.post { naprej(kopija) }
        }
        fun razresevalec(): NsdManager.ResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo) {
                val gostitelj = info.host?.hostAddress ?: return
                val l = info.attributes ?: emptyMap<String, ByteArray>()
                if (l["tls"]?.toString(Charsets.UTF_8) != "1") return
                val wsPot = l["ws"]?.toString(Charsets.UTF_8) ?: "/cast/ws"
                val hub = NajdeniHub(
                    naslov = "wss://$gostitelj:${info.port}$wsPot",
                    odtis = l["fp"]?.toString(Charsets.UTF_8)?.lowercase().orEmpty(),
                    id = l[IzvolitevHuba.TXT_ID]?.toString(Charsets.UTF_8).orEmpty(),
                    prioriteta = IzvolitevHuba.prioritetaIzOglasa(l[IzvolitevHuba.TXT_PRIORITETA]?.toString(Charsets.UTF_8)),
                    ime = l["name"]?.toString(Charsets.UTF_8).orEmpty(),
                )
                // Zapis v mDNS prezivi hub, ki ga ni vec: steje samo ziv hub.
                preveriHub("https://$gostitelj:${info.port}", null) { ziv -> if (ziv && !koncano.get()) najdeni.add(hub) }
            }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (koncano.get()) return
                try { @Suppress("DEPRECATION") nsd.resolveService(info, razresevalec()) } catch (_: Exception) {}
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { zakljuci() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { zakljuci(); return }
        glavna.postDelayed({ zakljuci() }, timeoutMs)
    }

    /** Zadnji znani naslov Huba; prazen, dokler ga naprava ni nikoli videla. */
    fun knownHubUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HUB_URL, "") ?: ""

    /**
     * Poišče Hub. Povratni klic dobi naslov WebSocketa ali null, če Huba ni.
     * Klic se zgodi natanko enkrat in vedno na glavni niti.
     *
     * Vrstni red je vprašanje zaupanja, ne udobja: zadnji znani (torej že potrjeni) Hub
     * ima prednost. Dokler se oglaša, mDNS sploh ne zaženemo — oglas v omrežju lahko odda
     * kdorkoli, potrjeni naslov pa je uporabnik enkrat že odobril.
     */
    fun discover(context: Context, timeoutMs: Long = 5000L, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        val glavnaNit = Handler(Looper.getMainLooper())
        val znani = knownHubUrl(app)
        if (znani.isBlank()) {
            iskanjeZMdns(app, timeoutMs, onResult)
            return
        }
        // Znani Hub: ce smo seznanjeni, mora pokazati isto potrdilo kot ob seznanitvi.
        preveriHub(osnovaIz(znani), if (jeSeznanjena(app)) HubTls.pripetiOdtis(app) else null) { jeHub ->
            if (jeHub) {
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_HUB_SEEN, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "Znani Safeer Hub se oglaša; mDNS ni potreben.")
                glavnaNit.post { onResult(znani) }
            } else {
                iskanjeZMdns(app, timeoutMs, onResult)
            }
        }
    }

    private fun iskanjeZMdns(context: Context, timeoutMs: Long, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            Log.i(TAG, "NsdManager ni na voljo; Huba ne iščem.")
            onResult(null)
            return
        }

        val glavna = Handler(Looper.getMainLooper())
        val koncano = AtomicBoolean(false)
        val multicast = try {
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("safeer-hub-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock ni bil mogoč: ${e.message}")
            null
        }

        var listener: NsdManager.DiscoveryListener? = null

        fun zakljuci(naslov: String?) {
            if (!koncano.compareAndSet(false, true)) return
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
            try { multicast?.release() } catch (_: Exception) {}
            glavna.post { onResult(naslov) }
        }

        // NsdManager dovoli en razresevalec na eno razresevanje: isti objekt drugic
        // vrne "listener already in use". Storitev se pogosto pojavi veckrat (enkrat na
        // omrezni vmesnik), zato za vsako najdeno storitev naredimo nov razresevalec.
        fun novRazresevalec(): NsdManager.ResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Hub najden, a ga ni bilo mogoče razrešiti (napaka $errorCode).")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val gostitelj = info.host?.hostAddress
                if (gostitelj.isNullOrBlank()) {
                    Log.w(TAG, "Razrešen Hub brez naslova.")
                    return
                }
                val lastnosti = info.attributes ?: emptyMap<String, ByteArray>()
                val wsPot = lastnosti["ws"]?.toString(Charsets.UTF_8) ?: "/cast/ws"
                val ticketPot = lastnosti["ticket"]?.toString(Charsets.UTF_8) ?: "/api/auth/ws-ticket"
                // Hub brez TLS ne pride v postev: po povezavi gredo zetoni, datoteke in zaslon.
                val tls = lastnosti["tls"]?.toString(Charsets.UTF_8) == "1"
                if (!tls) {
                    Log.w(TAG, "Hub na $gostitelj ne govori TLS; ne prevzemam ga (posodobi Safeer na tisti napravi).")
                    return
                }
                val naslov = "wss://$gostitelj:${info.port}$wsPot"
                val odtisOglasa = lastnosti["fp"]?.toString(Charsets.UTF_8)?.lowercase().orEmpty()
                // V hisi je lahko vec sredisc (televizor, telefon, racunalnik). Hub prepoznamo po
                // odtisu potrdila, ne po naslovu: isti odtis na novem naslovu je isti Hub (nov IP),
                // drug odtis je drug Hub - nanj preklopimo, z zetonom iz shrambe seznanitev ali z
                // novo kodo. Oglas sam po sebi ne dobi nobenega zaupanja: to da sele odtis, ki ga
                // preverimo na povezavi, in seznanitev.
                val znani = knownHubUrl(app)
                val pripetiZdaj = if (jeSeznanjena(app)) HubTls.pripetiOdtis(app) else null
                val drugNaslov = znani.isNotBlank() && jeSeznanjena(app) && !istiHub(znani, naslov)
                val istiHubPoOdtisu = pripetiZdaj != null && odtisOglasa.isNotBlank() && odtisOglasa == pripetiZdaj.lowercase()
                val preklop = drugNaslov && !istiHubPoOdtisu
                // Zapis v mDNS ostane v omrezju, tudi ko Hub ze ne tece vec, in oglasi se
                // ne preverjajo sami. Zato naslov preverimo, preden mu karkoli zaupamo.
                val pripeti = if (preklop) null else pripetiZdaj
                preveriHub("https://$gostitelj:${info.port}", pripeti) { ziv ->
                    if (!ziv) {
                        Log.i(TAG, "Zapis za Hub obstaja, a to ni Safeer Hub: $gostitelj")
                        return@preveriHub
                    }
                    val ur = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    if (preklop) {
                        Seznanitve.zapomniTrenutno(app)
                        val znanZeton = Seznanitve.zeton(app, odtisOglasa)
                        if (znanZeton != null) {
                            Log.i(TAG, "Drug Hub, s katerim smo ze seznanjeni; prevzamem shranjeni zeton.")
                            ur.putString("control_token", znanZeton).putString(HubTls.KEY_HUB_FP, odtisOglasa)
                        } else {
                            Log.i(TAG, "Drug Hub; potrebna je nova seznanitev s kodo.")
                            ur.remove("control_token").remove(HubTls.KEY_HUB_FP)
                        }
                    }
                    Log.i(TAG, "Najden Safeer Hub: $naslov")
                    ur.putString(KEY_HUB_URL, naslov)
                        .putString(KEY_HUB_TICKET_PATH, ticketPot)
                        .putLong(KEY_HUB_SEEN, System.currentTimeMillis())
                        .apply()
                    zakljuci(naslov)
                }
            }
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Iščem Safeer Hub ...")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (koncano.get()) return
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, novRazresevalec())
                } catch (e: Exception) {
                    Log.w(TAG, "Razreševanje ni steklo: ${e.message}")
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Iskanje se ni začelo (napaka $errorCode).")
                zakljuci(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Iskanja ni bilo mogoče zagnati: ${e.message}")
            zakljuci(null)
            return
        }

        // Iskanje se konča tudi, če ne najde ničesar -- mDNS ne sme teči naprej.
        glavna.postDelayed({
            if (!koncano.get()) {
                Log.i(TAG, "Safeer Hub ni bil najden.")
                zakljuci(null)
            }
        }, timeoutMs)
    }
}
