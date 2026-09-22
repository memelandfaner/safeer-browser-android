package com.safeer.mobile.browser.cast

// Preneseno iz brskalnika za televizor (si.safeer.tv.cast) brez sprememb v logiki:
// gostitelj Safeer Linka mora biti enak na vseh napravah, sicer se protokol razide.
// Ce se tu kaj spremeni, mora ista sprememba v tv-browser-2 (vir); kopijo naredi tools/link-core-sync.sh.

import java.security.SecureRandom
import java.util.UUID
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.IDENTITETA_HUBA
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.IZZIV_VELJA_MS
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.NACIN_SPAKE2
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.NAJVEC_BESEDILA
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.NAJVEC_IMENA
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.NAJVEC_VSTOPNIC
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.PIN_VELJA_MS
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.RAZLICICA_PROTOKOLA
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.VSTOPNICA_VELJA_MS
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.ZMOZNOST_SYNC
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.bajteVHex
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Companion.hexVBajte
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Pridruzitev
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.Prijava
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.QrPodatki
import com.safeer.mobile.browser.cast.HubUsmerjevalnik.SeznanjenaNaprava

/*
 * HTTP koncne tocke Safeer Huba po skupinah (seznanitev, deljenje, naprave, zaupanje, prijava, stanje, QR).
 *
 * Izloceno iz HubUsmerjevalnik.kt (v8), ki je imel cez 2000 vrstic. Logika je enaka; to so razsiritve
 * usmerjevalnika, ki delijo njegovo stanje in kljucavnico. Vstopna tocka ostane HubUsmerjevalnik.odgovori().
 * Del Link Core: kopijo na telefonu naredi tools/link-core-sync.sh.
 */

/** Seznanitev naprave: koda (SPAKE2), preklic, sorodna naprava, QR. */
internal fun HubUsmerjevalnik.odgovoriSeznanitev(zahteva: HubStreznik.Zahteva, pot: String, krajevni: Boolean): HubStreznik.Odgovor? {
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
    return null
}

/** Deljenje zaslona in besedila. */
internal fun HubUsmerjevalnik.odgovoriDeljenje(zahteva: HubStreznik.Zahteva, pot: String, krajevni: Boolean): HubStreznik.Odgovor? {
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
        if (register.povezavaOd(cilj) == null) {
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

    if (pot == "/cast/share/screen/stop" && zahteva.metoda == "POST") {
        if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
            return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
        }
        val id = (JsonLahki.objekt(zahteva.telo)?.niz("id") ?: "").trim()
        // koncajZaslon poklice nazaj zaslonKoncan, ki obvesti cilj.
        tokovi?.koncajZaslon(id)
        return HubStreznik.Odgovor(200, JsonLahki.Zapis().logicno("stopped", true).toString())
    }
    return null
}

/** Seznam naprav, preimenovanje in odhod z Huba. */
internal fun HubUsmerjevalnik.odgovoriNaprave(zahteva: HubStreznik.Zahteva, pot: String, krajevni: Boolean): HubStreznik.Odgovor? {
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

    if (pot == "/cast/devices" && zahteva.metoda == "GET") {
        if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
            return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
        }
        return HubStreznik.Odgovor(200, povezaniPrejemniki())
    }
    return null
}

/** Krog zaupanja: vpis kljuca, vzdevek, branje kroga. */
internal fun HubUsmerjevalnik.odgovoriZaupanje(zahteva: HubStreznik.Zahteva, pot: String, krajevni: Boolean): HubStreznik.Odgovor? {
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
        val ime = clan.ime.ifBlank { telo?.niz("name")?.takeIf { it.isNotBlank() } ?: alias }.take(NAJVEC_IMENA)
        krog.dodaj(KrogZaupanja.Clan(alias, clan.kljuc, ime, telo?.nizAli("platform")?.ifBlank { clan.platforma } ?: clan.platforma, KrogZaupanja.zdaj(), deviceId))
        return HubStreznik.Odgovor(200, JsonLahki.Zapis().niz("device_id", alias).surovo("ring", krog.json()).toString())
    }

    if (pot == "/cast/trust/ring" && zahteva.metoda == "GET") {
        if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
            return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
        }
        return HubStreznik.Odgovor(200, krog.json())
    }
    return null
}

/** Prijava s podpisom in vstopnice. */
internal fun HubUsmerjevalnik.odgovoriPrijava(zahteva: HubStreznik.Zahteva, pot: String, krajevni: Boolean): HubStreznik.Odgovor? {
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
            val ime = clan.ime.ifBlank { telo?.niz("name").orEmpty() }.take(NAJVEC_IMENA)
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
    return null
}

/** Stanje Huba za diagnostiko. */
internal fun HubUsmerjevalnik.odgovoriStanje(zahteva: HubStreznik.Zahteva, pot: String, krajevni: Boolean): HubStreznik.Odgovor? {
    if (pot == "/cast/health" && zahteva.metoda == "GET") {
        if (!krajevni || !jeVeljavenZeton(zahteva.glave["x-safeer-token"])) {
            return HubStreznik.Odgovor(401, napakaJson("Naprava ni seznanjena.", "naprava_ni_seznanjena"))
        }
        val stanje = synchronized(kljucnica) {
            JsonLahki.Zapis()
                .niz("status", "ok")
                .niz("protocol", RAZLICICA_PROTOKOLA)
                .stevilo("receivers", register.stevilo { it.vloga == "receiver" && it.povezava != null }.toDouble())
                .stevilo("senders", register.steviloPosiljateljev().toDouble())
                .stevilo("sync_peers", register.stevilo {
                    it.povezava != null && (it.zmoznosti.contains(ZMOZNOST_SYNC) || it.vloga == "sync-client")
                }.toDouble())
                // Samo imena kategorij, nikoli vsebina.
                .seznamNizov("sync_categories", sinhronizacija.keys.sorted())
                .toString()
        }
        return HubStreznik.Odgovor(200, stanje)
    }
    return null
}


/** Prijava s QR kodo po HTTP (glej [zacniQr]); klicatelj je ze preveril, da je zahteva krajevna. */
internal fun HubUsmerjevalnik.odgovorQr(pot: String, zahteva: HubStreznik.Zahteva): HubStreznik.Odgovor {
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
