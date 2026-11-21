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

    fun save(context: Context, name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no media store")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** "SAM_0001.JPG" from the camera's small copy -> "SAM_0001_preview.JPG", so the full picture can come later. */
    fun nameFor(name: String, preview: Boolean): String =
        if (!preview) name else name.substringBeforeLast('.') + "_preview." + name.substringAfterLast('.', "jpg")
}
