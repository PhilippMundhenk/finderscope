package org.mundhenk.finderscope.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.MacAddress
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import org.mundhenk.finderscope.core.CameraModel
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
     * Asks Android for the camera network. Without [known] the system shows the camera networks
     * nearby (the 2013+ firmware names them `AP_SSC_<model>_…`, the 2012 one `<model>_…`; both
     * contain an underscore, which is what the picker filters on). With [known] (name, hardware
     * address) it asks for exactly that network, which Android approves without asking again once
     * the user approved it before. [onResult] gets the network, or null when the user cancelled or
     * none came; [onIdentified] the name and hardware address of the network joined, where Android
     * tells them (it needs the location permission for that).
     */
    fun join(known: Pair<String, String?>? = null, onIdentified: (String, String?) -> Unit = { _, _ -> }, onResult: (Network?) -> Unit) {
        release()
        // Android's picker only lists what its own scan finds, and that sometimes stays empty
        // (field test: camera network up, picker searching forever). The phone's last scan
        // tells whether it sees the camera at all, and a camera seen there is asked for by name.
        val seen = if (known == null) cameraInScan() else null
        val target = known ?: seen
        val builder = WifiNetworkSpecifier.Builder()
        if (target != null) {
            builder.setSsid(target.first)
            target.second?.let { runCatching { builder.setBssid(MacAddress.fromString(it)) } }
        } else builder.setSsidPattern(PatternMatcher(".*_.*", PatternMatcher.PATTERN_SIMPLE_GLOB))
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()
        fun identify(info: WifiInfo?) {
            val ssid = info?.ssid?.trim('"').orEmpty()
            if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID.trim('<', '>') || ssid == WifiManager.UNKNOWN_SSID) return
            onIdentified(ssid, info?.bssid?.takeIf { it != "02:00:00:00:00:00" })
        }
        val cb = callback { event, n, caps ->
            when (event) {
                Event.AVAILABLE -> {
                    network = n
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) identify(context.getSystemService(WifiManager::class.java)?.connectionInfo)
                    onResult(n)
                }
                Event.CAPABILITIES -> identify(caps?.transportInfo as? WifiInfo)
                Event.UNAVAILABLE -> { network = null; onResult(null) }
                Event.LOST -> if (n == network) network = null
            }
        }
        callback = cb
        requester(request, cb)
    }

    private enum class Event { AVAILABLE, CAPABILITIES, UNAVAILABLE, LOST }

    /** The phone's scan results (tests set them; null = ask Android). */
    var scanResults: (() -> List<Triple<String, String, Long>>)? = null

    /**
     * A camera network in the phone's latest Wi-Fi scan (name, hardware address), the strongest
     * if there are several; null if the scan has none or cannot be read (no location permission).
     * Also asks for a fresh scan and logs what was found.
     */
    @Suppress("DEPRECATION")
    private fun cameraInScan(): Pair<String, String?>? {
        val wm = context.getSystemService(WifiManager::class.java)
        val results = scanResults?.invoke() ?: runCatching {
            runCatching { wm?.startScan() }
            val now = android.os.SystemClock.elapsedRealtime()
            // (name, hardware address, age in seconds); strongest first
            wm?.scanResults.orEmpty().sortedByDescending { it.level }.map { Triple(it.SSID.orEmpty(), it.BSSID.orEmpty(), (now - it.timestamp / 1000) / 1000) }
        }.getOrElse { LogBus.log("Wi-Fi scan not readable: $it"); emptyList() }
        val cameras = results.filter { isCameraNetwork(it.first) }
        if (cameras.isEmpty()) {
            LogBus.log("Wi-Fi scan: ${results.size} networks, no camera network among them${results.minOfOrNull { it.third }?.let { " (newest $it s old)" } ?: ""}")
            return null
        }
        LogBus.log("Wi-Fi scan: camera network ${CameraModel.parse(cameras.first().first).name} seen ${cameras.first().third} s ago")
        return cameras.first().first to cameras.first().second.ifEmpty { null }
    }

    /** A network callback that, from Android 12 on, also gets the network's name (with the location permission). */
    private fun callback(on: (Event, Network?, NetworkCapabilities?) -> Unit): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(n: Network) = on(Event.AVAILABLE, n, null)
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = on(Event.CAPABILITIES, n, caps)
            override fun onUnavailable() = on(Event.UNAVAILABLE, null, null)
            override fun onLost(n: Network) = on(Event.LOST, n, null)
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = on(Event.AVAILABLE, n, null)
            override fun onUnavailable() = on(Event.UNAVAILABLE, null, null)
            override fun onLost(n: Network) = on(Event.LOST, n, null)
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

    /** The name of the Wi-Fi the phone is on (needs the location permission); null when unknown. */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"') }.getOrNull()
        ?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID && it != WifiManager.UNKNOWN_SSID.trim('<', '>') }

    /** Puts a datagram socket (SSDP notices) on the camera network, where there is one. */
    fun bind(socket: DatagramSocket, host: String): DatagramSocket {
        if (!(host == "localhost" || host.startsWith("127."))) cameraNetwork()?.let { runCatching { it.bindSocket(socket) } }
        return socket
    }

    companion object {
        const val DEFAULT_CAMERA = "192.168.102.1"

        /** `AP_SSC_<model>_...` (2013+ firmware) or `<model>_...` (2012), optionally with a `[Camera]` prefix. */
        fun isCameraNetwork(ssid: String): Boolean =
            Regex("""^(\[Camera])?(AP_SSC_[A-Z0-9]+_|(NX|WB|ST|DV|EX|MV)[A-Z0-9]*_)""").containsMatchIn(ssid.trim('"'))

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
