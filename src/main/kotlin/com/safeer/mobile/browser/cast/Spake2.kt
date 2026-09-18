package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2.

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 (RFC 9382) nad krivuljo P-256 za seznanjanje v Safeer Linku.
 *
 * Zakaj: 6-mestna koda, ki jo gostitelj pokaze in jo uporabnik vtipka, ne sme nikoli
 * potovati po omrezju - niti sifrirana. S SPAKE2 obe strani iz kode izpeljeta skupni
 * kljuc; kdor kode ne pozna (napadalec v omrezju, tudi "clovek v sredini"), iz
 * izmenjanih sporocil ne izve nic in kode ne more uganiti brez povezave: vsak poskus
 * zahteva nov krog s Hubom, ki poskuse steje.
 *
 * Brez zunanjih knjiznic: aritmetika na P-256 z BigInteger. Enak zapis kot spake2.py v
 * brskalniku za Linux; oba preverja isti testni vektor iz RFC 9382 (Spake2Test).
 *
 * Vloge: Hub je stran A (uporablja tocko M), naprava, ki se prikljucuje, stran B (N).
 */
class Spake2 private constructor(
    private val w: BigInteger,
    private val jaz: ByteArray,
    private val oni: ByteArray,
    private val aad: ByteArray,
    private val strezniska: Boolean,
    private val x: BigInteger
) {
    private val moja: Tocka
    private var tt: ByteArray? = null
    var ke: ByteArray? = null
        private set
    private var kca: ByteArray? = null
    private var kcb: ByteArray? = null

    init {
        val maska = if (strezniska) M else NN
        moja = sestej(pomnozi(x, G), pomnozi(w, maska)) ?: throw IllegalStateException("neveljavna tocka")
    }

    /** Moje javno sporocilo (65 bajtov, nestisnjena tocka). */
    fun sporocilo(): ByteArray = kodiraj(moja)

    /** Vrne mojo potrditev; skupni kljuc je v `ke`. Ob neveljavni tuji tocki vrze IllegalArgumentException. */
    fun zakljuci(njihovo: ByteArray): ByteArray {
        val njihova = dekodiraj(njihovo)
        val maskaNjih = if (strezniska) NN else M
        val k = pomnozi(x, sestej(njihova, negiraj(pomnozi(w, maskaNjih))))
            ?: throw IllegalArgumentException("neveljaven skupni element")
        val (a, b, pa, pb) = if (strezniska) listOf(jaz, oni, kodiraj(moja), kodiraj(njihova))
        else listOf(oni, jaz, kodiraj(njihova), kodiraj(moja))
        val wB = na32(w.mod(N))
        val t = dolz(a) + dolz(b) + dolz(pa) + dolz(pb) + dolz(kodiraj(k)) + dolz(wB)
        tt = t
        val h = MessageDigest.getInstance("SHA-256").digest(t)
        ke = h.copyOfRange(0, 16)
        val ka = h.copyOfRange(16, 32)
        val kc = hkdf(ka, ByteArray(0), "ConfirmationKeys".toByteArray() + aad, 32)
        kca = kc.copyOfRange(0, 16)
        kcb = kc.copyOfRange(16, 32)
        return hmac(if (strezniska) kca!! else kcb!!, t)
    }

    /** True, ce je druga stran izpeljala isti kljuc (torej pozna isto kodo). */
    fun preveri(njihovaPotrditev: ByteArray): Boolean {
        val t = tt ?: return false
        val njihov = (if (strezniska) kcb else kca) ?: return false
        return MessageDigest.isEqual(hmac(njihov, t), njihovaPotrditev)
    }

    companion object {
        private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        private val A = P.subtract(BigInteger.valueOf(3))
        private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
        private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        private val G = Tocka(
            BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
            BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
        )
        // Tocki M in N iz RFC 9382, razdelek 6 (P-256).
        private val M = dekodiraj(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
        private val NN = dekodiraj(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))
        private val nakljucno = SecureRandom()

        /** Hub (stran A). `sol` je pair_id, da isti prepis ne velja v dveh sejah. */
        fun streznik(koda: String, jaz: String, odjemalec: String, aad: ByteArray = ByteArray(0), sol: ByteArray = ByteArray(0)): Spake2 =
            Spake2(wIzKode(koda, sol), jaz.toByteArray(), odjemalec.toByteArray(), aad, true, nakljucniSkalar())

        /** Naprava, ki se prikljucuje (stran B). */
        fun odjemalec(koda: String, jaz: String, hub: String, aad: ByteArray = ByteArray(0), sol: ByteArray = ByteArray(0)): Spake2 =
            Spake2(wIzKode(koda, sol), jaz.toByteArray(), hub.toByteArray(), aad, false, nakljucniSkalar())

        /** Samo za teste: znani w in x (testni vektor RFC 9382). */
        internal fun zaTest(w: BigInteger, jaz: String, oni: String, strezniska: Boolean, x: BigInteger): Spake2 =
            Spake2(w, jaz.toByteArray(), oni.toByteArray(), ByteArray(0), strezniska, x)

        /** w = MHF(koda) mod n; kratko kodo najprej raztegne PBKDF2 (20000 krogov) s soljo seje. */
        fun wIzKode(koda: String, sol: ByteArray): BigInteger {
            val spec = PBEKeySpec(koda.trim().toCharArray(), if (sol.isEmpty()) ByteArray(1) else sol, 20000, 48 * 8)
            val surovo = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return BigInteger(1, surovo).mod(N)
        }

        private fun nakljucniSkalar(): BigInteger {
            val b = ByteArray(48)
            nakljucno.nextBytes(b)
            return BigInteger(1, b).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        }

        // ------------------------------------------------------------ krivulja
        class Tocka(val x: BigInteger, val y: BigInteger)

        private fun inv(a: BigInteger): BigInteger = a.mod(P).modPow(P.subtract(BigInteger.valueOf(2)), P)

        private fun sestej(p1: Tocka?, p2: Tocka?): Tocka? {
            if (p1 == null) return p2
            if (p2 == null) return p1
            val lam: BigInteger
            if (p1.x == p2.x) {
                if (p1.y.add(p2.y).mod(P).signum() == 0) return null
                lam = BigInteger.valueOf(3).multiply(p1.x).multiply(p1.x).add(A).multiply(inv(BigInteger.valueOf(2).multiply(p1.y))).mod(P)
            } else {
                lam = p2.y.subtract(p1.y).multiply(inv(p2.x.subtract(p1.x))).mod(P)
            }
            val x3 = lam.multiply(lam).subtract(p1.x).subtract(p2.x).mod(P)
            val y3 = lam.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
            return Tocka(x3, y3)
        }

        private fun pomnozi(kIn: BigInteger, tocka: Tocka?): Tocka? {
            var k = kIn.mod(N)
            var rezultat: Tocka? = null
            var osnova = tocka
            while (k.signum() > 0) {
                if (k.testBit(0)) rezultat = sestej(rezultat, osnova)
                osnova = sestej(osnova, osnova)
                k = k.shiftRight(1)
            }
            return rezultat
        }

        private fun negiraj(t: Tocka?): Tocka? = if (t == null) null else Tocka(t.x, t.y.negate().mod(P))

        private fun naKrivulji(t: Tocka): Boolean {
            if (t.x.signum() < 0 || t.x >= P || t.y.signum() < 0 || t.y >= P) return false
            val levo = t.y.multiply(t.y).mod(P)
            val desno = t.x.multiply(t.x).multiply(t.x).add(A.multiply(t.x)).add(B).mod(P)
            return levo == desno
        }

        private fun na32(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            val r = ByteArray(32)
            val od = if (b.size > 32) b.size - 32 else 0
            val dolzina = minOf(32, b.size)
            System.arraycopy(b, od, r, 32 - dolzina, dolzina)
            return r
        }

        fun kodiraj(t: Tocka?): ByteArray {
            if (t == null) throw IllegalArgumentException("tocke v neskoncnosti ni mogoce kodirati")
            return byteArrayOf(4) + na32(t.x) + na32(t.y)
        }

        fun dekodiraj(b: ByteArray): Tocka {
            val t = when {
                b.size == 65 && b[0] == 4.toByte() ->
                    Tocka(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
                b.size == 33 && (b[0] == 2.toByte() || b[0] == 3.toByte()) -> {
                    val x = BigInteger(1, b.copyOfRange(1, 33))
                    val y2 = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P)
                    var y = y2.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
                    if (y.multiply(y).subtract(y2).mod(P).signum() != 0) throw IllegalArgumentException("tocka ni na krivulji")
                    if (y.testBit(0) != (b[0] == 3.toByte())) y = P.subtract(y)
                    Tocka(x, y)
                }
                else -> throw IllegalArgumentException("neveljaven zapis tocke")
            }
            if (!naKrivulji(t)) throw IllegalArgumentException("tocka ni na krivulji")
            return t
        }

        // ------------------------------------------------------------ pomocniki
        private fun dolz(b: ByteArray): ByteArray {
            val d = ByteArray(8)
            var n = b.size.toLong()
            for (i in 0 until 8) { d[i] = (n and 0xff).toByte(); n = n shr 8 }
            return d + b
        }

        private fun hmac(kljuc: ByteArray, podatki: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(kljuc, "HmacSHA256"))
            return mac.doFinal(podatki)
        }

        private fun hkdf(ikm: ByteArray, sol: ByteArray, info: ByteArray, dolzina: Int): ByteArray {
            val prk = hmac(if (sol.isEmpty()) ByteArray(32) else sol, ikm)
            var izhod = ByteArray(0)
            var blok = ByteArray(0)
            var i = 1
            while (izhod.size < dolzina) {
                blok = hmac(prk, blok + info + byteArrayOf(i.toByte()))
                izhod += blok
                i++
            }
            return izhod.copyOfRange(0, dolzina)
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
