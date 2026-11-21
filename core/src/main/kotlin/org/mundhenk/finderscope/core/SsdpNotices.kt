package org.mundhenk.finderscope.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicBoolean

/** One SSDP NOTIFY: NT (what), NTS (alive / byebye / the cameras' rotation values), USN (who), and the sender's address. */
data class SsdpNotice(val nt: String, val nts: String, val usn: String, val from: String = "")

/**
 * Listens for the camera's SSDP NOTIFY messages while a session runs: the cameras announce a
 * turn of the camera (`NTS: ssdp:rotationL`) and their goodbye (`ssdp:byebye`) this way. On a
 * phone this needs a multicast lock (the app holds one). [socket] is the multicast group socket
 * from [multicast], or any datagram socket (tests send to it directly).
 */
class SsdpNotices(private val socket: DatagramSocket, private val onNotice: (SsdpNotice) -> Unit) {
    private val running = AtomicBoolean(false)

    val port: Int get() = socket.localPort

    fun start(): SsdpNotices {
        if (running.getAndSet(true)) return this
        Thread({
            val buf = ByteArray(4096)
            while (running.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (e: IOException) { break }
                parse(String(p.data, 0, p.length, Charsets.ISO_8859_1))?.copy(from = p.address?.hostAddress ?: "")?.let(onNotice)
            }
        }, "ssdp-notices").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        socket.close()
    }

    companion object {
        val GROUP: InetAddress = InetAddress.getByName("239.255.255.250")
        const val PORT = 1900

        /** A socket in the SSDP multicast group (port 1900, shared with other listeners). */
        fun multicast(): DatagramSocket = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
            @Suppress("DEPRECATION") joinGroup(GROUP)
        }

        /** A NOTIFY datagram -> [SsdpNotice]; null for anything else (searches, answers). */
        fun parse(text: String): SsdpNotice? {
            val lines = text.split("\r\n", "\n")
            if (!lines.first().trim().startsWith("NOTIFY", ignoreCase = true)) return null
            fun header(name: String) = lines.drop(1).firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim() ?: ""
            return SsdpNotice(header("NT"), header("NTS"), header("USN"))
        }

        /** What the cameras send (also used by the simulator). */
        fun notify(nt: String, nts: String, usn: String, location: String): ByteArray = listOf(
            "NOTIFY * HTTP/1.1", "HOST: 239.255.255.250:1900", "CACHE-CONTROL: max-age=1800",
            "LOCATION: $location", "NT: $nt", "NTS: $nts", "USN: $usn", "", "",
        ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }
}
