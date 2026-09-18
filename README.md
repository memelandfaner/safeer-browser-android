# Safeer Browser for Android

A privacy-first mobile browser with local malware, phishing and C2 protection, ad and tracker
blocking, and background playback — all decided on the device.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_9%2B-3ddc84?style=flat-square)](#requirements)
[![Downloads](https://img.shields.io/badge/Download-Releases-00e5ff?style=flat-square)](../../releases/latest)

Slovenian: [README.sl.md](README.sl.md) · Website: [safeer.si](https://safeer.si)

> **Safeer is a security layer, not a guarantee.** It reduces exposure and blocks known
> threats. It cannot protect against every new or unknown attack.

---

## What it does

**Threat shield.** Botnet C2 servers, malware distribution hosts and phishing domains from
abuse.ch (ThreatFox, URLhaus) and Phishing Army, plus a built-in seed list, matched locally in
O(k) against a reverse-domain trie. Updated lists are built in the background and swapped in
atomically, so a stale tree never lingers. Downloads are checked for the publisher's structural
markers, a sane size and a minimum rule count before they are trusted, and the SHA-256 of each
accepted feed is written to a local audit log. Signed bundles must pass their Ed25519 signature
or they are not used at all.

**No bypass for the dangerous cases.** Video and embed exceptions never apply to C2 or malware
domains. If you choose to continue past a threat warning anyway, that override is a one-time
UUID token bound to that one domain for five minutes — a web page cannot forge it.

**Ad and tracker blocking.** An EasyList-compatible engine plus blocked-domain matching, with
cosmetic filtering for what survives.

**BankGuard.** Real banking and payment sites are left alone by cosmetic filters and script
injection, so the browser can never be the reason a payment fails.

**Tracking parameters stripped.** `utm_*`, `fbclid`, `gclid`, `msclkid`, `twclid`, `ttclid`,
`yclid`, `mc_eid`, `gad_source`, `gbraid`, `wbraid`, `dclid`, `igshid` and friends are removed
from links you open. Authentication parameters (`code`, `state`, `token`, `redirect_uri`, …),
payment parameters, search queries and media parameters are explicitly protected, so sign-ins
and checkouts keep working.

**Global Privacy Control and Do Not Track.** `Sec-GPC: 1` and `DNT: 1` on requests, and the
matching JavaScript properties in the page.

**Encrypted DNS.** DNS-over-HTTPS with HTTP/2, without a silent fallback to plaintext DNS when
it fails.

**Strict defaults.** Mixed content is never allowed on HTTPS pages. `content://` access from web
content is off. Camera, microphone, location and protected media are never granted
automatically — you are asked, with the origin shown, and a dismissed dialog is a denial.

**Sign-in windows that work.** A popup is opened only on a real user gesture, which stops
popunders; when its destination is an OAuth or sign-in URL, it becomes a real tab and keeps its
`window.opener`, so Google, Facebook, X and bank logins complete normally.

**Tabs that do not eat the battery.** Inactive tabs sleep and their views are released; open
tabs come back after a restart.

**Background playback** for music and podcasts, and **SponsorBlock** for YouTube.

## No per-site recipes

Safeer contains no adaptation written for one named website. Everything above works by what a
page *is*, not by who publishes it.

## Install

Download the APK from [Releases](../../releases/latest) and open it on the phone; Android will
ask you to allow installation from this source. Verify it first if you like:

```bash
sha256sum -c --ignore-missing SHA256SUMS
```

## Requirements

Android 9 (API 28) or newer. Built against API 36.

## Build from source

```bash
./build_mobile_apk.sh
```

Release signing uses a keystore that is not in this repository; without it, build the debug
variant. Tests:

```bash
bash tests/run_tests.sh
```

## Fork it

Whoever controls the browser sets the rules of the web. This project is Apache-2.0 so that you
can take it, change the block lists, change the look, add what you need, and ship your own.
That is not a footnote — it is the point.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues go through [SECURITY.md](SECURITY.md),
privately.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
