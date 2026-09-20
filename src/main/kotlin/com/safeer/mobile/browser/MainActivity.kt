package com.safeer.mobile.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.*
import java.net.URLEncoder

class MainActivity : android.app.Activity(), com.safeer.mobile.browser.link.Daljinec.VOspredju {

    companion object {
        /** Dodatek namere: ob zagonu odpri seznam prenosov (obvestilo o prejeti datoteki prek Safeer Linka). */
        const val ODPRI_PRENOSE = "com.safeer.mobile.browser.ODPRI_PRENOSE"
        private const val REQ_CODE_PERMISSIONS = 1001
        private const val REQ_CODE_GEO_PERMISSIONS = 1002
        private const val REQ_CODE_NOTIFICATION = 1003
        private const val REQ_CODE_DEFAULT_BROWSER = 1004
        private const val REQ_CODE_LINK_DATOTEKA = 1005
        private const val REQ_CODE_LINK_ZASLON = 1006
    }

    private var keyboardWasVisible = false

    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private lateinit var mainRoot: RelativeLayout
    private lateinit var mobileTopBar: LinearLayout
    private lateinit var btnHome: Button
    private lateinit var omniboxContainer: LinearLayout
    private lateinit var tvSecurityLock: TextView
    private lateinit var editUrl: EditText
    private lateinit var btnClearUrl: TextView
    private lateinit var btnSearchTrigger: TextView
    private lateinit var btnReload: Button
    private lateinit var btnStar: Button
    private lateinit var btnAddTab: Button
    private lateinit var btnTabCount: Button
    private lateinit var btnMenu: Button
    private lateinit var pageProgressBar: ProgressBar
    private lateinit var webViewContainer: FrameLayout

    // Overlays & Secondary Views
    private lateinit var tabSwitcherOverlay: RelativeLayout
    private lateinit var tabsGridView: GridView
    private lateinit var btnNewTabInSwitcher: Button
    private lateinit var btnCloseTabsSwitcher: Button
    private lateinit var btnCloseAllTabs: TextView

    private lateinit var findInPageBar: LinearLayout
    private lateinit var editFindText: EditText
    private lateinit var tvFindMatches: TextView
    private lateinit var btnFindPrev: Button
    private lateinit var btnFindNext: Button
    private lateinit var btnFindClose: Button

    // Managers & Repositories
    private lateinit var tabManager: TabManager
    private lateinit var repository: BrowserRepository
    private var historySuggestions: HistorySuggestions? = null
    private lateinit var downloadHandler: DownloadHandler
    /** Meni, odprt z daljinca z druge naprave: tipke daljinca gredo vanj, dokler je odprt. */
    private var meniDaljinca: android.app.Dialog? = null

    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var isDarkModeActive: Boolean = true

    override fun attachBaseContext(newBase: Context) {
        val lang = PreferencesManager.getLanguage(newBase)
        if (lang != "auto") {
            val locale = java.util.Locale(lang)
            java.util.Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("SafeerCrashHandler", "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Own system-bar and IME insets consistently, including Android 15+.
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(R.layout.activity_main)

        applyAppTheme(PreferencesManager.getTheme(this))

        repository = BrowserRepository(this)
        downloadHandler = DownloadHandler(this)

        // ⚙️ Naloži shranjene nastavitve iz PreferencesManager
        AdBlockEngine.isEnabled = PreferencesManager.isAdBlockEnabled(this)
        isDarkModeActive = PreferencesManager.isDarkModeEnabled(this)
        val appContext = applicationContext
        AdBlockEngine.onAdBlocked = {
            PreferencesManager.incrementAdsBlocked(appContext)
        }
        ThreatBlockEngine.onThreatBlocked = { _, _, _, _ ->
            PreferencesManager.incrementThreatsBlocked(appContext)
        }

        initViews()
        setupWindowInsets()
        setupTabManager()
        setupMediaPlaybackService()
        setupOmnibox()
        setupTopButtons()
        setupTouchGestures()
        setupFindInPage()

        // Agent za sezname groženj (ThreatFox, URLhaus, Phishing Army): shranjeni seznami takoj v ozadju,
        // preverjanje novih ~12 s po zagonu. Zagona in nalaganja strani ne upočasni.
        ThreatFeedsUpdater.start(this)
        // Dodatna plast: preverjen, podpisan seznam Safeer Threat Intelligence (izklopljen brez ključa)
        SignedThreatIntel.start(this)
        installServiceWorkerThreatShield()

        // 🛡️ Inicializiraj šifriran DNS (DoH) ali šifriran tunel (Tor / Proxy)
        DoHProxyEngine.applySettings(this) { handleIncomingIntent(intent, isInitial = true) }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val postNotif = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(postNotif) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(postNotif), REQ_CODE_NOTIFICATION)
            }
        }

    }

    private fun applyAppTheme(theme: String) {
        val (statusColor, navColor) = when (theme) {
            "amoled" -> Pair(Color.BLACK, Color.BLACK)
            "midnight" -> Pair(Color.parseColor("#0b1120"), Color.parseColor("#020617"))
            "emerald" -> Pair(Color.parseColor("#051f15"), Color.parseColor("#020f0a"))
            else -> Pair(Color.parseColor("#06090F"), Color.BLACK)
        }
        window.statusBarColor = statusColor
        window.navigationBarColor = navColor
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, isInitial = false)
    }

    private fun handleIncomingIntent(intent: Intent?, isInitial: Boolean) {
        var targetUrl: String? = null

        if (intent != null) {
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    // Prijava racunalnika s QR kodo (kamera odpre safeer.si/p#...): to ni stran za brskanje,
                    // ampak vprasanje »Dovoli?« - tudi kadar je Safeer privzeti brskalnik.
                    if (com.safeer.mobile.browser.link.QrPrijavaActivity.jeSafeerKoda(intent.data)) {
                        startActivity(Intent(this, com.safeer.mobile.browser.link.QrPrijavaActivity::class.java).setData(intent.data))
                        return
                    }
                    targetUrl = intent.dataString
                }
                Intent.ACTION_WEB_SEARCH -> {
                    val query = intent.getStringExtra(android.app.SearchManager.QUERY)
                        ?: intent.getStringExtra("query") ?: ""
                    if (query.isNotBlank()) {
                        targetUrl = PreferencesManager.buildSearchUrl(this, query)
                    }
                }
                Intent.ACTION_SEND -> {
                    if (intent.type?.startsWith("text/") == true) {
                        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: ""
                        if (shared.isNotBlank()) {
                            targetUrl = if (shared.startsWith("http://") || shared.startsWith("https://")) {
                                shared
                            } else {
                                PreferencesManager.buildSearchUrl(this, shared)
                            }
                        }
                    }
                }
            }
        }

        val restored = isInitial && tabManager.restoreSession()
        val finalUrl = targetUrl ?: if (isInitial && !restored) TabSessionCodec.HOME else null
        if (finalUrl != null) {
            if (isInitial && !restored) {
                tabManager.createTab(this, finalUrl, true)
            } else {
                // Keep the page the user was reading when another app opens a link.
                val existing = tabManager.getAllTabs().find { it.url == finalUrl }
                if (existing != null) tabManager.switchTab(existing.id)
                else tabManager.createTab(this, finalUrl, true)
            }
        }
        // Obvestilo o prejeti datoteki (Safeer Link) odpre seznam prenosov v brskalniku.
        if (intent?.getBooleanExtra(ODPRI_PRENOSE, false) == true) {
            intent.removeExtra(ODPRI_PRENOSE)
            window.decorView.post { if (!isFinishing) PrenosiUi.show(this) { url -> tabManager.createTab(this, url, true) } }
        }
    }

    private fun isDefaultBrowser(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) == true &&
                roles.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        } else {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
            packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
        }
    }

    private fun requestDefaultBrowser() {
        if (isDefaultBrowser()) {
            Toast.makeText(this, R.string.default_browser_active, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(android.app.role.RoleManager::class.java)
                if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) == true) {
                    startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), REQ_CODE_DEFAULT_BROWSER)
                    return
                }
            }
            startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), REQ_CODE_DEFAULT_BROWSER)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.default_browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_DEFAULT_BROWSER) {
            Toast.makeText(this, if (isDefaultBrowser()) R.string.default_browser_active else R.string.default_browser_unchanged, Toast.LENGTH_LONG).show()
        }
        if (requestCode == REQ_CODE_LINK_DATOTEKA) {
            // Datoteka za drugo napravo prek Safeer Linka: posljemo jo, ce je uporabnik katero izbral.
            val uri = data?.data
            val cilj = linkDatotekaCilj
            linkDatotekaCilj = ""
            if (resultCode == RESULT_OK && uri != null && cilj.isNotBlank()) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                linkMost?.posljiDatotekoUri(uri, cilj)
            }
        }
        if (requestCode == REQ_CODE_LINK_ZASLON) {
            val cilj = linkZaslonCilj
            val ime = linkZaslonIme
            linkZaslonCilj = ""
            linkZaslonIme = ""
            if (resultCode == RESULT_OK && data != null && cilj.isNotBlank()) {
                linkMost?.zajemDovoljen(resultCode, data, cilj, ime)
            } else {
                linkMost?.zajemZavrnjen(cilj)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Stran Linka je vprasala za dovoljenje za medije; naj se izrise s pravim stanjem.
        linkMost?.naDovoljenje(requestCode)
        when (requestCode) {
            REQ_CODE_PERMISSIONS -> {
                val req = pendingPermissionRequest
                pendingPermissionRequest = null
                if (req != null) {
                    val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (allGranted) {
                        req.grant(req.resources)
                    } else {
                        req.deny()
                    }
                }
            }
            REQ_CODE_GEO_PERMISSIONS -> {
                val origin = pendingGeoOrigin
                val cb = pendingGeoCallback
                pendingGeoOrigin = null
                pendingGeoCallback = null
                if (cb != null && origin != null) {
                    val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    cb.invoke(origin, granted, false)
                }
            }
        }
    }

    private var inForeground = false

    override fun onPause() {
        super.onPause()
        inForeground = false
        com.safeer.mobile.browser.cast.HubKrmilnik.naPrijavoZaZaslon = null
        com.safeer.mobile.browser.link.LinkSprejemnik.naStran = null
        com.safeer.mobile.browser.link.LinkSprejemnik.naBesedilo = null
        com.safeer.mobile.browser.link.LinkSprejemnik.naUkaz = null
        val playing = tabManager.getPlayingTab()
        if (playing != null) {
            // 🎵 Sound keeps playing: the WebView stays "visible" (ChromiumEngineView) and a foreground service with
            // a media notification keeps the process alive while the user is on the home screen or the screen is off.
            MediaPlaybackService.start(this, mediaTitle(playing), playing = true)
            return
        }
        tabManager.getActiveTab()?.loadedWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        com.safeer.mobile.browser.cast.HubKrmilnik.naPrijavoZaZaslon = { runOnUiThread { pokaziKodoZaSeznanitev() } }
        pokaziKodoZaSeznanitev()
        // Sprejem prek Safeer Linka, ko je brskalnik v ospredju: stran v zavihek, besedilo v okno.
        com.safeer.mobile.browser.link.LinkSprejemnik.naStran = { url -> runOnUiThread { openUrlInBrowser(url) } }
        com.safeer.mobile.browser.link.LinkSprejemnik.naBesedilo = { od, b -> runOnUiThread { pokaziPrejetoBesedilo(od, b) } }
        com.safeer.mobile.browser.link.LinkSprejemnik.naUkaz = this
        // Ce je uporabnik Safeer Link pustil prizgan oz. je telefon seznanjen, naj to velja tudi po ponovnem zagonu.
        com.safeer.mobile.browser.cast.HubStoritev.zagotovi(this)
        com.safeer.mobile.browser.link.LinkSprejemnik.zagotovi(this)
        MediaPlaybackService.stop(this)
        tabManager.getActiveTab()?.loadedWebView?.onResume()
    }

    private fun mediaTitle(tab: TabModel): String {
        val title = try { (tab.loadedWebView?.title ?: tab.title).trim() } catch (_: Exception) { "" }
        if (title.isNotEmpty() && title != "Nov zavihek") return title
        return try { android.net.Uri.parse(tab.url).host ?: I18n.t(this, "media_background_title", "Safeer") } catch (_: Exception) { "Safeer" }
    }

    private fun mediaCommand(script: String) {
        val tab = tabManager.getPlayingTab() ?: tabManager.getActiveTab() ?: return
        runOnUiThread { try { tab.loadedWebView?.evaluateJavascript(script, null) } catch (_: Exception) {} }
    }

    private fun setupMediaPlaybackService() {
        MediaPlaybackService.toggleHandler = {
            mediaCommand("(function(){var m=Array.prototype.slice.call(document.querySelectorAll('video,audio')).filter(function(x){return x.readyState>0||!x.paused;})[0]||document.querySelector('video,audio');if(!m)return;if(m.paused){m.play();}else{m.pause();}})();")
        }
        MediaPlaybackService.pauseHandler = {
            mediaCommand("(function(){document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});})();")
        }
        tabManager.onAudioStateChanged = { tab, playing ->
            if (!inForeground) {
                if (playing) MediaPlaybackService.start(this, mediaTitle(tab), playing = true)
                else if (tabManager.getPlayingTab() == null) MediaPlaybackService.start(this, mediaTitle(tab), playing = false)
            }
        }
    }

    override fun onStop() {
        tabManager.saveSession()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::tabManager.isInitialized && (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)) {
            tabManager.suspendInactiveTabs(memoryPressure = true)
        }
    }

    override fun onDestroy() {
        MediaPlaybackService.toggleHandler = null
        MediaPlaybackService.pauseHandler = null
        MediaPlaybackService.stop(this)
        DoHProxyEngine.stopServer()
        if (::tabManager.isInitialized) tabManager.dispose()
        super.onDestroy()
    }

    /**
     * Daljinec Safeer Controla (prek Safeer Linka), ko je brskalnik v ospredju: tipke gredo
     * v okno kot s tipkovnice, drsenje in posnetek po dejavnem zavihku. Kar ni nasteto,
     * izvede storitev sama (glasnost, aplikacije, ponovni zagon ...).
     */
    override fun izvediUkaz(dejanje: String, parametri: org.json.JSONObject): com.safeer.mobile.browser.link.Daljinec.Izid? {
        return when (dejanje) {
            "key" -> {
                val ime = parametri.optString("key", "").trim().lowercase()
                val meni = meniDaljinca?.takeIf { it.isShowing }
                when (ime) {
                    "home" -> { meni?.dismiss(); openUrlInBrowser(com.safeer.mobile.browser.link.LinkSprejemnik.DOMACA_STRAN); return com.safeer.mobile.browser.link.Daljinec.Izid(true, "Domov") }
                    "back" -> { if (meni != null) meni.dismiss() else onBackPressed(); return com.safeer.mobile.browser.link.Daljinec.Izid(true, "Nazaj") }
                    "menu" -> { if (meni != null) meni.dismiss() else showMobileMenu(); return com.safeer.mobile.browser.link.Daljinec.Izid(true, "Meni") }
                }
                val koda = com.safeer.mobile.browser.link.Daljinec.TIPKE[ime] ?: return com.safeer.mobile.browser.link.Daljinec.Izid(false, "Neznana tipka: $ime", koda = "neznana_tipka")
                val zdaj = android.os.SystemClock.uptimeMillis()
                // Odprt meni je svoje okno: tipke mora dobiti on.
                val cilj: (KeyEvent) -> Boolean = if (meni != null) meni::dispatchKeyEvent else this::dispatchKeyEvent
                cilj(KeyEvent(zdaj, zdaj, KeyEvent.ACTION_DOWN, koda, 0))
                cilj(KeyEvent(zdaj, zdaj + 40, KeyEvent.ACTION_UP, koda, 0))
                com.safeer.mobile.browser.link.Daljinec.Izid(true, "Tipka $ime")
            }
            "scroll" -> {
                val smer = parametri.optString("direction", "down").trim().lowercase()
                val js = when (smer) {
                    "up" -> "window.scrollBy({top:-Math.round(window.innerHeight*0.8),behavior:'smooth'})"
                    "down" -> "window.scrollBy({top:Math.round(window.innerHeight*0.8),behavior:'smooth'})"
                    "top" -> "window.scrollTo({top:0,behavior:'smooth'})"
                    "bottom" -> "window.scrollTo({top:document.documentElement.scrollHeight,behavior:'smooth'})"
                    else -> return com.safeer.mobile.browser.link.Daljinec.Izid(false, "Neznana smer: $smer")
                }
                val wv = tabManager.getActiveTab()?.webView ?: return com.safeer.mobile.browser.link.Daljinec.Izid(false, "Ni odprtega zavihka")
                try { wv.evaluateJavascript(js, null) } catch (_: Exception) { }
                com.safeer.mobile.browser.link.Daljinec.Izid(true, "Drsenje $smer")
            }
            "screenshot" -> posnetekZaslona()
            "status" -> com.safeer.mobile.browser.link.Daljinec.Izid(true, "Stanje", com.safeer.mobile.browser.link.Daljinec.stanje(this,
                org.json.JSONObject().put("url", PdfPregledovalnik.javniNaslov(tabManager.getActiveTab()?.url ?: ""))
                    .put("title", tabManager.getActiveTab()?.webView?.title ?: "")))
            else -> null
        }
    }

    /** Posnetek dejavnega zavihka: pomanjsan JPEG (najvec 640 px), da gre skozi sredisce. */
    private fun posnetekZaslona(): com.safeer.mobile.browser.link.Daljinec.Izid {
        val pogled: View = customVideoView ?: tabManager.getActiveTab()?.webView
            ?: return com.safeer.mobile.browser.link.Daljinec.Izid(false, "Ni odprtega zavihka")
        val sirina = pogled.width
        val visina = pogled.height
        if (sirina <= 0 || visina <= 0) return com.safeer.mobile.browser.link.Daljinec.Izid(false, "Zaslon se ni pripravljen")
        val merilo = minOf(1f, 640f / sirina)
        val slika = android.graphics.Bitmap.createBitmap(
            (sirina * merilo).toInt().coerceAtLeast(1), (visina * merilo).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.RGB_565)
        val platno = android.graphics.Canvas(slika)
        platno.scale(merilo, merilo)
        try {
            pogled.draw(platno)
        } catch (e: Throwable) {
            slika.recycle()
            return com.safeer.mobile.browser.link.Daljinec.Izid(false, "Posnetka ni bilo mogoce narediti: ${e.message}")
        }
        val izhod = java.io.ByteArrayOutputStream()
        slika.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, izhod)
        val (w, h) = Pair(slika.width, slika.height)
        slika.recycle()
        val b64 = android.util.Base64.encodeToString(izhod.toByteArray(), android.util.Base64.NO_WRAP)
        return com.safeer.mobile.browser.link.Daljinec.Izid(true, "Posnetek zaslona",
            org.json.JSONObject().put("image", "data:image/jpeg;base64," + b64).put("width", w).put("height", h))
    }

    private fun openUrlInBrowser(url: String) {
        val sanitized = UrlSanitizer.sanitize(url)
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null) {
            activeTab.webView.navigateDocument(sanitized)
        } else {
            tabManager.createTab(this, sanitized, true)
        }
    }

    private fun initViews() {
        mainRoot = findViewById(R.id.mainRoot)
        mobileTopBar = findViewById(R.id.mobileTopBar)
        btnHome = findViewById(R.id.btnHome)
        omniboxContainer = findViewById(R.id.omniboxContainer)
        tvSecurityLock = findViewById(R.id.tvSecurityLock)
        editUrl = findViewById(R.id.editUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnSearchTrigger = findViewById(R.id.btnSearchTrigger)
        btnReload = findViewById(R.id.btnReload)
        btnStar = findViewById(R.id.btnStar)
        btnAddTab = findViewById(R.id.btnAddTab)
        btnTabCount = findViewById(R.id.btnTabCount)
        btnMenu = findViewById(R.id.btnMenu)
        pageProgressBar = findViewById(R.id.pageProgressBar)
        webViewContainer = findViewById(R.id.webViewContainer)

        tabSwitcherOverlay = findViewById(R.id.tabSwitcherOverlay)
        tabsGridView = findViewById(R.id.tabsGridView)
        btnNewTabInSwitcher = findViewById(R.id.btnNewTabInSwitcher)
        btnCloseTabsSwitcher = findViewById(R.id.btnCloseTabsSwitcher)
        btnCloseAllTabs = findViewById(R.id.btnCloseAllTabs)

        findInPageBar = findViewById(R.id.findInPageBar)
        editFindText = findViewById(R.id.editFindText)
        tvFindMatches = findViewById(R.id.tvFindMatches)
        btnFindPrev = findViewById(R.id.btnFindPrev)
        btnFindNext = findViewById(R.id.btnFindNext)
        btnFindClose = findViewById(R.id.btnFindClose)
    }

    private fun applySystemBarInsets(statusBarHeight: Int, bottomInset: Int, leftInset: Int = 0, rightInset: Int = 0) {
        // Resize the native viewport so fixed website controls stay above the
        // navigation bar/keyboard. Do not add the keyboard and nav heights.
        mainRoot.setPadding(leftInset, 0, rightInset, bottomInset)
        val baseToolbarHeight = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
        val totalToolbarHeight = baseToolbarHeight + statusBarHeight
        val lp = mobileTopBar.layoutParams
        if (lp != null && lp.height != totalToolbarHeight) {
            lp.height = totalToolbarHeight
            mobileTopBar.layoutParams = lp
        }
        mobileTopBar.setPadding(
            mobileTopBar.paddingLeft,
            statusBarHeight,
            mobileTopBar.paddingRight,
            mobileTopBar.paddingBottom
        )

        // The root already reserves the bottom and side insets for all children.
        tabSwitcherOverlay.setPadding(0, statusBarHeight, 0, 0)

        val rootLp = webViewContainer.layoutParams as? RelativeLayout.LayoutParams
        if (rootLp != null && rootLp.bottomMargin != 0) {
            rootLp.bottomMargin = 0
            webViewContainer.layoutParams = rootLp
        }
    }

    private fun setupWindowInsets() {
        mainRoot.setOnApplyWindowInsetsListener { _, insets ->
            if (customVideoView != null) {
                mainRoot.setPadding(0, 0, 0, 0)
                return@setOnApplyWindowInsetsListener insets
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                applySystemBarInsets(bars.top, maxOf(bars.bottom, ime.bottom),
                    maxOf(bars.left, ime.left), maxOf(bars.right, ime.right))
                val keyboardVisible = insets.isVisible(android.view.WindowInsets.Type.ime())
                if (keyboardWasVisible && !keyboardVisible && editUrl.hasFocus()) {
                    editUrl.clearFocus()
                    mainRoot.requestFocus()
                }
                keyboardWasVisible = keyboardVisible
                // Children are already inside the safe viewport.
                WindowInsets.CONSUMED
            } else {
                // Android 9/10 retain the platform's fitted window/adjustResize.
                // Only reserve remaining insets; zero is a valid value.
                @Suppress("DEPRECATION")
                applySystemBarInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom,
                    insets.systemWindowInsetLeft, insets.systemWindowInsetRight)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        mainRoot.requestApplyInsets()
    }

    private fun setupTabManager() {
        tabManager = TabManager(webViewContainer) { count, activeTab ->
            btnTabCount.text = count.toString()
            if (activeTab != null) {
                updateOmniboxDisplay(activeTab.url, activeTab.title)
                if (activeTab.loadedWebView == null) {
                    tvSecurityLock.text = "⚠️"
                    pageProgressBar.visibility = View.GONE
                }
            }
        }
        tabManager.onViewCreated = { tab ->
            tab.webView.isDarkMode = isDarkModeActive
            attachTabListeners(tab)
        }
    }

    /** Zadnje prepusceno pojavno okno; isti klik zna sprozit vec dogodkov. */
    private var zadnjePojavno: Pair<String, Long> = "" to 0L

    /**
     * Stran je zahtevala novo okno. Kam pelje, se ne vemo, zato dobi zavihek v ozadju, ki ni
     * viden in v katerem se ne nalozi nic. Ko brskalnik pove prvi naslov, se zavihek pokaze
     * (prijava) ali tiho zapre (oglas). Stevec zavihkov se ob oglasu ne premakne.
     *
     * Okno namenoma dobi pravi pogled in ne nadomestka: brez povezave z izvorno stranjo
     * (window.opener) prijava z Google, Facebook ali X ne more vrniti odgovora.
     */
    private fun odpriPojavnoOkno(resultMsg: android.os.Message): Boolean {
        val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport ?: return false
        // Pogled se ni zavihek: dokler ne vemo, kam okno pelje, stevec zavihkov miruje.
        val pogled = try { tabManager.pripraviPojavni() } catch (_: Throwable) { return false }
        val odpiralec = tabManager.getActiveTab()?.id
        var odloceno = false

        fun zavrzi(razlog: String, naslov: String) {
            android.util.Log.i("SafeerPojavno", "$razlog: ${naslov.take(90)}")
            // Pogleda ne unicujemo znotraj njegovega lastnega povratnega klica - Chromium
            // je takrat se sredi obdelave navigacije. Pospravimo ga v naslednjem obhodu.
            pogled.post { tabManager.zavrziPojavni(pogled) }
        }

        pogled.vratarPojavnega = vratar@{ naslov ->
            if (odloceno) return@vratar false
            odloceno = true
            val zdaj = android.os.SystemClock.elapsedRealtime()
            val podvojeno = naslov == zadnjePojavno.first && zdaj - zadnjePojavno.second < 2_000L
            when {
                podvojeno -> {
                    zavrzi("Podvojeno okno zaprto", naslov)
                    false
                }
                !PrijavnaOkna.jePrijava(naslov) -> {
                    zavrzi("Pojavno okno preprečeno", naslov)
                    false
                }
                else -> {
                    zadnjePojavno = naslov to zdaj
                    // Prazna lupina je ze prikazala svojo stran; tak pogled ostane prazen,
                    // ce ga preselimo med zavihke, zato ga zavrzemo in naslov odpremo v
                    // novem zavihku. Okno, ki se ni nicesar prikazalo, pa sprejmemo takega,
                    // kot je - tako ostane povezava z izvorno stranjo (window.opener).
                    val lupina = (pogled.url ?: "") == "about:blank"
                    if (lupina) {
                        android.util.Log.i("SafeerPojavno", "Prijava v novem zavihku: ${naslov.take(90)}")
                        pogled.post {
                            tabManager.zavrziPojavni(pogled)
                            try {
                                // Tudi ta zavihek je pojavno okno: Nazaj ga zapre in vrne na
                                // stran, ki ga je odprla, ne pa hodi po njegovi zgodovini.
                                val nov = tabManager.createTab(this, naslov, true)
                                nov.jePojavni = true
                                nov.odpiralec = odpiralec
                            } catch (_: Throwable) {}
                        }
                        false
                    } else {
                        pogled.post {
                            try {
                                tabManager.posvoji(pogled, naslov, odpiralec)
                                android.util.Log.i("SafeerPojavno", "Prijavno okno odprto: ${naslov.take(90)}")
                            } catch (e: Throwable) {
                                android.util.Log.w("SafeerPojavno", "Zavihka ni bilo mogoce odpreti: ${e.message}")
                                tabManager.zavrziPojavni(pogled)
                            }
                        }
                        true
                    }
                }
            }
        }

        transport.webView = pogled
        resultMsg.sendToTarget()

        // Prazna lupina, ki nikamor ne odnavigira, se po tiho pospravi.
        pogled.postDelayed({
            if (!odloceno) {
                odloceno = true
                pogled.vratarPojavnega = null
                tabManager.zavrziPojavni(pogled)
            }
        }, 15_000L)
        return true
    }

    private fun attachTabListeners(tab: TabModel) {
        val wv = tab.webView

        wv.onProgressUpdate = { progress ->
            if (tabManager.getActiveTab()?.id == tab.id) {
                if (progress < 100) {
                    pageProgressBar.visibility = View.VISIBLE
                    pageProgressBar.progress = progress
                } else {
                    pageProgressBar.visibility = View.GONE
                }
            }
        }

        wv.onUrlChanged = { newUrl ->
            tab.url = newUrl
            tabManager.scheduleSave()
            if (tabManager.getActiveTab()?.id == tab.id) {
                updateOmniboxDisplay(newUrl, wv.title)
            }
        }

        wv.onPageLoaded = { finalUrl, pageTitle ->
            tab.url = finalUrl
            tab.title = pageTitle
            tabManager.scheduleSave()
            if (finalUrl.isNotEmpty() && !finalUrl.startsWith("about:", ignoreCase = true)) {
                val cleanTitle = if (pageTitle.isNotEmpty()) pageTitle else finalUrl
                repository.addHistory(cleanTitle, finalUrl)
            }
        }

        wv.onTitleChanged = { title ->
            tab.title = title
            tabManager.scheduleSave()
            if (tabManager.getActiveTab()?.id == tab.id) {
                updateOmniboxDisplay(tab.url, title)
            }
        }

        wv.onSecurityChanged = { isSecure ->
            if (tabManager.getActiveTab()?.id == tab.id) {
                tvSecurityLock.text = if (isSecure) "🔒" else "⚠️"
                tvSecurityLock.setTextColor(
                    if (isSecure) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
                )
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // PDF se ne prenese, ampak odpre v vgrajenem pregledovalniku (branje in urejanje).
            if (PdfPregledovalnik.jePdf(url, mimeType, contentDisposition)) {
                PdfPregledovalnik.odpri(this, wv, url, userAgent, contentDisposition, mimeType)
            } else {
                downloadHandler.startDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
        wv.onPdfPrenos = { url, ua -> downloadHandler.startDownload(url, ua, null, "application/pdf") }

        wv.onFullscreenToggled = { customView, callback ->
            if (customView != null) {
                customVideoView = customView
                customVideoCallback = callback
                mainRoot.setPadding(0, 0, 0, 0)
                mobileTopBar.visibility = View.GONE
                webViewContainer.visibility = View.GONE
                mainRoot.addView(
                    customView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                customVideoView?.let { mainRoot.removeView(it) }
                customVideoView = null
                customVideoCallback = null
                mobileTopBar.visibility = View.VISIBLE
                webViewContainer.visibility = View.VISIBLE
                mainRoot.requestApplyInsets()
            }
        }

        wv.onCreateWindowRequested = { _, isUserGesture, resultMsg ->
            val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport
            if (!isUserGesture || transport == null) false
            else {
                if (!PreferencesManager.isPopupBlockEnabled(this)) {
                    // Uporabnik je zascito izklopil: okno se odpre kot v navadnem brskalniku.
                    val novTab = tabManager.createTab(this, "about:blank", true)
                    transport.webView = novTab.webView
                    resultMsg.sendToTarget()
                    true
                } else {
                    // Okno dobi zavihek v ozadju, v katerem se ne nalozi nic, dokler ne
                    // izvemo, kam pelje. Prijava se pokaze, oglas se tiho zapre.
                    odpriPojavnoOkno(resultMsg)
                }
            }
        }

        wv.onCloseWindowRequested = {
            tabManager.closeTab(this, tab.id)
        }

        wv.onPermissionRequested = { request ->
            val resources = request.resources ?: emptyArray()
            val host = request.origin?.host ?: request.origin?.toString() ?: "Spletna stran"

            val labels = resources.map { res ->
                when (res) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "🎤 Mikrofon (zvok)"
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "📷 Kamera (video)"
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "🔑 Zaščitena medijska vsebina (DRM)"
                    else -> res.substringAfterLast(".")
                }
            }.joinToString("\n• ", prefix = "• ")

            runOnUiThread {
                try {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.perm_request_title))
                        .setMessage(getString(R.string.perm_request_msg, host, labels))
                        .setPositiveButton(getString(R.string.perm_allow)) { _, _ ->
                            val neededSystem = mutableListOf<String>()
                            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                neededSystem.add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                neededSystem.add(Manifest.permission.CAMERA)
                            }

                            if (neededSystem.isNotEmpty()) {
                                pendingPermissionRequest = request
                                requestPermissions(neededSystem.toTypedArray(), REQ_CODE_PERMISSIONS)
                            } else {
                                request.grant(resources)
                            }
                        }
                        .setNegativeButton(getString(R.string.perm_deny)) { _, _ ->
                            request.deny()
                        }
                        .setOnCancelListener {
                            request.deny()
                        }
                        .show()
                } catch (_: Exception) {
                    request.deny()
                }
            }
        }

        wv.onGeolocationRequested = { origin, callback ->
            runOnUiThread {
                try {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.geo_request_title))
                        .setMessage(getString(R.string.geo_request_msg, origin))
                        .setPositiveButton(getString(R.string.perm_allow)) { _, _ ->
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                pendingGeoOrigin = origin
                                pendingGeoCallback = callback
                                requestPermissions(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    ),
                                    REQ_CODE_GEO_PERMISSIONS
                                )
                            } else {
                                callback.invoke(origin, true, false)
                            }
                        }
                        .setNegativeButton(getString(R.string.perm_deny)) { _, _ ->
                            callback.invoke(origin, false, false)
                        }
                        .setOnCancelListener {
                            callback.invoke(origin, false, false)
                        }
                        .show()
                } catch (_: Exception) {
                    callback.invoke(origin, false, false)
                }
            }
        }
    }

    private fun setupOmnibox() {
        editUrl.setOnFocusChangeListener { _, hasFocus ->
            omniboxContainer.isActivated = hasFocus
            btnTabCount.visibility = if (hasFocus) View.GONE else View.VISIBLE
            btnMenu.visibility = if (hasFocus) View.GONE else View.VISIBLE
            tvSecurityLock.visibility = if (hasFocus) View.GONE else View.VISIBLE
            btnSearchTrigger.visibility = if (hasFocus) View.VISIBLE else View.GONE
            if (hasFocus) {
                val currentUrl = tabManager.getActiveTab()?.url ?: ""
                val isLocal = currentUrl.isEmpty() || currentUrl.startsWith("file:///android_asset/") || currentUrl == "about:blank"
                if (isLocal) {
                    editUrl.setText("")
                } else {
                    editUrl.setText(currentUrl)
                    editUrl.selectAll()
                }
                btnClearUrl.visibility = if (editUrl.text.isNotEmpty()) View.VISIBLE else View.GONE
            } else {
                btnClearUrl.visibility = View.GONE
                historySuggestions?.dismiss()
                val activeTab = tabManager.getActiveTab()
                updateOmniboxDisplay(activeTab?.url ?: "", activeTab?.loadedWebView?.title ?: activeTab?.title)
            }
        }

        // 🕒 Predlogi iz zgodovine in zaznamkov med tipkanjem
        historySuggestions = HistorySuggestions(this, omniboxContainer, repository) { url ->
            hideKeyboard()
            editUrl.clearFocus()
            performNavigation(url)
        }
        editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editUrl.hasFocus()) {
                    btnClearUrl.visibility = if (!s.isNullOrEmpty()) View.VISIBLE else View.GONE
                    val typed = s?.toString() ?: ""
                    // the address of the current page is preselected on focus: suggest only once the user types
                    if (editUrl.selectionStart == editUrl.selectionEnd) historySuggestions?.update(typed) else historySuggestions?.dismiss()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                historySuggestions?.dismiss()
                performNavigation(editUrl.text.toString().trim())
                hideKeyboard()
                editUrl.clearFocus()
                true
            } else {
                false
            }
        }

        btnClearUrl.setOnClickListener {
            editUrl.setText("")
            editUrl.requestFocus()
        }

        btnSearchTrigger.setOnClickListener {
            performNavigation(editUrl.text.toString().trim())
            hideKeyboard()
            editUrl.clearFocus()
        }
    }

    private fun updateStarState(url: String? = null) {
        val targetUrl = url ?: tabManager.getActiveTab()?.url ?: ""
        val isBm = if (targetUrl.isNotEmpty() && !targetUrl.startsWith("file:///android_asset/")) {
            repository.isBookmarked(targetUrl)
        } else false
        btnStar.text = if (isBm) "⭐" else "☆"
    }

    private fun updateOmniboxDisplay(url: String, title: String?) {
        updateStarState(url)
        if (editUrl.hasFocus()) return

        if (url.isEmpty() || url == "about:blank" || url.startsWith("file:///android_asset/brave_home.html")) {
            editUrl.setText("")
            editUrl.hint = getString(R.string.url_hint)
            tvSecurityLock.text = "S"
            return
        }

        try {
            val uri = Uri.parse(url)
            if (uri.scheme == "content" || uri.scheme == "file") {
                // Dokument z naprave (prenosi, Safeer Link): v vrstici pokazi ime datoteke, ne notranjega naslova.
                val ime = (title?.takeIf { it.isNotBlank() && !it.contains("://") } ?: uri.lastPathSegment?.substringAfterLast('/')).orEmpty()
                editUrl.setText("📄 " + ime.ifBlank { url })
                return
            }
            val host = uri.host ?: url
            val cleanHost = host.removePrefix("www.")
            val path = uri.path ?: ""
            val display = if (path.length > 1 && path != "/") "$cleanHost$path" else cleanHost
            editUrl.setText(display)
        } catch (_: Exception) {
            editUrl.setText(url)
        }
    }

    private fun performNavigation(input: String) {
        if (input.isEmpty()) return

        val rawUrl = when {
            input.startsWith("http://", ignoreCase = true) -> {
                val withoutScheme = input.substring(7)
                val host = withoutScheme.substringBefore('/').substringBefore(':').lowercase()
                val isLocal = host == "localhost" || host == "127.0.0.1" ||
                              host.startsWith("192.168.") || host.startsWith("10.") ||
                              host.startsWith("172.16.") || host.startsWith("172.17.") ||
                              host.startsWith("172.18.") || host.startsWith("172.19.") ||
                              host.startsWith("172.2") || host.startsWith("172.30.") || host.startsWith("172.31.")
                if (isLocal) input else "https://$withoutScheme"
            }
            input.startsWith("https://", ignoreCase = true) || input.startsWith("file://", ignoreCase = true) -> {
                input
            }
            input.contains(".") && !input.contains(" ") -> {
                "https://$input"
            }
            else -> {
                PreferencesManager.buildSearchUrl(this, input)
            }
        }

        val finalUrl = UrlSanitizer.sanitize(rawUrl)
        openUrlInBrowser(finalUrl)
    }

    private fun setupTopButtons() {
        btnHome.setOnClickListener {
            openUrlInBrowser("file:///android_asset/brave_home.html")
        }

        btnReload.setOnClickListener {
            val activeTab = tabManager.getActiveTab()
            activeTab?.webView?.reload()
        }

        btnStar.setOnClickListener {
            val activeTab = tabManager.getActiveTab()
            val curUrl = activeTab?.url ?: ""
            if (curUrl.isNotEmpty() && !curUrl.startsWith("file:///android_asset/")) {
                val isBm = repository.isBookmarked(curUrl)
                if (isBm) {
                    repository.removeBookmark(curUrl)
                    btnStar.text = "☆"
                    Toast.makeText(this, getString(R.string.toast_bookmark_removed), Toast.LENGTH_SHORT).show()
                } else {
                    repository.addBookmark(activeTab?.loadedWebView?.title ?: activeTab?.title ?: "Zaznamek", curUrl)
                    btnStar.text = "⭐"
                    Toast.makeText(this, getString(R.string.toast_bookmark_added), Toast.LENGTH_SHORT).show()
                }
            } else {
                showBookmarksDialog()
            }
        }

        btnStar.setOnLongClickListener {
            showBookmarksDialog()
            true
        }

        btnAddTab.setOnClickListener {
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
        }

        btnTabCount.setOnClickListener {
            toggleTabSwitcher()
        }
        btnTabCount.setOnLongClickListener {
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
            editUrl.requestFocus()
            showKeyboard()
            true
        }

        btnMenu.setOnClickListener {
            showMobileMenu()
        }

        // Tab switcher buttons
        btnNewTabInSwitcher.setOnClickListener {
            tabSwitcherOverlay.visibility = View.GONE
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
        }

        btnCloseTabsSwitcher.setOnClickListener {
            tabSwitcherOverlay.visibility = View.GONE
        }

        btnCloseAllTabs.setOnClickListener {
            tabManager.closeAllTabs(this)
            tabSwitcherOverlay.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 80

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX > 0) {
                        tabManager.switchToPrevTab()
                        Toast.makeText(this@MainActivity, getString(R.string.toast_prev_tab), Toast.LENGTH_SHORT).show()
                    } else {
                        tabManager.switchToNextTab()
                        Toast.makeText(this@MainActivity, getString(R.string.toast_next_tab), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        })

        omniboxContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun toggleTabSwitcher() {
        if (tabSwitcherOverlay.visibility == View.VISIBLE) {
            tabSwitcherOverlay.visibility = View.GONE
        } else {
            renderTabsGrid()
            tabSwitcherOverlay.visibility = View.VISIBLE
        }
    }

    private fun renderTabsGrid() {
        val allTabs = tabManager.getAllTabs()
        val activeId = tabManager.getActiveTab()?.id

        tabsGridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = allTabs.size
            override fun getItem(position: Int): Any = allTabs[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tab = allTabs[position]
                val view = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_tab_card, parent, false)

                val tvTitle = view.findViewById<TextView>(R.id.tvTabTitle)
                val tvUrl = view.findViewById<TextView>(R.id.tvTabUrl)
                val tvActiveBadge = view.findViewById<TextView>(R.id.tvActiveBadge)
                val btnClose = view.findViewById<TextView>(R.id.btnTabClose)
                val cardRoot = view.findViewById<RelativeLayout>(R.id.tabCardRoot)

                tvTitle.text = tab.title.ifEmpty { "Zavihek ${position + 1}" }
                tvUrl.text = tab.url
                
                val isActive = (tab.id == activeId)
                cardRoot.setBackgroundResource(if (isActive) R.drawable.bg_tab_card_active else R.drawable.bg_tab_card)
                tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE

                view.setOnClickListener {
                    tabManager.switchTab(tab.id)
                    tabSwitcherOverlay.visibility = View.GONE
                }

                btnClose.setOnClickListener {
                    tabManager.closeTab(this@MainActivity, tab.id)
                    renderTabsGrid()
                }

                return view
            }
        }
    }

    // ------------------------------------------------------------------
    // Safeer Cast — pošiljanje trenutne strani na televizor v domačem omrežju
    // ------------------------------------------------------------------

    /** Naslov vozlišča; shranjen, da ga ni treba vnašati vsakič. */
    /** Naslov vozlišča, če ga je uporabnik nastavil. Privzetega ni -- Hub je nadgradnja. */
    private fun castHubUrl(): String =
        getSharedPreferences("safeer_cast_prefs", MODE_PRIVATE).getString("hub_url", "") ?: ""

    private fun saveCastHubUrl(url: String) {
        getSharedPreferences("safeer_cast_prefs", MODE_PRIVATE).edit().putString("hub_url", url).apply()
    }

    /** Zeton Safeer Controla; z njim odjemalec dobi enokratno vstopnico za vozlisce. */
    private fun castToken(): String? =
        getSharedPreferences("safeer_cast_prefs", MODE_PRIVATE).getString("control_token", null)

    /** Pot do vstopnice, kot jo je objavil Hub. */
    private fun castTicketPath(): String =
        getSharedPreferences("safeer_cast_prefs", MODE_PRIVATE)
            .getString("hub_ticket_path", "/cast/ticket") ?: "/cast/ticket"

    private fun saveCastToken(token: String) {
        // Prazen vnos pomeni "zetona ni", ne "zeton je prazen niz": s praznim nizom bi stran
        // Safeer Linka mislila, da je naprava ze seznanjena, in bi se zaman povezovala.
        val ocisceno = token.trim()
        val urejanje = getSharedPreferences("safeer_cast_prefs", MODE_PRIVATE).edit()
        if (ocisceno.isEmpty()) urejanje.remove("control_token") else urejanje.putString("control_token", ocisceno)
        urejanje.apply()
    }

    /** Brez odgovora vozlišča: naj uporabnik vnese njegov naslov (IP je viden v Safeer Cast). */
    private fun askForCastHub() {
        val naslov = EditText(this)
        naslov.setText(castHubUrl())
        naslov.hint = "ws://naslov:8990/cast/ws"

        val zeton = EditText(this)
        zeton.setText(castToken() ?: "")
        zeton.hint = getString(R.string.cast_token_hint)

        val stolpec = android.widget.LinearLayout(this)
        stolpec.orientation = android.widget.LinearLayout.VERTICAL
        val rob = (16 * resources.displayMetrics.density).toInt()
        stolpec.setPadding(rob, rob, rob, 0)
        stolpec.addView(naslov)
        stolpec.addView(zeton)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cast_none_found))
            .setView(stolpec)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val noviNaslov = naslov.text.toString().trim()
                if (noviNaslov.isNotBlank()) saveCastHubUrl(noviNaslov)
                saveCastToken(zeton.text.toString())
                if (noviNaslov.isNotBlank()) odpriSafeerLink()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Seznanitev s Safeer Hubom iz menija (zasilna pot; obicajno tece prek zaslona Safeer Link).
     *
     * Pri novem Hubu kodo pokaze gostitelj, tu jo uporabnik vtipka. Pri starejsem Hubu ostane
     * stari postopek: kodo pokazemo tu, potrdi se v Safeer Controlu. Ce ne uspe, ostane vse,
     * kot je bilo -- brskalnik deluje naprej.
     */
    private fun seznaniSHubom() {
        var okno: AlertDialog? = null
        com.safeer.mobile.browser.cast.HubPairing.pair(
            this, castHubUrl(),
            com.safeer.mobile.browser.cast.HubKrmilnik.lastniId(),
            "Safeer (" + android.os.Build.MODEL + ")",
            { nacin, koda ->
                okno?.dismiss()
                okno = if (nacin == com.safeer.mobile.browser.cast.HubPairing.NACIN_KODA_NA_GOSTITELJU) {
                    val vnos = EditText(this).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        hint = "------"
                        setPadding(48, 32, 48, 32)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(I18n.t(this, "pair_title"))
                        .setMessage(I18n.t(this, "pair_enter_code"))
                        .setView(vnos)
                        .setPositiveButton(I18n.t(this, "pair_connect")) { _, _ ->
                            com.safeer.mobile.browser.cast.HubPairing.potrdiKodo(
                                this, vnos.text.toString(),
                                com.safeer.mobile.browser.cast.HubKrmilnik.lastniId()
                            ) { uspelo, _ ->
                                if (!uspelo) {
                                    Toast.makeText(this, I18n.t(this, "pair_wrong_code"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            com.safeer.mobile.browser.cast.HubPairing.prekini()
                        }
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(I18n.t(this, "pair_title"))
                        .setMessage(I18n.t(this, "pair_confirm_code") + "\n\n" + koda)
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            },
            { uspelo ->
                okno?.dismiss()
                if (uspelo) {
                    Toast.makeText(this, I18n.t(this, "pair_connected"), Toast.LENGTH_SHORT).show()
                }
            })
    }

    // ------------------------------------------------------------------
    // Safeer Link — naprave, pošiljanje in sinhronizacija na enem mestu
    // ------------------------------------------------------------------

    private var linkOkno: Dialog? = null

    // ---- Safeer Link: koda za seznanitev, ko stran Linka ni odprta ----
    private var kodaOkno: AlertDialog? = null

    /** Besedilo z druge naprave, prejeto v ozadju, ko je brskalnik v ospredju: okno kot na strani Linka. */
    private fun pokaziPrejetoBesedilo(od: String, besedilo: String) {
        try {
            val cisto = besedilo.trim()
            val jePovezava = (cisto.startsWith("http://") || cisto.startsWith("https://")) && !cisto.contains(Regex("\\s"))
            val okno = AlertDialog.Builder(this)
                .setTitle("💬 " + I18n.t(this, "share_received_text").replace("{ime}", od))
                .setMessage(besedilo.take(4000))
                .setNegativeButton(android.R.string.ok, null)
                .setNeutralButton(I18n.t(this, "share_copy")) { _, _ ->
                    try {
                        val odlozisce = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        odlozisce.setPrimaryClip(android.content.ClipData.newPlainText("Safeer Link", besedilo))
                        Toast.makeText(this, I18n.t(this, "share_copied"), Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) { }
                }
            if (jePovezava) {
                okno.setPositiveButton(I18n.t(this, "share_open_link")) { _, _ -> openUrlInBrowser(cisto) }
            }
            okno.show()
        } catch (e: Exception) {
            try { Toast.makeText(this, "💬 $od: " + besedilo.take(200), Toast.LENGTH_LONG).show() } catch (_: Exception) { }
        }
    }

    /**
     * Druga naprava se zeli povezati na Hub tega telefona: kodo pokazemo takoj, tudi ce
     * uporabnik bere stran. Ce je odprta stran Safeer Linka, kodo pokaze ona.
     */
    private fun pokaziKodoZaSeznanitev() {
        try {
            if (linkOkno != null) {
                kodaOkno?.let { if (it.isShowing) it.dismiss() }
                kodaOkno = null
                return
            }
            val p = com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik?.cakajocePrijave()?.lastOrNull()
            if (p == null) {
                kodaOkno?.let { if (it.isShowing) it.dismiss() }
                kodaOkno = null
                return
            }
            val obstojece = kodaOkno
            if (obstojece != null && obstojece.isShowing && obstojece.window?.decorView?.tag == p.pairId) return
            obstojece?.let { if (it.isShowing) it.dismiss() }
            val koda = p.pin.map { it.toString() }.joinToString("  ")
            val vsebina = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 24, 48, 8)
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = I18n.t(this@MainActivity, "pair_code_body").replace("{ime}", p.ime)
                    textSize = 16f
                })
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = koda
                    textSize = 40f
                    setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 24, 0, 8)
                })
            }
            val okno = AlertDialog.Builder(this)
                .setTitle(I18n.t(this, "pair_code_title"))
                .setView(vsebina)
                .setNegativeButton(I18n.t(this, "pair_code_reject")) { _, _ ->
                    try { com.safeer.mobile.browser.cast.HubKrmilnik.usmerjevalnik?.zavrniPrijavo(p.pairId) } catch (_: Exception) { }
                }
                .setPositiveButton(android.R.string.ok, null)
                .create()
            okno.setOnDismissListener { if (kodaOkno === okno) kodaOkno = null }
            kodaOkno = okno
            okno.show()
            try { okno.window?.decorView?.tag = p.pairId } catch (_: Exception) { }
        } catch (e: Exception) {
            android.util.Log.w("SafeerLink", "Kode za seznanitev ni bilo mogoce pokazati: " + e.message)
        }
    }
    private var linkMost: com.safeer.mobile.browser.link.LinkMost? = null
    private var linkDatotekaCilj: String = ""
    private var linkZaslonCilj: String = ""
    private var linkZaslonIme: String = ""

    /** Sistemski izbirnik datotek za posiljanje prek Safeer Linka; izbira se vrne v onActivityResult. */
    private fun izberiDatotekoZaLink(cilj: String) {
        linkDatotekaCilj = cilj
        try {
            val namera = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(namera, REQ_CODE_LINK_DATOTEKA)
        } catch (e: Exception) {
            linkDatotekaCilj = ""
            Toast.makeText(this, getString(R.string.link_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Sistemsko vprasanje za zajem zaslona; odgovor pride v onActivityResult. */
    private fun zahtevajZajemZaslona(cilj: String, ime: String) {
        linkZaslonCilj = cilj
        linkZaslonIme = ime
        try {
            val upravitelj = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            // Od Androida 14 lahko vprasamo samo za cel zaslon: uporabnik dobi eno vprasanje,
            // ne izbire med aplikacijami, ki bi ga zmedla.
            // (Prek odseva, ker je android.jar v orodjih starejsi od API 34.)
            val namera = try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val razredNastavitve = Class.forName("android.media.projection.MediaProjectionConfig")
                    val nastavitev = razredNastavitve.getMethod("createConfigForDefaultDisplay").invoke(null)
                    upravitelj.javaClass.getMethod("createScreenCaptureIntent", razredNastavitve)
                        .invoke(upravitelj, nastavitev) as Intent
                } else upravitelj.createScreenCaptureIntent()
            } catch (_: Throwable) {
                upravitelj.createScreenCaptureIntent()
            }
            startActivityForResult(namera, REQ_CODE_LINK_ZASLON)
        } catch (e: Exception) {
            linkZaslonCilj = ""
            linkZaslonIme = ""
            linkMost?.zajemZavrnjen(cilj)
        }
    }

    /**
     * Odpre Safeer Link v svojem pogledu.
     *
     * Pogled je locen od zavihkov in nalozi samo stran iz aplikacije, zato most, ki ga
     * ima, ni dosegljiv nobeni spletni strani. Zaradi istega razloga pogled ne sme
     * nikamor navigirati: vse, kar ni domaca stran, zavrnemo.
     */
    private fun odpriSafeerLink() {
        try {
            val pogled = android.webkit.WebView(this)
            pogled.settings.javaScriptEnabled = true
            pogled.settings.domStorageEnabled = true
            pogled.settings.allowFileAccess = false
            pogled.settings.allowContentAccess = false
            pogled.settings.setSupportMultipleWindows(false)
            pogled.settings.javaScriptCanOpenWindowsAutomatically = false
            pogled.setBackgroundColor(android.graphics.Color.parseColor("#0b1017"))

            val okno = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            okno.setContentView(pogled)

            // Naslov strani preberemo tu, na glavni niti. Most ga bo vprasal z druge
            // niti, kjer WebView svojih metod ne da brati (url bi bil null).
            val zavihek = tabManager.getActiveTab()
            // Odprt PDF ima notranji naslov pregledovalnika; drugim napravam posljemo izvornega.
            val naslovStrani = PdfPregledovalnik.javniNaslov(zavihek?.loadedWebView?.url ?: zavihek?.url ?: "")
            val imeStrani = zavihek?.loadedWebView?.title ?: zavihek?.title

            val most = com.safeer.mobile.browser.link.LinkMost(
                this,
                pogled,
                { Pair(naslovStrani, imeStrani) },
                { okno.dismiss() },
                { naslov -> openUrlInBrowser(naslov) },
                { cilj -> izberiDatotekoZaLink(cilj) },
                { cilj, ime -> zahtevajZajemZaslona(cilj, ime) }
            )
            linkMost = most
            pogled.addJavascriptInterface(most, "SafeerLink")

            pogled.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(view: android.webkit.WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    // This local dialog can share a renderer with browser tabs.
                    okno.dismiss()
                    return true
                }

                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    // Ta pogled ne sme nikamor: naslove odpira brskalnik, ne Link.
                    val naslov = request?.url?.toString() ?: return true
                    if (naslov.startsWith("file:///android_asset/link/")) return false
                    okno.dismiss()
                    if (naslov.startsWith("http://") || naslov.startsWith("https://")) {
                        openUrlInBrowser(naslov)
                    }
                    return true
                }
            }

            okno.setOnDismissListener {
                try { most.pospravi() } catch (_: Exception) {}
                (pogled.parent as? ViewGroup)?.removeView(pogled)
                try { pogled.destroy() } catch (_: Exception) {}
                linkOkno = null
                linkMost = null
            }

            pogled.loadUrl("file:///android_asset/link/index.html")
            linkOkno = okno
            okno.show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.link_open_failed), Toast.LENGTH_SHORT).show()
            android.util.Log.w("SafeerLink", "Zaslon se ni odprl: " + e.message)
        }
    }

    private fun showMobileMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_mobile_menu)
        // Keep the quick actions visible and the complete options list scrollable on small screens.
        dialog.findViewById<View>(R.id.menuOptionsScroll).layoutParams.height =
            minOf((resources.displayMetrics.heightPixels * 0.60f).toInt(), (520 * resources.displayMetrics.density).toInt())
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.BOTTOM)

        val activeTab = tabManager.getActiveTab()
        val wv = activeTab?.webView

        val menuBtnBack = dialog.findViewById<ImageButton>(R.id.menuBtnBack)
        val menuBtnForward = dialog.findViewById<ImageButton>(R.id.menuBtnForward)
        val menuBtnReload = dialog.findViewById<ImageButton>(R.id.menuBtnReload)
        val menuBtnStar = dialog.findViewById<ImageButton>(R.id.menuBtnStar)
        val menuBtnShare = dialog.findViewById<ImageButton>(R.id.menuBtnShare)

        menuBtnBack.setOnClickListener {
            if (wv?.canGoBack() == true) wv.goBack()
            dialog.dismiss()
        }

        menuBtnForward.setOnClickListener {
            if (wv?.canGoForward() == true) wv.goForward()
            dialog.dismiss()
        }

        menuBtnBack.isEnabled = wv?.canGoBack() == true
        menuBtnForward.isEnabled = wv?.canGoForward() == true
        // Ikona brez besedila: onemogocen gumb pokazemo zbledelo, da je jasno, da nima kam.
        menuBtnBack.alpha = if (menuBtnBack.isEnabled) 1f else 0.35f
        menuBtnForward.alpha = if (menuBtnForward.isEnabled) 1f else 0.35f
        menuBtnReload.setImageResource(if (wv != null && wv.progress < 100) R.drawable.ic_m_close else R.drawable.ic_m_reload)
        menuBtnReload.setOnClickListener {
            if (wv != null && wv.progress < 100) wv.stopLoading() else wv?.reload()
            dialog.dismiss()
        }

        val curUrl = activeTab?.url ?: ""
        val isBm = repository.isBookmarked(curUrl)
        menuBtnStar.setImageResource(if (isBm) R.drawable.ic_m_star_filled else R.drawable.ic_m_star)
        menuBtnStar.setOnClickListener {
            if (isBm) {
                repository.removeBookmark(curUrl)
                Toast.makeText(this, getString(R.string.toast_bookmark_removed), Toast.LENGTH_SHORT).show()
            } else {
                repository.addBookmark(wv?.title ?: "Zaznamek", curUrl)
                Toast.makeText(this, getString(R.string.toast_bookmark_added), Toast.LENGTH_SHORT).show()
            }
            updateStarState(curUrl)
            dialog.dismiss()
        }

        menuBtnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, curUrl)
            }
            startActivity(Intent.createChooser(shareIntent, "Deli stran"))
            dialog.dismiss()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuHome).setOnClickListener {
            openUrlInBrowser("file:///android_asset/brave_home.html")
            dialog.dismiss()
        }
        val defaultRow = dialog.findViewById<LinearLayout>(R.id.rowMenuDefaultBrowser)
        if (isDefaultBrowser()) {
            dialog.findViewById<TextView>(R.id.labelMenuDefaultBrowser).setText(R.string.default_browser_active)
            defaultRow.isEnabled = false
        } else defaultRow.setOnClickListener {
            dialog.dismiss()
            requestDefaultBrowser()
        }
        dialog.findViewById<LinearLayout>(R.id.rowMenuNewTab).setOnClickListener {
            tabManager.createTab(this, "file:///android_asset/brave_home.html", true)
            dialog.dismiss()
            editUrl.requestFocus()
            showKeyboard()
        }

        // Safeer Link je vedno na voljo: brez sredisca stran sama pove, kako ga vklopis,
        // brskalnik pa dela naprej kot doslej.
        val vrsticaLink = dialog.findViewById<LinearLayout>(R.id.rowMenuSafeerLink)
        vrsticaLink.setOnClickListener {
            dialog.dismiss()
            odpriSafeerLink()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuBookmarks).setOnClickListener {
            dialog.dismiss()
            showBookmarksDialog()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuDownloads).setOnClickListener {
            dialog.dismiss()
            // Seznam prenosov v brskalniku (ne sistemska aplikacija, iz katere se ni bilo mogoce vrniti).
            PrenosiUi.show(this) { url -> tabManager.createTab(this, url, true) }
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuHistory).setOnClickListener {
            dialog.dismiss()
            showHistoryDialog()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuFindInPage).setOnClickListener {
            dialog.dismiss()
            showFindInPage()
        }

        val cbDesktop = dialog.findViewById<CheckBox>(R.id.cbDesktopSite)
        cbDesktop.isChecked = activeTab?.isDesktop ?: false
        dialog.findViewById<LinearLayout>(R.id.rowMenuDesktopSite).setOnClickListener {
            val nextState = !cbDesktop.isChecked
            cbDesktop.isChecked = nextState
            activeTab?.isDesktop = nextState
            tabManager.scheduleSave()
            wv?.isDesktopMode = nextState
            wv?.reload()
            dialog.dismiss()
        }

        // Stanje scita: zeleno, s stevilom groženj, ki jih je ta zagon ze ustavil.
        val ustavljenih = ThreatBlockEngine.totalBlockedThreats.get()
        dialog.findViewById<TextView>(R.id.tvThreatCountBadge).text =
            if (ustavljenih > 0) getString(R.string.menu_shield_active_count, ustavljenih) else getString(R.string.menu_shield_active)
        // Threat Shield Status Dialog
        dialog.findViewById<LinearLayout>(R.id.rowMenuThreatStats).setOnClickListener {
            dialog.dismiss()
            showThreatStatsDialog()
        }

        val cbAdBlock = dialog.findViewById<CheckBox>(R.id.cbAdBlock)
        cbAdBlock.isChecked = AdBlockEngine.isEnabled
        dialog.findViewById<LinearLayout>(R.id.rowMenuAdBlock).setOnClickListener {
            AdBlockEngine.isEnabled = !AdBlockEngine.isEnabled
            cbAdBlock.isChecked = AdBlockEngine.isEnabled
            PreferencesManager.setAdBlockEnabled(this, AdBlockEngine.isEnabled)
            Toast.makeText(
                this,
                if (AdBlockEngine.isEnabled) I18n.t(this, "toast_adblock_on") else I18n.t(this, "toast_adblock_off"),
                Toast.LENGTH_SHORT
            ).show()
            wv?.reload()
            dialog.dismiss()
        }

        val cbPopupBlock = dialog.findViewById<CheckBox>(R.id.cbPopupBlock)
        cbPopupBlock.isChecked = PreferencesManager.isPopupBlockEnabled(this)
        dialog.findViewById<LinearLayout>(R.id.rowMenuPopupBlock).setOnClickListener {
            val vklopljeno = !PreferencesManager.isPopupBlockEnabled(this)
            PreferencesManager.setPopupBlockEnabled(this, vklopljeno)
            cbPopupBlock.isChecked = vklopljeno
            Toast.makeText(
                this,
                if (vklopljeno) getString(R.string.toast_popup_block_on) else getString(R.string.toast_popup_block_off),
                Toast.LENGTH_SHORT
            ).show()
            wv?.reload()
            dialog.dismiss()
        }

        val cbDark = dialog.findViewById<CheckBox>(R.id.cbDarkMode)
        cbDark.isChecked = isDarkModeActive
        dialog.findViewById<LinearLayout>(R.id.rowMenuDarkMode).setOnClickListener {
            isDarkModeActive = !isDarkModeActive
            cbDark.isChecked = isDarkModeActive
            PreferencesManager.setDarkModeEnabled(this, isDarkModeActive)
            tabManager.getAllTabs().forEach { t ->
                t.loadedWebView?.applyDarkMode(isDarkModeActive)
            }
            Toast.makeText(
                this,
                if (isDarkModeActive) I18n.t(this, "toast_dark_on") else I18n.t(this, "toast_dark_off"),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialog.findViewById<LinearLayout>(R.id.rowMenuSettings)?.setOnClickListener {
            dialog.dismiss()
            showSettingsDialog()
        }

        // Daljinec z druge naprave: tipke gredo v odprti meni (svoje okno), ne v dejavnost.
        meniDaljinca = dialog
        dialog.setOnDismissListener { if (meniDaljinca === dialog) meniDaljinca = null }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val engines = arrayOf("Google", "DuckDuckGo", "Brave Search")
        val engineKeys = arrayOf(
            PreferencesManager.SEARCH_GOOGLE,
            PreferencesManager.SEARCH_DUCKDUCKGO,
            PreferencesManager.SEARCH_BRAVE
        )
        val currentEngine = PreferencesManager.getSearchEngine(this)
        val selectedEngineIndex = engineKeys.indexOf(currentEngine).let { if (it >= 0) it else 0 }

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 24)
        }

        // 0. Jezik vmesnika / Interface Language
        val tvLangTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "settings_language")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvLangTitle)

        val langKeys = arrayOf("auto", "sl", "en", "de", "es", "fr", "it")
        val langNames = arrayOf(
            I18n.t(this, "lang_auto"),
            "Slovenščina",
            "English",
            "Deutsch",
            "Español",
            "Français",
            "Italiano"
        )
        val currentLang = PreferencesManager.getLanguage(this)
        val selectedLangIdx = langKeys.indexOf(currentLang).let { if (it >= 0) it else 0 }

        val rgLang = RadioGroup(this)
        langKeys.forEachIndexed { idx, _ ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = langNames[idx]
                isChecked = (idx == selectedLangIdx)
                setTextColor(Color.WHITE)
            }
            rgLang.addView(rb)
        }
        view.addView(rgLang)

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 1. Iskalnik
        val tvSearchTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "settings_search_engine")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvSearchTitle)

        val rgEngine = RadioGroup(this)
        engineKeys.forEachIndexed { idx, _ ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = engines[idx]
                isChecked = (idx == selectedEngineIndex)
                setTextColor(Color.WHITE)
            }
            rgEngine.addView(rb)
        }
        view.addView(rgEngine)

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 2. Izgled brskalnika (Browser Appearance & Styling)
        val tvAppearanceTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "appearance_title")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvAppearanceTitle)

        // 2.1. Izbira teme brskalnika
        val tvThemeLabel = TextView(this).apply {
            text = I18n.t(this@MainActivity, "theme_label")
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 4)
        }
        view.addView(tvThemeLabel)

        val themeKeys = arrayOf("dark_slate", "amoled", "midnight", "emerald")
        // Imena tem razen prve so lastna imena in se ne prevajajo.
        val themeLabels = arrayOf(
            I18n.t(this, "theme_dark_slate"),
            "🖤 AMOLED", "🌌 Midnight", "🍃 Emerald")
        val currentTheme = PreferencesManager.getTheme(this)
        val selectedThemeIdx = themeKeys.indexOf(currentTheme).let { if (it >= 0) it else 0 }

        // Navpicno: stiri imena tem v eni vrstici se na telefonu ne izidejo - zadnji dve sta
        // se stisnili v navpicno, odrezano besedilo.
        val rgTheme = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        themeKeys.forEachIndexed { idx, _ ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = themeLabels[idx]
                isChecked = (idx == selectedThemeIdx)
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, 6, 0, 6)
            }
            rgTheme.addView(rb)
        }
        view.addView(rgTheme)

        // 2.2. Temni način
        val cbDarkMode = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "dark_mode")
            isChecked = PreferencesManager.isDarkModeEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 0)
        }
        view.addView(cbDarkMode)

        // 2.3. Izbor pisave
        val tvFontLabel = TextView(this).apply {
            text = if (currentLang == "sl") "Izbor pisave:" else "Font Style:"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 10, 0, 4)
        }
        view.addView(tvFontLabel)

        val fontKeys = arrayOf("system", "sans", "serif", "monospace")
        val fontLabels = if (currentLang == "sl") {
            arrayOf("🖥️ Sistemska", "🔤 Sans-serif", "📖 Serif", "💻 Monospace")
        } else {
            arrayOf("🖥️ System", "🔤 Sans-serif", "📖 Serif", "💻 Monospace")
        }
        val currentFont = PreferencesManager.getFontFamily(this)
        val selectedFontIdx = fontKeys.indexOf(currentFont).let { if (it >= 0) it else 0 }

        val rgFont = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fontKeys.forEachIndexed { idx, _ ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = fontLabels[idx]
                isChecked = (idx == selectedFontIdx)
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 0, 14, 0)
            }
            rgFont.addView(rb)
        }
        view.addView(rgFont)

        // 2.4. Velikost pisave strani (Povečava)
        val tvZoomLabel = TextView(this).apply {
            text = if (currentLang == "sl") "Velikost pisave strani (Povečava):" else "Page Text Size / Zoom:"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 10, 0, 6)
        }
        view.addView(tvZoomLabel)

        val zoomKeys = intArrayOf(85, 100, 115, 130)
        val zoomLabels = arrayOf("85%", "100%", "115%", "130%")
        val currentZoom = PreferencesManager.getTextZoom(this)
        val selectedZoomIdx = zoomKeys.indexOf(currentZoom).let { if (it >= 0) it else 1 }

        val rgZoom = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        zoomKeys.forEachIndexed { idx, _ ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = zoomLabels[idx]
                isChecked = (idx == selectedZoomIdx)
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 0, 20, 0)
            }
            rgZoom.addView(rb)
        }
        view.addView(rgZoom)

        // 2.5. Brave način
        val cbBraveMode = CheckBox(this).apply {
            text = if (currentLang == "sl") "🦁 Brave način (Brave Shield začetna stran in statistika)" else "🦁 Brave Mode (Brave Shield dashboard & widgets)"
            isChecked = PreferencesManager.isBraveModeEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 0)
        }
        view.addView(cbBraveMode)

        val tvBraveDesc = TextView(this).apply {
            text = if (currentLang == "sl") "    Ob izklopu začetna stran deluje v čistem minimalističnem načinu zgolj z iskalnikom." else "    When disabled, home page operates in minimalist mode with search bar only."
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 6)
        }
        view.addView(tvBraveDesc)

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 3. Zasebnost & Varnost
        val tvShieldTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "settings_privacy_security")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvShieldTitle)

        val cbAdBlock = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "adblock_shield")
            isChecked = PreferencesManager.isAdBlockEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
        }
        view.addView(cbAdBlock)

        val cbThirdPartyCookies = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "third_party_cookies")
            isChecked = PreferencesManager.isThirdPartyCookiesEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
        }
        view.addView(cbThirdPartyCookies)
        view.addView(Button(this).apply {
            setText(if (isDefaultBrowser()) R.string.default_browser_active else R.string.menu_default_browser)
            setOnClickListener { requestDefaultBrowser() }
        })

        val cbJs = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "enable_javascript")
            isChecked = PreferencesManager.isJavaScriptEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
        }
        view.addView(cbJs)

        val cbAdguard = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "adguard_protection")
            isChecked = PreferencesManager.isAdguardProtectionEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        view.addView(cbAdguard)

        val tvAdguardDesc = TextView(this).apply {
            text = I18n.t(this@MainActivity, "adguard_desc")
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(64, 0, 0, 8)
        }
        view.addView(tvAdguardDesc)

        val cbSponsorBlock = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "sponsorblock")
            isChecked = PreferencesManager.isSponsorBlockEnabled(this@MainActivity)
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        view.addView(cbSponsorBlock)
        view.addView(TextView(this).apply {
            text = I18n.t(this@MainActivity, "sponsorblock_desc")
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(64, 0, 0, 8)
        })

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 2.1. Šifriran DNS (DoH) za zaščito pred cenzuro
        val tvDohTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "doh_title")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvDohTitle)

        val dohNames = arrayOf(
            I18n.t(this, "doh_quad9"),
            I18n.t(this, "doh_adguard"),
            I18n.t(this, "doh_cloudflare"),
            I18n.t(this, "doh_google"),
            I18n.t(this, "doh_custom"),
            I18n.t(this, "doh_disabled")
        )
        val dohKeys = arrayOf("quad9", "adguard", "cloudflare", "google", "custom", "disabled")
        var currentDoh = PreferencesManager.getDohProvider(this)
        if (!PreferencesManager.isDohEnabled(this)) currentDoh = "disabled"
        val selectedDohIdx = dohKeys.indexOf(currentDoh).let { if (it >= 0) it else dohKeys.indexOf("cloudflare") }

        val editCustomDoh = EditText(this).apply {
            hint = I18n.t(this@MainActivity, "custom_doh_hint")
            setText(PreferencesManager.getCustomDohUrl(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            visibility = if (currentDoh == "custom") View.VISIBLE else View.GONE
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#1e293b"))
        }

        val rgDoh = RadioGroup(this)
        dohKeys.forEachIndexed { idx, key ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = dohNames[idx]
                isChecked = (idx == selectedDohIdx)
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && key == "custom") {
                        editCustomDoh.visibility = View.VISIBLE
                    } else if (isChecked) {
                        editCustomDoh.visibility = View.GONE
                    }
                }
            }
            rgDoh.addView(rb)
        }
        view.addView(rgDoh)
        view.addView(editCustomDoh)

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 2.2. Šifriran tunel / Proxy
        val tvProxyTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "proxy_title")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvProxyTitle)

        val proxyNames = arrayOf(
            I18n.t(this, "proxy_disabled"),
            I18n.t(this, "proxy_custom")
        )
        val proxyKeys = arrayOf("disabled", "custom")
        val currentProxy = PreferencesManager.getSecureProxyMode(this)
        val selectedProxyIdx = proxyKeys.indexOf(currentProxy).let { if (it >= 0) it else 0 }

        val editCustomProxy = EditText(this).apply {
            hint = I18n.t(this@MainActivity, "custom_proxy_hint")
            setText(PreferencesManager.getSecureProxyUrl(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            visibility = if (currentProxy == "custom") View.VISIBLE else View.GONE
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#1e293b"))
        }

        val rgProxy = RadioGroup(this)
        proxyKeys.forEachIndexed { idx, key ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = proxyNames[idx]
                isChecked = (idx == selectedProxyIdx)
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && key == "custom") {
                        editCustomProxy.visibility = View.VISIBLE
                    } else if (isChecked) {
                        editCustomProxy.visibility = View.GONE
                    }
                }
            }
            rgProxy.addView(rb)
        }
        view.addView(rgProxy)
        view.addView(editCustomProxy)

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 2.3. Mapa za prenose (velja tudi za datoteke, prejete prek Safeer Linka)
        val tvPrenosiTitle = TextView(this).apply {
            text = I18n.t(this@MainActivity, "downloads_title")
            textSize = 15f
            setTextColor(Color.parseColor("#00d2ff"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        view.addView(tvPrenosiTitle)

        val tvPrenosiDesc = TextView(this).apply {
            text = I18n.t(this@MainActivity, "downloads_desc")
            textSize = 12f
            setTextColor(Color.parseColor("#94a3b8"))
            setPadding(0, 0, 0, 12)
        }
        view.addView(tvPrenosiDesc)

        val prenosiKeys = arrayOf(
            PrenosiMapa.PRENOSI,
            PrenosiMapa.DOKUMENTI,
            PrenosiMapa.SLIKE,
            PrenosiMapa.GLASBA,
            PrenosiMapa.FILMI
        )
        val prenosiNames = arrayOf(
            I18n.t(this, "dir_downloads"),
            I18n.t(this, "dir_documents"),
            I18n.t(this, "dir_pictures"),
            I18n.t(this, "dir_music"),
            I18n.t(this, "dir_movies")
        )
        val trenutnaPrenosiMapa = PreferencesManager.getDownloadDir(this)
        val selectedPrenosiIdx = prenosiKeys.indexOf(trenutnaPrenosiMapa).let { if (it >= 0) it else 0 }

        val tvPrenosiCurrent = TextView(this).apply {
            text = I18n.t(this@MainActivity, "downloads_current").format(PrenosiMapa.opis(this@MainActivity))
            textSize = 12f
            setTextColor(Color.parseColor("#22c55e"))
            setPadding(0, 8, 0, 0)
        }

        val cbPrenosiPodmapa = CheckBox(this).apply {
            text = I18n.t(this@MainActivity, "downloads_subfolder")
            isChecked = PreferencesManager.getDownloadSubfolder(this@MainActivity).isNotEmpty()
            setTextColor(Color.WHITE)
        }

        val rgPrenosi = RadioGroup(this)
        prenosiKeys.forEachIndexed { idx, _ ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = prenosiNames[idx]
                isChecked = (idx == selectedPrenosiIdx)
                setTextColor(Color.WHITE)
            }
            rgPrenosi.addView(rb)
        }
        view.addView(rgPrenosi)
        view.addView(cbPrenosiPodmapa)
        view.addView(tvPrenosiCurrent)

        // Ločilna črta
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // 3. Počisti podatke
        val btnClearData = Button(this).apply {
            text = I18n.t(this@MainActivity, "btn_clear_data")
            setBackgroundResource(R.drawable.bg_mobile_icon_button)
            setTextColor(Color.parseColor("#ff5555"))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(I18n.t(this@MainActivity, "clear_data_dialog_title"))
                    .setMessage(I18n.t(this@MainActivity, "clear_data_dialog_msg"))
                    .setPositiveButton(I18n.t(this@MainActivity, "btn_clear")) { _, _ ->
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        tabManager.getAllTabs().forEach { it.loadedWebView?.clearCache(true) }
                        repository.clearHistory()
                        tabManager.closeAllTabs(this@MainActivity)
                        Toast.makeText(this@MainActivity, I18n.t(this@MainActivity, "toast_data_cleared"), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(I18n.t(this@MainActivity, "btn_cancel"), null)
                    .show()
            }
        }
        view.addView(btnClearData)

        // 4. Info
        val tvInfo = TextView(this).apply {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            text = "\nSafeer Mobile Browser v$version • Target SDK 36\nSafeer is a security layer, not a guarantee against all online threats."
            textSize = 11f
            setTextColor(Color.parseColor("#64748b"))
            setPadding(0, 16, 0, 0)
        }
        view.addView(tvInfo)

        val scroll = ScrollView(this).apply {
            addView(view)
            setBackgroundColor(Color.parseColor("#0a0f1d"))
        }

        AlertDialog.Builder(this)
            .setTitle(I18n.t(this, "settings_title"))
            .setView(scroll)
            .setPositiveButton(I18n.t(this, "btn_save")) { _, _ ->
                val langCheckedId = rgLang.checkedRadioButtonId
                val langCheckedRb = rgLang.findViewById<RadioButton>(langCheckedId)
                val langIdx = rgLang.indexOfChild(langCheckedRb)
                val selLang = if (langIdx in langKeys.indices) langKeys[langIdx] else "auto"
                val oldLang = PreferencesManager.getLanguage(this)
                PreferencesManager.setLanguage(this, selLang)

                val checkedId = rgEngine.checkedRadioButtonId
                val checkedRb = rgEngine.findViewById<RadioButton>(checkedId)
                val radioIdx = rgEngine.indexOfChild(checkedRb)
                if (radioIdx in engineKeys.indices) {
                    PreferencesManager.setSearchEngine(this, engineKeys[radioIdx])
                }

                val newAdBlock = cbAdBlock.isChecked
                PreferencesManager.setAdBlockEnabled(this, newAdBlock)
                AdBlockEngine.isEnabled = newAdBlock

                val newDark = cbDarkMode.isChecked
                PreferencesManager.setDarkModeEnabled(this, newDark)
                isDarkModeActive = newDark
                tabManager.getAllTabs().forEach { it.loadedWebView?.applyDarkMode(newDark) }

                val zoomCheckedId = rgZoom.checkedRadioButtonId
                val zoomCheckedRb = rgZoom.findViewById<RadioButton>(zoomCheckedId)
                val zoomIdx = rgZoom.indexOfChild(zoomCheckedRb)
                val selZoom = if (zoomIdx in zoomKeys.indices) zoomKeys[zoomIdx] else 100
                PreferencesManager.setTextZoom(this, selZoom)
                tabManager.getAllTabs().forEach { it.loadedWebView?.settings?.textZoom = selZoom }

                // 🎨 Tema
                val themeCheckedId = rgTheme.checkedRadioButtonId
                val themeCheckedRb = rgTheme.findViewById<RadioButton>(themeCheckedId)
                val themeIdx = rgTheme.indexOfChild(themeCheckedRb)
                val selTheme = if (themeIdx in themeKeys.indices) themeKeys[themeIdx] else "dark_slate"
                PreferencesManager.setTheme(this, selTheme)
                applyAppTheme(selTheme)

                // 🔤 Pisava
                val fontCheckedId = rgFont.checkedRadioButtonId
                val fontCheckedRb = rgFont.findViewById<RadioButton>(fontCheckedId)
                val fontIdx = rgFont.indexOfChild(fontCheckedRb)
                val selFont = if (fontIdx in fontKeys.indices) fontKeys[fontIdx] else "system"
                PreferencesManager.setFontFamily(this, selFont)
                tabManager.getAllTabs().forEach { it.loadedWebView?.applyFontFamily(selFont) }

                // 🦁 Brave način
                val newBraveMode = cbBraveMode.isChecked
                PreferencesManager.setBraveModeEnabled(this, newBraveMode)
                tabManager.getAllTabs().forEach { tab ->
                    val curUrl = tab.loadedWebView?.url ?: ""
                    if (curUrl.startsWith("file:///android_asset/brave_home.html")) {
                        tab.loadedWebView?.evaluateJavascript("if (window.setBraveMode) { window.setBraveMode($newBraveMode); }", null)
                    }
                }

                val newThirdParty = cbThirdPartyCookies.isChecked
                PreferencesManager.setThirdPartyCookiesEnabled(this, newThirdParty)
                tabManager.getAllTabs().forEach {
                    it.loadedWebView?.let { view -> android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, newThirdParty) }
                }

                val newJs = cbJs.isChecked
                PreferencesManager.setJavaScriptEnabled(this, newJs)
                tabManager.getAllTabs().forEach {
                    it.loadedWebView?.settings?.javaScriptEnabled = newJs
                }

                PreferencesManager.setAdguardProtectionEnabled(this, cbAdguard.isChecked)
                PreferencesManager.setSponsorBlockEnabled(this, cbSponsorBlock.isChecked)

                // 🛡️ DoH & Proxy posodobitev
                val dohCheckedId = rgDoh.checkedRadioButtonId
                val dohCheckedRb = rgDoh.findViewById<RadioButton>(dohCheckedId)
                val dohIdx = rgDoh.indexOfChild(dohCheckedRb)
                val selDoh = if (dohIdx in dohKeys.indices) dohKeys[dohIdx] else "cloudflare"
                PreferencesManager.setDohEnabled(this, selDoh != "disabled")
                PreferencesManager.setDohProvider(this, selDoh)
                PreferencesManager.setCustomDohUrl(this, editCustomDoh.text.toString().trim())

                val proxyCheckedId = rgProxy.checkedRadioButtonId
                val proxyCheckedRb = rgProxy.findViewById<RadioButton>(proxyCheckedId)
                val proxyIdx = rgProxy.indexOfChild(proxyCheckedRb)
                val selProxy = if (proxyIdx in proxyKeys.indices) proxyKeys[proxyIdx] else "disabled"
                PreferencesManager.setSecureProxyMode(this, selProxy)
                PreferencesManager.setSecureProxyUrl(this, editCustomProxy.text.toString().trim())

                DoHProxyEngine.applySettings(this)

                // 📁 Mapa za prenose
                val prenosiCheckedId = rgPrenosi.checkedRadioButtonId
                val prenosiCheckedRb = rgPrenosi.findViewById<RadioButton>(prenosiCheckedId)
                val prenosiIdx = rgPrenosi.indexOfChild(prenosiCheckedRb)
                val selPrenosi = if (prenosiIdx in prenosiKeys.indices) prenosiKeys[prenosiIdx] else PrenosiMapa.PRENOSI
                PreferencesManager.setDownloadDir(this, selPrenosi)
                PreferencesManager.setDownloadSubfolder(
                    this,
                    if (cbPrenosiPodmapa.isChecked) PreferencesManager.PODMAPA_SAFEER else ""
                )

                Toast.makeText(this, I18n.t(this, "toast_settings_saved"), Toast.LENGTH_SHORT).show()

                if (selLang != oldLang) {
                    recreate()
                }
            }
            .setNegativeButton(I18n.t(this, "btn_cancel"), null)
            .show()
    }

    /**
     * Zahteve service workerjev ne gredo skozi WebViewClient; brez tega bi kompromitirana stran lahko
     * iz service workerja kontaktirala C2 strežnik. (WebSocket povezav WebView ne izpostavi.)
     */
    private fun installServiceWorkerThreatShield() {
        try {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                    ThreatBlockEngine.handleThreatIntercept(request.url.toString(), false)
            })
        } catch (e: Exception) {
            android.util.Log.w("SafeerSecurity", "Service worker Threat Shield ni na voljo: ${e.message}")
        }
    }

    private fun showThreatStatsDialog() {
        val totalThreats = ThreatBlockEngine.totalBlockedThreats.get()
        val c2 = ThreatBlockEngine.blockedC2Count.get()
        val malware = ThreatBlockEngine.blockedMalwareCount.get()
        val phishing = ThreatBlockEngine.blockedPhishingCount.get()
        val totalAds = AdBlockEngine.blockedAdsCount.get()

        AlertDialog.Builder(this)
            .setTitle("🛑 Safeer Threat Shield & AdBlock")
            .setMessage(
                """
                Varnostni ščit varuje vašo napravo pred nevarnimi C2 strežniki in zlonamerno kodo:
                
                Statistika v tem zagonu:
                • Blokiranih C2 Botnet strežnikov: $c2
                • Blokiranih Malware prenosov: $malware
                • Blokiranih Phishing strani: $phishing
                • Skupaj preprečenih groženj: $totalThreats
                • Blokiranih oglasov in sledilcev: $totalAds
                
                Viri: abuse.ch ThreatFox IOC, URLhaus, Phishing Army, HaGeZi TIF in Fake, SI-CERT, StevenBlack Hosts; oglasi: EasyList.
                Zaščita pred lažnimi spletnimi bankami: prave banke delujejo nemoteno.
                """.trimIndent() + "\n" + ThreatFeedsUpdater.statusLine() + "\n" + SignedThreatIntel.statusLine()
            )
            .setPositiveButton("Posodobi sezname") { _, _ ->
                Toast.makeText(this, "🔄 Posodabljam varnostne sezname...", Toast.LENGTH_SHORT).show()
                ThreatFeedsUpdater.updateFeedsAsync(this) { result ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val message = if (result.successful) "Seznami uspešno preverjeni: ${result.totalRules} varnostnih pravil v uporabi"
                                else "Posodobitev ni v celoti uspela. Prejšnji veljavni seznami ostajajo v uporabi."
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            showThreatStatsDialog()
                        }
                    }
                }
                SignedThreatIntel.requestUpdate { installed ->
                    if (installed) runOnUiThread {
                        Toast.makeText(this@MainActivity, "✅ Preverjen seznam Safeer Threat Intelligence posodobljen", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Zapri", null)
            .show()
    }

    private fun showFindInPage() {
        findInPageBar.visibility = View.VISIBLE
        editFindText.requestFocus()
        showKeyboard(editFindText)

        val activeWv = tabManager.getActiveTab()?.webView
        activeWv?.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            tvFindMatches.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
        }
        val query = editFindText.text.toString()
        if (query.isNotEmpty()) {
            activeWv?.findAllAsync(query)
        } else {
            tvFindMatches.text = "0/0"
        }
    }

    private fun showBookmarksDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_bookmarks)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val listView = dialog.findViewById<ListView>(R.id.bookmarksListView)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseBookmarks)
        val bookmarks = repository.getBookmarks()

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = bookmarks.size
            override fun getItem(position: Int): Any = bookmarks[position]
            override fun getItemId(position: Int): Long = bookmarks[position].id

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val bm = bookmarks[position]
                val view = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_bookmark, parent, false)

                view.findViewById<TextView>(R.id.tvBmIcon).text = bm.icon
                view.findViewById<TextView>(R.id.tvBmTitle).text = bm.title
                view.findViewById<TextView>(R.id.tvBmUrl).text = bm.url

                view.setOnClickListener {
                    tabManager.getActiveTab()?.webView?.navigateDocument(bm.url)
                    dialog.dismiss()
                }

                view.findViewById<Button>(R.id.btnDeleteBm).setOnClickListener {
                    repository.removeBookmark(bm.url)
                    dialog.dismiss()
                    showBookmarksDialog()
                }

                return view
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showHistoryDialog() {
        HistoryUi.show(
            this, repository,
            open = { url ->
                val tab = tabManager.getActiveTab()
                if (tab != null) tab.webView.navigateDocument(url) else tabManager.createTab(this, url, true)
            },
            openInNewTab = { url -> tabManager.createTab(this, url, true) },
        )
    }

    private fun setupFindInPage() {
        editFindText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val wv = tabManager.getActiveTab()?.webView ?: return
                wv.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                    tvFindMatches.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
                }
                wv.findAllAsync(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnFindPrev.setOnClickListener {
            tabManager.getActiveTab()?.webView?.findNext(false)
        }

        btnFindNext.setOnClickListener {
            tabManager.getActiveTab()?.webView?.findNext(true)
        }

        btnFindClose.setOnClickListener {
            tabManager.getActiveTab()?.webView?.clearMatches()
            findInPageBar.visibility = View.GONE
            hideKeyboard(editFindText)
        }
    }

    private fun showKeyboard(target: View = editUrl) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(target: View = editUrl) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    override fun onBackPressed() {
        if (editUrl.hasFocus()) {
            hideKeyboard()
            editUrl.clearFocus()
            mainRoot.requestFocus()
            return
        }
        if (customVideoView != null) {
            tabManager.getActiveTab()?.webView?.exitFullscreenVideo()
            return
        }

        if (findInPageBar.visibility == View.VISIBLE) {
            tabManager.getActiveTab()?.webView?.clearMatches()
            findInPageBar.visibility = View.GONE
            return
        }

        if (tabSwitcherOverlay.visibility == View.VISIBLE) {
            tabSwitcherOverlay.visibility = View.GONE
            return
        }

        val activeTab = tabManager.getActiveTab()
        val currentUrl = activeTab?.webView?.url ?: ""
        if (isYoutubeMixWatch(currentUrl) && activeTab != null) {
            activeTab.webView.evaluateJavascript(UserScriptManager.YOUTUBE_MIX_BACK_HOME_JS, null)
            return
        }

        // Pojavno okno (prijava): Nazaj ga zapre in nas vrne na stran, ki ga je odprla.
        // Prva stran takega okna je prazna lupina - obicajen Nazaj bi pustil prazen zaslon.
        if (activeTab != null && tabManager.jePojavni(activeTab) && !jeSmiselnoNazaj(activeTab.webView)) {
            tabManager.zapriPojavni(this, activeTab)
            return
        }

        if (activeTab != null && activeTab.webView.canGoBack()) {
            activeTab.webView.goBack()
            return
        }

        if (tabManager.count > 1 && activeTab != null) {
            tabManager.closeTab(this, activeTab.id)
            return
        }

        super.onBackPressed()
    }

    /** Ali ima Nazaj kam iti, ali bi nas pustil na prazni lupini pojavnega okna? */
    private fun jeSmiselnoNazaj(pogled: android.webkit.WebView): Boolean {
        if (!pogled.canGoBack()) return false
        return try {
            val seznam = pogled.copyBackForwardList()
            val prejsnji = seznam.getItemAtIndex(seznam.currentIndex - 1)?.url.orEmpty()
            prejsnji.isNotBlank() && !prejsnji.startsWith("about:")
        } catch (_: Throwable) {
            false
        }
    }

    private fun isYoutubeMixWatch(url: String): Boolean {
        val u = url.lowercase()
        if (!u.contains("youtube.com") && !u.contains("youtu.be")) return false
        if (!u.contains("/watch")) return false
        return u.contains("list=rd") || u.contains("start_radio")
    }
}
