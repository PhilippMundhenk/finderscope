package org.mundhenk.finderscope.core

import java.io.InputStream

/** UPnP names and the one XML helper the camera protocol needs. */
object Upnp {
    const val MEDIA_SERVER_TYPE = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CDS_SERVICE_TYPE = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val CMS_SERVICE_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun soapResponse(action: String, body: String): String = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="$CDS_SERVICE_TYPE">$body</u:${action}Response>
  </s:Body>
</s:Envelope>"""
}

/** Reads at most [limit] bytes of [inner] (an HTTP body with Content-Length); EOF afterwards. */
class BoundedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var consumed = 0L
    val remaining: Long get() = limit - consumed

    override fun read(): Int {
        if (consumed >= limit) return -1
        val b = inner.read()
        if (b >= 0) consumed++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val n = inner.read(b, off, minOf(len.toLong(), limit - consumed).toInt())
        if (n > 0) consumed += n
        return n
    }
}

/** Reads and discards the rest of the stream. */
internal fun InputStream.skipAll() {
    val buf = ByteArray(64 * 1024)
    while (read(buf) >= 0) { /* drain */ }
}
