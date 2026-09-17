package com.safeer.mobile.browser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * 📁 Prenosi znotraj Safeerja.
 *
 * Seznam datotek, ki jih je prenesel brskalnik (DownloadManager) ali prejel Safeer Link, z odpiranjem,
 * deljenjem in brisanjem. Uporabnik ostane v brskalniku: prej je gumb odprl sistemsko aplikacijo za
 * prenose, iz katere se s tipko nazaj ni vrnil v Safeer. Prenosi v teku kazejo napredek in se sami
 * osvezujejo; sistemska mapa je se vedno dosegljiva z gumbom »Odpri mapo«.
 */
object PrenosiUi {
    private const val BG = "#0F172A"
    private const val CARD = "#1E293B"
    private const val TEXT = "#F1F5F9"
    private const val MUTED = "#94A3B8"
    private const val ACCENT = "#C7D2FE"
    private const val WARN = "#FCD34D"
    private const val AUTHORITY = "com.safeer.mobile.browser.fileprovider"

    /** Javne mape, ki jih ponudnik vsebine sme streci pod /javno/<mapa>/... */
    val JAVNE_MAPE: List<String> = listOf(
        Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES
    )

    private class Vnos(
        val ime: String,
        val velikost: Long,
        val cas: Long,
        val stanje: Int,
        val napredek: Int,
        val dmId: Long,
        val datoteka: File?,
        val mime: String?,
    ) {
        val koncan: Boolean get() = stanje == DownloadManager.STATUS_SUCCESSFUL
        val vTeku: Boolean get() = stanje == DownloadManager.STATUS_RUNNING || stanje == DownloadManager.STATUS_PENDING
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun mimeZa(ime: String): String {
        val ext = ime.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun ikona(mime: String?, ime: String): String {
        val m = mime ?: mimeZa(ime)
        return when {
            m.startsWith("image/") -> "🖼️"
            m.startsWith("video/") -> "🎬"
            m.startsWith("audio/") -> "🎵"
            m == "application/pdf" -> "📕"
            m == "application/vnd.android.package-archive" -> "📲"
            m.contains("zip") || m.contains("compressed") || m.contains("tar") || m.contains("7z") || m.contains("rar") -> "🗜️"
            m.startsWith("text/") || m.contains("document") || m.contains("word") || m.contains("sheet") || m.contains("presentation") -> "📄"
            else -> "📦"
        }
    }

    fun velikost(bajti: Long): String {
        if (bajti < 0) return ""
        if (bajti < 1024) return "$bajti B"
        val kb = bajti / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f kB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    /** Vsebinski URI za datoteko v javni mapi (prek MobileFileProvider) ali v mapi aplikacije. */
    fun uriZaDatoteko(context: Context, datoteka: File): Uri? {
        val pot = try { datoteka.canonicalPath } catch (_: Throwable) { datoteka.absolutePath }
        for (mapa in JAVNE_MAPE) {
            val koren = try { Environment.getExternalStoragePublicDirectory(mapa).canonicalPath } catch (_: Throwable) { continue }
            if (pot.startsWith(koren + File.separator)) {
                val b = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("javno").appendPath(mapa)
                pot.removePrefix(koren + File.separator).split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
                return b.build()
            }
        }
        val zunanja = try { context.getExternalFilesDir(null)?.canonicalPath } catch (_: Throwable) { null }
        if (zunanja != null && pot.startsWith(zunanja + File.separator)) {
            val b = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("external_files")
            pot.removePrefix(zunanja + File.separator).split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
            return b.build()
        }
        val notranja = try { context.filesDir.canonicalPath } catch (_: Throwable) { null }
        if (notranja != null && pot.startsWith(notranja + File.separator)) {
            val b = Uri.Builder().scheme("content").authority(AUTHORITY)
            pot.removePrefix(notranja + File.separator).split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
            return b.build()
        }
        return null
    }

    private fun zberi(context: Context): List<Vnos> {
        val vnosi = ArrayList<Vnos>()
        val poti = HashSet<String>()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        try {
            dm?.query(DownloadManager.Query())?.use { c ->
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iSoFar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val iTime = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                val iMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (c.moveToNext()) {
                    val id = if (iId >= 0) c.getLong(iId) else -1L
                    val local = if (iLocal >= 0) c.getString(iLocal) else null
                    val datoteka = local?.let { try { Uri.parse(it) } catch (_: Throwable) { null } }
                        ?.takeIf { it.scheme == "file" }?.path?.let { File(it) }
                    val naslov = if (iTitle >= 0) c.getString(iTitle).orEmpty() else ""
                    val ime = datoteka?.name ?: naslov.ifEmpty { "datoteka" }
                    val total = if (iTotal >= 0) c.getLong(iTotal) else -1L
                    val soFar = if (iSoFar >= 0) c.getLong(iSoFar) else 0L
                    val stanje = if (iStatus >= 0) c.getInt(iStatus) else DownloadManager.STATUS_SUCCESSFUL
                    val napredek = if (total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else 0
                    if (datoteka != null) poti.add(datoteka.absolutePath)
                    // Datoteko je uporabnik izbrisal drugje: vnos ni vec zanimiv.
                    if (stanje == DownloadManager.STATUS_SUCCESSFUL && datoteka != null && !datoteka.exists()) continue
                    val mime = if (iMime >= 0) c.getString(iMime) else null
                    vnosi.add(Vnos(ime, if (total > 0) total else soFar, if (iTime >= 0) c.getLong(iTime) else 0L,
                        stanje, napredek, id, datoteka, mime))
                }
            }
        } catch (_: Throwable) { }
        // Datoteke v mapi prenosov (prejete prek Safeer Linka ali prenesene, ko DownloadManager vnosa ne pozna vec).
        val mape = LinkedHashSet<File>()
        try { mape.add(PrenosiMapa.ciljnaMapa(context)) } catch (_: Throwable) { }
        try { mape.add(com.safeer.mobile.browser.cast.HubKrmilnik.mapaZaPrejete(context)) } catch (_: Throwable) { }
        try { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { mape.add(it) } } catch (_: Throwable) { }
        for (mapa in mape) {
            val seznam = try { mapa.listFiles() } catch (_: Throwable) { null } ?: continue
            for (f in seznam) {
                if (!f.isFile || f.name.startsWith(".")) continue
                if (!poti.add(f.absolutePath)) continue
                vnosi.add(Vnos(f.name, f.length(), f.lastModified(), DownloadManager.STATUS_SUCCESSFUL, 100, -1L, f, mimeZa(f.name)))
            }
        }
        vnosi.sortWith(compareByDescending<Vnos> { !it.koncan }.thenByDescending { it.cas })
        return vnosi
    }

    /** Vrste, ki jih Safeer prikaze sam (v novem zavihku, vgrajeni pregledovalnik PDF), brez izbirnika aplikacij. */
    private fun znaSam(mime: String): Boolean = mime == "application/pdf"

    /** Odpre datoteko; vrne true, ce se je kaj odprlo (okno s prenosi se takrat zapre). */
    private fun odpri(context: Context, dm: DownloadManager?, v: Vnos, vZavihku: ((String) -> Unit)?): Boolean {
        var uri: Uri? = null
        var mime: String? = v.mime
        if (v.dmId >= 0 && dm != null) {
            uri = try { dm.getUriForDownloadedFile(v.dmId) } catch (_: Throwable) { null }
            if (mime.isNullOrEmpty()) mime = try { dm.getMimeTypeForDownloadedFile(v.dmId) } catch (_: Throwable) { null }
        }
        if (uri == null && v.datoteka != null) {
            if (!v.datoteka.exists()) { Toast.makeText(context, I18n.t(context, "dl_missing"), Toast.LENGTH_SHORT).show(); return false }
            uri = uriZaDatoteko(context, v.datoteka)
        }
        if (uri == null) { Toast.makeText(context, I18n.t(context, "dl_missing"), Toast.LENGTH_SHORT).show(); return false }
        if (mime.isNullOrEmpty() || mime == "application/octet-stream") mime = mimeZa(v.ime)
        if (vZavihku != null && znaSam(mime)) {
            // PDF odpre Safeer sam - v novem zavihku, brez izbirnika aplikacij.
            vZavihku(uri.toString())
            return true
        }
        val namera = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(namera)
            return true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), v.ime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Throwable) {
                Toast.makeText(context, I18n.t(context, "dl_no_app"), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(context, I18n.t(context, "dl_no_app"), Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun deli(context: Context, dm: DownloadManager?, v: Vnos) {
        var uri: Uri? = if (v.dmId >= 0 && dm != null) try { dm.getUriForDownloadedFile(v.dmId) } catch (_: Throwable) { null } else null
        if (uri == null && v.datoteka != null && v.datoteka.exists()) uri = uriZaDatoteko(context, v.datoteka)
        if (uri == null) { Toast.makeText(context, I18n.t(context, "dl_missing"), Toast.LENGTH_SHORT).show(); return }
        val mime = v.mime?.takeIf { it.isNotEmpty() } ?: mimeZa(v.ime)
        val namera = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(namera, v.ime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(context, I18n.t(context, "dl_no_app"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun izbrisi(dm: DownloadManager?, v: Vnos): Boolean {
        var ok = false
        if (v.dmId >= 0 && dm != null) ok = try { dm.remove(v.dmId) > 0 } catch (_: Throwable) { false }
        if (v.datoteka != null && v.datoteka.exists()) ok = (try { v.datoteka.delete() } catch (_: Throwable) { false }) || ok
        return ok
    }

    /** @param vZavihku odpre naslov (tudi content://) v novem zavihku brskalnika; null = vedno prek sistema. */
    fun show(context: Context, vZavihku: ((String) -> Unit)? = null) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8))
        }
        root.addView(TextView(context).apply {
            text = "📁 " + I18n.t(context, "dl_title")
            setTextColor(Color.parseColor(TEXT)); textSize = 18f; setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = I18n.t(context, "dl_folder").replace("{mapa}", PrenosiMapa.opis(context))
            setTextColor(Color.parseColor(MUTED)); textSize = 12f
            setPadding(0, dp(context, 2), 0, 0)
        })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(context, 8) })

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton(I18n.t(context, "dl_open_folder"), null)
            .setNegativeButton(I18n.t(context, "close"), null)
            .create()

        val glavnaNit = Handler(Looper.getMainLooper())
        var osvezevanje: Runnable? = null
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

        fun render() {
            list.removeAllViews()
            val vnosi = zberi(context)
            if (vnosi.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = I18n.t(context, "dl_empty"); setTextColor(Color.parseColor(MUTED))
                    setPadding(dp(context, 8), dp(context, 24), dp(context, 8), dp(context, 24)); gravity = Gravity.CENTER
                })
            }
            var lastDay = ""
            for (v in vnosi) {
                val day = if (v.koncan) HistoryUi.dayLabel(context, v.cas) else ""
                if (day != lastDay && day.isNotEmpty()) {
                    lastDay = day
                    list.addView(TextView(context).apply {
                        text = day; setTextColor(Color.parseColor(ACCENT)); textSize = 12f; setTypeface(null, Typeface.BOLD)
                        setPadding(0, dp(context, 12), 0, dp(context, 4))
                    })
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                    setBackgroundColor(Color.parseColor(CARD))
                    isClickable = true; isFocusable = true
                }
                row.addView(TextView(context).apply { text = ikona(v.mime, v.ime); textSize = 22f; setPadding(0, 0, dp(context, 12), 0) })
                val besedila = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                besedila.addView(TextView(context).apply {
                    text = v.ime; setTextColor(Color.parseColor(TEXT)); textSize = 15f; maxLines = 2
                })
                val podnapis = when (v.stanje) {
                    DownloadManager.STATUS_RUNNING -> I18n.t(context, "dl_running").replace("{odstotki}", v.napredek.toString())
                    DownloadManager.STATUS_PENDING -> I18n.t(context, "dl_pending")
                    DownloadManager.STATUS_PAUSED -> I18n.t(context, "dl_paused")
                    DownloadManager.STATUS_FAILED -> I18n.t(context, "dl_failed")
                    else -> listOf(velikost(v.velikost), if (v.cas > 0) timeFormat.format(Date(v.cas)) else "").filter { it.isNotEmpty() }.joinToString(" · ")
                }
                besedila.addView(TextView(context).apply {
                    text = podnapis; textSize = 12f; maxLines = 1
                    setTextColor(Color.parseColor(if (v.stanje == DownloadManager.STATUS_FAILED) WARN else MUTED))
                })
                row.addView(besedila, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener {
                    if (v.koncan) { if (odpri(context, dm, v, vZavihku)) dialog.dismiss() }
                    else if (v.stanje == DownloadManager.STATUS_FAILED) ponudiBrisanje(context, dm, v) { render() }
                }
                row.setOnLongClickListener {
                    val options = arrayOf(I18n.t(context, "dl_open"), I18n.t(context, "dl_share"), I18n.t(context, "dl_delete"))
                    AlertDialog.Builder(context).setTitle(v.ime).setItems(options) { _, which ->
                        when (which) {
                            0 -> { if (odpri(context, dm, v, vZavihku)) dialog.dismiss() }
                            1 -> deli(context, dm, v)
                            2 -> ponudiBrisanje(context, dm, v) { render() }
                        }
                    }.show()
                    true
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 6) })
            }
            // Prenosi v teku: osvezi cez sekundo, dokler je okno odprto.
            osvezevanje?.let { glavnaNit.removeCallbacks(it) }
            if (vnosi.any { it.vTeku } && dialog.isShowing) {
                val r = Runnable { if (dialog.isShowing) render() }
                osvezevanje = r
                glavnaNit.postDelayed(r, 1000)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {
                    Toast.makeText(context, I18n.t(context, "dl_no_app"), Toast.LENGTH_SHORT).show()
                }
            }
            render()
        }
        dialog.setOnDismissListener { osvezevanje?.let { glavnaNit.removeCallbacks(it) } }
        dialog.show()
    }

    private fun ponudiBrisanje(context: Context, dm: DownloadManager?, v: Vnos, potem: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(I18n.t(context, "dl_delete_confirm").replace("{ime}", v.ime))
            .setPositiveButton(I18n.t(context, "dl_delete")) { _, _ ->
                izbrisi(dm, v)
                Toast.makeText(context, I18n.t(context, "dl_deleted"), Toast.LENGTH_SHORT).show()
                potem()
            }
            .setNegativeButton(I18n.t(context, "close"), null)
            .show()
    }
}
