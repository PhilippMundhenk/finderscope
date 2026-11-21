package org.mundhenk.finderscope.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import java.net.DatagramSocket
import java.net.Inet4Address
import javax.net.SocketFactory

/**
 * The camera's own Wi-Fi. In Remote Viewfinder mode the camera is the access point and the
 * gateway, and the network has no internet: Android would send everything over mobile data.
 * finderscope therefore asks for the camera network explicitly ([join], the system shows the
 * matching networks) and binds only its own sockets to it ([socketFactory]); the rest of the
 * phone keeps its internet, and nothing in the user's saved networks is touched.
 */
class CameraWifi(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var network: Network? = null; private set

    /** Hands the request to Android (which shows its network picker); the UI tests answer it themselves. */
    var requester: (NetworkRequest, ConnectivityManager.NetworkCallback) -> Unit = { r, cb -> cm.requestNetwork(r, cb) }

    /**
     * Shows the system's network picker for camera networks. The 2013+ firmware names them
     * `AP_SSC_<model>_…`, the 2012 one `<model>_…`; both contain an underscore, which is what the
     * picker filters on. [onResult] gets the network, or null when the user cancelled / none came.
     */
    fun join(onResult: (Network?) -> Unit) {
        release()
        val specifier = WifiNetworkSpecifier.Builder().setSsidPattern(PatternMatcher(".*_.*", PatternMatcher.PATTERN_SIMPLE_GLOB)).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { network = n; onResult(n) }
            override fun onUnavailable() { network = null; onResult(null) }
            override fun onLost(n: Network) { if (n == network) network = null }
        }
        callback = cb
        requester(request, cb)
    }

    /** Gives the camera network back to the system (Android then returns to the usual Wi-Fi). */
    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    /** The network to talk to the camera on: the one [join] got, else the Wi-Fi the phone is on. */
    fun cameraNetwork(): Network? = network ?: wifiNetworks().firstOrNull()

    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> =
        cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    /** The camera's address: the gateway of the camera network (usually 192.168.102.1). */
    fun gateway(): String? {
        val lp: LinkProperties = cameraNetwork()?.let { cm.getLinkProperties(it) } ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    /** Sockets over the camera network; loopback (the demo camera) stays on the default factory. */
    fun socketFactory(host: String): SocketFactory {
        if (host == "localhost" || host.startsWith("127.")) return SocketFactory.getDefault()
        return cameraNetwork()?.socketFactory ?: SocketFactory.getDefault()
    }

    /** Puts a datagram socket (SSDP notices) on the camera network, where there is one. */
    fun bind(socket: DatagramSocket, host: String): DatagramSocket {
        if (!(host == "localhost" || host.startsWith("127."))) cameraNetwork()?.let { runCatching { it.bindSocket(socket) } }
        return socket
    }

    companion object {
        const val DEFAULT_CAMERA = "192.168.102.1"

        /** "192.168.102.1" or "192.168.102.1:7676" -> host and port. */
        fun parse(address: String): Pair<String, Int>? {
            val a = address.trim().removePrefix("http://").trimEnd('/')
            if (a.isEmpty() || a.contains(' ')) return null
            val host = a.substringBefore(':')
            val port = if (a.contains(':')) a.substringAfter(':').toIntOrNull() ?: return null else 7676
            return if (host.isEmpty() || port !in 1..65535) null else host to port
        }
    }
}
