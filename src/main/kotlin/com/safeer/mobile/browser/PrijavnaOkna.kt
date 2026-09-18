package com.safeer.mobile.browser

/**
 * Ali pojavno okno vodi na prijavo?
 *
 * Odlocitev je vezana na to, KAM okno pelje, in ne na to, na kateri strani smo. Brskalnik
 * zato ne potrebuje seznama strani, na katerih prijava deluje - deluje povsod, kjer okno
 * res pelje na prijavo.
 *
 * Dve pravili:
 *  1. katerakoli stran https, katere pot vsebuje odsek OAuth (`oauth`, `oauth2`,
 *     `authorize`). Tako deluje tudi lastna prijava podjetja ali manjse storitve, ne da bi
 *     jo morali nasteti. Oglasna pojavna okna teh poti ne uporabljajo;
 *  2. znani ponudniki prijave (Google, Apple, Microsoft, Facebook, X in drugi) s potjo, ki
 *     govori o prijavi - za primere, kjer poti ne oznaci beseda oauth (npr. ServiceLogin).
 *
 * Vse drugo ostane preprecen oglas. Loceno od [AuthenticationPages]: tam gre za to, ali je
 * stran, na kateri smo, prijavna (in ji zato ne vbrizgamo skript), tu pa za to, ali sme
 * novo okno sploh nastati.
 *
 * Naslov razclenimo sami, da je pravilo mogoce preizkusiti na JVM.
 */
object PrijavnaOkna {

    private val ponudniki = setOf(
        "accounts.google.com", "accounts.youtube.com", "accounts.google.si",
        "appleid.apple.com", "idmsa.apple.com",
        "login.microsoftonline.com", "login.microsoft.com", "login.live.com",
        "facebook.com", "m.facebook.com", "web.facebook.com",
        "x.com", "twitter.com", "api.twitter.com", "mobile.twitter.com",
        "github.com", "gitlab.com", "bitbucket.org",
        "linkedin.com", "discord.com", "id.twitch.tv", "login.yahoo.com",
        "accounts.spotify.com", "auth.openai.com", "paypal.com",
        "signin.aws.amazon.com", "amazon.com"
    )

    /**
     * Odseki poti, ki sami po sebi dovolj jasno govorijo o prijavi. Poleg OAuth tudi
     * `login`, `signin` in `auth`: marsikatera stran odpre svoje prijavno okno na taki
     * poti, oglasna okna pa je ne uporabljajo. Ujeti mora biti cel odsek - `authorize`
     * da, `authorized-dealer` ne.
     */
    private val odsekiOauth = setOf(
        "oauth", "oauth2", "authorize", "login", "signin", "sign-in", "auth", "sso"
    )

    private fun potGovoriOPrijavi(odsek: String): Boolean {
        return odsek.contains("login") || odsek.contains("signin") || odsek.contains("sign-in") ||
            odsek.contains("auth") || odsek == "dialog" || odsek == "connect"
    }

    fun jePrijava(naslov: String?): Boolean {
        val vhod = naslov?.trim().orEmpty()
        if (vhod.isEmpty()) return false
        if (!vhod.startsWith("https://", ignoreCase = true)) return false
        val brezSheme = vhod.substring(8)
        val konecGostitelja = brezSheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val avtoriteta = if (konecGostitelja < 0) brezSheme else brezSheme.substring(0, konecGostitelja)
        val ostanek = if (konecGostitelja < 0) "" else brezSheme.substring(konecGostitelja)
        val gostitelj = avtoriteta.substringAfterLast('@').substringBefore(':').lowercase().trim()
        if (gostitelj.isEmpty()) return false
        val pot = ostanek.substringBefore('?').substringBefore('#').lowercase()
        val odseki = pot.split('/').filter { it.isNotEmpty() }
        if (odseki.isEmpty()) return false
        if (odseki.any { it in odsekiOauth }) return true
        return jeZnanPonudnik(gostitelj) && odseki.any { potGovoriOPrijavi(it) }
    }

    private fun jeZnanPonudnik(gostitelj: String): Boolean {
        val golo = gostitelj.removePrefix("www.")
        if (golo in ponudniki || gostitelj in ponudniki) return true
        if (ponudniki.any { golo.endsWith(".$it") }) return true
        if (golo.endsWith(".auth0.com") || golo.endsWith(".okta.com") ||
            golo.endsWith(".oktapreview.com") || golo.endsWith(".onelogin.com")
        ) return true
        return golo.startsWith("accounts.") || golo.startsWith("login.") ||
            golo.startsWith("signin.") || golo.startsWith("auth.") || golo.startsWith("sso.")
    }
}
