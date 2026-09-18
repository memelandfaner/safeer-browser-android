package com.safeer.mobile.browser

/**
 * Pojavno okno sme skozi samo, kadar res pelje na prijavo. Preizkus pazi na oboje:
 * da prijava z Google, Facebook, X in lastnimi prijavnimi strezniki deluje, in da
 * oglasno okno ne najde poti mimo.
 */

private var padli = 0
private var presteti = 0

private fun preveri(naslov: String?, pricakovano: Boolean, opis: String) {
    presteti++
    val dobljeno = PrijavnaOkna.jePrijava(naslov)
    if (dobljeno != pricakovano) {
        padli++
        println("FAIL $opis: $naslov -> $dobljeno (pricakovano $pricakovano)")
    }
}

fun main() {
    // --- prijava mora delovati ---
    preveri("https://accounts.google.com/o/oauth2/v2/auth?client_id=x", true, "Google OAuth")
    preveri("https://accounts.google.com/signin/oauth/consent", true, "Google privolitev")
    preveri("https://accounts.google.com/ServiceLogin?service=mail", true, "Google ServiceLogin")
    preveri("https://www.facebook.com/v19.0/dialog/oauth?client_id=x", true, "Facebook dialog")
    preveri("https://m.facebook.com/login.php?next=x", true, "Facebook mobilna prijava")
    preveri("https://x.com/i/oauth2/authorize?client_id=x", true, "X OAuth2")
    preveri("https://twitter.com/i/oauth2/authorize?client_id=x", true, "Twitter OAuth2")
    preveri("https://appleid.apple.com/auth/authorize?client_id=x", true, "Apple")
    preveri("https://login.microsoftonline.com/common/oauth2/v2.0/authorize", true, "Microsoft")
    preveri("https://github.com/login/oauth/authorize?client_id=x", true, "GitHub")
    preveri("https://accounts.spotify.com/authorize?client_id=x", true, "Spotify")
    preveri("https://id.twitch.tv/oauth2/authorize?client_id=x", true, "Twitch")
    preveri("https://mojepodjetje.si/oauth2/authorize?client_id=x", true, "lasten OAuth streznik")
    preveri("https://prijava.example.org/authorize?x=1", true, "lasten authorize")
    preveri("https://sso.univerza.si/login?service=x", true, "sso. prijava")
    preveri("https://dev-1234.okta.com/oauth2/v1/authorize", true, "Okta")

    // --- oglasi in vse drugo ne smejo skozi ---
    preveri("https://example.com/oglas.html", false, "navaden oglas")
    preveri("https://neznanastran.si/popunder.php?id=1", false, "popunder")
    preveri("https://www.24ur.com/novice", false, "navadna stran")
    preveri("https://example.com/", false, "gola domena")
    preveri("https://example.com", false, "domena brez poti")
    preveri("http://accounts.google.com/o/oauth2/v2/auth", false, "http, ne https")
    preveri("https://oglasi.example.com/banner/authorized-dealer.jpg", false, "beseda authorized ni odsek")
    preveri("https://example.com/authorize-dealer", false, "authorize- ni odsek authorize")
    preveri("https://example.com/prijava", false, "slovenska pot ni v pravilu")
    preveri("https://oglasi.example.com/go?to=x", false, "preusmeritveni oglas")
    preveri("https://example.com/out/12345", false, "oglasni izhod")
    preveri("about:blank", false, "prazna stran")
    preveri("", false, "prazen naslov")
    preveri(null, false, "brez naslova")
    preveri("javascript:alert(1)", false, "javascript")
    preveri("https://", false, "samo shema")
    // Lastno prijavno okno strani: to mora delovati, sicer prijava na pol spleta ne dela.
    preveri("https://example.com/login", true, "lastno prijavno okno strani")
    preveri("https://trgovina.si/auth/callback", true, "lastni auth odsek")

    println("PrijavnaOkna: ${presteti - padli} passed, $padli failed")
    if (padli > 0) throw IllegalStateException("$padli preizkusov ni uspelo")
}
