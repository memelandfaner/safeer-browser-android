package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2.

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
        var povezava: Odjemalec?
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
    private val vstopnice = LinkedHashMap<String, Long>()
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
    // Uporabnik lahko napravo poimenuje po svoje ("Dnevna soba", "Anina tablica"). Ime
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

    init {
        naloziZetone()
        naloziVzdevke()
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
        return false
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
        return null
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
    private fun oznaciStaroNapravo(pairId: String) = synchronized(kljucnica) {
        val prijava = prijave[pairId] ?: return
        if (!prijava.staroPovprasevanje) {
            prijava.staroPovprasevanje = true
            naSpremembePrijav?.invoke()
        }
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

    // ------------------------------------------------------------------ vstopnice

    /**
     * Enokratna vstopnica za WebSocket, kratke veljavnosti. Povezava brez nje sploh ne nastane -
     * enako kot na racunalniku, kjer jo izda Controlov SessionManager.
     */
    fun izdajVstopnico(): String = synchronized(kljucnica) {
        pocistiVstopnice()
        if (vstopnice.size >= NAJVEC_VSTOPNIC) {
            // Najstarejsa pade ven; drugace bi jih nekdo lahko naracal poljubno veliko.
            vstopnice.remove(vstopnice.keys.first())
        }
        val vstopnica = nakljucni(16)
        vstopnice[vstopnica] = ura()
        return vstopnica
    }

    private fun pocistiVstopnice() {
        val zdaj = ura()
        val potekle = vstopnice.filterValues { zdaj - it > VSTOPNICA_VELJA_MS }.keys.toList()
        for (kljuc in potekle) vstopnice.remove(kljuc)
    }

    /** Porabi vstopnico; druga uporaba iste ne uspe. */
    fun porabiVstopnico(vstopnica: String?): Boolean {
        if (vstopnica.isNullOrEmpty()) return false
        synchronized(kljucnica) {
            pocistiVstopnice()
            val najdena = vstopnice.keys.firstOrNull { enaka(it, vstopnica) } ?: return false
            vstopnice.remove(najdena)
            return true
        }
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

        // Kar ni na seznamu, se ne posreduje nikamor. Dovoljenja se ne smejo siriti po nesreci.
        return potrditev(id, "error", "Neznan tip sporočila: '$tip'", prostor, koda = "neznan_tip")
    }

    private fun registriraj(od: Odjemalec, sporocilo: JsonLahki.Pogled, id: String): String {
        val tovor = sporocilo.objekt("payload")
        val deviceId = tovor?.niz("device_id")
        if (deviceId.isNullOrBlank()) return potrditev(id, "rejected", "Manjka device_id.", koda = "manjka_device_id")

        val vloga = tovor.niz("role") ?: "receiver"
        val zmoznosti = tovor.nizi("capabilities").ifEmpty { listOf("url", "control") }
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
            // Naslov vzamemo iz vticnice, ne iz tega, kar naprava trdi o sebi.
            naprava.naslov = od.naslov
            naprava.zadnjic = ura() / 1000.0
            naprava.povezava = od
            if (vloga != "receiver") posiljatelji.add(od)
        }
        // Vsaka nova naprava spremeni seznam za vse: tudi posiljatelj je zdaj mozen cilj deljenja.
        objaviNaprave()
        naSpremembeNaprav?.invoke()
        return potrditev(id, "accepted")
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

        if (pot == "/cast/ticket" && zahteva.metoda == "POST") {
            if (!krajevni) return HubStreznik.Odgovor(403, napakaJson("Samo v krajevnem omrežju.", "samo_krajevno"))
            if (!jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
                return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
            }
            return HubStreznik.Odgovor(
                200,
                JsonLahki.Zapis()
                    .niz("ticket", izdajVstopnico())
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
        const val ZMOZNOST_SYNC = "sync"

        private const val KLJUC_ZETONOV = "cast_naprave"
        private const val KLJUC_VZDEVKOV = "cast_vzdevki"

        private val ZNANE_POTI = setOf(
            "/cast/pair/start", "/cast/pair/claim", "/cast/pair/sibling", "/cast/ticket", "/cast/devices", "/cast/health"
        )

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
