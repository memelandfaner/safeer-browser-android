package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import android.content.Context
import android.net.http.SslCertificate
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.net.HttpURLConnection
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * TLS za Safeer Link: vsaka povezava s Hubom je sifrirana in vezana na Hubov kljuc.
 *
 * Hub ima svoj par kljucev v Android KeyStore (zasebni kljuc nikoli ne zapusti naprave) in
 * sam sebi podpisano potrdilo. Overitelja v domacem omrezju ni, zato naprave Hub prepoznajo
 * po prstnem odtisu potrdila (SHA-256), ki si ga zapomnijo ob seznanitvi: seznanitev s
 * kodo (SPAKE2) odtis podpise, zato ga napadalec v sredini ne more podtakniti. Po tem
 * naprava sprejme samo potrdilo s tem odtisom - vsako drugo je napaka, ne opozorilo.
 *
 * Pred seznanitvijo naprava odtisa se ne pozna: takrat povezavo sprejme, si odtis zapomni
 * in ga vplete v seznanitev. Ce je vmes napadalec, se koda ne ujema in seznanitev pade.
 */
object HubTls {

    private const val TAG = "SafeerHubTls"
    // v2: kljuc mora dovoliti tudi DIGEST_NONE - TLS (Conscrypt) podpisuje ze izracunani
    // izvlecek (NONEwithECDSA); brez tega rokovanje pade z "Incompatible digest".
    private const val ALIAS = "safeer_link_hub_tls_v2"
    private const val STARI_ALIAS = "safeer_link_hub_tls"
    private const val SHRAMBA = "AndroidKeyStore"

    /** Kljuc v nastavitvah (safeer_cast_prefs): odtis Huba, ki mu ta naprava zaupa. */
    const val KEY_HUB_FP = "hub_fp"
    const val PREFS = "safeer_cast_prefs"

    private val PROTOKOLI = arrayOf("TLSv1.3", "TLSv1.2")

    @Volatile
    private var lastnoPotrdilo: X509Certificate? = null

    // ------------------------------------------------------------------ gostitelj

    /** Potrdilo Huba na tej napravi; kljuc nastane ob prvem klicu in ostane. */
    @Synchronized
    fun potrdilo(): X509Certificate {
        lastnoPotrdilo?.let { return it }
        val ks = KeyStore.getInstance(SHRAMBA).apply { load(null) }
        try { if (ks.containsAlias(STARI_ALIAS)) ks.deleteEntry(STARI_ALIAS) } catch (_: Throwable) { }
        var c = ks.getCertificate(ALIAS) as? X509Certificate
        if (c == null || c.notAfter.before(Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000))) {
            if (c != null) ks.deleteEntry(ALIAS)
            val zdaj = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Safeer Link"))
                .setCertificateSerialNumber(BigInteger(63, SecureRandom()))
                .setCertificateNotBefore(Date(zdaj - 24L * 3600 * 1000))
                .setCertificateNotAfter(Date(zdaj + 20L * 365 * 24 * 3600 * 1000))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, SHRAMBA).apply { initialize(spec) }.generateKeyPair()
            c = ks.getCertificate(ALIAS) as X509Certificate
            Log.i(TAG, "Nov kljuc Huba, odtis ${odtis(c).take(16)}…")
        }
        lastnoPotrdilo = c
        return c
    }

    /** Prstni odtis (SHA-256, hex) lastnega potrdila. */
    fun lastniOdtis(): String = odtis(potrdilo())

    /**
     * Javni kljuc te naprave (isti kljuc kot za TLS huba), base64 zapisa SubjectPublicKeyInfo:
     * vnos v krog zaupanja. Ker hubovo potrdilo nosi prav ta kljuc, naprave hub prepoznajo po
     * krogu, ne po odtisu enega potrdila.
     */
    fun javniKljucB64(): String =
        android.util.Base64.encodeToString(potrdilo().publicKey.encoded, android.util.Base64.NO_WRAP)

    /** Podpis s kljucem te naprave (SHA256withECDSA, DER), base64. Kljuc ne zapusti KeyStore. */
    fun podpisi(podatki: ByteArray): String {
        potrdilo()
        val ks = KeyStore.getInstance(SHRAMBA).apply { load(null) }
        val kljuc = ks.getKey(ALIAS, null) as java.security.PrivateKey
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initSign(kljuc)
        s.update(podatki)
        return android.util.Base64.encodeToString(s.sign(), android.util.Base64.NO_WRAP)
    }

    /** Tovarna streznih vticnic: samo TLS 1.2/1.3, kljuc iz KeyStore. */
    fun streznik(): SSLServerSocketFactory {
        potrdilo()
        val ks = KeyStore.getInstance(SHRAMBA).apply { load(null) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx.serverSocketFactory
    }

    /** Sprejeto strezno vticnico omeji na varne protokole. */
    fun nastaviStrezno(v: SSLServerSocket) {
        v.useClientMode = false
        v.enabledProtocols = v.supportedProtocols.filter { it in PROTOKOLI }.toTypedArray()
        v.needClientAuth = false
    }

    // ------------------------------------------------------------------ odjemalec

    /**
     * Zaupnik, ki Hub prepozna po odtisu. `pripeti` = null pomeni "se ne poznam" (samo
     * med seznanitvijo); takrat sprejme katerokoli potrdilo in si zapomni njegov odtis.
     */
    // Lastni zaupnik je namen: Hub ima samopodpisano potrdilo, zaupamo samo pripetemu odtisu
    // (med seznanitvijo pa SPAKE2 veze odtis na kodo, zato napadalec v sredini pade).
    @Suppress("CustomX509TrustManager")
    class Zaupnik(private val pripeti: String?, private val pripetiKljuc: String? = null) : X509TrustManager {
        @Volatile
        var videni: String? = null
            private set

        /** Javni kljuc potrdila, ki smo ga videli (base64 SPKI) - za primerjavo s krogom zaupanja. */
        @Volatile
        var videniKljuc: String? = null
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("odjemalskih potrdil ne preverjamo")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("Hub ni poslal potrdila")
            val o = odtis(chain[0])
            videni = o
            val k = android.util.Base64.encodeToString(chain[0].publicKey.encoded, android.util.Base64.NO_WRAP)
            videniKljuc = k
            if (pripeti != null && !MessageDigest.isEqual(o.toByteArray(), pripeti.toByteArray())) {
                throw CertificateException("Safeer Link: Hub ima drugo potrdilo, kot je bilo ob seznanitvi (odtis se ne ujema)")
            }
            // Izvoljeni hub (drug clan kroga): njegovo potrdilo mora nositi kljuc, ki ga ima v krogu zaupanja.
            if (pripetiKljuc != null && !MessageDigest.isEqual(k.toByteArray(), pripetiKljuc.toByteArray())) {
                throw CertificateException("Safeer Link: potrdilo huba ne nosi kljuca iz kroga zaupanja")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Odjemalska tovarna + zaupnik za dani (ali se neznani) odtis. */
    fun odjemalec(pripeti: String?, pripetiKljuc: String? = null): Pair<SSLSocketFactory, Zaupnik> {
        val z = Zaupnik(pripeti, pripetiKljuc)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(z), SecureRandom())
        return ctx.socketFactory to z
    }

    /** Odjemalsko vticnico omeji na varne protokole. */
    fun nastaviOdjemalsko(v: SSLSocket) {
        v.useClientMode = true
        v.enabledProtocols = v.supportedProtocols.filter { it in PROTOKOLI }.toTypedArray()
    }

    /**
     * HttpsURLConnection z odtisom Huba, ki mu ta naprava zaupa. Za navadno http:// povezavo
     * (Hub brez TLS, npr. med prehodom) ne naredi nic.
     */
    fun pripravi(povezava: HttpURLConnection, pripeti: String?): Zaupnik? {
        if (povezava !is HttpsURLConnection) return null
        val (tovarna, z) = odjemalec(pripeti)
        povezava.sslSocketFactory = tovarna
        // Naslov je IP v domacem omrezju; identiteto potrjuje odtis, ne ime gostitelja.
        povezava.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        return z
    }

    /**
     * HttpURLConnection do Huba, ki mu ta naprava ze zaupa: samo https in samo z odtisom,
     * ki si ga je zapomnila ob seznanitvi. Brez odtisa ali brez TLS povezave ne odpre -
     * zeton in vsebina ne smeta nikoli potovati v cistem besedilu.
     */
    fun zavaruj(povezava: HttpURLConnection, context: Context) {
        if (povezava !is HttpsURLConnection) throw java.io.IOException("Safeer Link: Hub brez TLS - posodobi Safeer na gostitelju")
        val odtis = pripetiOdtis(context)
            ?: throw java.io.IOException("Safeer Link: naprava s tem Hubom se ni seznanjena (ni odtisa potrdila)")
        pripravi(povezava, odtis)
    }

    /**
     * OkHttp za pogovor s Hubom: TLS z odtisom, ki mu ta naprava zaupa (`pripeti`), ali -
     * samo pred seznanitvijo - s katerimkoli, ki si ga zapomni. Vrne zaupnika, da lahko
     * klicatelj po prvem odgovoru prebere `videni`.
     */
    fun okhttp(graditelj: okhttp3.OkHttpClient.Builder, pripeti: String?, pripetiKljuc: String? = null): Pair<okhttp3.OkHttpClient.Builder, Zaupnik> {
        val (tovarna, z) = odjemalec(pripeti, pripetiKljuc)
        graditelj.sslSocketFactory(tovarna, z)
        graditelj.hostnameVerifier { _, _ -> true }
        return graditelj to z
    }

    fun pripetiOdtis(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HUB_FP, null)?.takeIf { it.isNotBlank() }

    fun shraniOdtis(context: Context, odtis: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HUB_FP, odtis).apply()
    }

    fun pozabiOdtis(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HUB_FP).apply()
    }

    // ------------------------------------------------------------------ pomocniki

    fun odtis(c: Certificate): String = sha256Hex(c.encoded)

    /** Odtis potrdila, kot ga vidi WebView (za stran, ki jo strese Hub, npr. pogled zaslona). */
    fun odtis(c: SslCertificate): String? {
        val surovo = SslCertificate.saveState(c).getByteArray("x509-certificate") ?: return null
        return try {
            sha256Hex(CertificateFactory.getInstance("X.509").generateCertificate(surovo.inputStream()).encoded)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Za WebView: stran s Huba (npr. pogled deljenega zaslona) ima Hubovo samopodpisano
     * potrdilo. Sprejmemo jo samo, ce je naslov Hubov IN se odtis potrdila ujema s
     * pripetim. Vse drugo ostane napaka - nikoli "nadaljuj kljub opozorilu".
     */
    fun jeZaupanjaVredenHub(context: Context, napaka: android.net.http.SslError?): Boolean {
        if (napaka == null) return false
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pripeti = prefs.getString(KEY_HUB_FP, null)?.takeIf { it.isNotBlank() } ?: return false
            val hubWs = prefs.getString("hub_url", "") ?: ""
            if (!hubWs.startsWith("wss://")) return false
            val hubHttp = hubWs.replace(Regex("^wss"), "https")
                .substringBefore("/cast/ws").substringBefore("/link/ws").substringBefore("/safeer/ws").trimEnd('/')
            val potrdilo = napaka.certificate ?: return false
            val videni = odtis(potrdilo) ?: return false
            jeNaslovHuba(napaka.url, hubHttp) &&
                MessageDigest.isEqual(videni.toByteArray(), pripeti.toByteArray())
        } catch (e: Throwable) {
            false
        }
    }

    /** Ali je naslov (https://gostitelj:vrata/...) naslov Huba, ki mu zaupamo. */
    fun jeNaslovHuba(url: String, hubHttp: String): Boolean {
        if (hubHttp.isBlank()) return false
        return try {
            val a = java.net.URL(url)
            val b = java.net.URL(hubHttp)
            a.protocol == b.protocol && a.host == b.host && a.port == b.port
        } catch (e: Exception) {
            false
        }
    }

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
