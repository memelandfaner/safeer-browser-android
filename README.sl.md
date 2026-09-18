# Safeer Browser za Android

Mobilni brskalnik, ki postavlja zasebnost na prvo mesto: lokalna zaščita pred zlonamerno kodo,
lažnim predstavljanjem in botnetnimi strežniki, blokada oglasov in sledilcev ter predvajanje v
ozadju — vse odločitve padejo na napravi.

[![Licenca](https://img.shields.io/badge/Licenca-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Platforma](https://img.shields.io/badge/Platforma-Android_9%2B-3ddc84?style=flat-square)](#zahteve)
[![Prenos](https://img.shields.io/badge/Prenos-Izdaje-00e5ff?style=flat-square)](../../releases/latest)

English: [README.md](README.md) · Spletna stran: [safeer.si](https://safeer.si)

> **Safeer je varnostna plast, ne jamstvo.** Zmanjša izpostavljenost in blokira znane grožnje.
> Pred vsakim novim ali neznanim napadom te ne more zaščititi.

---

## Kaj zna

**Ščit pred grožnjami.** Botnetni strežniki C2, gostitelji zlonamerne kode in domene lažnega
predstavljanja iz virov abuse.ch (ThreatFox, URLhaus) in Phishing Army, poleg vgrajene semenske
baze, ujeti lokalno v O(k) prek obrnjenega domenskega drevesa. Posodobljeni seznami se zgradijo
v ozadju in atomsko zamenjajo, zato staro drevo nikoli ne ostane v rabi. Pri prenosu se preveri
strukturne označbe izdajatelja, smiselna velikost in najmanjše število pravil, vsota SHA-256
vsakega sprejetega vira pa se zapiše v krajevni revizijski dnevnik. Podpisani paketi morajo
prestati podpis Ed25519, sicer se ne uporabijo.

**Brez obvoza tam, kjer je nevarno.** Izjeme za video in vgradnjo nikoli ne veljajo za domene
C2 ali zlonamerne kode. Če se kljub opozorilu odločiš nadaljevati, je ta obvoz enkraten žeton
UUID, vezan na tisto eno domeno in pet minut — spletna stran ga ne more ponarediti.

**Blokada oglasov in sledilcev.** Filter, združljiv z EasyList, in ujemanje blokiranih domen, s
kozmetičnim filtrom za tisto, kar ostane.

**BankGuard.** Prave bančne in plačilne strani so izvzete iz kozmetičnega filtriranja in
vbrizgavanja skript, zato brskalnik nikoli ne more biti razlog, da plačilo ne uspe.

**Odstranjeni sledilni parametri.** `utm_*`, `fbclid`, `gclid`, `msclkid`, `twclid`, `ttclid`,
`yclid`, `mc_eid`, `gad_source`, `gbraid`, `wbraid`, `dclid`, `igshid` in podobni gredo ven iz
povezav, ki jih odpreš. Prijavni parametri (`code`, `state`, `token`, `redirect_uri`, …),
plačilni parametri, iskalne poizvedbe in parametri predvajalnikov so izrecno zaščiteni, zato
prijave in nakupi delujejo naprej.

**Global Privacy Control in Do Not Track.** `Sec-GPC: 1` in `DNT: 1` v zahtevah ter ustrezni
lastnosti v JavaScriptu strani.

**Šifriran DNS.** DNS prek HTTPS z delujočim HTTP/2 in brez tihega preklopa na navadni DNS ob
napaki.

**Stroge privzete nastavitve.** Mešane vsebine na straneh HTTPS niso dovoljene. Dostop do sheme
`content://` iz spletne vsebine je izklopljen. Kamera, mikrofon, lokacija in zaščiteni mediji
niso nikoli odobreni samodejno — vprašani ste, z izpisanim izvorom, zaprto okno pa pomeni
zavrnitev.

**Prijavna okna, ki delujejo.** Pojavno okno se odpre samo ob pravem uporabnikovem dejanju, kar
ustavi popunderje; ko je njegov cilj naslov OAuth ali prijave, postane pravi zavihek in obdrži
`window.opener`, zato prijave z Googlom, Facebookom, X in bankami stečejo do konca.

**Zavihki, ki ne jedo baterije.** Nedejavni zavihki zaspijo in njihovi pogledi se sprostijo;
odprti zavihki se vrnejo po ponovnem zagonu.

**Predvajanje v ozadju** za glasbo in podkaste ter **SponsorBlock** za YouTube.

## Brez receptov za posamezne strani

Safeer ne vsebuje prilagoditve, napisane za eno imenovano spletno stran. Vse našteto deluje po
tem, KAJ stran je, in ne po tem, kdo jo objavlja.

## Namestitev

Prenesi APK iz [Izdaj](../../releases/latest) in ga odpri na telefonu; Android bo vprašal za
dovoljenje za namestitev iz tega vira. Če želiš, ga prej preveri:

```bash
sha256sum -c --ignore-missing SHA256SUMS
```

## Zahteve

Android 9 (API 28) ali novejši. Grajeno proti API 36.

## Gradnja iz izvorne kode

```bash
./build_mobile_apk.sh
```

Za podpis izdaje se uporabi ključ, ki ga v tem repozitoriju ni; brez njega zgradi
razhroščevalno različico. Testi:

```bash
bash tests/run_tests.sh
```

## Vzemi in predelaj

Kdor obvladuje brskalnik, določa pravila spleta. Projekt je pod Apache-2.0 prav zato, da ga
lahko vzameš, zamenjaš sezname, spremeniš videz, dodaš, kar potrebuješ, in izdaš svojega. To ni
opomba pod črto — to je bistvo.

## Sodelovanje

Glej [CONTRIBUTING.md](CONTRIBUTING.md). Varnostne težave gredo po poti iz
[SECURITY.md](SECURITY.md), zasebno.

## Licenca

Apache License 2.0 — glej [LICENSE](LICENSE).
