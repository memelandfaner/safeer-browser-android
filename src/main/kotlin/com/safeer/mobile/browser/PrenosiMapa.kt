package com.safeer.mobile.browser

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Kam se shranjujejo prenesene datoteke.
 *
 * Eno samo mesto za celo aplikacijo: brskalnikovi prenosi in - ko bo prenos datotek prek
 * Safeer Linka narejen - tudi datoteke, ki jih uporabnik prejme z druge svoje naprave.
 * Uporabnik ima s tem eno nastavitev, ne dveh, in datoteke najde vedno na istem mestu.
 *
 * Privzeto ostane tako, kot je bilo doslej: javna mapa Prenosi, brez podmape.
 */
object PrenosiMapa {

    /** Vrednosti, ki jih hranimo v nastavitvah. Javne mape Androida, brez posebnih dovoljenj. */
    const val PRENOSI = PreferencesManager.DIR_DOWNLOADS
    const val DOKUMENTI = PreferencesManager.DIR_DOCUMENTS
    const val SLIKE = PreferencesManager.DIR_PICTURES
    const val GLASBA = PreferencesManager.DIR_MUSIC
    const val FILMI = PreferencesManager.DIR_MOVIES

    val VSE: List<String> get() = PreferencesManager.DOWNLOAD_DIRS

    /** Ime javne mape, kot ga pozna Android. */
    fun sistemskoIme(izbira: String): String = when (izbira) {
        DOKUMENTI -> Environment.DIRECTORY_DOCUMENTS
        SLIKE -> Environment.DIRECTORY_PICTURES
        GLASBA -> Environment.DIRECTORY_MUSIC
        FILMI -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    /**
     * Pot znotraj javne mape, kot jo pricakuje DownloadManager:
     * ime datoteke ali "podmapa/ime datoteke".
     */
    fun relativnaPot(context: Context, imeDatoteke: String): String {
        val podmapa = PreferencesManager.getDownloadSubfolder(context).trim().trim('/')
        return if (podmapa.isEmpty()) imeDatoteke else "$podmapa/$imeDatoteke"
    }

    /** Javna mapa, ki jo je izbral uporabnik (za prejete datoteke prek Safeer Linka). */
    fun ciljnaMapa(context: Context): File {
        val koren = Environment.getExternalStoragePublicDirectory(
            sistemskoIme(PreferencesManager.getDownloadDir(context))
        )
        val podmapa = PreferencesManager.getDownloadSubfolder(context).trim().trim('/')
        val mapa = if (podmapa.isEmpty()) koren else File(koren, podmapa)
        if (!mapa.exists()) {
            try { mapa.mkdirs() } catch (_: Throwable) { }
        }
        return mapa
    }

    /** Kratek opis za nastavitve, npr. "Prenosi/Safeer". */
    fun opis(context: Context): String {
        val ime = when (PreferencesManager.getDownloadDir(context)) {
            DOKUMENTI -> "Documents"
            SLIKE -> "Pictures"
            GLASBA -> "Music"
            FILMI -> "Movies"
            else -> "Download"
        }
        val podmapa = PreferencesManager.getDownloadSubfolder(context).trim().trim('/')
        return if (podmapa.isEmpty()) ime else "$ime/$podmapa"
    }
}
