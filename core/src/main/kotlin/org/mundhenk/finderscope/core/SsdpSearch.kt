package org.mundhenk.finderscope.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI

/** Where a camera's UPnP description is: from an SSDP LOCATION like http://192.168.102.1:7676/smp_2_. */
data class CameraLocation(val host: String, val port: Int, val path: String) {
    companion object {
        fun parse(url: String): CameraLocation? = runCatching {
            val u = URI(url.trim())
            if (u.host == null) null else CameraLocation(u.host, if (u.port > 0) u.port else 80, u.rawPath.ifEmpty { "/" })
        }.getOrNull()
    }
}

/**
 * Finds the camera by SSDP, the way the cameras expect to be found: an M-SEARCH for everything
 * (`ssdp:all`) about every second, and whatever comes back - search answers or the camera's own
 * NOTIFYs - with a LOCATION that names a MediaServer description. On a phone [socket] is the
 * multicast group socket on the camera network ([SsdpNotices.multicast]); tests pass a plain socket
 * and the simulator's address as [target].
 */
object SsdpSearch {
    private val M_SEARCH = listOf(
        "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", "MAN: \"ssdp:discover\"", "MX: 1", "ST: ssdp:all", "", "",
    ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)

    fun search(
        socket: DatagramSocket,
        timeoutMs: Long,
        target: InetSocketAddress = InetSocketAddress(SsdpNotices.GROUP, SsdpNotices.PORT),
        log: (String) -> Unit = {},
    ): CameraLocation? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(4096)
        var nextSearch = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= deadline) return null
            if (now >= nextSearch) {
                try { socket.send(DatagramPacket(M_SEARCH, M_SEARCH.size, target)) } catch (e: IOException) { log("SSDP search not sent: ${e.message}") }
                nextSearch = now + 1_000
            }
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.soTimeout = maxOf(1, minOf(nextSearch, deadline) - System.currentTimeMillis()).toInt()
                socket.receive(p)
            } catch (_: SocketTimeoutException) { continue } catch (e: IOException) { log("SSDP: ${e.message}"); return null }
            val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
            locationOf(text)?.let { log("SSDP: camera description at $it"); return it }
        }
    }

    /** A search answer or NOTIFY that points at a camera's description; null for searches and anything else. */
    fun locationOf(text: String): CameraLocation? {
        val lines = text.split("\r\n", "\n")
        val first = lines.first().trim()
        if (!first.startsWith("HTTP/1.1 200", ignoreCase = true) && !first.startsWith("NOTIFY", ignoreCase = true)) return null
        fun header(name: String) = lines.drop(1).firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim()
        if (header("NTS")?.equals("ssdp:byebye", ignoreCase = true) == true) return null
        val what = (header("ST") ?: header("NT") ?: "")
        // the cameras announce a MediaServer (with ContentDirectory / ConnectionManager services) at /smp_2_
        val location = header("LOCATION") ?: return null
        if (!location.contains("smp_") && !what.contains("MediaServer") && !what.contains("ContentDirectory") && !what.contains("ConnectionManager") && !what.contains("rootdevice")) return null
        return CameraLocation.parse(location)
    }
}
