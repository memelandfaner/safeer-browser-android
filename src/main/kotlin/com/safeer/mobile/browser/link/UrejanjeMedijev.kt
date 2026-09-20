package com.safeer.mobile.browser.link

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.FileOutputStream

/**
 * Brisanje in vrtenje slik te naprave, kadar jih ureja nekdo drug v Safeer Linku (televizor).
 *
 * Android ne dovoli, da bi aplikacija tiho spreminjala ali brisala fotografije, ki jih ni sama
 * ustvarila - tudi ce ima dovoljenje za branje. Zato:
 *
 *   1. dejanje najprej **poskusimo** (za slike, ki jih je na napravo shranil Safeer, uspe takoj,
 *      brez vprasanj);
 *   2. ce Android zahteva privolitev, to **pove naravnost**: televizor izpise »Potrdi na napravi«,
 *      naprava pa pokaze sistemsko vprasanje. Tega ne zaobidemo - dovoljenje za vse datoteke
 *      (MANAGE_EXTERNAL_STORAGE) bi bilo veliko vec, kot uporabnik prosi.
 *
 * Brisanje je premik v Smeti naprave (`createTrashRequest`), ne dokoncen izbris - isto kot na
 * racunalniku, kjer datoteka gre v Smeti.
 *
 * Vrtenje slike JPEG spremeni samo oznako EXIF Orientation: slikovne tocke ostanejo nedotaknjene,
 * zato ni izgube kakovosti in je hitro tudi pri veliki fotografiji. Druge oblike (PNG, WebP)
 * oznake nimajo, zato jih moramo zavrteti in zapisati znova.
 */
object UrejanjeMedijev {

    private const val TAG = "SafeerUrejanje"

    /** Izid dejanja. `potrditev` pomeni, da mora uporabnik potrditi na tej napravi. */
    class Izid(val ok: Boolean, val napaka: String = "", val potrditev: Boolean = false)

    /** Kot vrtenja: samo pravi kot, kot ga ponuja televizor. */
    private val V_DESNO = mapOf(1 to 6, 6 to 3, 3 to 8, 8 to 1, 2 to 7, 7 to 4, 4 to 5, 5 to 2)
    private val V_LEVO = V_DESNO.entries.associate { (k, v) -> v to k }

    // ------------------------------------------------------------------ brisanje

    fun izbrisi(context: Context, uri: Uri): Izid {
        return try {
            val vrstic = context.contentResolver.delete(uri, null, null)
            if (vrstic > 0) Izid(true) else Izid(false, "ni_datoteke")
        } catch (e: SecurityException) {
            Log.i(TAG, "Brisanje zahteva privolitev uporabnika")
            Izid(false, "potrebna_potrditev", potrditev = true)
        } catch (e: Throwable) {
            Log.w(TAG, "Brisanje ni uspelo: ${e.message}")
            Izid(false, "brisanje_ni_uspelo")
        }
    }

    /** Vprasanje za uporabnika (Smeti), ki ga pokaze [PotrditevActivity]. Null pred Androidom 11. */
    fun vprasanjeZaBrisanje(context: Context, uri: Uri): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30)
            try { MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true) }
            catch (e: Throwable) { Log.w(TAG, "Vprasanja ni bilo mogoce sestaviti: ${e.message}"); null }
        else null

    /** Vprasanje za uporabnika (pisanje), ki ga pokaze [PotrditevActivity]. Null pred Androidom 11. */
    fun vprasanjeZaPisanje(context: Context, uri: Uri): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30)
            try { MediaStore.createWriteRequest(context.contentResolver, listOf(uri)) }
            catch (e: Throwable) { Log.w(TAG, "Vprasanja ni bilo mogoce sestaviti: ${e.message}"); null }
        else null

    // ------------------------------------------------------------------ vrtenje

    /** [stopinje] 90 = v desno, 270 = v levo. */
    fun zavrti(context: Context, uri: Uri, stopinje: Int): Izid {
        if (stopinje != 90 && stopinje != 270) return Izid(false, "neveljaven_kot")
        val vrsta = try { context.contentResolver.getType(uri) ?: "" } catch (_: Throwable) { "" }
        return try {
            if (vrsta.contains("jpeg") || vrsta.contains("jpg")) zavrtiExif(context, uri, stopinje)
            else zavrtiZnova(context, uri, stopinje, vrsta)
        } catch (e: SecurityException) {
            Log.i(TAG, "Vrtenje zahteva privolitev uporabnika")
            Izid(false, "potrebna_potrditev", potrditev = true)
        } catch (e: Throwable) {
            Log.w(TAG, "Vrtenje ni uspelo: ${e.message}")
            Izid(false, "vrtenje_ni_uspelo")
        }
    }

    /** JPEG: spremenimo samo oznako EXIF - slikovne tocke ostanejo nedotaknjene. */
    private fun zavrtiExif(context: Context, uri: Uri, stopinje: Int): Izid {
        context.contentResolver.openFileDescriptor(uri, "rw").use { fd ->
            if (fd == null) return Izid(false, "ni_datoteke")
            val exif = ExifInterface(fd.fileDescriptor)
            val staro = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                .let { if (it in 1..8) it else 1 }
            val novo = (if (stopinje == 90) V_DESNO else V_LEVO)[staro] ?: return Izid(false, "vrtenje_ni_uspelo")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, novo.toString())
            exif.saveAttributes()
            posodobiZbirko(context, uri, novo)
        }
        return Izid(true)
    }

    /** PNG in druge oblike brez oznake: sliko zavrtimo in zapisemo znova. */
    private fun zavrtiZnova(context: Context, uri: Uri, stopinje: Int, vrsta: String): Izid {
        val slika: Bitmap = context.contentResolver.openInputStream(uri).use { vhod ->
            if (vhod == null) return Izid(false, "ni_datoteke")
            BitmapFactory.decodeStream(vhod)
        } ?: return Izid(false, "ni_slika")
        val m = Matrix().apply { postRotate(if (stopinje == 90) 90f else 270f) }
        val zavrtena = try {
            Bitmap.createBitmap(slika, 0, 0, slika.width, slika.height, m, true)
        } catch (e: OutOfMemoryError) {
            slika.recycle()
            return Izid(false, "prevelika_slika")
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "rwt").use { fd ->
                if (fd == null) return Izid(false, "ni_datoteke")
                FileOutputStream(fd.fileDescriptor).use { izhod ->
                    val oblika = if (vrsta.contains("png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    if (!zavrtena.compress(oblika, 95, izhod)) return Izid(false, "vrtenje_ni_uspelo")
                }
            }
            posodobiZbirko(context, uri, ExifInterface.ORIENTATION_NORMAL)
            return Izid(true)
        } finally {
            if (zavrtena !== slika) slika.recycle()
            zavrtena.recycle()
        }
    }

    /**
     * Zbirka (MediaStore) hrani svoj stolpec ORIENTATION. Brez te posodobitve bi galerija naprave
     * se naprej kazala staro obrnjenost, ceprav je v datoteki ze nova oznaka.
     */
    private fun posodobiZbirko(context: Context, uri: Uri, orientacija: Int) {
        val stopinje = when (orientacija) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        try {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.ORIENTATION, stopinje)
            }, null, null)
        } catch (e: Throwable) {
            // Ni usodno: datoteka je zavrtena, le galerija naprave se lahko osvezi pozneje.
            Log.i(TAG, "Zbirke ni bilo mogoce posodobiti: ${e.message}")
        }
    }
}
