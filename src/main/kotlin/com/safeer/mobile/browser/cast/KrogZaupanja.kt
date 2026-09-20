package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Krog zaupanja: naprave, ki si zaupajo med seboj, ne samo hubu.
 *
 * Danes je zaupanje hubovsko: hub izda zeton, naprava si zapomni odtis huba in zeton. Drug hub
 * pomeni novo seznanitev za vsako napravo. Krog to obrne: vsaka naprava ima svoj kljuc (EC P-256,
 * na Androidu v AndroidKeyStore, na Linuxu v nastavitvah), krog pa je seznam vseh javnih kljucev,
 * ki ga hrani VSAKA naprava. Kdorkoli iz kroga lahko postane hub, ker ostale naprave preverijo
 * njegov kljuc, ne odtisa enega potrdila; in hub preveri podpis naprave, ne zetona.
 *
 * Zapis (JSON, brez seznamov - JsonLahki bere samo objekte in nize):
 *   {"v":1, "clani": {"<id>": {"kljuc": "<base64 SPKI>", "ime": "...", "platforma": "tv",
 *                               "dodano": 1758300000.0, "dodal": "<id>"}},
 *           "umiki": {"<id>": {"umaknjeno": 1758300000.0, "umaknil": "<id>"}}}
 *
 * Zdruzevanje dveh krogov je deterministicno, zato pridejo vse naprave do istega kroga ne glede
 * na vrstni red sporocil: unija clanov po id (novejsi zapis zmaga), umik ima prednost pred
 * vnosom, ki je starejsi od njega; vnos, novejsi od umika, napravo vrne (ponovna seznanitev).
 *
 * Ta razred ne pozna Androida in tece tudi v preizkusih na JVM.
 */
class KrogZaupanja(private val shramba: HubUsmerjevalnik.Shramba? = null) {

    data class Clan(
        val id: String,
        /** Javni kljuc, base64 zapisa X.509 SubjectPublicKeyInfo (DER). */
        val kljuc: String,
        val ime: String,
        val platforma: String,
        val dodano: Double,
        val dodal: String,
    )

    data class Umik(val id: String, val umaknjeno: Double, val umaknil: String)

    private val kljucnica = Any()
    private val clani = LinkedHashMap<String, Clan>()
    private val umiki = LinkedHashMap<String, Umik>()

    /** Klice se ob vsaki spremembi kroga (hub ga takrat razposlje). */
    @Volatile
    var naSpremembo: (() -> Unit)? = null

    init {
        shramba?.beri(KLJUC_SHRAMBE)?.let { zdruzi(it, obvesti = false) }
    }

    fun clani(): List<Clan> = synchronized(kljucnica) { clani.values.filter { jeVeljaven(it) } }

    fun clan(id: String): Clan? = synchronized(kljucnica) { clani[id]?.takeIf { jeVeljaven(it) } }

    fun jeClan(id: String): Boolean = clan(id) != null

    /**
     * Clan za [id], tudi kadar je id izpeljan iz kljuca (`n-…`, [idIzKljuca]) in je ta kljuc v krogu pod
     * drugim (starejsim) id-jem: id iz kljuca dokazuje isti kljuc, zato naprava ostane ista. Pripona za
     * sorodnika (`n-…-os`, `n-…-control`) ne moti - kljuc je isti.
     */
    fun clanZaId(id: String): Clan? {
        clan(id)?.let { return it }
        if (!jeIdIzKljuca(id)) return null
        val jedro = id.take(DOLZINA_ID_IZ_KLJUCA)
        return clani().firstOrNull { idIzKljuca(it.kljuc) == jedro }
    }

    fun stevilo(): Int = clani().size

    /** Doda ali osvezi clana. Vrne true, ce se je krog spremenil. */
    fun dodaj(clan: Clan): Boolean {
        if (clan.id.isBlank() || clan.kljuc.isBlank() || dekodirajKljuc(clan.kljuc) == null) return false
        val spremenjeno = synchronized(kljucnica) {
            val obstojeci = clani[clan.id]
            if (obstojeci != null && obstojeci.kljuc == clan.kljuc && obstojeci.dodano >= clan.dodano
                && umiki[clan.id]?.let { it.umaknjeno > obstojeci.dodano } != true) {
                // Ista naprava, isti kljuc: osvezimo le ime, ce se je spremenilo.
                if (obstojeci.ime == clan.ime) return@synchronized false
                clani[clan.id] = obstojeci.copy(ime = clan.ime)
                return@synchronized true
            }
            clani[clan.id] = clan
            // Nov vnos, novejsi od umika, napravo vrne.
            umiki[clan.id]?.let { if (clan.dodano > it.umaknjeno) umiki.remove(clan.id) }
            true
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /** Umakne napravo iz kroga (nadgrobnik ostane, da umik preide na vse naprave). Naprava, ki je
     *  ni v krogu, ne spremeni nicesar - sicer bi vsak tuj id pustil nadgrobnik in razposlal krog. */
    fun umakni(id: String, kdo: String, ob: Double = zdaj()): Boolean {
        val spremenjeno = synchronized(kljucnica) {
            val c = clani[id] ?: return@synchronized false
            if (umiki[id]?.let { it.umaknjeno > c.dodano } == true) return@synchronized false
            umiki[id] = Umik(id, ob, kdo)
            true
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /**
     * Zdruzi tuj krog s svojim. Vrne true, ce se je nas krog spremenil. Neveljavne vnose (brez
     * kljuca, nerazumljiv kljuc) preskoci - pokvarjen zapis ne sme pokvariti kroga.
     */
    fun zdruzi(json: String, obvesti: Boolean = true): Boolean {
        val pogled = JsonLahki.objekt(json) ?: return false
        var spremenjeno = false
        synchronized(kljucnica) {
            pogled.objekt("umiki")?.let { u ->
                for (id in u.kljuci()) {
                    val z = u.objekt(id) ?: continue
                    val ob = z.stevilo("umaknjeno") ?: continue
                    val obstojeci = umiki[id]
                    if (obstojeci == null || obstojeci.umaknjeno < ob) {
                        umiki[id] = Umik(id, ob, z.nizAli("umaknil"))
                        spremenjeno = true
                    }
                }
            }
            pogled.objekt("clani")?.let { c ->
                for (id in c.kljuci()) {
                    val z = c.objekt(id) ?: continue
                    val kljuc = z.niz("kljuc") ?: continue
                    if (dekodirajKljuc(kljuc) == null) continue
                    val nov = Clan(id, kljuc, z.nizAli("ime", id), z.nizAli("platforma"), z.stevilo("dodano") ?: 0.0, z.nizAli("dodal"))
                    val obstojeci = clani[id]
                    if (obstojeci == null || obstojeci.dodano < nov.dodano
                        || (obstojeci.dodano == nov.dodano && obstojeci.kljuc != nov.kljuc && obstojeci.kljuc < nov.kljuc)) {
                        clani[id] = nov
                        spremenjeno = true
                    }
                }
            }
            // Vnos, novejsi od umika, napravo vrne; umik brez clana ostane kot spomin.
            for ((id, u) in umiki.toMap()) {
                val c = clani[id] ?: continue
                if (c.dodano > u.umaknjeno) { umiki.remove(id); spremenjeno = true }
            }
        }
        if (spremenjeno) { shrani(); if (obvesti) naSpremembo?.invoke() }
        return spremenjeno
    }

    /** Zapis kroga; clani in umiki po id, da je isti krog na vsaki napravi tudi isti niz. */
    fun json(): String = synchronized(kljucnica) {
        val c = JsonLahki.Zapis()
        for (clan in clani.values.sortedBy { it.id }) {
            c.surovo(clan.id, JsonLahki.Zapis()
                .niz("kljuc", clan.kljuc).niz("ime", clan.ime).niz("platforma", clan.platforma)
                .stevilo("dodano", clan.dodano).niz("dodal", clan.dodal).toString())
        }
        val u = JsonLahki.Zapis()
        for (umik in umiki.values.sortedBy { it.id }) {
            u.surovo(umik.id, JsonLahki.Zapis().stevilo("umaknjeno", umik.umaknjeno).niz("umaknil", umik.umaknil).toString())
        }
        JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", c.toString()).surovo("umiki", u.toString()).toString()
    }

    /**
     * Ali je [podpisB64] podpis [podatkov] s kljucem naprave [id] (SHA256withECDSA, DER podpis).
     * Naprava, ki ni v krogu ali je umaknjena, nima veljavnega podpisa.
     */
    fun preveriPodpis(id: String, podatki: ByteArray, podpisB64: String): Boolean {
        val clan = clan(id) ?: return false
        // Izrecno funkcija spremljevalca: ta metoda ima isti podpis in bi sicer poklicala samo sebe
        // (kljuc kot id -> ni clana -> false). Zato je JVM preizkus nekoc padel s 401.
        return preveriPodpisSKljucem(clan.kljuc, podatki, podpisB64)
    }

    private fun jeVeljaven(c: Clan): Boolean = umiki[c.id]?.let { it.umaknjeno > c.dodano } != true

    private fun shrani() {
        shramba?.pisi(KLJUC_SHRAMBE, json())
    }

    companion object {
        const val KLJUC_SHRAMBE = "cast_krog"

        fun zdaj(): Double = System.currentTimeMillis() / 1000.0

        fun dekodirajKljuc(b64: String): java.security.PublicKey? = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
        } catch (_: Throwable) { null }

        /** Ali je [podpisB64] podpis [podatkov] z javnim kljucem [kljucB64] (SHA256withECDSA, DER podpis). */
        fun preveriPodpisSKljucem(kljucB64: String, podatki: ByteArray, podpisB64: String): Boolean {
            val kljuc = dekodirajKljuc(kljucB64) ?: return false
            return try {
                val s = Signature.getInstance("SHA256withECDSA")
                s.initVerify(kljuc)
                s.update(podatki)
                s.verify(Base64.getDecoder().decode(podpisB64))
            } catch (_: Throwable) { false }
        }

        /** Id naprave iz javnega kljuca: prvih 16 sestnajstiskih znakov SHA-256 zapisa SPKI. */
        fun idIzKljuca(kljucB64: String): String {
            val izvlecek = java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(kljucB64))
            return "n-" + izvlecek.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** Dolzina id-ja iz kljuca brez pripone sorodnika: "n-" + 16 znakov. */
        const val DOLZINA_ID_IZ_KLJUCA = 18

        /** Ali je [id] izpeljan iz kljuca (`n-<16 hex>`, po zelji s pripono `-os`, `-control` ...). */
        fun jeIdIzKljuca(id: String): Boolean =
            id.length >= DOLZINA_ID_IZ_KLJUCA && id.startsWith("n-") && id.substring(2, DOLZINA_ID_IZ_KLJUCA).all { it in '0'..'9' || it in 'a'..'f' } &&
                (id.length == DOLZINA_ID_IZ_KLJUCA || id[DOLZINA_ID_IZ_KLJUCA] == '-')
    }
}
