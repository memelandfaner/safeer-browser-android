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
  // pripelje v orodno vrstico, dol iz orodne vrstice nazaj v dokument.
  try {
    if (new URLSearchParams(location.search).get("tv") === "1") {
      const vDokumentu = () => {
        const c = document.getElementById("viewerContainer");
        if (c) { c.setAttribute("tabindex", "-1"); c.focus({ preventScroll: true }); }
      };
      document.addEventListener("keydown", (e) => {
        const t = e.target;
        const tag = ((t && t.tagName) || "").toLowerCase();
        if (tag === "input" || tag === "select" || tag === "textarea" || (t && t.isContentEditable)) return;
        const c = document.getElementById("viewerContainer");
        if (!c) return;
        const vOrodni = t && t !== document.body && t !== c && !c.contains(t);
        if (vOrodni) {
          if (e.key === "ArrowDown") { vDokumentu(); e.preventDefault(); e.stopPropagation(); }
          return;
        }
        const korak = Math.round(c.clientHeight * 0.8);
        const app = window.PDFViewerApplication;
        switch (e.key) {
          case "ArrowDown":
            c.scrollBy({ top: korak, behavior: "smooth" }); break;
          case "ArrowUp":
            if (c.scrollTop <= 0) {
              const g = document.querySelector("#zoomInButton, #toolbarViewer button");
              if (g) g.focus();
            } else c.scrollBy({ top: -korak, behavior: "smooth" });
            break;
          case "ArrowRight":
            if (app && app.pdfViewer) app.pdfViewer.nextPage(); break;
          case "ArrowLeft":
            if (app && app.pdfViewer) app.pdfViewer.previousPage(); break;
          default:
            return;
        }
        e.preventDefault();
        e.stopPropagation();
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
