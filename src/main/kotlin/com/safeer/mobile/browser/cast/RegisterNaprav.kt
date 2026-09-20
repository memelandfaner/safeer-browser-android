package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

/**
 * Register povezanih naprav Safeer Linka: eno mesto, ki ve, kdo je povezan.
 *
 * Izloceno iz HubUsmerjevalnik, ki je zrasel cez 2000 vrstic in je mesal prijavo, seznanitev,
 * zaupanje in usmerjanje. Tu je samo troje: kdo je povezan, kaj o sebi pove in kaj se zgodi,
 * ko se ista naprava vrne z novo povezavo (nova zamenja staro - brez tega naprava ostane
 * "povezana", a nevidna).
 *
 * Kljucavnica je ista kot v usmerjevalniku in je podana od zunaj: register je del istega
 * stanja, ne svoj otok. Java monitor je ponovno vstopen, zato sme usmerjevalnik klicati
 * register tudi znotraj svojega synchronized bloka.
 *
 * Razred ne pozna Androida (preizkusljiv na navadnem JVM), ne posilja sporocil in ne ve
 * nicesar o zetonih, seznanitvi ali krogu zaupanja.
 */
class RegisterNaprav(
    private val kljucnica: Any,
    private val ura: () -> Long,
    private val najvecNaprav: Int,
    private val najvecImena: Int
) {

    /** Ena naprava, kakor jo vidi register. */
    class Naprava(
        val id: String,
        var ime: String,
        var vloga: String,
        var zmoznosti: List<String>,
        var naslov: String,
        var zadnjic: Double,
        var povezava: HubUsmerjevalnik.Odjemalec?,
        // Protocol v1: model naprave. Prazno pri odjemalcih protokola 0.2.
        var protokol: String = "",
        var platforma: String = "",
        var vrsta: String = "",
        var razlicica: String = "",
        var prioriteta: Int = 0,
        /** Katalog aplikacij naprave: JSON objekt {"<id>": {"name": ..., "kind": ...}} ali prazno. */
        var aplikacije: String = ""
    )

    private val naprave = LinkedHashMap<String, Naprava>()
    private val posiljatelji = LinkedHashSet<HubUsmerjevalnik.Odjemalec>()

    // ------------------------------------------------------------------ branje

    fun najdi(id: String?): Naprava? = synchronized(kljucnica) { if (id == null) null else naprave[id] }

    fun povezavaOd(id: String?): HubUsmerjevalnik.Odjemalec? = synchronized(kljucnica) {
        if (id == null) null else naprave[id]?.povezava
    }

    /** Naprave z odprto povezavo (te vidi uporabnik v Napravah). */
    fun povezane(): List<Naprava> = synchronized(kljucnica) { naprave.values.filter { it.povezava != null } }

    /** Vse naprave, tudi tiste, ki so trenutno odklopljene (ime in zmoznosti so uporabni, ko se vrnejo). */
    fun vse(): List<Naprava> = synchronized(kljucnica) { naprave.values.toList() }

    /** Povezave vseh povezanih naprav; kopija, da posiljanje tece zunaj kljucavnice. */
    fun povezanePovezave(): List<HubUsmerjevalnik.Odjemalec> = synchronized(kljucnica) {
        naprave.values.mapNotNull { it.povezava }
    }

    fun steviloPovezanih(): Int = synchronized(kljucnica) { naprave.count { it.value.povezava != null } }

    fun stevilo(pogoj: (Naprava) -> Boolean): Int = synchronized(kljucnica) { naprave.values.count(pogoj) }

    fun idPovezave(povezava: HubUsmerjevalnik.Odjemalec): String? = synchronized(kljucnica) {
        naprave.entries.firstOrNull { it.value.povezava === povezava }?.key
    }

    fun posiljateljiKopija(): List<HubUsmerjevalnik.Odjemalec> = synchronized(kljucnica) { posiljatelji.toList() }

    fun steviloPosiljateljev(): Int = synchronized(kljucnica) { posiljatelji.size }

    fun odstraniPosiljatelja(povezava: HubUsmerjevalnik.Odjemalec) {
        synchronized(kljucnica) { posiljatelji.remove(povezava) }
    }

    /** Naprava se je oglasila (npr. odgovor na ukaz): osvezi cas zadnjega stika. */
    fun osveziZadnjic(id: String?) {
        synchronized(kljucnica) { if (id != null) naprave[id]?.zadnjic = ura() / 1000.0 }
    }

    fun posodobiKatalog(id: String, katalog: String): Boolean = synchronized(kljucnica) {
        val n = naprave[id] ?: return false
        n.aplikacije = katalog
        true
    }

    // ------------------------------------------------------------------ prijava in odklop

    /** Izid prijave: sprejeta, ali zavrnjena z razlogom za napravo. */
    class Izid(val sprejeta: Boolean, val koda: String = "")

    /**
     * Prijavi ali posodobi napravo.
     *
     * Ce se ista naprava javi z novo povezavo (po izpadu ali ponovnem zagonu sredisca), nova
     * zamenja staro in staro zapremo - sicer bi ob zaprtju stare vpis naprave izgubil povezavo,
     * nova pa bi ostala odprta in nevidna. Staro zapremo zunaj kljucavnice: pisanje na vticnico
     * ne sme drzati registra.
     */
    fun registriraj(
        od: HubUsmerjevalnik.Odjemalec,
        deviceId: String,
        ime: String,
        vloga: String,
        zmoznosti: List<String>,
        protokol: String = "",
        platforma: String = "",
        vrsta: String = "",
        razlicica: String = "",
        prioriteta: Int = 0,
        aplikacije: String? = null
    ): Izid {
        val stara = synchronized(kljucnica) { naprave[deviceId]?.povezava?.takeIf { it !== od } }
        if (stara != null) {
            synchronized(kljucnica) { posiljatelji.remove(stara) }
            try {
                stara.zapri(1000, "nova povezava iste naprave")
            } catch (_: Throwable) {
            }
        }
        synchronized(kljucnica) {
            if (!naprave.containsKey(deviceId) && naprave.size >= najvecNaprav) {
                pocisti()
                if (naprave.size >= najvecNaprav) return Izid(false, "prevec_naprav")
            }
            val naprava = naprave.getOrPut(deviceId) {
                Naprava(deviceId, deviceId, vloga, zmoznosti, od.naslov, ura() / 1000.0, null)
            }
            naprava.ime = (ime.takeIf { it.isNotBlank() } ?: deviceId).take(najvecImena)
            naprava.vloga = vloga
            naprava.zmoznosti = zmoznosti
            naprava.protokol = protokol.take(8)
            naprava.platforma = platforma.take(16)
            naprava.vrsta = vrsta.take(16)
            naprava.razlicica = razlicica.take(32)
            naprava.prioriteta = prioriteta.coerceIn(0, 1000)
            if (aplikacije != null) naprava.aplikacije = aplikacije
            // Naslov vzamemo iz vticnice, ne iz tega, kar naprava trdi o sebi.
            naprava.naslov = od.naslov
            naprava.zadnjic = ura() / 1000.0
            naprava.povezava = od
            if (vloga != "receiver") posiljatelji.add(od)
        }
        return Izid(true)
    }

    /** Povezava je padla. Vrne true, ce se je seznam spremenil (potem ga je treba objaviti). */
    fun odklopi(povezava: HubUsmerjevalnik.Odjemalec): Boolean = synchronized(kljucnica) {
        var spremenjeno = false
        val odklopljeni = naprave.filterValues { it.povezava === povezava }.keys.toList()
        for (id in odklopljeni) {
            val naprava = naprave[id] ?: continue
            naprava.povezava = null
            spremenjeno = true
            // Naprave ne pozabimo takoj: ime in zmoznosti so uporabni, ko se vrne.
            // Ce jih je prevec, pade ven najstarejsa odklopljena.
            pocisti()
        }
        posiljatelji.remove(povezava)
        spremenjeno
    }

    /** Naprava je odsla (odjava, odvzem zaupanja): odstrani jo in vrni njeno povezavo. */
    fun odstrani(id: String): HubUsmerjevalnik.Odjemalec? = synchronized(kljucnica) {
        val n = naprave.remove(id) ?: return null
        n.povezava?.let { posiljatelji.remove(it) }
        n.povezava
    }

    /** Ce je naprav prevec, gredo ven odklopljene - povezanih nikoli ne vrzemo iz registra. */
    fun pocisti() {
        synchronized(kljucnica) {
            while (naprave.size > najvecNaprav) {
                val odvecna = naprave.entries.firstOrNull { it.value.povezava == null } ?: break
                naprave.remove(odvecna.key)
            }
        }
    }
}
