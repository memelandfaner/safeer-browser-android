// Safeer: PDF.js v brskalniku na telefonu in televizorju.
//  - shranjevanje (tudi z opombami) gre skozi most SafeerPdf v mapo prenosov, ker Android
//    WebView ne zna prenesti blob: naslova;
//  - tiskanje in "odpri datoteko" nista na voljo (ni tiskalnika v WebViewu, ni izbirnika).
(function () {
  const most = window.SafeerPdf;
  const zeton = (() => { try { return new URLSearchParams(location.search).get("z") || ""; } catch (e) { return ""; } })();

  // Jezik pregledovalnika: brskalnik ga poda v naslovu (?lang=sl), ker PDF.js sicer vzame
  // navigator.language, ki na Androidu ne sledi jeziku vmesnika Safeerja.
  try {
    const jezik = new URLSearchParams(location.search).get("lang");
    if (jezik) {
      Object.defineProperty(navigator, "language", { get: () => jezik, configurable: true });
      Object.defineProperty(navigator, "languages", { get: () => [jezik, "en-US"], configurable: true });
    }
  } catch (e) { console.warn("SafeerPdf jezik:", e); }

  function vBase64(bytes) {
    let s = "";
    const kos = 0x8000;
    for (let i = 0; i < bytes.length; i += kos) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + kos));
    }
    return btoa(s);
  }

  // Televizor: smerne tipke premikajo dokument, ne fokusa po gumbih. Gor na vrhu dokumenta
  // pripelje v orodno vrstico, dol iz orodne vrstice nazaj v dokument, gor iz orodne vrstice
  // preda fokus brskalniku (naslovna vrstica). Brskalnik na TV klice SafeerPdfTipka(smer)
  // neposredno (tipke prestreze sam), sicer deluje prek keydown.
  try {
    const tv = new URLSearchParams(location.search).get("tv") === "1";
    const vsebnik = () => document.getElementById("viewerContainer");
    const vDokumentu = () => {
      const c = vsebnik();
      if (c) { c.setAttribute("tabindex", "-1"); c.focus({ preventScroll: true }); }
    };
    const gumbiOrodne = () => Array.from(document.querySelectorAll("#toolbarContainer button, #toolbarContainer select, #toolbarContainer input"))
      .filter((g) => !g.disabled && !g.hidden && g.offsetParent !== null);
    let zadnjiGor = null;
    window.SafeerPdfTipka = function (smer) {
      const c = vsebnik();
      if (!c) return false;
      const t = document.activeElement;
      const tag = ((t && t.tagName) || "").toLowerCase();
      const vOrodni = t && t !== document.body && t !== c && !c.contains(t);
      if (vOrodni) {
        const g = gumbiOrodne();
        const i = g.indexOf(t);
        if (smer === "ArrowDown") { vDokumentu(); return true; }
        if (smer === "ArrowLeft") { if (i > 0) g[i - 1].focus(); return true; }
        if (smer === "ArrowRight") { if (i >= 0 && i < g.length - 1) g[i + 1].focus(); return true; }
        if (smer === "ArrowUp") {
          try { if (window.SafeerPdf && window.SafeerPdf.fokusVen) { window.SafeerPdf.fokusVen("gor"); return true; } } catch (e) {}
          return false;
        }
        return false;
      }
      if (tag === "input" || tag === "textarea" || (t && t.isContentEditable)) return false;
      const korak = Math.round(c.clientHeight * 0.8);
      const app = window.PDFViewerApplication;
      switch (smer) {
        case "ArrowDown": c.scrollBy({ top: korak, behavior: "smooth" }); return true;
        case "ArrowUp": {
          // Na vrhu dokumenta ali na vrhu trenutne strani gre gor v orodno vrstico (levo/desno
          // listata po straneh, zato je vrh strani vedno le nekaj pritiskov stran).
          let naVrhu = c.scrollTop <= 0;
          try {
            const pv = app && app.pdfViewer && app.pdfViewer.getPageView(app.pdfViewer.currentPageNumber - 1);
            const meja = -Math.max(24, Math.round(c.clientHeight * 0.08));
            if (pv && pv.div) naVrhu = naVrhu || (pv.div.getBoundingClientRect().top - c.getBoundingClientRect().top) >= meja;
          } catch (e) {}
          // Ce se prejsnji "gor" ni premaknil (npr. rob dokumenta), gre v orodno vrstico.
          if (zadnjiGor && zadnjiGor.top === c.scrollTop && Date.now() - zadnjiGor.cas < 4000) naVrhu = true;
          zadnjiGor = { top: c.scrollTop, cas: Date.now() };
          if (naVrhu) { zadnjiGor = null; const g = gumbiOrodne(); if (g.length) g[0].focus(); }
          else c.scrollBy({ top: -korak, behavior: "smooth" });
          return true;
        }
        case "ArrowRight": if (app && app.pdfViewer) app.pdfViewer.nextPage(); return true;
        case "ArrowLeft": if (app && app.pdfViewer) app.pdfViewer.previousPage(); return true;
      }
      return false;
    };
    // Brskalnik postavi fokus v orodno vrstico ("orodna") ali v dokument ("dokument").
    window.SafeerPdfFokus = function (kam) {
      if (kam === "orodna") { const g = gumbiOrodne(); if (g.length) { g[0].focus(); return true; } }
      vDokumentu();
      return true;
    };
    if (tv) {
      document.addEventListener("keydown", (e) => {
        if (["ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight"].indexOf(e.key) < 0) return;
        if (window.SafeerPdfTipka(e.key)) { e.preventDefault(); e.stopPropagation(); }
      }, true);
      window.addEventListener("load", () => setTimeout(vDokumentu, 600));
    }
  } catch (e) { console.warn("SafeerPdf tv:", e); }

  function pripni() {
    const app = window.PDFViewerApplication;
    if (!app || !app.initializedPromise) { setTimeout(pripni, 50); return; }
    app.initializedPromise.then(() => {
      if (!most || !app.downloadManager) return;
      const dm = app.downloadManager;
      dm.download = function (data, url, filename) {
        try {
          if (data) {
            most.shrani(filename || "dokument.pdf", vBase64(data instanceof Uint8Array ? data : new Uint8Array(data)), zeton);
          } else {
            most.prenesiIzvirnik(filename || "dokument.pdf", zeton);
          }
        } catch (e) {
          console.error("SafeerPdf:", e);
        }
      };
      dm.downloadData = function (data, filename, contentType) {
        try {
          most.shrani(filename || "datoteka", vBase64(data instanceof Uint8Array ? data : new Uint8Array(data)), zeton);
        } catch (e) {
          console.error("SafeerPdf:", e);
        }
      };
      dm.openOrDownloadData = function (data, filename) { dm.downloadData(data, filename, ""); return false; };
    });
  }
  pripni();
})();
