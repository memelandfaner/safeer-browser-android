package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import java.security.SecureRandom
import java.util.UUID

/**
 * Usmerjevalnik Safeer Huba na televizorju: register naprav, seznanjanje, vstopnice in
 * posredovanje sporocil Cast/Sync.
 *
 * Govori natanko isti jezik kot Hub na racunalniku (core/cast/protocol.py, hub.py, router.py,
 * razlicica protokola 0.2), zato odjemalcev na telefonu in televizorju ni treba spreminjati -
 * zamenja se le, kje streznik stoji.
 *
 * Tri stvari so tu drugacne kot na racunalniku, in to namenoma:
 *
 *  1. Potrjevanje je krajevno in kratko. Kodo pokaze gostitelj - naprava, na kateri Safeer
 *     Link tece - uporabnik pa jo prepise na napravo, ki se prikljucuje. Kdor gostiteljevega
 *     zaslona ne vidi, se ne more prikljuciti, tudi ce je v istem omrezju; prav zato je
 *     Safeer Link uporaben tudi na javnem wifiju. Sest stevilk se z daljincem prebere, ne
 *     tipka - tipka jih telefon. Starejsi odjemalec, ki kodo se vedno kaze pri sebi in caka
 *     na potrditev tu, je prepoznan po povprasevanju /cast/pair/claim in ga vmesnik obravnava
 *     po starem. Zetona se na televizor ne vnasa nikoli.
 *  2. Potrditev ni na voljo po omrezju. Koncne tocke za cakajoce prijave, potrditev in odvzem
 *     dostopa obstajajo samo kot metode, ki jih poklice uporabniski vmesnik televizorja.
 *     Cesar ni v streznku, ni mogoce zlorabiti.
 *  3. Meje so postavljene ze v zasnovi, ne naknadno. Register naprav, cakajoce prijave,
 *     vstopnice in shramba za sinhronizacijo imajo vsak svojo zgornjo mejo, ker je televizor
 *     naprava z malo pomnilnika in ga sistem ob pomanjkanju brez opozorila ubije.
 *
 * Razred ne pozna Androida, da ga je mogoce preizkusiti v navadnem JVM.
 */
class HubUsmerjevalnik(
    private val shramba: Shramba? = null,
    private val ura: () -> Long = { System.currentTimeMillis() },
    private val nakljucni: (Int) -> String = { privzetoNakljucno(it) }
) {

    /** Trajna shramba za zetone seznanjenih naprav (na Androidu SharedPreferences). */
    interface Shramba {
        fun beri(kljuc: String): String?
        fun pisi(kljuc: String, vrednost: String)
    }

    /** Ena povezana naprava, kakor jo vidi usmerjevalnik. */
    interface Odjemalec {
        val naslov: String
        /** Vstopnica, s katero je bila povezava odprta (poizvedba ?ticket=); veze prijavo na napravo. */
        val vstopnica: String? get() = null
        fun poslji(besedilo: String)
        fun zapri(koda: Int, razlog: String)
    }

    private class Naprava(
        val id: String,
        var ime: String,
        var vloga: String,
        var zmoznosti: List<String>,
        var naslov: String,
        var zadnjic: Double,
        var povezava: Odjemalec?,
        // Protocol v1: model naprave. Prazno pri odjemalcih protokola 0.2.
        var protokol: String = "",
        var platforma: String = "",
        var vrsta: String = "",
        var razlicica: String = "",
        var prioriteta: Int = 0,
        /** Katalog aplikacij naprave: JSON objekt {"<id>": {"name": ..., "kind": ...}} ali prazno. */
        var aplikacije: String = ""
    )

    private class Prijava(
        val pairId: String,
        val deviceId: String,
        val ime: String,
        val pin: String,
        val naslov: String,
        var nastala: Long,
        var potrjena: Boolean = false,
        var zeton: String? = null,
        /** Koliko napacnih kod je naprava ze vtipkala; po NAJVEC_POSKUSOV prijava pade. */
        var poskusov: Int = 0,
        /**
         * Naprava govori po starem: kodo kaze sama in caka, da jo uporabnik potrdi tu.
         * Prepoznamo jo po tem, da povprasuje /cast/pair/claim. Samo takim napravam
         * vmesnik ponudi gumb Potrdi - vse druge kodo vtipkajo.
         */
        var staroPovprasevanje: Boolean = false,
        /** Tekoci krog SPAKE2 (po /cast/pair/spake, pred /cast/pair/finish). */
        var spake: Spake2? = null,
        /** Koliko krogov SPAKE2 je naprava zacela; tudi to je omejeno, da ne sonda v nedogled. */
        var krogov: Int = 0
    )

    /** Izid vnosa kode na napravi, ki se prikljucuje. */
    data class IzidKode(val zeton: String?, val napaka: String?)

    /** Izid prvega koraka SPAKE2: Hubovo sporocilo in potrditev, ali napaka. */
    data class IzidSpake(val pa: ByteArray?, val ca: ByteArray?, val napaka: String?)

    private class Kategorija(
        val ime: String,
        val razlicica: Double,
        val cas: Double,
        val podatkiSurovo: String,
        val vir: String?
    ) {
        val bajtov: Int = podatkiSurovo.toByteArray(Charsets.UTF_8).size
    }

    /** Podatki o cakajoci prijavi za vmesnik televizorja. */
    data class CakajocaPrijava(
        val pairId: String,
        val ime: String,
        val pin: String,
        val naslov: String,
        val starostSekund: Int,
        /** Naprava po starem caka na potrditev tu; nova napravo kodo vtipka pri sebi. */
        val potrebujePotrditev: Boolean = false
    )

    data class SeznanjenaNaprava(val deviceId: String, val ime: String, val seznanjenaOb: Double)

    private val kljucnica = Any()

    private val naprave = LinkedHashMap<String, Naprava>()
    private val posiljatelji = LinkedHashSet<Odjemalec>()
    private val prijave = LinkedHashMap<String, Prijava>()
    private val zetoni = LinkedHashMap<String, SeznanjenaNaprava>()
    /** Vstopnica za WebSocket: kdaj je bila izdana in kateri napravi (zeton ali podpis), ce je znano. */
    private class Vstopnica(val izdana: Long, val deviceId: String?)

    private val vstopnice = LinkedHashMap<String, Vstopnica>()

    /** Porabljene vstopnice, vezane na napravo: vstopnica -> device_id, dokler se povezava ne prijavi. */
    private val vezaneVstopnice = LinkedHashMap<String, String>()
    private val sinhronizacija = LinkedHashMap<String, Kategorija>()

    /** Klice se, ko se seznam cakajocih prijav spremeni, da vmesnik pokaze kodo brez spraševanja. */
    @Volatile
    var naSpremembePrijav: (() -> Unit)? = null

    /**
     * Prstni odtis (SHA-256, hex) potrdila TLS tega Huba. Vpleten je v seznanitev: naprava
     * v svoj izracun vplete odtis, ki ga je videla na povezavi, Hub svojega. Ce ju je kdo
     * vmes zamenjal (napadalec s svojim potrdilom), se potrditvi ne ujemata in seznanitev
     * pade - napadalec kode ne pozna in je ne more popraviti. Prazen v preizkusih brez TLS.
     */
    @Volatile
    var lastniOdtis: String = ""

    /** Klice se ob spremembi seznama povezanih naprav (za prikaz v Safeer Linku). */
    @Volatile
    var naSpremembeNaprav: (() -> Unit)? = null

    /**
     * Tokovi (zaslon, datoteke); nastavi jih krmilnik, ki pozna mape naprave. Ob nastavitvi se
     * usmerjevalnik pripne nanje: ko je datoteka cela, poslje cilju share.file; ko se deljenje
     * zaslona konca, poslje cilju share.screen stop. Posiljatelj za to ne rabi WebSocketa.
     */
    @Volatile
    var tokovi: HubTokovi? = null
        set(vrednost) {
            field = vrednost
            vrednost?.naDatoteko = { d -> datotekaPrispela(d) }
            vrednost?.naKonecZaslona = { id, _ -> zaslonKoncan(id) }
            vrednost?.jeCiljPovezan = { cilj -> synchronized(kljucnica) { naprave[cilj]?.povezava != null } }
            vrednost?.napravaZeZetona = { zeton -> napravaZeZetona(zeton) }
            vrednost?.zasediCilj = { cilj, posiljatelj -> zasedi(cilj, posiljatelj, "file") }
            vrednost?.sprostiCilj = { cilj, posiljatelj -> sprosti(cilj, posiljatelj) }
        }

    /** Deljeni zasloni, ki tecejo: id deljenja -> ciljna naprava oz. posiljatelj. */
    private val deljeniZasloni = HashMap<String, String>()
    private val deljeniZasloniPosiljatelji = HashMap<String, String>()

    // ------------------------------------------------------------------ ena naprava naenkrat
    //
    // Z eno napravo deli naenkrat samo ena naprava. Ce tablica deli zaslon s televizorjem,
    // mora racunalnik pocakati, da tablica konca; s telefonom pa lahko racunalnik deli
    // medtem. Tako na cilju nikoli ne trcita dva vira in uporabnik vedno ve, kaj gleda.

    private class Zasedba(val posiljatelj: String, val vrsta: String, val od: Long)

    /** cilj -> kdo z njim trenutno deli */
    private val zasedeno = HashMap<String, Zasedba>()

    /**
     * Zasede cilj za posiljatelja. Vrne null, ce je cilj prost (ali ga ze ima isti posiljatelj),
     * sicer id naprave, ki ga ima. Zasedba se sprosti ob koncu deljenja (sprosti).
     */
    private fun zasedi(cilj: String, posiljatelj: String, vrsta: String): String? {
        var spremenjeno = false
        val kdo = synchronized(kljucnica) {
            val z = zasedeno[cilj]
            if (z != null && z.posiljatelj != posiljatelj) return@synchronized z.posiljatelj
            if (z == null) spremenjeno = true
            zasedeno[cilj] = Zasedba(posiljatelj, vrsta, ura())
            null
        }
        if (spremenjeno) objaviNaprave()
        return kdo
    }

    private fun sprosti(cilj: String, posiljatelj: String) {
        val spremenjeno = synchronized(kljucnica) {
            val z = zasedeno[cilj]
            if (z != null && z.posiljatelj == posiljatelj) { zasedeno.remove(cilj); true } else false
        }
        if (spremenjeno) objaviNaprave()
    }

    /** Kdo trenutno deli s ciljem (id), ali null. */
    fun zasedenOd(cilj: String): String? = synchronized(kljucnica) { zasedeno[cilj]?.posiljatelj }

    private fun odgovorZasedeno(cilj: String, kdo: String): HubStreznik.Odgovor {
        val ime = imeNaprave(kdo)
        return HubStreznik.Odgovor(
            409,
            JsonLahki.Zapis()
                .niz("napaka", "Z napravo trenutno deli $ime. Počakaj, da konča.")
                .niz("koda", "naprava_zasedena")
                .niz("busy_by", kdo)
                .niz("busy_by_name", ime)
                .niz("target", cilj)
                .toString()
        )
    }

    // ------------------------------------------------------------------ imena naprav
    //
    // Uporabnik lahko napravo poimenuje po svoje ("Dnevna soba", "Matejeva tablica"). Ime
    // hrani Hub, zato ga vidijo vse naprave enako, ne glede na to, kaj naprava trdi o sebi.

    private val vzdevki = HashMap<String, String>()

    private fun naloziVzdevke() {
        val zapis = shramba?.beri(KLJUC_VZDEVKOV) ?: return
        val pogled = JsonLahki.objekt(zapis) ?: return
        for (id in pogled.kljuci()) {
            val ime = pogled.niz(id) ?: continue
            if (ime.isNotBlank()) vzdevki[id] = ime.take(NAJVEC_IMENA)
        }
    }

    private fun shraniVzdevke() {
        val shramba = this.shramba ?: return
        val zapis = JsonLahki.Zapis()
        for ((id, ime) in vzdevki) zapis.niz(id, ime)
        shramba.pisi(KLJUC_VZDEVKOV, zapis.toString())
    }

    /** Ime, kot ga vidi uporabnik: njegov vzdevek, sicer ime, ki ga je naprava povedala o sebi. */
    fun imeNaprave(id: String): String = synchronized(kljucnica) {
        vzdevki[id] ?: naprave[id]?.ime ?: zetoni.values.firstOrNull { it.deviceId == id }?.ime ?: id
    }

    /** Preimenuje napravo; prazno ime vzdevek odstrani. Vrne false pri neveljavnem imenu. */
    fun preimenuj(id: String, ime: String): Boolean {
        val cisto = ime.replace(Regex("[\\u0000-\\u001f<>]"), "").trim().take(NAJVEC_IMENA)
        if (id.isBlank()) return false
        synchronized(kljucnica) {
            if (cisto.isEmpty()) vzdevki.remove(id) else vzdevki[id] = cisto
            shraniVzdevke()
        }
        objaviNaprave()
        naSpremembeNaprav?.invoke()
        return true
    }

    // ------------------------------------------------------------------ krog zaupanja
    //
    // Kljuci naprav (KrogZaupanja). Hub ga hrani in razposilja; naprava, ki se izkaze s starim
    // zetonom, vanj vpise svoj kljuc in od takrat naprej pride s podpisom - brez nove kode.

    val krog = KrogZaupanja(shramba)

    /** Id in odtis TLS tega huba; nastavi krmilnik. Podpis prijave je vezan na odtis, da ga ni mogoce prenesti na drug hub. */
    @Volatile
    var lastniId: String = ""

    /** Odprti izzivi za prijavo s podpisom: nonce -> (device_id, izdan). */
    private val izzivi = LinkedHashMap<String, Pair<String, Long>>()

    init {
        naloziZetone()
        naloziVzdevke()
        krog.naSpremembo = { objaviKrog() }
    }

    private fun objaviKrog() {
        val sporocilo = sporociloKroga()
        val kopija = synchronized(kljucnica) { naprave.values.mapNotNull { it.povezava } }
        for (povezava in kopija) posljiVarno(povezava, sporocilo)
    }

    private fun sporociloKroga(): String = ovojnica("trust.update").surovo("payload", krog.json()).toString()

    /** Hub sam je clan kroga: krmilnik vpise njegov kljuc ob zagonu. */
    fun vpisiLastniKljuc(deviceId: String, ime: String, kljucB64: String, platforma: String) {
        lastniId = deviceId
        val obstojeci = krog.clan(deviceId)
        if (obstojeci != null && obstojeci.kljuc == kljucB64 && obstojeci.ime == ime) return
        krog.dodaj(KrogZaupanja.Clan(deviceId, kljucB64, ime, platforma, KrogZaupanja.zdaj(), deviceId))
    }

    /** Kaj naprava podpise ob prijavi: vezano na ta hub (odtis) in na izziv, zato podpis drugje ne velja. */
    fun podatkiZaPodpis(deviceId: String, nonce: String): ByteArray =
        "safeer-link-auth\n${lastniOdtis.lowercase()}\n$nonce\n$deviceId".toByteArray(Charsets.UTF_8)

    private fun pocistiIzzive() {
        val zdaj = ura()
        val potekli = izzivi.filterValues { zdaj - it.second > IZZIV_VELJA_MS }.keys.toList()
        for (k in potekli) izzivi.remove(k)
    }

    // ------------------------------------------------------------------ zetoni naprav

    private fun naloziZetone() {
        val zapis = shramba?.beri(KLJUC_ZETONOV) ?: return
        val pogled = JsonLahki.objekt(zapis) ?: return
        var podvojenih = 0
        for (zeton in pogled.kljuci()) {
            if (zetoni.size >= NAJVEC_SEZNANJENIH) break
            val naprava = pogled.objekt(zeton) ?: continue
            val id = naprava.niz("device_id") ?: continue
            val nova = SeznanjenaNaprava(id, naprava.nizAli("name", id), naprava.stevilo("paired_at") ?: 0.0)
            // Stare shrambe imajo isto napravo veckrat (vsaka ponovna seznanitev je dodala zeton);
            // obdrzimo najnovejso seznanitev.
            val obstojeca = zetoni.entries.firstOrNull { it.value.deviceId == id }
            if (obstojeca != null) {
                podvojenih++
                if (obstojeca.value.seznanjenaOb >= nova.seznanjenaOb) continue
                zetoni.remove(obstojeca.key)
            }
            zetoni[zeton] = nova
        }
        if (podvojenih > 0) shraniZetone()
    }

    /** Seznam je poln, razen ce se ista naprava le znova seznanja (njen stari vnos bo zamenjan). */
    private fun jePolno(deviceId: String): Boolean =
        zetoni.size >= NAJVEC_SEZNANJENIH && zetoni.values.none { it.deviceId == deviceId }

    /**
     * Ponovna seznanitev iste naprave zamenja prejsnjo: stari zeton je naprava ze zavrgla,
     * v seznamu pa bi jo uporabnik sicer videl dvakrat.
     */
    private fun vpisiZeton(zeton: String, naprava: SeznanjenaNaprava) {
        val stari = zetoni.filterValues { it.deviceId == naprava.deviceId }.keys.toList()
        for (kljuc in stari) zetoni.remove(kljuc)
        zetoni[zeton] = naprava
    }

    private fun shraniZetone() {
        val shramba = this.shramba ?: return
        val zapis = JsonLahki.Zapis()
        for ((zeton, naprava) in zetoni) {
            zapis.surovo(
                zeton,
                JsonLahki.Zapis()
                    .niz("device_id", naprava.deviceId)
                    .niz("name", naprava.ime)
                    .stevilo("paired_at", naprava.seznanjenaOb)
                    .toString()
            )
        }
        shramba.pisi(KLJUC_ZETONOV, zapis.toString())
    }

    /** Primerjava v stalnem casu; zeton je varnostna vrednost, ne navaden niz. */
    private fun enaka(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var razlika = 0
        for (i in a.indices) razlika = razlika or (a[i].code xor b[i].code)
        return razlika == 0
    }

    fun jeVeljavenZeton(zeton: String?): Boolean {
        if (zeton.isNullOrEmpty()) return false
        synchronized(kljucnica) {
            for (znani in zetoni.keys) if (enaka(znani, zeton)) return true
        }
        return napravaSeje(zeton) != null
    }

    // ------------------------------------------------------------------ seje (krog zaupanja)

    /** Sejni zeton -> (naprava, potece). Izda ga prijava s podpisom; velja za HTTP kot zeton seznanitve. */
    private val seje = LinkedHashMap<String, Pair<String, Long>>()

    /**
     * Sejni zeton za napravo, ki se je prijavila s podpisom kljuca (krog zaupanja). Brez njega bi
     * naprava, ki se je umaknila izvoljenemu hubu, lahko odprla WebSocket, ne pa poslala datoteke ali
     * deliti zaslona (te gredo po HTTP z zetonom). Zeton zivi v pomnilniku huba: ob novem hubu se
     * naprava prijavi znova in dobi novega.
     */
    fun izdajSejo(deviceId: String): String = synchronized(kljucnica) {
        pocistiSeje()
        while (seje.size >= NAJVEC_SEJ) seje.remove(seje.keys.first())
        val zeton = "saf_seja_" + nakljucni(24)
        seje[zeton] = Pair(deviceId, ura() + SEJA_VELJA_MS)
        zeton
    }

    private fun pocistiSeje() {
        val zdaj = ura()
        val potekle = seje.filterValues { it.second < zdaj }.keys.toList()
        for (k in potekle) seje.remove(k)
    }

    /** Naprava sejnega zetona, ce je zeton veljaven in je naprava se v krogu (umik iz kroga sejo ubije). */
    private fun napravaSeje(zeton: String): String? {
        val naprava = synchronized(kljucnica) {
            pocistiSeje()
            seje.entries.firstOrNull { enaka(it.key, zeton) }?.value?.first
        } ?: return null
        return if (krog.clanZaId(naprava) != null) naprava else null
    }

    /**
     * Naprava, ki ji zeton pripada. Zahteve po HTTP tako ne morejo trditi, da prihajajo z
     * druge naprave: posiljatelj je tisti, cigar zeton je, ne tisti, ki je zapisan v telesu.
     */
    fun napravaZeZetona(zeton: String?): String? {
        if (zeton.isNullOrEmpty()) return null
        synchronized(kljucnica) {
            for ((znani, naprava) in zetoni) if (enaka(znani, zeton)) return naprava.deviceId
        }
        return napravaSeje(zeton)
    }

    // ------------------------------------------------------------------ seznanjanje

    private fun pocistiPrijave() {
        val zdaj = ura()
        val potekle = prijave.filterValues {
            zdaj - it.nastala > (if (it.potrjena) PREVZEM_VELJA_MS else PIN_VELJA_MS)
        }.keys.toList()
        for (kljuc in potekle) prijave.remove(kljuc)
    }

    /**
     * Naprava se prijavi in dobi kodo, ki jo pokaze na svojem zaslonu. Vrne null, ce je
     * cakajocih prijav prevec - takrat naj naprava poskusi cez nekaj minut.
     */
    fun zacniSeznanitev(deviceId: String, ime: String, naslov: String): Pair<String, String>? {
        synchronized(kljucnica) {
            pocistiPrijave()
            // Ista naprava, ki poskusa znova, naj ne kopici prijav.
            val stare = prijave.filterValues { it.deviceId == deviceId }.keys.toList()
            for (kljuc in stare) prijave.remove(kljuc)
            if (prijave.size >= NAJVEC_CAKAJOCIH) return null
            val prijava = Prijava(
                pairId = nakljucni(8),
                deviceId = deviceId,
                ime = if (ime.isBlank()) deviceId else ime,
                pin = pin(),
                naslov = naslov,
                nastala = ura()
            )
            prijave[prijava.pairId] = prijava
            naSpremembePrijav?.invoke()
            return prijava.pairId to prijava.pin
        }
    }

    /** Sestmestna koda. SecureRandom, ne navadni random - to je varnostna vrednost. */
    private fun pin(): String {
        val stevilka = 100000 + (nakljucniStevec.nextInt(900000))
        return stevilka.toString()
    }

    /** Kaj caka na potrditev; vmesnik televizorja pokaze ime in kodo. */
    fun cakajocePrijave(): List<CakajocaPrijava> = synchronized(kljucnica) {
        pocistiPrijave()
        prijave.values.map {
            CakajocaPrijava(it.pairId, it.ime, it.pin, it.naslov,
                ((ura() - it.nastala) / 1000).toInt(), it.staroPovprasevanje)
        }
    }

    /**
     * Uporabnik je na televizorju pritisnil V redu. Izda zeton, ki ga naprava prevzame sama -
     * televizor ga nikoli ne pokaze in uporabniku ga ni treba nikamor prepisovati.
     */
    fun potrdiPrijavo(pairId: String): Boolean = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return false
        if (jePolno(prijava.deviceId)) {
            // Raje povemo, da ne gre, kot da bi seznam rasel v nedogled.
            return false
        }
        prijava.potrjena = true
        prijava.nastala = ura()
        prijava.zeton = "saf_tv_" + nakljucni(24)
        vpisiZeton(prijava.zeton!!, SeznanjenaNaprava(prijava.deviceId, prijava.ime, ura() / 1000.0))
        shraniZetone()
        naSpremembePrijav?.invoke()
        return true
    }

    /**
     * Naprava, ki se prikljucuje, vtipka sestmestno kodo, ki jo gostitelj pokaze na svojem
     * zaslonu. Kdor kode ne vidi, se ne more prikljuciti - tudi ce je v istem omrezju.
     * Prav to je razlog, da je Safeer Link varen tudi na javnem wifiju.
     *
     * Ugibanja ni: po NAJVEC_POSKUSOV zgresenih kodah prijava pade in naprava mora zaceti
     * znova, kar pomeni novo kodo. Primerjava kode tece v stalnem casu.
     */
    fun potrdiSKodo(pairId: String, koda: String): IzidKode = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return IzidKode(null, "prijava_ne_obstaja")
        if (prijava.potrjena && prijava.zeton != null) {
            // Ista naprava je kodo ze vnesla; zeton dobi natanko enkrat.
            val zeton = prijava.zeton
            prijave.remove(pairId)
            naSpremembePrijav?.invoke()
            return IzidKode(zeton, null)
        }
        val vnos = koda.trim()
        if (!enaka(prijava.pin, vnos)) {
            prijava.poskusov += 1
            if (prijava.poskusov >= NAJVEC_POSKUSOV) {
                prijave.remove(pairId)
                naSpremembePrijav?.invoke()
                return IzidKode(null, "prevec_poskusov")
            }
            naSpremembePrijav?.invoke()
            return IzidKode(null, "napacna_koda")
        }
        if (jePolno(prijava.deviceId)) {
            return IzidKode(null, "preveč_naprav")
        }
        val zeton = "saf_tv_" + nakljucni(24)
        vpisiZeton(zeton, SeznanjenaNaprava(prijava.deviceId, prijava.ime, ura() / 1000.0))
        shraniZetone()
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return IzidKode(zeton, null)
    }

    /**
     * Prvi korak seznanitve s SPAKE2 (RFC 9382): naprava poslje svojo tocko pB, Hub iz kode,
     * ki jo kaze na zaslonu, izpelje svojo in vrne pA s potrditvijo cA. Koda po omrezju ne
     * potuje; kdor je ne pozna, iz pA/pB ne izve nic in je ne more uganiti brez povezave.
     *
     * Kot sol sluzi pair_id (isti prepis ne velja v dveh sejah), kot dodatni podatek (AAD)
     * pa odtis potrdila TLS: naprava vplete odtis, ki ga je videla, Hub svojega.
     */
    fun spakeKorak1(pairId: String, deviceId: String, pb: ByteArray): IzidSpake = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return IzidSpake(null, null, "prijava_ne_obstaja")
        if (prijava.deviceId != deviceId) return IzidSpake(null, null, "prijava_ne_obstaja")
        prijava.krogov += 1
        if (prijava.krogov > NAJVEC_POSKUSOV) {
            prijave.remove(pairId)
            naSpremembePrijav?.invoke()
            return IzidSpake(null, null, "prevec_poskusov")
        }
        return try {
            val s = Spake2.streznik(prijava.pin, IDENTITETA_HUBA, prijava.deviceId,
                lastniOdtis.toByteArray(Charsets.UTF_8), pairId.toByteArray(Charsets.UTF_8))
            val ca = s.zakljuci(pb)
            prijava.spake = s
            IzidSpake(s.sporocilo(), ca, null)
        } catch (e: IllegalArgumentException) {
            prijava.spake = null
            IzidSpake(null, null, "neveljavna_tocka")
        }
    }

    /**
     * Drugi korak: naprava poslje svojo potrditev cB. Ujemanje pomeni, da pozna isto kodo
     * in da je videla isto potrdilo TLS - takrat dobi zeton. Sicer steje kot zgresena koda.
     */
    fun spakeKorak2(pairId: String, deviceId: String, cb: ByteArray): IzidKode = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return IzidKode(null, "prijava_ne_obstaja")
        if (prijava.deviceId != deviceId) return IzidKode(null, "prijava_ne_obstaja")
        val s = prijava.spake ?: return IzidKode(null, "manjka_korak")
        prijava.spake = null
        if (!s.preveri(cb)) {
            prijava.poskusov += 1
            if (prijava.poskusov >= NAJVEC_POSKUSOV) {
                prijave.remove(pairId)
                naSpremembePrijav?.invoke()
                return IzidKode(null, "prevec_poskusov")
            }
            naSpremembePrijav?.invoke()
            return IzidKode(null, "napacna_koda")
        }
        if (jePolno(prijava.deviceId)) return IzidKode(null, "preveč_naprav")
        val zeton = "saf_tv_" + nakljucni(24)
        vpisiZeton(zeton, SeznanjenaNaprava(prijava.deviceId, prijava.ime, ura() / 1000.0))
        shraniZetone()
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return IzidKode(zeton, null)
    }

    /** Zabelezi, da naprava caka po starem, da ji vmesnik ponudi gumb Potrdi. */
    private fun oznaciStaroNapravo(pairId: String): Unit = synchronized(kljucnica) {
        val prijava = prijave[pairId] ?: return
        if (!prijava.staroPovprasevanje) {
            prijava.staroPovprasevanje = true
            naSpremembePrijav?.invoke()
        }
    }

    /** Naprava, ki je prijavo zacela, jo je opustila: koda na zaslonu ne sme viseti do poteka. */
    fun prekliciPrijavo(pairId: String, deviceId: String): Boolean = synchronized(kljucnica) {
        val prijava = prijave[pairId] ?: return false
        if (prijava.deviceId != deviceId || prijava.potrjena) return false
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return true
    }

    fun zavrniPrijavo(pairId: String): Boolean = synchronized(kljucnica) {
        val odstranjena = prijave.remove(pairId) != null
        if (odstranjena) naSpremembePrijav?.invoke()
        return odstranjena
    }

    /** Naprava prevzame svoj zeton. Uspe natanko enkrat. */
    fun prevzemiZeton(pairId: String): String? = synchronized(kljucnica) {
        pocistiPrijave()
        val prijava = prijave[pairId] ?: return null
        if (!prijava.potrjena || prijava.zeton == null) return null
        prijave.remove(pairId)
        naSpremembePrijav?.invoke()
        return prijava.zeton
    }

    /**
     * Zeton za napravo, na kateri Hub tece: gostitelj je hkrati zaslon, na katerega je mogoce
     * posiljati. Klice se samo iz procesa, nikoli po omrezju - koncne tocke za to ni.
     * Ista naprava dobi vedno isti zeton, da se ob vsakem zagonu ne kopicijo novi.
     */
    fun zagotoviLastniZeton(deviceId: String, ime: String): String = synchronized(kljucnica) {
        for ((zeton, naprava) in zetoni) if (naprava.deviceId == deviceId) return zeton
        val zeton = "saf_tv_" + nakljucni(24)
        zetoni[zeton] = SeznanjenaNaprava(deviceId, ime, ura() / 1000.0)
        shraniZetone()
        return zeton
    }

    // ------------------------------------------------------------------ prijava s QR kodo

    /**
     * Prijava s QR kodo (Safeer OS in Safeer Control na racunalniku): naprava pokaze QR, uporabnik ga
     * poskenira s telefonom ali tablico, ki sta ze v Safeer Linku, in tam potrdi »Dovoli«.
     *
     * - V QR je skrivnost; hub pozna samo njen SHA-256, zato je ne more izdati niti sam.
     * - Dovoli lahko samo ze seznanjena naprava (njen zeton) in samo, kdor QR vidi (skrivnost).
     * - Zeton prevzame samo naprava, ki je prijavo zacela: za prevzem ima drugo skrivnost, ki je v QR
     *   ni. Kdor QR fotografira, z njim zetona ne dobi.
     * - Ugibanja ni: po NAJVEC_POSKUSOV napacnih skrivnostih prijava pade; velja PIN_VELJA_MS.
     */
    private class QrPrijava(
        val qrId: String,
        val deviceId: String,
        val ime: String,
        val platforma: String,
        /** SHA-256 (hex) skrivnosti iz QR. */
        val odtisSkrivnosti: String,
        /** Skrivnost za prevzem zetona; pozna jo samo naprava, ki je prijavo zacela. */
        val prevzem: String,
        var nastala: Long,
        var zeton: String? = null,
        var odobril: String = "",
        var poskusov: Int = 0
    )

    private val qrPrijave = LinkedHashMap<String, QrPrijava>()

    /** Kar naprava, ki dovoljuje, pokaze uporabniku, preden potrdi. */
    data class QrPodatki(val deviceId: String, val ime: String, val platforma: String)

    private fun pocistiQr() {
        val zdaj = ura()
        qrPrijave.entries.removeAll { zdaj - it.value.nastala > (if (it.value.zeton != null) PREVZEM_VELJA_MS else PIN_VELJA_MS) }
    }

    /** Odpre prijavo s QR kodo. Vrne qr_id ali napako (prevec_prijav, neveljavno). */
    fun zacniQr(deviceId: String, ime: String, platforma: String, odtisSkrivnosti: String, prevzem: String): Pair<String?, String?> =
        synchronized(kljucnica) {
            if (!Regex("^[0-9a-f]{64}$").matches(odtisSkrivnosti) || prevzem.length !in 16..128) return null to "neveljavno"
            pocistiQr()
            qrPrijave.entries.removeAll { it.value.deviceId == deviceId }
            if (qrPrijave.size >= NAJVEC_CAKAJOCIH) return null to "prevec_prijav"
            val p = QrPrijava(nakljucni(12), deviceId, if (ime.isBlank()) deviceId else ime,
                platforma.take(16), odtisSkrivnosti, prevzem, ura())
            qrPrijave[p.qrId] = p
            return p.qrId to null
        }

    /** Prijava, ce skrivnost iz QR drzi; sicer napaka (qr_ne_obstaja, prevec_poskusov). Klice se pod kljucnico. */
    private fun qrZaSkrivnost(qrId: String, skrivnost: String): Pair<QrPrijava?, String?> {
        pocistiQr()
        val p = qrPrijave[qrId] ?: return null to "qr_ne_obstaja"
        if (!enaka(sha256Hex(skrivnost), p.odtisSkrivnosti)) {
            p.poskusov += 1
            if (p.poskusov >= NAJVEC_POSKUSOV) {
                qrPrijave.remove(qrId)
                return null to "prevec_poskusov"
            }
            return null to "qr_ne_obstaja"
        }
        return p to null
    }

    /** Kdo se zeli prijaviti - za vprasanje na napravi, ki dovoljuje. */
    fun qrPodatki(qrId: String, skrivnost: String): Pair<QrPodatki?, String?> = synchronized(kljucnica) {
        val (p, napaka) = qrZaSkrivnost(qrId, skrivnost)
        if (p == null) return null to napaka
        return QrPodatki(p.deviceId, p.ime, p.platforma) to null
    }

    /**
     * Seznanjena naprava [odobril] dovoli prijavo. Zeton nastane takoj (kot pri kodi), prevzame
     * ga naprava, ki je prijavo zacela. Ponovna potrditev iste prijave nicesar ne spremeni.
     */
    fun odobriQr(qrId: String, skrivnost: String, odobril: String): Pair<QrPodatki?, String?> {
        val izid = synchronized(kljucnica) {
            val (p, napaka) = qrZaSkrivnost(qrId, skrivnost)
            if (p == null) return null to napaka
            if (p.deviceId == odobril) return null to "ista_naprava"
            if (p.zeton == null) {
                if (jePolno(p.deviceId)) return null to "prevec_naprav"
                val zeton = "saf_tv_" + nakljucni(24)
                vpisiZeton(zeton, SeznanjenaNaprava(p.deviceId, p.ime, ura() / 1000.0))
                shraniZetone()
                p.zeton = zeton
                p.odobril = odobril
                p.nastala = ura()
            }
            QrPodatki(p.deviceId, p.ime, p.platforma)
        }
        naSpremembeNaprav?.invoke()
        return izid to null
    }

    /**
     * Naprava, ki je prijavo zacela, vprasa za izid: ("caka", null), ("odobreno", zeton) natanko enkrat,
     * ali ("qr_ne_obstaja", null) - tudi ob napacni skrivnosti za prevzem, da ne izdamo, kaj obstaja.
     */
    fun prevzemiQr(qrId: String, deviceId: String, prevzem: String): Pair<String, String?> = synchronized(kljucnica) {
        pocistiQr()
        val p = qrPrijave[qrId] ?: return "qr_ne_obstaja" to null
        if (p.deviceId != deviceId || !enaka(p.prevzem, prevzem)) return "qr_ne_obstaja" to null
        val zeton = p.zeton ?: return "caka" to null
        qrPrijave.remove(qrId)
        return "odobreno" to zeton
    }

    /** Naprava je okno zaprla ali QR osvezila: stara prijava ne sme viseti do poteka. */
    fun prekliciQr(qrId: String, deviceId: String, prevzem: String): Boolean = synchronized(kljucnica) {
        val p = qrPrijave[qrId] ?: return false
        if (p.deviceId != deviceId || !enaka(p.prevzem, prevzem) || p.zeton != null) return false
        qrPrijave.remove(qrId)
        return true
    }

    // ------------------------------------------------------------------ pridruzitev s QR kodo sredisca

    /**
     * QR, ki ga pokaze SREDISCE (prijavno okno Safeer OS na televizorju): telefon ali tablica ga poskenira
     * in se pridruzi. Velja enako kot 6-mestna koda: pridruzi se lahko samo, kdor vidi zaslon sredisca.
     * V kodi je celoten odtis potrdila sredisca, zato telefon ze prvo povezavo pripne nanj (vsiljivec v
     * sredini z drugim potrdilom pade). Skrivnost ima 128 bitov, velja PIN_VELJA_MS, porabi se enkrat,
     * ugibanje je omejeno. Kodo ustvari proces sredisca (zaslon) ali seznanjena naprava v krajevnem
     * omrezju (/cast/pair/qr/invite, »Poveži novo napravo« na racunalniku) - nikoli tujec.
     */
    private class Pridruzitev(val id: String, val odtisSkrivnosti: String, val nastala: Long, var poskusov: Int = 0)

    private val pridruzitve = LinkedHashMap<String, Pridruzitev>()

    /** Koda -> ime naprave, ki se je z njo pridruzila (za »povezano« na napravi, ki je kodo pokazala). */
    private val pridruzeni = LinkedHashMap<String, String>()

    /** Sredisce izve, kdo se je pridruzil (device_id, ime) - zaslon pokaze »povezano« in novo kodo. */
    @Volatile
    var naPridruzitev: ((String, String) -> Unit)? = null

    private fun pocistiPridruzitve() {
        val zdaj = ura()
        pridruzitve.entries.removeAll { zdaj - it.value.nastala > PIN_VELJA_MS }
    }

    /** Nova koda za zaslon sredisca: (id, skrivnost). Klice se samo v procesu. */
    fun ustvariPridruzitev(): Pair<String, String> = synchronized(kljucnica) {
        pocistiPridruzitve()
        while (pridruzitve.size >= NAJVEC_CAKAJOCIH) pridruzitve.remove(pridruzitve.keys.first())
        val id = nakljucni(12)
        val skrivnost = nakljucni(16)
        pridruzitve[id] = Pridruzitev(id, sha256Hex(skrivnost), ura())
        id to skrivnost
    }

    /** Zaslon je kodo zamenjal ali zaprl. */
    fun prekliciPridruzitev(id: String): Unit = synchronized(kljucnica) { pridruzitve.remove(id) }

    /** Naprava s skrivnostjo iz QR se pridruzi: (zeton, null) ali (null, napaka). Koda velja enkrat. */
    fun pridruzi(id: String, skrivnost: String, deviceId: String, ime: String): Pair<String?, String?> {
        val zeton = synchronized(kljucnica) {
            pocistiPridruzitve()
            val p = pridruzitve[id] ?: return null to "qr_ne_obstaja"
            if (!enaka(sha256Hex(skrivnost), p.odtisSkrivnosti)) {
                p.poskusov += 1
                if (p.poskusov >= NAJVEC_POSKUSOV) {
                    pridruzitve.remove(id)
                    return null to "prevec_poskusov"
                }
                return null to "qr_ne_obstaja"
            }
            if (jePolno(deviceId)) return null to "prevec_naprav"
            pridruzitve.remove(id)
            while (pridruzeni.size >= NAJVEC_CAKAJOCIH) pridruzeni.remove(pridruzeni.keys.first())
            pridruzeni[id] = if (ime.isBlank()) deviceId else ime
            val nov = "saf_tv_" + nakljucni(24)
            vpisiZeton(nov, SeznanjenaNaprava(deviceId, if (ime.isBlank()) deviceId else ime, ura() / 1000.0))
            shraniZetone()
            nov
        }
        naSpremembeNaprav?.invoke()
        try { naPridruzitev?.invoke(deviceId, ime) } catch (_: Throwable) { }
        return zeton to null
    }

    private fun sha256Hex(niz: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(niz.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun seznanjeneNaprave(): List<SeznanjenaNaprava> = synchronized(kljucnica) {
        zetoni.values.map { n -> vzdevki[n.deviceId]?.let { n.copy(ime = it) } ?: n }
    }

    /** Odvzame dostop napravi in jo, ce je povezana, tudi odklopi. */
    fun prekliciNapravo(deviceId: String): Int {
        val odklopi = ArrayList<Odjemalec>()
        val koliko: Int
        synchronized(kljucnica) {
            val odvzeti = zetoni.filterValues { it.deviceId == deviceId }.keys.toList()
            for (kljuc in odvzeti) zetoni.remove(kljuc)
            koliko = odvzeti.size
            if (koliko > 0) shraniZetone()
            naprave[deviceId]?.povezava?.let { odklopi.add(it) }
        }
        for (povezava in odklopi) {
            try {
                povezava.zapri(1008, "dostop odvzet")
            } catch (e: Exception) {
                // Naprave, ki je ze izginila, ni treba odklapljati.
            }
        }
        return koliko
    }

    /**
     * Naprava sama zapusti Safeer Link (»Odjavi ta racunalnik«, nezaupan racunalnik ob koncu prijave):
     * odvzamemo zetone in jo umaknemo iz kroga zaupanja - z njo vred vse id-je z istim kljucem (ista
     * naprava pod drugim imenom, npr. Control in brskalnik na istem racunalniku). Druge naprave ostanejo.
     * Vrne id-je, ki so odsli.
     */
    fun odidi(deviceId: String): List<String> {
        val kljuc = krog.clanZaId(deviceId)?.kljuc
        val idji = LinkedHashSet<String>()
        idji.add(deviceId)
        if (kljuc != null) for (c in krog.clani()) if (c.kljuc == kljuc && c.id != lastniId) idji.add(c.id)
        for (id in idji) {
            prekliciNapravo(id)
            // Umik mora biti novejsi od vpisa (vpis v isti milisekundi bi ga sicer preglasil).
            val dodano = krog.clan(id)?.dodano ?: 0.0
            if (id != lastniId) krog.umakni(id, deviceId, maxOf(KrogZaupanja.zdaj(), dodano + 0.001))
        }
        synchronized(kljucnica) { seje.entries.removeAll { it.value.first in idji } }
        naSpremembeNaprav?.invoke()
        return idji.toList()
    }

    // ------------------------------------------------------------------ vstopnice

    /**
     * Enokratna vstopnica za WebSocket, kratke veljavnosti. Povezava brez nje sploh ne nastane -
     * enako kot na racunalniku, kjer jo izda Controlov SessionManager.
     */
    fun izdajVstopnico(deviceId: String? = null): String = synchronized(kljucnica) {
        pocistiVstopnice()
        if (vstopnice.size >= NAJVEC_VSTOPNIC) {
            // Najstarejsa pade ven; drugace bi jih nekdo lahko naracal poljubno veliko.
            vstopnice.remove(vstopnice.keys.first())
        }
        val vstopnica = nakljucni(16)
        vstopnice[vstopnica] = Vstopnica(ura(), deviceId?.takeIf { it.isNotBlank() })
        return vstopnica
    }

    private fun pocistiVstopnice() {
        val zdaj = ura()
        val potekle = vstopnice.filterValues { zdaj - it.izdana > VSTOPNICA_VELJA_MS }.keys.toList()
        for (kljuc in potekle) vstopnice.remove(kljuc)
    }

    /**
     * Porabi vstopnico; druga uporaba iste ne uspe. Vstopnica, izdana znani napravi (zeton ali
     * podpis), ostane vezana nanjo, da se povezava po njej ne more prijaviti pod tujim device_id.
     */
    fun porabiVstopnico(vstopnica: String?): Boolean {
        if (vstopnica.isNullOrEmpty()) return false
        synchronized(kljucnica) {
            pocistiVstopnice()
            val najdena = vstopnice.keys.firstOrNull { enaka(it, vstopnica) } ?: return false
            val v = vstopnice.remove(najdena)
            if (v?.deviceId != null) {
                if (vezaneVstopnice.size >= NAJVEC_VSTOPNIC) vezaneVstopnice.remove(vezaneVstopnice.keys.first())
                vezaneVstopnice[najdena] = v.deviceId
            }
            return true
        }
    }

    /** Naprava, ki ji je bila vstopnica izdana, ali null, ce vstopnica ni bila vezana (ali je ni). */
    fun napravaVstopnice(vstopnica: String?): String? {
        if (vstopnica.isNullOrEmpty()) return null
        return synchronized(kljucnica) { vezaneVstopnice.entries.firstOrNull { enaka(it.key, vstopnica) }?.value }
    }

    // ------------------------------------------------------------------ register naprav

    private fun napraveJson(): String {
        // Vse povezane naprave, z vlogo zraven: "zaslon" (receiver) sprejema strani in videe,
        // deliti (besedilo, datoteka, zaslon) pa je mogoce s katerokoli. Kdo je kaj, odloci
        // vmesnik po polju role, ne Hub s filtriranjem.
        val povezane = naprave.values.filter { it.povezava != null }
        return povezane.joinToString(",", "[", "]") { napravaJson(it) }
    }

    private fun napravaJson(naprava: Naprava): String {
        val zapis = JsonLahki.Zapis()
            .niz("id", naprava.id)
            .niz("name", vzdevki[naprava.id] ?: naprava.ime)
            .niz("own_name", naprava.ime)
            .niz("role", naprava.vloga)
            .seznamNizov("capabilities", naprava.zmoznosti)
            .niz("ip", naprava.naslov)
            .nic("port")
            .stevilo("last_seen", naprava.zadnjic)
        // Protocol v1: model naprave in katalog aplikacij, samo kadar ju naprava pove.
        if (naprava.protokol.isNotBlank()) zapis.niz("protocol", naprava.protokol)
        if (naprava.platforma.isNotBlank()) zapis.niz("platform", naprava.platforma)
        if (naprava.vrsta.isNotBlank()) zapis.niz("kind", naprava.vrsta)
        if (naprava.razlicica.isNotBlank()) zapis.niz("version", naprava.razlicica)
        if (naprava.prioriteta > 0) zapis.stevilo("priority", naprava.prioriteta.toDouble())
        if (naprava.aplikacije.isNotBlank()) zapis.surovo("apps", naprava.aplikacije)
        val z = zasedeno[naprava.id]
        if (z != null) {
            zapis.niz("busy_by", z.posiljatelj)
                .niz("busy_by_name", vzdevki[z.posiljatelj] ?: naprave[z.posiljatelj]?.ime ?: z.posiljatelj)
                .niz("busy_kind", z.vrsta)
        }
        return zapis.toString()
    }

    /** Seznam povezanih prejemnikov za vmesnik in za koncno tocko /cast/devices. */
    fun povezaniPrejemniki(): String = synchronized(kljucnica) { napraveJson() }

    fun steviloNaprav(): Int = synchronized(kljucnica) { naprave.count { it.value.povezava != null } }

    private fun idPovezave(povezava: Odjemalec): String? =
        naprave.entries.firstOrNull { it.value.povezava === povezava }?.key

    fun odklopi(povezava: Odjemalec) {
        var spremenjeno = false
        synchronized(kljucnica) {
            val odklopljeni = naprave.filterValues { it.povezava === povezava }.keys.toList()
            for (id in odklopljeni) {
                val naprava = naprave[id] ?: continue
                naprava.povezava = null
                spremenjeno = true
                // Naprave ne pozabimo takoj: ime in zmoznosti so uporabni, ko se vrne.
                // Ce jih je prevec, pade ven najstarejsa odklopljena.
                pocistiRegister()
            }
            posiljatelji.remove(povezava)
        }
        if (spremenjeno) {
            objaviNaprave()
            naSpremembeNaprav?.invoke()
        }
    }

    private fun pocistiRegister() {
        while (naprave.size > NAJVEC_NAPRAV) {
            val odvecna = naprave.entries.firstOrNull { it.value.povezava == null } ?: break
            naprave.remove(odvecna.key)
        }
    }

    // ------------------------------------------------------------------ obdelava sporocil

    fun obdelaj(od: Odjemalec, surovo: String) {
        val odgovor = odgovorNa(od, surovo)
        if (odgovor != null) od.poslji(odgovor)
    }

    /**
     * Vrne odgovor, ki naj se poslje posiljatelju, ali null, ce odgovora ni.
     * Locena metoda zato, da jo je mogoce preizkusiti brez omrezja.
     */
    fun odgovorNa(od: Odjemalec, surovo: String): String? {
        val sporocilo = JsonLahki.objekt(surovo)
            ?: return potrditev("unknown", "error", "Neveljavno sporočilo.", koda = "neveljavno_sporocilo")
        val tip = sporocilo.niz("type")
        val id = sporocilo.nizAli("id", "unknown")
        val prostor = prostorOd(tip)

        if (tip.isNullOrEmpty()) return potrditev(id, "error", "Sporočilu manjka polje 'type'.", koda = "manjka_type")

        if (tip == "cast.register") return registriraj(od, sporocilo, id)

        if (tip == "cast.ping") return ovojnica("cast.pong", id).toString()

        if (tip in SYNC_POSREDOVANJE) return usmeriSinhronizacijo(od, sporocilo, surovo, id)

        if (tip == "sync.ack") {
            val cilj = sporocilo.niz("target")
            val povezava = synchronized(kljucnica) { naprave[cilj]?.povezava }
            povezava?.poslji(surovo)
            return null
        }

        if (tip in CAST_POSREDOVANJE) {
            val cilj = sporocilo.niz("target")
            // Stran (cast.url) sme na vsako napravo, ki jo zna odpreti - tudi na telefon ali
            // racunalnik, ko jo poslje televizor. Predvajanje in nadzor ostaneta za zaslone.
            val prejemnik = synchronized(kljucnica) {
                naprave[cilj]?.takeIf {
                    it.vloga == "receiver" || (tip == "cast.url" && it.zmoznosti.contains("url"))
                }?.takeIf { it.povezava !== od }?.povezava
            } ?: return potrditev(id, "rejected", "Ciljna naprava '${cilj ?: ""}' ni povezana ali ne obstaja.", koda = "naprava_ni_povezana")
            return if (posljiVarno(prejemnik, surovo)) potrditev(id, "accepted")
            else potrditev(id, "error", "Napaka pri posredovanju prejemniku.", koda = "posredovanje_ni_uspelo")
        }

        if (tip in SHARE_POSREDOVANJE) {
            // Deljenje med napravama: besedilo, datoteka, zaslon. Cilj je lahko katerakoli
            // povezana naprava, ne le "zaslon" - telefon poslje telefonu, tablica racunalniku.
            // Hub vsebine ne odpira; posreduje jo napravi, ki jo je uporabnik izbral.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = synchronized(kljucnica) { idPovezave(od) } ?: ""
            val prejemnik = synchronized(kljucnica) { naprave[cilj]?.povezava }
                ?: return potrditev(id, "rejected", "Ciljna naprava '$cilj' ni povezana ali ne obstaja.", "share", "naprava_ni_povezana")
            if (prejemnik === od) return potrditev(id, "rejected", "Naprava ne more deliti sama s sabo.", "share", "isti_naprava")
            zasedenOd(cilj)?.let { kdo ->
                if (kdo != posiljatelj) return potrditev(id, "rejected", "Z napravo trenutno deli ${imeNaprave(kdo)}. Počakaj, da konča.", "share", "naprava_zasedena")
            }
            val zapis = JsonLahki.objekt(surovo) ?: return potrditev(id, "error", "Neveljavno sporočilo.", "share", "neveljavno_sporocilo")
            return if (posredujDeljenje(tip, posiljatelj, cilj, zapis.surovo("payload"), id)) potrditev(id, "accepted", null, "share")
            else potrditev(id, "error", "Napaka pri posredovanju.", "share", "posredovanje_ni_uspelo")
        }

        if (tip in CONTROL_POSREDOVANJE) {
            // Daljinec med napravama (Safeer Control): ukaz gre samo napravi, ki je prijavila
            // zmoznost "remote", odgovor pa nazaj posiljatelju ukaza. Hub ukaza ne izvaja in
            // ga ne razlaga; posiljatelja vpise sam, da se ga ne da ponarediti.
            val cilj = sporocilo.niz("target") ?: ""
            val posiljatelj = synchronized(kljucnica) { idPovezave(od) } ?: ""
            val (prejemnik, zmoznosti) = synchronized(kljucnica) {
                val n = naprave[cilj]
                Pair(n?.povezava, n?.zmoznosti ?: emptyList())
            }
            if (prejemnik == null) {
                return potrditev(id, "rejected", "Ciljna naprava '$cilj' ni povezana ali ne obstaja.", "control", "naprava_ni_povezana")
            }
            if (prejemnik === od) return potrditev(id, "rejected", "Naprava ne more upravljati sama sebe.", "control", "isti_naprava")
            if (tip == "control.command" && !zmoznosti.contains(ZMOZNOST_DALJINEC)) {
                return potrditev(id, "rejected", "Naprave '${imeNaprave(cilj)}' ni mogoče upravljati; posodobi Safeer na njej.", "control", "brez_daljinca")
            }
            val zapis = JsonLahki.objekt(surovo) ?: return potrditev(id, "error", "Neveljavno sporočilo.", "control", "neveljavno_sporocilo")
            val naprej = JsonLahki.Zapis()
                .niz("id", id)
                .niz("type", tip)
                .niz("target", cilj)
                .niz("sender", posiljatelj)
                .niz("sender_name", imeNaprave(posiljatelj))
                .stevilo("timestamp", ura() / 1000.0)
            zapis.niz("ref_id")?.let { naprej.niz("ref_id", it) }
            zapis.surovo("payload")?.let { naprej.surovo("payload", it) }
            return if (posljiVarno(prejemnik, naprej.toString())) {
                // Odgovor na sam ukaz pride kasneje kot control.result; potrditev pove le, da je
                // ukaz prisel do naprave. Odgovorov (control.result) ne potrjujemo nazaj.
                if (tip == "control.command") potrditev(id, "accepted", null, "control") else null
            } else potrditev(id, "error", "Napaka pri posredovanju.", "control", "posredovanje_ni_uspelo")
        }

        if (tip == "cast.status") {
            val deviceId = sporocilo.niz("device_id")
            synchronized(kljucnica) {
                naprave[deviceId]?.zadnjic = ura() / 1000.0
            }
            objaviPosiljateljem(surovo)
            return null
        }

        if (tip == "cast.ack") return null

        if (tip == "apps.announce") {
            // Protocol v1: naprava (ponudnik) naknadno objavi ali osvezi svoj katalog aplikacij.
            // Hub ga hrani in razposlje v cast.devices; vsebine ne razlaga.
            val katalog = sporocilo.objekt("payload")?.surovo("apps")
                ?: return potrditev(id, "rejected", "Manjka apps.", "apps", "manjka_apps")
            val preverjen = preveriKatalog(katalog)
            val spremenjeno = synchronized(kljucnica) {
                val n = idPovezave(od)?.let { naprave[it] } ?: return potrditev(id, "rejected", "Naprava ni prijavljena.", "apps", "ni_prijavljena")
                if (n.aplikacije == preverjen) false else { n.aplikacije = preverjen; true }
            }
            if (spremenjeno) { objaviNaprave(); naSpremembeNaprav?.invoke() }
            return potrditev(id, "accepted", null, "apps")
        }

        // Kar ni na seznamu, se ne posreduje nikamor. Dovoljenja se ne smejo siriti po nesreci.
        return potrditev(id, "error", "Neznan tip sporočila: '$tip'", prostor, koda = "neznan_tip")
    }

    /**
     * Ista naprava pod dvema id-jema (stari id in id iz kljuca, ali sorodnika z istim kljucem): vstopnica,
     * izdana enemu, velja za prijavo drugega. Kljuc je identiteta, id je le ime zanjo.
     */
    private fun istiKljuc(a: String, b: String): Boolean {
        val ka = krog.clanZaId(a)?.kljuc ?: return false
        val kb = krog.clanZaId(b)?.kljuc ?: return false
        return ka == kb
    }

    private fun registriraj(od: Odjemalec, sporocilo: JsonLahki.Pogled, id: String): String {
        val tovor = sporocilo.objekt("payload")
        val deviceId = tovor?.niz("device_id")
        if (deviceId.isNullOrBlank()) return potrditev(id, "rejected", "Manjka device_id.", koda = "manjka_device_id")
        // Vstopnica je bila izdana znani napravi (po zetonu ali podpisu): prijava pod drugim id ne velja.
        // Tako je device_id vezan na zeton oz. kljuc, ne le na to, kar naprava trdi o sebi.
        val vezana = napravaVstopnice(od.vstopnica)
        if (vezana != null && vezana != deviceId && !istiKljuc(vezana, deviceId)) {
            return potrditev(id, "rejected", "device_id se ne ujema z napravo, ki ji je bila izdana vstopnica.", koda = "napacen_device_id")
        }
        od.vstopnica?.let { v -> synchronized(kljucnica) { vezaneVstopnice.keys.firstOrNull { enaka(it, v) }?.let { vezaneVstopnice.remove(it) } } }

        val vloga = tovor.niz("role") ?: "receiver"
        val zmoznosti = tovor.nizi("capabilities").ifEmpty { listOf("url", "control") }
        // Ista naprava z novo povezavo (po izpadu, ponovnem zagonu): nova zamenja staro, stara se zapre.
        // Sicer bi ob zaprtju stare vpis naprave izgubil povezavo, nova pa bi ostala odprta in nevidna.
        val stara = synchronized(kljucnica) { naprave[deviceId]?.povezava?.takeIf { it !== od } }
        if (stara != null) {
            synchronized(kljucnica) { posiljatelji.remove(stara) }
            try { stara.zapri(1000, "nova povezava iste naprave") } catch (_: Throwable) { }
        }
        synchronized(kljucnica) {
            if (!naprave.containsKey(deviceId) && naprave.size >= NAJVEC_NAPRAV) {
                pocistiRegister()
                if (naprave.size >= NAJVEC_NAPRAV) {
                    return potrditev(id, "rejected", "Preveč naprav; odklopite katero od prejšnjih.", koda = "prevec_naprav")
                }
            }
            val naprava = naprave.getOrPut(deviceId) {
                Naprava(deviceId, deviceId, vloga, zmoznosti, od.naslov, ura() / 1000.0, null)
            }
            naprava.ime = (tovor.niz("name")?.takeIf { it.isNotBlank() } ?: deviceId).take(NAJVEC_IMENA)
            naprava.vloga = vloga
            naprava.zmoznosti = zmoznosti
            // Protocol v1: model naprave (odjemalec 0.2 teh polj nima - ostanejo prazna).
            naprava.protokol = tovor.nizAli("protocol").take(8)
            naprava.platforma = tovor.nizAli("platform").take(16)
            naprava.vrsta = tovor.nizAli("kind").take(16)
            naprava.razlicica = tovor.nizAli("version").take(32)
            naprava.prioriteta = (tovor.stevilo("priority") ?: 0.0).toInt().coerceIn(0, 1000)
            tovor.surovo("apps")?.let { naprava.aplikacije = preveriKatalog(it) }
            // Naslov vzamemo iz vticnice, ne iz tega, kar naprava trdi o sebi.
            naprava.naslov = od.naslov
            naprava.zadnjic = ura() / 1000.0
            naprava.povezava = od
            if (vloga != "receiver") posiljatelji.add(od)
        }
        // Vsaka nova naprava spremeni seznam za vse: tudi posiljatelj je zdaj mozen cilj deljenja.
        objaviNaprave()
        naSpremembeNaprav?.invoke()
        // Krog zaupanja dobi vsaka naprava ob prijavi, da ga ima tudi takrat, ko hub ugasne.
        if (krog.stevilo() > 0) posljiVarno(od, sporociloKroga())
        return potrditev(id, "accepted")
    }

    /**
     * Katalog aplikacij, kot ga sme hub hraniti: JSON objekt {"<id>": {"name": "...", "kind": "..."}},
     * najvec NAJVEC_APLIKACIJ vnosov, kratka imena. Kar ne ustreza, odpade - naprava z malo
     * pomnilnika ne sme hraniti tujega smetja. Vrne ociscen zapis ali prazno.
     */
    fun preveriKatalog(surovo: String): String {
        if (surovo.length > NAJVEC_KATALOG_BAJTOV) return ""
        val pogled = JsonLahki.objekt(surovo) ?: return ""
        val zapis = JsonLahki.Zapis()
        var stevilo = 0
        for (idApp in pogled.kljuci()) {
            if (stevilo >= NAJVEC_APLIKACIJ) break
            val a = pogled.objekt(idApp) ?: continue
            val cistId = idApp.take(NAJVEC_IMENA)
            if (cistId.isBlank()) continue
            val vnos = JsonLahki.Zapis()
                .niz("name", a.nizAli("name", cistId).take(NAJVEC_IMENA))
                .niz("kind", a.nizAli("kind").take(16))
            a.niz("icon")?.let { if (it.length <= 256) vnos.niz("icon", it) }
            zapis.surovo(cistId, vnos.toString())
            stevilo++
        }
        return if (stevilo == 0) "" else zapis.toString()
    }

    private fun usmeriSinhronizacijo(
        od: Odjemalec,
        sporocilo: JsonLahki.Pogled,
        surovo: String,
        id: String
    ): String? {
        val tip = sporocilo.niz("type")
        val posiljatelj = synchronized(kljucnica) { idPovezave(od) }
        val tovor = sporocilo.objekt("payload")

        if (tip == "sync.data" && tovor != null) {
            shraniKategorijo(tovor, posiljatelj)
        }

        if (tip == "sync.request" && tovor != null) {
            val ime = tovor.niz("category") ?: ""
            val shranjeno = synchronized(kljucnica) { sinhronizacija[ime] }
            if (shranjeno != null) {
                val od_razlicice = tovor.stevilo("since_version")
                if (od_razlicice == null || shranjeno.razlicica > od_razlicice) {
                    val tovorNazaj = JsonLahki.Zapis()
                        .niz("category", shranjeno.ime)
                        .stevilo("version", shranjeno.razlicica)
                        .stevilo("timestamp", shranjeno.cas)
                        .surovo("data", shranjeno.podatkiSurovo)
                    val odgovor = ovojnica("sync.data", cilj = posiljatelj)
                        .surovo("payload", tovorNazaj.toString())
                    posljiVarno(od, odgovor.toString())
                }
                return potrditev(id, "accepted", null, "sync")
            }
        }

        val cilj = sporocilo.niz("target")
        if (!cilj.isNullOrEmpty() && cilj != VSEM) {
            val povezava = synchronized(kljucnica) { naprave[cilj]?.povezava }
                ?: return potrditev(id, "rejected", "Naprava '$cilj' ni povezana ali ne obstaja.", "sync", "naprava_ni_povezana")
            return if (posljiVarno(povezava, surovo)) potrditev(id, "accepted", null, "sync")
            else potrditev(id, "error", "Napaka pri posredovanju.", "sync", "posredovanje_ni_uspelo")
        }

        val prejemniki = synchronized(kljucnica) {
            naprave.values.filter {
                it.povezava != null && it.id != posiljatelj &&
                    (it.zmoznosti.contains(ZMOZNOST_SYNC) || it.vloga == "sync-client")
            }.mapNotNull { it.povezava }
        }
        if (prejemniki.isEmpty()) {
            return potrditev(id, "rejected", "Nobena druga naprava ne sinhronizira.", "sync", "nobena_ne_sinhronizira")
        }
        var dostavljeno = 0
        for (povezava in prejemniki) if (posljiVarno(povezava, surovo)) dostavljeno++
        return if (dostavljeno > 0) potrditev(id, "accepted", null, "sync")
        else potrditev(id, "error", "Nobene naprave ni bilo mogoče doseči.", "sync", "nobene_ni_doseglo")
    }

    /**
     * Shrani zadnje stanje kategorije, da naprava, ki je bila ugasnjena, lahko dohiti.
     * Meje so tu, ker gre za pomnilnik televizorja: prevelika kategorija se ne shrani,
     * prevec kategorij pa ne nastane.
     */
    private fun shraniKategorijo(tovor: JsonLahki.Pogled, vir: String?) {
        val ime = tovor.niz("category") ?: return
        val podatki = tovor.surovo("data") ?: return
        val bajtov = podatki.toByteArray(Charsets.UTF_8).size
        if (bajtov > NAJVECJA_KATEGORIJA) return
        val cas = tovor.stevilo("timestamp") ?: 0.0
        synchronized(kljucnica) {
            val staro = sinhronizacija[ime]
            if (staro != null && staro.cas > cas) return
            val nova = Kategorija(ime, tovor.stevilo("version") ?: 0.0, cas, podatki, vir)
            sinhronizacija[ime] = nova
            // Ce smo cez skupno mejo, gredo ven najstarejsi vpisi, dokler nismo spet pod njo.
            while (sinhronizacija.size > NAJVEC_KATEGORIJ ||
                sinhronizacija.values.sumOf { it.bajtov } > NAJVEC_SKUPAJ_SYNC
            ) {
                val najstarejsa = sinhronizacija.keys.firstOrNull { it != ime } ?: break
                sinhronizacija.remove(najstarejsa)
            }
        }
    }

    fun kategorijeSinhronizacije(): List<String> = synchronized(kljucnica) { sinhronizacija.keys.sorted() }

    private fun objaviNaprave() {
        val sporocilo = ovojnica("cast.devices").surovo("devices", povezaniPrejemniki()).toString()
        // Seznam dobijo vsi povezani, ne le posiljatelji: tudi zaslon mora vedeti, komu lahko
        // kaj poslje, ker je deljenje dvosmerno.
        val kopija = synchronized(kljucnica) { naprave.values.mapNotNull { it.povezava } }
        for (povezava in kopija) posljiVarno(povezava, sporocilo)
    }

    private fun objaviPosiljateljem(sporocilo: String) {
        val kopija = synchronized(kljucnica) { posiljatelji.toList() }
        for (posiljatelj in kopija) {
            if (!posljiVarno(posiljatelj, sporocilo)) {
                synchronized(kljucnica) { posiljatelji.remove(posiljatelj) }
            }
        }
    }

    /**
     * Posreduje deljenje ciljni napravi. Posiljatelja vpise Hub, da se ga ne da ponarediti;
     * ime posiljatelja vzame iz registra, ce ga pozna. Vrne false, ce cilj ni povezan.
     */
    private fun posredujDeljenje(tip: String, posiljatelj: String, cilj: String, tovor: String?, id: String = novId()): Boolean {
        val prejemnik = synchronized(kljucnica) { naprave[cilj]?.povezava } ?: return false
        val naprej = JsonLahki.Zapis()
            .niz("id", id)
            .niz("type", tip)
            .niz("target", cilj)
            .niz("sender", posiljatelj)
            .niz("sender_name", imeNaprave(posiljatelj))
            .stevilo("timestamp", ura() / 1000.0)
        if (tovor != null) naprej.surovo("payload", tovor)
        return posljiVarno(prejemnik, naprej.toString())
    }

    private fun novId(): String = "hub-" + nakljucni(8)

    /** Datoteka je na Hubu cela: cilju povemo, kje jo prevzame (ali da je ze v njegovi mapi). */
    private fun datotekaPrispela(d: HubTokovi.Datoteka) {
        val tovor = JsonLahki.Zapis()
            .niz("id", d.id)
            .niz("name", d.ime)
            .stevilo("size", d.velikost.toDouble())
            .niz("path", if (d.zaGostitelja) "" else d.potPrevzema())
            .niz("sha256", d.sha256)
            .logicno("for_host", d.zaGostitelja)
            .toString()
        // Ce cilj ni povezan, datoteka pocaka na Hubu (eno uro); posiljatelj je dobil odgovor 200.
        posredujDeljenje("share.file", d.posiljatelj, d.cilj, tovor)
    }

    /** Deljenje zaslona se je koncalo (posiljatelj je nehal ali odsel): cilj naj neha gledati. */
    private fun zaslonKoncan(id: String) {
        val cilj = synchronized(kljucnica) { deljeniZasloni.remove(id) } ?: return
        val posiljatelj = synchronized(kljucnica) { deljeniZasloniPosiljatelji.remove(id) } ?: ""
        posredujDeljenje("share.screen", posiljatelj, cilj,
            JsonLahki.Zapis().niz("action", "stop").niz("id", id).toString())
        sprosti(cilj, posiljatelj)
    }

    private fun posljiVarno(komu: Odjemalec, besedilo: String): Boolean = try {
        komu.poslji(besedilo)
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ ovojnice

    private fun ovojnica(tip: String, id: String? = null, cilj: String? = null): JsonLahki.Zapis =
        JsonLahki.Zapis()
            .niz("id", id ?: UUID.randomUUID().toString())
            .niz("type", tip)
            .stevilo("timestamp", ura() / 1000.0)
            .nic("sender")
            .niz("target", cilj)

    private fun potrditev(
        refId: String,
        stanje: String,
        napaka: String? = null,
        prostor: String = "cast",
        koda: String? = null
    ): String = ovojnica("$prostor.ack")
        .niz("ref_id", refId)
        .niz("status", stanje)
        .niz("error", napaka)
        // Stabilna oznaka: odjemalec jo prevede v svoj jezik, besedilo je le rezerva.
        .niz("error_code", koda)
        .toString()

    private fun prostorOd(tip: String?): String {
        if (tip.isNullOrEmpty() || !tip.contains(".")) return "cast"
        return tip.substringBefore(".")
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * Koncne tocke, ki jih televizor ponuja po omrezju. Namenoma jih je malo:
     * prijava, prevzem zetona, vstopnica, seznam naprav in stanje. Potrjevanje in odvzem
     * dostopa se dogajata samo na televizorju, zato ju tu ni.
     */
    fun odgovori(zahteva: HubStreznik.Zahteva): HubStreznik.Odgovor? {
        val pot = zahteva.pot
        val krajevni = jeKrajevni(zahteva.odjemalec)

        if (pot == "/cast/pair/start" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val deviceId = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
            val ime = (telo?.niz("name") ?: "").trim().take(NAJVEC_IMENA)
            if (deviceId.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka device_id.", "manjka_device_id"))
            val prijava = zacniSeznanitev(deviceId, ime, zahteva.odjemalec)
                ?: return HubStreznik.Odgovor(429, napakaJson("Preveč čakajočih prijav; poskusite čez nekaj minut.", "prevec_prijav"))
            // Kode NE vrnemo napravi, ki se prikljucuje. Pokaze jo gostitelj na svojem
            // zaslonu, uporabnik pa jo tam prebere in vtipka. Nacin povemo izrecno, da
            // odjemalec ve, kaj naj pokaze; starejsi Hub tega polja nima in takrat velja
            // stari postopek (koda na napravi, potrditev na gostitelju).
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis()
                    .niz("pair_id", prijava.first)
                    .niz("nacin", NACIN_SPAKE2)
                    .niz("hub_id", IDENTITETA_HUBA)
                    .niz("fp", lastniOdtis)
                    .stevilo("expires_in_seconds", (PIN_VELJA_MS / 1000).toDouble())
                    .toString()
            )
        }

        if (pot == "/cast/pair/cancel" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val pairId = (telo?.niz("pair_id") ?: "").trim()
            val deviceId = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
            if (pairId.isEmpty() || deviceId.isEmpty()) {
                return HubStreznik.Odgovor(400, napakaJson("Manjka pair_id ali device_id.", "manjka_pair_id"))
            }
            // Preklice lahko samo naprava, ki je prijavo zacela: pozna njen pair_id in svoj device_id.
            // Preklic nicesar ne odpre in ne izda - le skrije kodo, ki je nihce vec ne potrebuje.
            val preklicana = prekliciPrijavo(pairId, deviceId)
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("cancelled", preklicana).toString())
        }

        if (pot == "/cast/pair/sibling" && zahteva.metoda == "POST") {
            // Sorodna naprava na istem racunalniku (Safeer Control ob ze seznanjenem Safeer Browserju):
            // zeton seznanjene naprave (isti uporabnik, ista datoteka) da zeton se njenemu sorodniku,
            // brez nove kode. Sorodnik je le id z isto osnovo (npr. pc-mojpc -> pc-mojpc-control).
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            val lastnik = napravaZeZetona(zahteva.glave["x-safeer-token"])
                ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val deviceId = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
            val ime = (telo?.niz("name") ?: "").trim().take(NAJVEC_IMENA).ifEmpty { deviceId }
            if (deviceId.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka device_id.", "manjka_device_id"))
            if (deviceId == lastnik || !deviceId.startsWith("$lastnik-")) {
                return HubStreznik.Odgovor(403, napakaJson("Ni sorodna naprava.", "ni_sorodnik"))
            }
            val zeton = synchronized(kljucnica) {
                if (jePolno(deviceId)) return HubStreznik.Odgovor(429, napakaJson("Preveč seznanjenih naprav.", "prevec_naprav"))
                val nov = "saf_tv_" + nakljucni(24)
                vpisiZeton(nov, SeznanjenaNaprava(deviceId, ime, ura() / 1000.0))
                shraniZetone()
                nov
            }
            naSpremembeNaprav?.invoke()
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("token", zeton).niz("hub_id", IDENTITETA_HUBA).niz("fp", lastniOdtis).toString())
        }

        if (pot == "/cast/pair/spake" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val pairId = (telo?.niz("pair_id") ?: "").trim()
            val deviceId = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
            val pb = hexVBajte((telo?.niz("pb") ?: "").trim())
            if (pairId.isEmpty() || deviceId.isEmpty() || pb == null) {
                return HubStreznik.Odgovor(400, napakaJson("Manjka pair_id, device_id ali pb.", "manjka_pb"))
            }
            val izid = spakeKorak1(pairId, deviceId, pb)
            if (izid.pa == null || izid.ca == null) {
                val (kodaHttp, sporocilo) = when (izid.napaka) {
                    "prevec_poskusov" -> 429 to "Preveč poskusov. Začnite znova."
                    "prijava_ne_obstaja" -> 404 to "Prijava je potekla. Začnite znova."
                    "neveljavna_tocka" -> 400 to "Neveljavno sporočilo."
                    else -> 409 to "Seznanitev ni mogoča."
                }
                return HubStreznik.Odgovor(kodaHttp, napakaJson(sporocilo, izid.napaka ?: "seznanitev_ni_mogoca"))
            }
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis().niz("pa", bajteVHex(izid.pa)).niz("ca", bajteVHex(izid.ca)).toString()
            )
        }

        if (pot == "/cast/pair/finish" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val pairId = (telo?.niz("pair_id") ?: "").trim()
            val deviceId = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
            val cb = hexVBajte((telo?.niz("cb") ?: "").trim())
            if (pairId.isEmpty() || deviceId.isEmpty() || cb == null) {
                return HubStreznik.Odgovor(400, napakaJson("Manjka pair_id, device_id ali cb.", "manjka_cb"))
            }
            val izid = spakeKorak2(pairId, deviceId, cb)
            val zeton = izid.zeton
            if (zeton == null) {
                val (kodaHttp, sporocilo) = when (izid.napaka) {
                    "napacna_koda" -> 401 to "Koda ni pravilna."
                    "prevec_poskusov" -> 429 to "Preveč poskusov. Začnite znova."
                    "prijava_ne_obstaja" -> 404 to "Prijava je potekla. Začnite znova."
                    "manjka_korak" -> 409 to "Najprej pošljite pb."
                    else -> 409 to "Seznanitev ni mogoča."
                }
                return HubStreznik.Odgovor(kodaHttp, napakaJson(sporocilo, izid.napaka ?: "seznanitev_ni_mogoca"))
            }
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis().logicno("approved", true).niz("token", zeton).toString()
            )
        }

        if ((pot == "/cast/pair/verify" || pot == "/cast/pair/claim") && zahteva.metoda == "POST") {
            // Stari postopek je kodo posiljal po omrezju oziroma potrditev ni bila vezana na
            // potrdilo TLS. Napravo z novo razlicico Safeerja to ne prizadene; stara naj se posodobi.
            return HubStreznik.Odgovor(410, napakaJson("Posodobi Safeer: seznanjanje zdaj poteka po varnejšem postopku.", "posodobi_aplikacijo"))
        }

        if (pot == "/cast/pair/verify-staro-onemogoceno" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val pairId = (telo?.niz("pair_id") ?: "").trim()
            val koda = (telo?.niz("pin") ?: "").trim().take(16)
            if (pairId.isEmpty() || koda.isEmpty()) {
                return HubStreznik.Odgovor(400, napakaJson("Manjka pair_id ali koda.", "manjka_koda"))
            }
            val izid = potrdiSKodo(pairId, koda)
            val zeton = izid.zeton
            if (zeton == null) {
                val (koda_http, sporocilo) = when (izid.napaka) {
                    "napacna_koda" -> 401 to "Koda ni pravilna."
                    "prevec_poskusov" -> 429 to "Preveč poskusov. Začnite znova."
                    "prijava_ne_obstaja" -> 404 to "Prijava je potekla. Začnite znova."
                    else -> 409 to "Seznanitev ni mogoča."
                }
                return HubStreznik.Odgovor(
                    koda_http,
                    napakaJson(sporocilo, izid.napaka ?: "seznanitev_ni_mogoca")
                )
            }
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis().logicno("approved", true).niz("token", zeton).toString()
            )
        }

        if (pot == "/cast/pair/claim-staro-onemogoceno" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            // Kdor povprasuje po tej poti, govori po starem: kodo kaze pri sebi in caka
            // na potrditev tu. Samo taki napravi vmesnik ponudi gumb Potrdi.
            oznaciStaroNapravo(telo?.niz("pair_id") ?: "")
            val zeton = prevzemiZeton(telo?.niz("pair_id") ?: "")
                ?: return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("approved", false).toString())
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis().logicno("approved", true).niz("token", zeton).toString()
            )
        }

        if (pot.startsWith("/cast/pair/qr/") && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Seznanjanje je mogoče samo v krajevnem omrežju.", "samo_krajevno"))
            return odgovorQr(pot, zahteva)
        }

        if (pot == "/cast/share/screen/start" && zahteva.metoda == "POST") {
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            val t = tokovi ?: return HubStreznik.Odgovor(503, napakaJson("Deljenje zaslona tu ni na voljo.", "ni_tokov"))
            val telo = JsonLahki.objekt(zahteva.telo)
            // Posiljatelj je naprava, ki ji pripada zeton - ne tisto, kar pise v telesu.
            val posiljatelj = napravaZeZetona(zahteva.glave["x-safeer-token"]) ?: ""
            val cilj = (telo?.niz("target") ?: "").trim().take(NAJVEC_IMENA)
            if (posiljatelj.isEmpty()) return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            if (cilj.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka target.", "manjka_target"))
            if (cilj == posiljatelj) return HubStreznik.Odgovor(400, napakaJson("Naprava ne more deliti sama s sabo.", "isti_naprava"))
            if (synchronized(kljucnica) { naprave[cilj]?.povezava } == null) {
                return HubStreznik.Odgovor(404, napakaJson("Ciljna naprava '$cilj' ni povezana.", "naprava_ni_povezana"))
            }
            zasedi(cilj, posiljatelj, "screen")?.let { kdo -> return odgovorZasedeno(cilj, kdo) }
            val (id, kljuc) = t.zacniZaslon(posiljatelj)
                ?: run { sprosti(cilj, posiljatelj); return HubStreznik.Odgovor(503, napakaJson("Preveč deljenih zaslonov.", "prevec_zaslonov")) }
            val potGledanja = "/cast/screen/$id/view?k=$kljuc"
            synchronized(kljucnica) {
                deljeniZasloni[id] = cilj
                deljeniZasloniPosiljatelji[id] = posiljatelj
            }
            // Cilj izve za deljenje od Huba: odpre stran gledalca. Ce mu tega ni mogoce povedati,
            // deljenja ne zacnemo - posiljatelj bi sicer delil v prazno.
            val obvescen = posredujDeljenje("share.screen", posiljatelj, cilj,
                JsonLahki.Zapis().niz("action", "start").niz("id", id).niz("path", potGledanja).toString())
            if (!obvescen) {
                synchronized(kljucnica) { deljeniZasloni.remove(id); deljeniZasloniPosiljatelji.remove(id) }
                t.koncajZaslon(id)
                sprosti(cilj, posiljatelj)
                return HubStreznik.Odgovor(502, napakaJson("Ciljne naprave ni bilo mogoče obvestiti.", "posredovanje_ni_uspelo"))
            }
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis()
                    .niz("id", id)
                    .niz("push_path", "/cast/screen/$id?k=$kljuc")
                    .niz("view_path", potGledanja)
                    .toString()
            )
        }

        if (pot == "/cast/share/text" && zahteva.metoda == "POST") {
            // Besedilo po HTTP: isti ucinek kot share.text po WebSocketu, a brez povezave.
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            val telo = JsonLahki.objekt(zahteva.telo)
            val posiljatelj = napravaZeZetona(zahteva.glave["x-safeer-token"]) ?: ""
            val cilj = (telo?.niz("target") ?: "").trim().take(NAJVEC_IMENA)
            val besedilo = (telo?.niz("text") ?: "").take(NAJVEC_BESEDILA)
            if (posiljatelj.isEmpty()) return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            if (cilj.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka target.", "manjka_target"))
            if (besedilo.isBlank()) return HubStreznik.Odgovor(400, napakaJson("Besedilo je prazno.", "prazno_besedilo"))
            if (cilj == posiljatelj) return HubStreznik.Odgovor(400, napakaJson("Naprava ne more deliti sama s sabo.", "isti_naprava"))
            zasedenOd(cilj)?.let { kdo -> if (kdo != posiljatelj) return odgovorZasedeno(cilj, kdo) }
            val poslano = posredujDeljenje("share.text", posiljatelj, cilj, JsonLahki.Zapis().niz("text", besedilo).toString())
            return if (poslano) HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("sent", true).toString())
            else HubStreznik.Odgovor(404, napakaJson("Ciljna naprava '$cilj' ni povezana.", "naprava_ni_povezana"))
        }

        if (pot == "/cast/devices/leave" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            // Odide lahko samo naprava sama: kdo je, pove njen zeton, ne telo zahteve.
            val deviceId = napravaZeZetona(zahteva.glave["x-safeer-token"])
                ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            val odsli = odidi(deviceId)
            val z = JsonLahki.Zapis().logicno("left", true)
            return HubStreznik.Odgovor(200, z.stevilo("count", odsli.size.toDouble()).toString())
        }

        if (pot == "/cast/devices/rename" && zahteva.metoda == "POST") {
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            val telo = JsonLahki.objekt(zahteva.telo)
            val id = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
            val ime = telo?.niz("name") ?: ""
            if (id.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka device_id.", "manjka_device_id"))
            preimenuj(id, ime)
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("id", id).niz("name", imeNaprave(id)).toString())
        }

        if (pot == "/cast/share/screen/stop" && zahteva.metoda == "POST") {
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            val id = (JsonLahki.objekt(zahteva.telo)?.niz("id") ?: "").trim()
            // koncajZaslon poklice nazaj zaslonKoncan, ki obvesti cilj.
            tokovi?.koncajZaslon(id)
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("stopped", true).toString())
        }

        if (pot == "/cast/trust/enroll" && zahteva.metoda == "POST") {
            // Prehod z zetona na kljuc: naprava z veljavnim zetonom vpise svoj kljuc v krog.
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            val deviceId = napravaZeZetona(zahteva.glave["x-safeer-token"])
                ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val kljuc = telo?.niz("pubkey")?.trim().orEmpty()
            if (kljuc.isEmpty() || KrogZaupanja.dekodirajKljuc(kljuc) == null) {
                return HubStreznik.Odgovor(400, napakaJson("Manjka ali neveljaven javni ključ.", "neveljaven_kljuc"))
            }
            val ime = (telo?.niz("name")?.takeIf { it.isNotBlank() } ?: imeNaprave(deviceId)).take(NAJVEC_IMENA)
            krog.dodaj(KrogZaupanja.Clan(deviceId, kljuc, ime, telo?.nizAli("platform").orEmpty().take(16), KrogZaupanja.zdaj(), lastniId))
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("device_id", deviceId).surovo("ring", krog.json()).toString())
        }

        if (pot == "/cast/auth/challenge" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            val deviceId = (JsonLahki.objekt(zahteva.telo)?.niz("device_id") ?: "").trim()
            // Id iz kljuca (n-...) velja tudi, ce je ta kljuc v krogu pod starim id-jem: naprava je ista.
            if (deviceId.isEmpty() || krog.clanZaId(deviceId) == null) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni v krogu zaupanja.", "naprava_ni_v_krogu"))
            }
            val nonce = nakljucni(24)
            synchronized(kljucnica) {
                pocistiIzzive()
                if (izzivi.size >= NAJVEC_VSTOPNIC) izzivi.remove(izzivi.keys.first())
                izzivi[nonce] = Pair(deviceId, ura())
            }
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("nonce", nonce).niz("hub_id", lastniId).niz("fp", lastniOdtis)
                .stevilo("expires_in_seconds", (IZZIV_VELJA_MS / 1000).toDouble()).toString())
        }

        if (pot == "/cast/auth/ticket" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val deviceId = (telo?.niz("device_id") ?: "").trim()
            val nonce = (telo?.niz("nonce") ?: "").trim()
            val podpis = (telo?.niz("signature") ?: "").trim()
            val izziv = synchronized(kljucnica) { pocistiIzzive(); if (nonce.isEmpty()) null else izzivi.remove(nonce) }
            if (izziv == null || izziv.first != deviceId) {
                return HubStreznik.Odgovor(401, napakaJson("Izziv ni veljaven ali je potekel.", "neveljaven_izziv"))
            }
            val clan = krog.clanZaId(deviceId)
            if (clan == null || !KrogZaupanja.preveriPodpisSKljucem(clan.kljuc, podatkiZaPodpis(deviceId, nonce), podpis)) {
                return HubStreznik.Odgovor(401, napakaJson("Podpis se ne ujema s ključem naprave.", "napacen_podpis"))
            }
            if (clan.id != deviceId) {
                // Prehod na id iz kljuca: podpis dokazuje isti kljuc, zato nov id vpisemo kot alias starega.
                // Seznanitev prezivi - nic novega ne vstopi v krog.
                val ime = (telo?.niz("name")?.takeIf { it.isNotBlank() } ?: clan.ime).take(NAJVEC_IMENA)
                val platforma = telo?.nizAli("platform")?.take(16)?.ifBlank { clan.platforma } ?: clan.platforma
                krog.dodaj(KrogZaupanja.Clan(deviceId, clan.kljuc, ime, platforma, KrogZaupanja.zdaj(), clan.id))
            }
            return HubStreznik.Odgovor(200, JsonLahki.Zapis()
                .niz("ticket", izdajVstopnico(deviceId))
                .niz("session_token", izdajSejo(deviceId))
                .stevilo("expires_in_seconds", (VSTOPNICA_VELJA_MS / 1000).toDouble())
                .surovo("ring", krog.json())
                .toString())
        }

        if (pot == "/cast/trust/alias" && zahteva.metoda == "POST") {
            // Ista naprava, drug id (TV brskalnik + Safeer OS, tablica + njen zaslon, Control + brskalnik):
            // clan kroga s podpisom svojega kljuca (izziv za znani id) vpise se svoj drugi id z istim kljucem.
            // Nic novega ne vstopi v krog - le se eno ime za kljuc, ki mu ze zaupamo.
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            val telo = JsonLahki.objekt(zahteva.telo)
            val deviceId = (telo?.niz("device_id") ?: "").trim()
            val nonce = (telo?.niz("nonce") ?: "").trim()
            val podpis = (telo?.niz("signature") ?: "").trim()
            val alias = (telo?.niz("alias") ?: "").trim().take(NAJVEC_IMENA)
            if (alias.isEmpty() || alias == deviceId) return HubStreznik.Odgovor(400, napakaJson("Manjka alias.", "manjka_alias"))
            val izziv = synchronized(kljucnica) { pocistiIzzive(); if (nonce.isEmpty()) null else izzivi.remove(nonce) }
            if (izziv == null || izziv.first != deviceId) {
                return HubStreznik.Odgovor(401, napakaJson("Izziv ni veljaven ali je potekel.", "neveljaven_izziv"))
            }
            if (!krog.preveriPodpis(deviceId, podatkiZaPodpis(deviceId, nonce), podpis)) {
                return HubStreznik.Odgovor(401, napakaJson("Podpis se ne ujema s ključem naprave.", "napacen_podpis"))
            }
            val clan = krog.clan(deviceId) ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni v krogu zaupanja.", "naprava_ni_v_krogu"))
            val obstojeci = krog.clan(alias)
            if (obstojeci != null && obstojeci.kljuc != clan.kljuc) {
                return HubStreznik.Odgovor(409, napakaJson("Ta id ima v krogu drug ključ.", "alias_zaseden"))
            }
            val ime = (telo?.niz("name")?.takeIf { it.isNotBlank() } ?: alias).take(NAJVEC_IMENA)
            krog.dodaj(KrogZaupanja.Clan(alias, clan.kljuc, ime, telo?.nizAli("platform")?.ifBlank { clan.platforma } ?: clan.platforma, KrogZaupanja.zdaj(), deviceId))
            return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("device_id", alias).surovo("ring", krog.json()).toString())
        }

        if (pot == "/cast/trust/ring" && zahteva.metoda == "GET") {
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            return HubStreznik.Odgovor(200, krog.json())
        }

        if (pot == "/cast/ticket" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            val lastnikZetona = napravaZeZetona(zahteva.glave["x-safeer-token"])
                ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis()
                    .niz("ticket", izdajVstopnico(lastnikZetona))
                    .stevilo("expires_in_seconds", (VSTOPNICA_VELJA_MS / 1000).toDouble())
                    .toString()
            )
        }

        if (pot == "/cast/devices" && zahteva.metoda == "GET") {
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            return HubStreznik.Odgovor(200, povezaniPrejemniki())
        }

        if (pot == "/cast/health" && zahteva.metoda == "GET") {
            if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            val stanje = synchronized(kljucnica) {
                JsonLahki.Zapis()
                    .niz("status", "ok")
                    .niz("protocol", RAZLICICA_PROTOKOLA)
                    .stevilo("receivers", naprave.count { it.value.vloga == "receiver" && it.value.povezava != null }.toDouble())
                    .stevilo("senders", posiljatelji.size.toDouble())
                    .stevilo("sync_peers", naprave.count {
                        it.value.povezava != null &&
                            (it.value.zmoznosti.contains(ZMOZNOST_SYNC) || it.value.vloga == "sync-client")
                    }.toDouble())
                    // Samo imena kategorij, nikoli vsebina.
                    .seznamNizov("sync_categories", sinhronizacija.keys.sorted())
                    .toString()
            }
            return HubStreznik.Odgovor(200, stanje)
        }

        // Znana pot z napacnim glagolom ni "ni te poti": naprava, ki isce Hub, prav po tem
        // loci Safeer Hub od poljubnega streznika na istih vratih.
        if (pot in ZNANE_POTI) {
            return HubStreznik.Odgovor(405, napakaJson("Ta način za to pot ni dovoljen.", "metoda_ni_dovoljena"))
        }
        return null
    }

    /** Prijava s QR kodo po HTTP (glej [zacniQr]); klicatelj je ze preveril, da je zahteva krajevna. */
    private fun odgovorQr(pot: String, zahteva: HubStreznik.Zahteva): HubStreznik.Odgovor {
        val telo = JsonLahki.objekt(zahteva.telo)
        val qrId = (telo?.niz("qr_id") ?: "").trim().take(64)
        val deviceId = (telo?.niz("device_id") ?: "").trim().take(NAJVEC_IMENA)
        val skrivnost = (telo?.niz("secret") ?: "").trim().take(128)
        val prevzem = (telo?.niz("poll_secret") ?: "").trim().take(128)
        fun napaka(koda: String?): HubStreznik.Odgovor = when (koda) {
            "prevec_prijav" -> HubStreznik.Odgovor(429, napakaJson("Preveč čakajočih prijav; poskusite čez nekaj minut.", koda))
            "prevec_poskusov" -> HubStreznik.Odgovor(429, napakaJson("Preveč poskusov. Na računalniku se je pokazala nova koda.", koda))
            "prevec_naprav" -> HubStreznik.Odgovor(409, napakaJson("Preveč seznanjenih naprav.", koda))
            "ista_naprava" -> HubStreznik.Odgovor(409, napakaJson("Naprava ne more dovoliti sama sebi.", koda))
            "neveljavno" -> HubStreznik.Odgovor(400, napakaJson("Neveljavna zahteva.", koda))
            else -> HubStreznik.Odgovor(404, napakaJson("Koda je potekla. Na računalniku se je pokazala nova.", "qr_ne_obstaja"))
        }
        fun podatki(p: QrPodatki) = JsonLahki.Zapis().niz("device_id", p.deviceId).niz("name", p.ime).niz("platform", p.platforma)
        when (pot) {
            "/cast/pair/qr/start" -> {
                if (deviceId.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka device_id.", "manjka_device_id"))
                val ime = (telo?.niz("name") ?: "").trim().take(NAJVEC_IMENA)
                val platforma = (telo?.niz("platform") ?: "").trim()
                val (id, n) = zacniQr(deviceId, ime, platforma, (telo?.niz("secret_sha256") ?: "").trim().lowercase(), prevzem)
                if (id == null) return napaka(n)
                return HubStreznik.Odgovor(200, JsonLahki.Zapis()
                    .niz("qr_id", id)
                    .niz("hub_id", IDENTITETA_HUBA)
                    .niz("fp", lastniOdtis)
                    .stevilo("expires_in_seconds", (PIN_VELJA_MS / 1000).toDouble())
                    .toString())
            }
            "/cast/pair/qr/info", "/cast/pair/qr/approve" -> {
                // Samo naprava, ki je ze v Safeer Linku (telefon, tablica), vidi in dovoli prijavo.
                val odobril = napravaZeZetona(zahteva.glave["x-safeer-token"])
                    ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
                if (qrId.isEmpty() || skrivnost.isEmpty()) return napaka("qr_ne_obstaja")
                val (p, n) = if (pot.endsWith("/info")) qrPodatki(qrId, skrivnost) else odobriQr(qrId, skrivnost, odobril)
                if (p == null) return napaka(n)
                val z = podatki(p)
                if (pot.endsWith("/approve")) z.logicno("approved", true)
                return HubStreznik.Odgovor(200, z.toString())
            }
            "/cast/pair/qr/status" -> {
                val (stanje, zeton) = prevzemiQr(qrId, deviceId, prevzem)
                if (stanje == "qr_ne_obstaja") return napaka(stanje)
                val z = JsonLahki.Zapis().logicno("approved", zeton != null)
                if (zeton != null) z.niz("token", zeton).niz("hub_id", IDENTITETA_HUBA).niz("fp", lastniOdtis)
                return HubStreznik.Odgovor(200, z.toString())
            }
            "/cast/pair/qr/join" -> {
                // Pridruzitev s QR kodo sredisca: skrivnost iz kode je dovolj (kot koda z zaslona).
                if (deviceId.isEmpty()) return HubStreznik.Odgovor(400, napakaJson("Manjka device_id.", "manjka_device_id"))
                if (qrId.isEmpty() || skrivnost.isEmpty()) return napaka("qr_ne_obstaja")
                val ime = (telo?.niz("name") ?: "").trim().take(NAJVEC_IMENA)
                val (zeton, n) = pridruzi(qrId, skrivnost, deviceId, ime)
                if (zeton == null) return napaka(n)
                return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("approved", true).niz("token", zeton)
                    .niz("hub_id", IDENTITETA_HUBA).niz("fp", lastniOdtis).toString())
            }
            "/cast/pair/qr/cancel" -> {
                return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("cancelled", prekliciQr(qrId, deviceId, prevzem)).toString())
            }
            "/cast/pair/qr/invite" -> {
                // »Poveži novo napravo« na napravi, ki je ze v Safeer Linku (npr. racunalnik): sredisce ustvari
                // enkratno kodo za pridruzitev, kot jo sicer pokaze na svojem zaslonu. Samo za seznanjeno
                // napravo v krajevnem omrezju - taka lahko novo napravo ze zdaj dovoli s QR prijavo.
                napravaZeZetona(zahteva.glave["x-safeer-token"])
                    ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
                if (qrId.isNotEmpty()) prekliciPridruzitev(qrId)
                val (id, s) = ustvariPridruzitev()
                return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("qr_id", id).niz("secret", s).niz("fp", lastniOdtis)
                    .stevilo("expires_in_seconds", (PIN_VELJA_MS / 1000).toDouble())
                    // Spletni odjemalec: naprava, ki pokaze kodo, jo zgradi kot http://<sredisce>:<web_port>/#...
                    .stevilo("web_port", spletnaVrata.toDouble()).toString())
            }
            "/cast/pair/qr/invite/status" -> {
                napravaZeZetona(zahteva.glave["x-safeer-token"])
                    ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
                val (caka, ime) = synchronized(kljucnica) {
                    pocistiPridruzitve()
                    (pridruzitve.containsKey(qrId)) to pridruzeni[qrId]
                }
                val z = JsonLahki.Zapis().logicno("pending", caka).logicno("joined", ime != null)
                if (ime != null) z.niz("name", ime)
                return HubStreznik.Odgovor(200, z.toString())
            }
            "/cast/pair/qr/invite/cancel" -> {
                napravaZeZetona(zahteva.glave["x-safeer-token"])
                    ?: return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
                prekliciPridruzitev(qrId)
                return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("cancelled", true).toString())
            }
        }
        return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
    }


    // ------------------------------------------------------------------ spletni odjemalec (naprava brez Safeerja)

    /** Datoteke spletnega odjemalca (assets/link-web/...); nastavi krmilnik. Brez tega spletna vrata vracajo 404. */
    @Volatile var beriSredstvo: ((String) -> String?)? = null

    /** Vrata spletnega odjemalca, kot jih je odprl krmilnik (0 = ni na voljo); gredo v vabilo za novo napravo. */
    @Volatile var spletnaVrata: Int = 0

    /**
     * Zahteve na spletnih vratih (goli HTTP, samo domace omrezje): stran spletnega odjemalca in ozek izbor
     * poti huba, ki jih ta potrebuje. Telefon brez Safeerja tako iz navadnega brskalnika poslje povezavo
     * ali besedilo in upravlja televizor. Datotek, zaslona in kroga zaupanja po tej poti ni - to je
     * namenoma samo za tisto, kar sme teci brez sifriranja v domacem omrezju.
     */
    fun odgovoriSplet(zahteva: HubStreznik.Zahteva): HubStreznik.Odgovor? {
        if (!jeKrajevni(zahteva.odjemalec)) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
        val pot = zahteva.pot
        if (zahteva.metoda == "GET" && (pot == "/" || pot == "/index.html" || pot.startsWith("/web/"))) {
            val ime = if (pot == "/" || pot == "/index.html") "index.html" else pot.removePrefix("/web/")
            if (ime.isBlank() || ime.contains("..") || ime.contains('/')) return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
            val vsebina = beriSredstvo?.invoke(ime) ?: return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
            val vrsta = when (ime.substringAfterLast('.', "")) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "application/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "svg" -> "image/svg+xml"
                "json" -> "application/json; charset=utf-8"
                else -> "text/plain; charset=utf-8"
            }
            return HubStreznik.Odgovor(200, vsebina, vrsta)
        }
        if (pot in SPLETNE_POTI) return odgovori(zahteva)
        return HubStreznik.Odgovor(404, napakaJson("Ni te poti.", "ni_poti"))
    }

    /**
     * Nadgradnja v WebSocket je dovoljena samo iz krajevnega omrezja in samo z veljavno
     * enokratno vstopnico. Vrne razlog zavrnitve ali null, ce je vse v redu.
     */
    fun preveriVstopnico(zahteva: HubStreznik.Zahteva): String? {
        if (zahteva.pot != "/cast/ws") return "neznana pot"
        if (!jeKrajevni(zahteva.odjemalec)) return "samo v krajevnem omrežju"
        if (!porabiVstopnico(zahteva.poizvedba["ticket"])) return "neveljavna ali potekla vstopnica"
        return null
    }

    private fun napakaJson(sporocilo: String, koda: String = ""): String =
        JsonLahki.Zapis().niz("detail", sporocilo).niz("code", koda).toString()

    companion object {
        const val RAZLICICA_PROTOKOLA = "0.2"
        const val VSEM = "all"
        /** Vrata spletnega odjemalca (goli HTTP; ce so zasedena, jih streznik izbere sam in QR koda nosi prava). */
        const val SPLETNA_VRATA = 8991
        /** Poti huba, ki jih spletni odjemalec sme klicati: pridruzitev, vstopnica, naprave, besedilo, odhod, preimenovanje. */
        val SPLETNE_POTI = setOf("/cast/pair/qr/join", "/cast/ticket", "/cast/devices", "/cast/share/text", "/cast/health", "/cast/devices/leave",
            "/cast/devices/rename")
        const val ZMOZNOST_SYNC = "sync"

        private const val KLJUC_ZETONOV = "cast_naprave"
        private const val KLJUC_VZDEVKOV = "cast_vzdevki"

        private val ZNANE_POTI = setOf(
            "/cast/pair/start", "/cast/pair/claim", "/cast/pair/sibling", "/cast/ticket", "/cast/devices", "/cast/health",
            "/cast/trust/enroll", "/cast/trust/ring", "/cast/trust/alias", "/cast/auth/challenge", "/cast/auth/ticket",
            "/cast/pair/qr/start", "/cast/pair/qr/info", "/cast/pair/qr/approve", "/cast/pair/qr/status", "/cast/pair/qr/cancel",
            "/cast/pair/qr/join", "/cast/pair/qr/invite", "/cast/pair/qr/invite/status", "/cast/pair/qr/invite/cancel",
            "/cast/devices/leave"
        )
        /** Izziv za prijavo s podpisom velja minuto: dovolj za en krog po omrezju, premalo za zbiranje. */
        private const val IZZIV_VELJA_MS = 60_000L

        private val CAST_POSREDOVANJE = setOf("cast.url", "cast.media", "cast.control")
        private val SYNC_POSREDOVANJE = setOf("sync.request", "sync.data", "sync.status")
        /** Deljenje med napravama; Hub vsebine ne odpira, le posreduje izbrani napravi. */
        private val SHARE_POSREDOVANJE = setOf("share.text", "share.file", "share.screen")
        /** Daljinec (Safeer Control): ukaz napravi z zmoznostjo "remote" in njen odgovor nazaj. */
        private val CONTROL_POSREDOVANJE = setOf("control.command", "control.result")
        const val ZMOZNOST_DALJINEC = "remote"

        // Meje so del zasnove, ne naknadni popravek. Televizor ima malo pomnilnika in ga
        // sistem ob pomanjkanju ubije brez opozorila, zato ima vsak seznam svojo streho.
        const val NAJVEC_NAPRAV = 16
        const val NAJVEC_CAKAJOCIH = 8
        const val NAJVEC_SEZNANJENIH = 16
        const val NAJVEC_VSTOPNIC = 8
        const val NAJVEC_KATEGORIJ = 8
        const val NAJVECJA_KATEGORIJA = 192 * 1024
        const val NAJVEC_SKUPAJ_SYNC = 512 * 1024
        const val NAJVEC_IMENA = 64
        /** Protocol v1: katalog aplikacij ene naprave (vnosov in bajtov). */
        const val NAJVEC_APLIKACIJ = 200
        const val NAJVEC_KATALOG_BAJTOV = 32 * 1024
        /** Razlicica protokola, ki jo odjemalci v1 povedo v cast.register (`protocol`). */
        const val PROTOKOL_V1 = "1.0"
        /** Sejni zetoni (prijava s podpisom): najvec hkrati in koliko casa veljajo. */
        const val NAJVEC_SEJ = 64
        const val SEJA_VELJA_MS = 12 * 60 * 60 * 1000L
        /** Najvec znakov besedila v enem deljenju (share.text po HTTP). */
        const val NAJVEC_BESEDILA = 20_000

        /** Koliko zgresenih kod prenese ena prijava, preden pade. Ugibanje s tem nima smisla. */
        const val NAJVEC_POSKUSOV = 5

        /** Kodo pokaze gostitelj, naprava jo vtipka. Starejsi Hub tega nacina ne pozna. */
        const val NACIN_KODA_NA_GOSTITELJU = "koda_na_gostitelju"
        /** Seznanitev s SPAKE2: koda ostane na obeh zaslonih, po omrezju gredo le tocke krivulje. */
        const val NACIN_SPAKE2 = "spake2"
        /** Identiteta Huba v transkriptu SPAKE2 (obe strani jo poznata vnaprej). */
        const val IDENTITETA_HUBA = "safeer-link-hub"

        fun hexVBajte(h: String): ByteArray? {
            if (h.isEmpty() || h.length % 2 != 0 || h.length > 4096 || !h.all { it in "0123456789abcdefABCDEF" }) return null
            return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun bajteVHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        const val PIN_VELJA_MS = 300_000L
        const val PREVZEM_VELJA_MS = 600_000L
        const val VSTOPNICA_VELJA_MS = 60_000L

        private val nakljucniStevec = SecureRandom()

        private fun privzetoNakljucno(bajtov: Int): String {
            val podatki = ByteArray(bajtov)
            nakljucniStevec.nextBytes(podatki)
            val izpis = StringBuilder(bajtov * 2)
            for (b in podatki) izpis.append(String.format("%02x", b.toInt() and 0xFF))
            return izpis.toString()
        }

        /**
         * Seznanjanje in predvajanje sta krajevna stvar; z interneta se naprava ne sme prijaviti.
         * Enako kot pri Chromecastu: kdor je v hisi, sme vprasati, kdor ni, ne more niti vprasati.
         */
        fun jeKrajevni(naslov: String): Boolean {
            if (naslov.isBlank()) return false
            return try {
                val ip = java.net.InetAddress.getByName(naslov)
                ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
                    ip.isAnyLocalAddress
            } catch (e: Exception) {
                false
            }
        }
    }
}
