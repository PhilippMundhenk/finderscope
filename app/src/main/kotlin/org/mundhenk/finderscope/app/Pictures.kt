package org.mundhenk.finderscope.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/** Saves pictures into Pictures/finderscope, byte for byte as the camera sent them (EXIF kept). */
object Pictures {
    const val FOLDER = "finderscope"

    /**
     * [position] (latitude, longitude): written into the copy's EXIF. The cameras take the
     * position with ShotWithGPS but do not use it (NX1000 firmware), so this is the only place
     * it ends up.
     */
    fun save(context: Context, name: String, bytes: ByteArray, position: Pair<Double, Double>? = null): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no media store")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        if (position != null) runCatching {
            resolver.openFileDescriptor(uri, "rw")!!.use { fd ->
                android.media.ExifInterface(fd.fileDescriptor).apply {
                    val (lat, lon) = position
                    setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, dms(lat))
                    setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                    setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, dms(lon))
                    setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
                    saveAttributes()
                }
            }
        }.onFailure { LogBus.log("location not written into $name: $it") }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Degrees as EXIF wants them: "deg/1,min/1,sec*1000/1000". */
    internal fun dms(value: Double): String {
        val v = kotlin.math.abs(value)
        val deg = v.toInt(); val minF = (v - deg) * 60; val min = minF.toInt()
        val milliSec = Math.round((minF - min) * 60 * 1000)
        return "$deg/1,$min/1,$milliSec/1000"
    }

    /** "SAM_0001.JPG" from the camera's small copy -> "SAM_0001_preview.JPG", so the full picture can come later. */
    fun nameFor(name: String, preview: Boolean): String =
        if (!preview) name else name.substringBeforeLast('.') + "_preview." + name.substringAfterLast('.', "jpg")
}
