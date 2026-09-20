/* Safeer Link – spletni odjemalec. Telefon (ali katera koli naprava) brez Safeerja: navaden brskalnik
   v domacem omrezju. Govori isti protokol kot aplikacije (pridruzitev s kodo, vstopnica, WebSocket,
   cast.url / share.text / control.command); streznik je sredisce Safeer Linka samo (goli HTTP na
   svojih vratih, samo krajevno omrezje). Nic ne gre v oblak. */
(function () {
  "use strict";

  var B = {
    sl: { podnaslov: "Brez namestitve, samo domače omrežje", povezujem: "Povezujem …", povezano: "Povezano", niPovezave: "Ni povezave",
      pridruziNaslov: "Poveži to napravo", pridruziOpis: "Koda s zaslona je tu. Vpiši ime, po katerem bodo druge naprave prepoznale to napravo, in potrdi.",
      ime: "Ime naprave", privzetoIme: "Telefon (splet)", pridruzi: "Poveži", odpriSafeer: "Imaš Safeer Browser? Odpri v Safeer",
      brezKodeNaslov: "Ni kode za povezavo", brezKodeOpis: "Na televizorju ali računalniku odpri Safeer OS → Naprave → Poveži novo napravo in poskeniraj kodo s kamero telefona.",
      naprave: "Naprave", niNaprav: "Trenutno ni nobene druge naprave.", izberi: "Dotakni se naprave, ki jo želiš upravljati.",
      dejanja: "Dejanja", daljinec: "Daljinec", poslji: "Pošlji", nazaj: "Nazaj", domov: "Domov", meni: "Meni",
      url: "Povezava (odpre se na napravi)", posljiUrl: "Odpri na napravi", besedilo: "Besedilo", posljiBesedilo: "Pošlji besedilo",
      prejeto: "Prejeto", pozabi: "Odklopi to napravo", poslano: "Poslano.", napaka: "Ni uspelo: ", brezDaljinca: "Te naprave ni mogoče upravljati.",
      kodaPotekla: "Koda je potekla ali ni prava. Na zaslonu se pokaže nova.", drobno: "Podatki ostanejo v domačem omrežju. Povezava je brez šifriranja (samo za daljinec, povezave in besedila); za datoteke in zaslon uporabi Safeer Browser.",
      preimenuj: "Preimenuj", shraniIme: "Shrani", vnesiIme: "Ime naprave …", preimenovano: "Ime je shranjeno – vidijo ga vse naprave.", napPreimenovanje: "Imena ni bilo mogoče shraniti.", taNaprava: "Ta naprava",
      vloga: { receiver: "zaslon", sender: "naprava", hub: "središče" } },
    en: { podnaslov: "No install, home network only", povezujem: "Connecting …", povezano: "Connected", niPovezave: "Not connected",
      pridruziNaslov: "Connect this device", pridruziOpis: "The code from the screen is here. Enter a name other devices will see and confirm.",
      ime: "Device name", privzetoIme: "Phone (web)", pridruzi: "Connect", odpriSafeer: "Have Safeer Browser? Open in Safeer",
      brezKodeNaslov: "No pairing code", brezKodeOpis: "On the TV or computer open Safeer OS → Devices → Connect a new device and scan the code with your phone camera.",
      naprave: "Devices", niNaprav: "No other device right now.", izberi: "Tap the device you want to control.",
      dejanja: "Actions", daljinec: "Remote", poslji: "Send", nazaj: "Back", domov: "Home", meni: "Menu",
      url: "Link (opens on the device)", posljiUrl: "Open on device", besedilo: "Text", posljiBesedilo: "Send text",
      prejeto: "Received", pozabi: "Disconnect this device", poslano: "Sent.", napaka: "Failed: ", brezDaljinca: "This device cannot be controlled.",
      kodaPotekla: "The code expired or is wrong. A new one appears on the screen.", drobno: "Data stays in your home network. This connection is unencrypted (remote, links and text only); use Safeer Browser for files and screen.",
      preimenuj: "Rename", shraniIme: "Save", vnesiIme: "Device name …", preimenovano: "Name saved – all devices see it.", napPreimenovanje: "The name could not be saved.", taNaprava: "This device",
      vloga: { receiver: "screen", sender: "device", hub: "hub" } },
    de: { podnaslov: "Ohne Installation, nur Heimnetz", povezujem: "Verbinde …", povezano: "Verbunden", niPovezave: "Nicht verbunden",
      pridruziNaslov: "Dieses Gerät verbinden", pridruziOpis: "Der Code vom Bildschirm ist da. Gib einen Namen ein, den andere Geräte sehen, und bestätige.",
      ime: "Gerätename", privzetoIme: "Handy (Web)", pridruzi: "Verbinden", odpriSafeer: "Safeer Browser installiert? In Safeer öffnen",
      brezKodeNaslov: "Kein Kopplungscode", brezKodeOpis: "Öffne am Fernseher oder Computer Safeer OS → Geräte → Neues Gerät verbinden und scanne den Code mit der Handykamera.",
      naprave: "Geräte", niNaprav: "Gerade kein anderes Gerät.", izberi: "Tippe auf das Gerät, das du steuern willst.",
      dejanja: "Aktionen", daljinec: "Fernbedienung", poslji: "Senden", nazaj: "Zurück", domov: "Start", meni: "Menü",
      url: "Link (öffnet sich auf dem Gerät)", posljiUrl: "Auf Gerät öffnen", besedilo: "Text", posljiBesedilo: "Text senden",
      prejeto: "Empfangen", pozabi: "Dieses Gerät trennen", poslano: "Gesendet.", napaka: "Fehlgeschlagen: ", brezDaljinca: "Dieses Gerät lässt sich nicht steuern.",
      kodaPotekla: "Der Code ist abgelaufen oder falsch. Auf dem Bildschirm erscheint ein neuer.", drobno: "Daten bleiben im Heimnetz. Diese Verbindung ist unverschlüsselt (nur Fernbedienung, Links und Text); für Dateien und Bildschirm nutze Safeer Browser.",
      preimenuj: "Umbenennen", shraniIme: "Speichern", vnesiIme: "Gerätename …", preimenovano: "Name gespeichert – alle Geräte sehen ihn.", napPreimenovanje: "Der Name konnte nicht gespeichert werden.", taNaprava: "Dieses Gerät",
      vloga: { receiver: "Bildschirm", sender: "Gerät", hub: "Zentrale" } },
    es: { podnaslov: "Sin instalar, solo red doméstica", povezujem: "Conectando…", povezano: "Conectado", niPovezave: "Sin conexión",
      pridruziNaslov: "Conectar este dispositivo", pridruziOpis: "El código de la pantalla está aquí. Escribe un nombre que verán los demás dispositivos y confirma.",
      ime: "Nombre del dispositivo", privzetoIme: "Móvil (web)", pridruzi: "Conectar", odpriSafeer: "¿Tienes Safeer Browser? Abrir en Safeer",
      brezKodeNaslov: "No hay código", brezKodeOpis: "En el televisor o el ordenador abre Safeer OS → Dispositivos → Conectar nuevo dispositivo y escanea el código con la cámara del móvil.",
      naprave: "Dispositivos", niNaprav: "Ahora mismo no hay otro dispositivo.", izberi: "Toca el dispositivo que quieres controlar.",
      dejanja: "Acciones", daljinec: "Mando", poslji: "Enviar", nazaj: "Atrás", domov: "Inicio", meni: "Menú",
      url: "Enlace (se abre en el dispositivo)", posljiUrl: "Abrir en el dispositivo", besedilo: "Texto", posljiBesedilo: "Enviar texto",
      prejeto: "Recibido", pozabi: "Desconectar este dispositivo", poslano: "Enviado.", napaka: "No se pudo: ", brezDaljinca: "Este dispositivo no se puede controlar.",
      kodaPotekla: "El código caducó o no es correcto. En la pantalla aparece uno nuevo.", drobno: "Los datos se quedan en la red doméstica. Esta conexión no está cifrada (solo mando, enlaces y texto); para archivos y pantalla usa Safeer Browser.",
      preimenuj: "Renombrar", shraniIme: "Guardar", vnesiIme: "Nombre del dispositivo…", preimenovano: "Nombre guardado: lo ven todos los dispositivos.", napPreimenovanje: "No se pudo guardar el nombre.", taNaprava: "Este dispositivo",
      vloga: { receiver: "pantalla", sender: "dispositivo", hub: "central" } },
    fr: { podnaslov: "Sans installation, réseau domestique seulement", povezujem: "Connexion…", povezano: "Connecté", niPovezave: "Non connecté",
      pridruziNaslov: "Connecter cet appareil", pridruziOpis: "Le code de l'écran est là. Saisis un nom que verront les autres appareils et confirme.",
      ime: "Nom de l'appareil", privzetoIme: "Téléphone (web)", pridruzi: "Connecter", odpriSafeer: "Tu as Safeer Browser ? Ouvrir dans Safeer",
      brezKodeNaslov: "Pas de code", brezKodeOpis: "Sur le téléviseur ou l'ordinateur, ouvre Safeer OS → Appareils → Connecter un nouvel appareil et scanne le code avec l'appareil photo du téléphone.",
      naprave: "Appareils", niNaprav: "Aucun autre appareil pour le moment.", izberi: "Touche l'appareil à contrôler.",
      dejanja: "Actions", daljinec: "Télécommande", poslji: "Envoyer", nazaj: "Retour", domov: "Accueil", meni: "Menu",
      url: "Lien (s'ouvre sur l'appareil)", posljiUrl: "Ouvrir sur l'appareil", besedilo: "Texte", posljiBesedilo: "Envoyer le texte",
      prejeto: "Reçu", pozabi: "Déconnecter cet appareil", poslano: "Envoyé.", napaka: "Échec : ", brezDaljinca: "Cet appareil ne peut pas être contrôlé.",
      kodaPotekla: "Le code a expiré ou est incorrect. Un nouveau s'affiche à l'écran.", drobno: "Les données restent dans le réseau domestique. Cette connexion n'est pas chiffrée (télécommande, liens et texte seulement) ; pour les fichiers et l'écran, utilise Safeer Browser.",
      preimenuj: "Renommer", shraniIme: "Enregistrer", vnesiIme: "Nom de l'appareil…", preimenovano: "Nom enregistré : tous les appareils le voient.", napPreimenovanje: "Le nom n'a pas pu être enregistré.", taNaprava: "Cet appareil",
      vloga: { receiver: "écran", sender: "appareil", hub: "centre" } },
    it: { podnaslov: "Senza installazione, solo rete di casa", povezujem: "Connessione…", povezano: "Connesso", niPovezave: "Non connesso",
      pridruziNaslov: "Collega questo dispositivo", pridruziOpis: "Il codice dello schermo è qui. Inserisci un nome che gli altri dispositivi vedranno e conferma.",
      ime: "Nome del dispositivo", privzetoIme: "Telefono (web)", pridruzi: "Collega", odpriSafeer: "Hai Safeer Browser? Apri in Safeer",
      brezKodeNaslov: "Nessun codice", brezKodeOpis: "Sul televisore o sul computer apri Safeer OS → Dispositivi → Collega nuovo dispositivo e inquadra il codice con la fotocamera del telefono.",
      naprave: "Dispositivi", niNaprav: "Al momento nessun altro dispositivo.", izberi: "Tocca il dispositivo da controllare.",
      dejanja: "Azioni", daljinec: "Telecomando", poslji: "Invia", nazaj: "Indietro", domov: "Home", meni: "Menu",
      url: "Link (si apre sul dispositivo)", posljiUrl: "Apri sul dispositivo", besedilo: "Testo", posljiBesedilo: "Invia testo",
      prejeto: "Ricevuto", pozabi: "Scollega questo dispositivo", poslano: "Inviato.", napaka: "Non riuscito: ", brezDaljinca: "Questo dispositivo non può essere controllato.",
      kodaPotekla: "Il codice è scaduto o non è giusto. Sullo schermo ne compare uno nuovo.", drobno: "I dati restano nella rete di casa. Questa connessione non è cifrata (solo telecomando, link e testo); per file e schermo usa Safeer Browser.",
      preimenuj: "Rinomina", shraniIme: "Salva", vnesiIme: "Nome del dispositivo…", preimenovano: "Nome salvato: lo vedono tutti i dispositivi.", napPreimenovanje: "Impossibile salvare il nome.", taNaprava: "Questo dispositivo",
      vloga: { receiver: "schermo", sender: "dispositivo", hub: "centro" } }
  };
  var jezik = (navigator.language || "en").slice(0, 2).toLowerCase();
  var T = B[jezik] || B.en;
  function t(k) { return T[k] || B.en[k] || k; }
  function $(id) { return document.getElementById(id); }
  function pokazi(id, da) { $(id).hidden = !da; }
  function ubezi(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function nakljucno(n) { var a = new Uint8Array(n); crypto.getRandomValues(a); return Array.prototype.map.call(a, function (b) { return ("0" + b.toString(16)).slice(-2); }).join(""); }

  // Besedila strani
  document.documentElement.lang = jezik;
  [["podnaslov", "podnaslov"], ["pridruziNaslov", "pridruziNaslov"], ["pridruziOpis", "pridruziOpis"], ["imeNapis", "ime"], ["gumbPridruzi", "pridruzi"],
   ["gumbOdpriSafeer", "odpriSafeer"], ["brezKodeNaslov", "brezKodeNaslov"], ["brezKodeOpis", "brezKodeOpis"], ["napraveNaslov", "naprave"],
   ["dejanjaNaslov", "dejanja"], ["zavDaljinec", "daljinec"], ["zavPoslji", "poslji"], ["tBack", "nazaj"], ["tHome", "domov"], ["tMenu", "meni"],
   ["urlNapis", "url"], ["gumbPosljiUrl", "posljiUrl"], ["besediloNapis", "besedilo"], ["gumbPosljiBesedilo", "posljiBesedilo"],
   ["prejetoNaslov", "prejeto"], ["gumbPozabi", "pozabi"], ["drobno", "drobno"]].forEach(function (p) { $(p[0]).textContent = t(p[1]); });

  var osnova = location.protocol + "//" + location.host;
  var ws = null, naprave = [], izbrana = null, cakajoci = {}, prejeto = [];
  var seja = null;
  try { seja = JSON.parse(localStorage.getItem("safeerLinkSplet") || "null"); } catch (e) { seja = null; }

  function stanje(barva, besedilo) { $("pika").className = "pika " + barva; $("stanjeBesedilo").textContent = besedilo; }
  function opomba(id, besedilo, napaka) { var o = $(id); o.textContent = besedilo || ""; o.className = "opomba" + (napaka ? " napaka" : ""); }

  function parametri() {
    var h = (location.hash || "").replace(/^#/, ""), p = {};
    h.split("&").forEach(function (d) { var i = d.indexOf("="); if (i > 0) p[d.slice(0, i)] = decodeURIComponent(d.slice(i + 1)); });
    return p;
  }

  function zahteva(pot, telo, zeton) {
    var g = { "Content-Type": "application/json" };
    if (zeton) g["X-Safeer-Token"] = zeton;
    return fetch(osnova + pot, { method: telo === undefined ? "GET" : "POST", headers: g, body: telo === undefined ? undefined : JSON.stringify(telo) })
      .then(function (r) { return r.text().then(function (b) { var j = {}; try { j = JSON.parse(b); } catch (e) {} j._koda = r.status; return j; }); });
  }

  // ---- pridruzitev s kodo iz QR (j = id, s = skrivnost)
  function pridruzi(p) {
    var ime = ($("ime").value || t("privzetoIme")).trim().slice(0, 40);
    var id = (seja && seja.device_id) || ("web-" + nakljucno(8));
    opomba("opombaPridruzi", t("povezujem"));
    zahteva("/cast/pair/qr/join", { qr_id: p.j, secret: p.s, device_id: id, name: ime }).then(function (r) {
      if (!r.token) { opomba("opombaPridruzi", r.code === "prevec_naprav" ? (r.detail || t("napaka")) : t("kodaPotekla"), true); return; }
      seja = { token: r.token, device_id: id, name: ime, hub: osnova };
      try { localStorage.setItem("safeerLinkSplet", JSON.stringify(seja)); } catch (e) {}
      try { history.replaceState(null, "", location.pathname); } catch (e) {}
      povezi();
    }, function () { opomba("opombaPridruzi", t("niPovezave"), true); });
  }

  // ---- povezava: vstopnica -> WebSocket -> cast.register (isti protokol kot aplikacije)
  function povezi() {
    pokazi("zPridruzi", false); pokazi("zBrezKode", false); pokazi("zNaprave", true);
    stanje("rumena", t("povezujem"));
    zahteva("/cast/ticket", {}, seja.token).then(function (r) {
      if (!r.ticket) { if (r._koda === 401) { pozabi(); return; } stanje("rdeca", t("niPovezave")); setTimeout(povezi, 5000); return; }
      var s = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/cast/ws?ticket=" + encodeURIComponent(r.ticket));
      ws = s;
      s.onopen = function () {
        s.send(JSON.stringify({ id: nakljucno(8), type: "cast.register", payload: {
          device_id: seja.device_id, name: seja.name, role: "sender", capabilities: [], protocol: "1", platform: "web", kind: "handheld" } }));
        stanje("zelena", t("povezano"));
      };
      s.onmessage = function (e) { var m; try { m = JSON.parse(e.data); } catch (x) { return; } sporocilo(m); };
      s.onclose = function () { if (ws === s) { ws = null; stanje("rdeca", t("niPovezave")); setTimeout(povezi, 4000); } };
      s.onerror = function () { try { s.close(); } catch (e) {} };
    }, function () { stanje("rdeca", t("niPovezave")); setTimeout(povezi, 5000); });
  }

  function poslji(m) { if (!ws || ws.readyState !== 1) return false; ws.send(JSON.stringify(m)); return true; }

  function sporocilo(m) {
    if (m.type === "cast.ping") { poslji({ id: m.id, type: "cast.pong" }); return; }
    if (m.type === "cast.devices") {
      (m.devices || []).forEach(function (d) { if (d.id === seja.device_id && d.name) mojeIme = d.name; });
      naprave = (m.devices || []).filter(function (d) { return d.id !== seja.device_id; });
      if (izbrana && !naprave.some(function (d) { return d.id === izbrana; })) izbrana = null;
      narisiNaprave(); return;
    }
    if (m.type === "control.result") {
      var p = m.payload || {};
      opomba("opombaDejanj", p.ok ? (p.message || "") : t("napaka") + (p.message || ""), !p.ok);
      if (cakajoci[m.ref_id]) { clearTimeout(cakajoci[m.ref_id]); delete cakajoci[m.ref_id]; }
      return;
    }
    if (m.type === "control.ack" || m.type === "cast.ack" || m.type === "share.ack") {
      if (m.status && m.status !== "accepted") opomba("opombaDejanj", t("napaka") + (m.message || m.status), true);
      else if (m.type !== "control.ack") opomba("opombaDejanj", t("poslano"));
      return;
    }
    if (m.type === "share.text" || m.type === "cast.url") {
      var pl = m.payload || {};
      prejeto.unshift({ od: m.sender_name || m.sender || "", besedilo: pl.text || pl.url || "", url: pl.url || "" });
      prejeto = prejeto.slice(0, 20);
      poslji({ id: nakljucno(8), type: m.type === "cast.url" ? "cast.ack" : "share.ack", ref_id: m.id || "", status: "accepted" });
      narisiPrejeto(); return;
    }
  }

  function ikona(d) { return d.platform === "tv" ? "📺" : d.platform === "tablet" ? "📱" : d.platform === "phone" ? "📱" : d.platform === "web" ? "🌐" : "💻"; }

  // Preimenovanje: ime hrani sredisce, zato ga vidijo vse naprave (tudi ime te naprave).
  var preimenujem = null, mojeIme = "";
  function vrsticaImena(li, d, jaz) {
    if (preimenujem !== d.id) {
      var g = document.createElement("button");
      g.className = "drobniGumb pero"; g.textContent = "✎"; g.title = t("preimenuj"); g.setAttribute("aria-label", t("preimenuj"));
      g.addEventListener("click", function (e) { e.stopPropagation(); preimenujem = d.id; narisiNaprave(); });
      li.appendChild(g);
      return;
    }
    var okvir = document.createElement("div"); okvir.className = "preimenuj";
    var vnos = document.createElement("input"); vnos.type = "text"; vnos.maxLength = 64; vnos.placeholder = t("vnesiIme"); vnos.value = d.name || "";
    var shrani = document.createElement("button"); shrani.className = "glavni tanek"; shrani.textContent = t("shraniIme");
    function posljiIme() {
      zahteva("/cast/devices/rename", { device_id: d.id, name: vnos.value }, seja.token).then(function (r) {
        preimenujem = null;
        if (r._koda === 200) { if (jaz) { mojeIme = r.name || ""; } opomba("opombaNaprav", t("preimenovano")); }
        else opomba("opombaNaprav", t("napPreimenovanje"), true);
        if (jaz) d.name = r.name || d.name;
        narisiNaprave(true);
      }, function () { opomba("opombaNaprav", t("niPovezave"), true); });
    }
    shrani.addEventListener("click", function (e) { e.stopPropagation(); posljiIme(); });
    vnos.addEventListener("keydown", function (e) { if (e.key === "Enter") posljiIme(); if (e.key === "Escape") { preimenujem = null; narisiNaprave(); } });
    vnos.addEventListener("click", function (e) { e.stopPropagation(); });
    okvir.appendChild(vnos); okvir.appendChild(shrani);
    li.appendChild(okvir);
    setTimeout(function () { vnos.focus(); vnos.select(); }, 0);
  }

  function narisiNaprave(brezOpombe) {
    var ul = $("seznamNaprav"); ul.innerHTML = "";
    // Ta naprava: ime, kot ga vidijo druge; ✎ ga spremeni.
    var jaz = document.createElement("li");
    jaz.className = "jaz";
    jaz.innerHTML = '<span class="ikona">🌐</span><div><div class="ime">' + ubezi(mojeIme || (seja && seja.name) || "") + '</div><div class="pod">' + ubezi(t("taNaprava")) + "</div></div>";
    vrsticaImena(jaz, { id: seja.device_id, name: mojeIme || (seja && seja.name) || "" }, true);
    ul.appendChild(jaz);
    naprave.forEach(function (d) {
      var li = document.createElement("li");
      li.className = d.id === izbrana ? "izbrana" : "";
      var zna = [];
      if ((d.capabilities || []).indexOf("remote") >= 0) zna.push(t("daljinec"));
      if (d.role === "receiver" || (d.capabilities || []).indexOf("url") >= 0) zna.push(t("poslji"));
      li.innerHTML = '<span class="ikona">' + ikona(d) + '</span><div><div class="ime">' + ubezi(d.name || d.id) + '</div><div class="pod">' +
        ubezi(zna.join(" · ") || (T.vloga[d.role] || d.role || "")) + "</div></div>";
      li.addEventListener("click", function () { izbrana = d.id; narisiNaprave(); });
      vrsticaImena(li, d, false);
      ul.appendChild(li);
    });
    if (!brezOpombe) opomba("opombaNaprav", naprave.length ? (izbrana ? "" : t("izberi")) : t("niNaprav"));
    var d = naprave.filter(function (x) { return x.id === izbrana; })[0];
    pokazi("panelDejanja", !!d);
    if (d) {
      var daljinec = (d.capabilities || []).indexOf("remote") >= 0;
      $("zavDaljinec").hidden = !daljinec;
      if (!daljinec) zavihek("poslji"); else if ($("tDaljinec").hidden && $("tPoslji").hidden) zavihek("daljinec");
    }
  }

  function narisiPrejeto() {
    pokazi("panelPrejeto", prejeto.length > 0);
    var ul = $("seznamPrejeto"); ul.innerHTML = "";
    prejeto.forEach(function (p) {
      var li = document.createElement("li"); li.className = "sporocilo";
      li.innerHTML = '<div class="ime">' + ubezi(p.od) + '</div><div class="pod">' + (p.url ? '<a href="' + ubezi(p.url) + '" target="_blank" rel="noopener">' + ubezi(p.url) + "</a>" : ubezi(p.besedilo)) + "</div>";
      ul.appendChild(li);
    });
  }

  function zavihek(z) {
    document.querySelectorAll(".zavihek").forEach(function (b) { b.classList.toggle("izbran", b.getAttribute("data-z") === z); });
    pokazi("tDaljinec", z === "daljinec"); pokazi("tPoslji", z === "poslji");
  }
  document.querySelectorAll(".zavihek").forEach(function (b) { b.addEventListener("click", function () { zavihek(b.getAttribute("data-z")); }); });

  // ---- dejanja
  function ukaz(dejanje, parametri) {
    if (!izbrana) return;
    var id = nakljucno(8);
    if (!poslji({ id: id, type: "control.command", target: izbrana, payload: { action: dejanje, params: parametri || {} } })) {
      opomba("opombaDejanj", t("niPovezave"), true); return;
    }
    opomba("opombaDejanj", "");
    cakajoci[id] = setTimeout(function () { delete cakajoci[id]; }, 8000);
  }
  document.querySelectorAll("[data-k]").forEach(function (b) { b.addEventListener("click", function () { ukaz("key", { key: b.getAttribute("data-k") }); }); });
  document.querySelectorAll("[data-v]").forEach(function (b) { b.addEventListener("click", function () { ukaz("volume", { direction: b.getAttribute("data-v") }); }); });

  $("gumbPosljiUrl").addEventListener("click", function () {
    var u = ($("url").value || "").trim();
    if (!izbrana || !u) return;
    if (!/^https?:\/\//i.test(u)) u = "https://" + u;
    if (poslji({ id: nakljucno(8), type: "cast.url", target: izbrana, payload: { url: u } })) opomba("opombaDejanj", t("povezujem"));
    else opomba("opombaDejanj", t("niPovezave"), true);
  });
  $("gumbPosljiBesedilo").addEventListener("click", function () {
    var b = ($("besedilo").value || "").trim();
    if (!izbrana || !b) return;
    zahteva("/cast/share/text", { target: izbrana, text: b }, seja.token).then(function (r) {
      if (r._koda === 200) { opomba("opombaDejanj", t("poslano")); $("besedilo").value = ""; }
      else opomba("opombaDejanj", t("napaka") + (r.detail || r._koda), true);
    }, function () { opomba("opombaDejanj", t("niPovezave"), true); });
  });
  $("gumbPozabi").addEventListener("click", pozabi);
  function pozabi() {
    try { if (seja) zahteva("/cast/devices/leave", {}, seja.token); } catch (e) {}
    try { localStorage.removeItem("safeerLinkSplet"); } catch (e) {}
    seja = null; if (ws) { var s = ws; ws = null; try { s.close(); } catch (e) {} }
    zacetek();
  }
  $("gumbPridruzi").addEventListener("click", function () { pridruzi(parametri()); });

  // ---- zacetek
  function zacetek() {
    var p = parametri();
    stanje("siva", t("niPovezave"));
    if (p.j && p.s) {
      pokazi("zPridruzi", true); pokazi("zBrezKode", false); pokazi("zNaprave", false);
      $("ime").value = (seja && seja.name) || t("privzetoIme");
      var a = $("gumbOdpriSafeer"); a.href = "safeer://link/qr?" + location.hash.replace(/^#/, ""); a.hidden = false;
      return;
    }
    if (seja && seja.token) { povezi(); return; }
    pokazi("zPridruzi", false); pokazi("zBrezKode", true); pokazi("zNaprave", false);
  }
  zacetek();
})();
