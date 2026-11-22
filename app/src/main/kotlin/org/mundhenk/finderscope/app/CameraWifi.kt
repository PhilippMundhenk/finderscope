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
    var requester: (NetworkRequest, ConnectivityManager.NetworkCallback) -> Unit = { r, cb ->
        // a network asked for by name gets an answer within [namedTimeoutMs] (onUnavailable), so a
        // request Android silently drops is noticed; the picker waits for the user as long as it takes
        if (named) cm.requestNetwork(r, cb, requestTimeoutMs) else cm.requestNetwork(r, cb)
    }

    /** How long Android gets to connect to a camera network asked for by name. */
    var namedTimeoutMs = 30_000
    private var named = false
    private var requestTimeoutMs = 30_000

    /**
     * Asks Android for the camera network. Without [known] the system shows the camera networks
     * nearby (the 2013+ firmware names them `AP_SSC_<model>_…`, the 2012 one `<model>_…`; both
     * contain an underscore, which is what the picker filters on). With [known] (name, hardware
     * address) it asks for exactly that network, which Android approves without asking again once
     * the user approved it before. [onResult] gets the network, or null when the user cancelled or
     * none came; [onIdentified] the name and hardware address of the network joined, where Android
     * tells them (it needs the location permission for that).
     */
    fun join(known: Pair<String, String?>? = null, timeoutMs: Int = namedTimeoutMs, onIdentified: (String, String?) -> Unit = { _, _ -> }, onLost: () -> Unit = {}, onResult: (Network?) -> Unit) {
        release()
        // [known]: a network by name (remembered, or just found by [findCamera]); none: Android's picker
        val target = known
        named = target != null
        requestTimeoutMs = timeoutMs
        LogBus.log(if (target != null) "asking Android for the camera network ${CameraModel.parse(target.first).name}" else "asking Android's picker for a camera network")
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
                // the camera switched off or out of range
                Event.LOST -> if (n == network) { network = null; onLost() }
            }
        }
        callback = cb
        requester(request, cb)
    }

    private enum class Event { AVAILABLE, CAPABILITIES, UNAVAILABLE, LOST }

    /** The phone's scan results (tests set them; null = ask Android). */
    var scanResults: (() -> List<Triple<String, String, Long>>)? = null

    /** How often [findCamera] looks at the scan results (tests shorten it). */
    var pollMs = 2_000L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var search: Runnable? = null

    /**
     * Waits for the camera's network to show up in the phone's Wi-Fi scan, for up to [timeoutMs]:
     * the camera may open its network long after Join was pressed, and Android's picker does not
     * reliably notice a network that appears while it is open. Looks every [pollMs] and asks for a
     * fresh scan every 30 s (Android allows about four per two minutes). [onResult] gets the
     * network (name, hardware address) as soon as one is seen ([known]: only that one), or null
     * with `searched = true` after the time is up. Where the scan cannot be read (no location
     * permission, location off) it answers at once with [known] and `searched = false`.
     */
    fun findCamera(known: Pair<String, String?>?, timeoutMs: Long, onResult: (target: Pair<String, String?>?, searched: Boolean) -> Unit) {
        cancelSearch()
        if (scanResults == null && !scanReadable()) { onResult(known, false); return }
        val started = android.os.SystemClock.elapsedRealtime()
        var lastScanRequest = 0L
        var reported = false
        val step = object : Runnable {
            override fun run() {
                if (search !== this) return
                handler.removeCallbacks(this)
                val now = android.os.SystemClock.elapsedRealtime()
                // Android grants an app about four scans per two minutes and drops the rest: asking
                // every 10 s spends them early, when the camera most likely comes on
                if (now - lastScanRequest >= SCAN_EVERY_MS) { lastScanRequest = now; requestScan() }
                val results = readScan()
                val found = results.firstOrNull { if (known != null) it.first.trim('"') == known.first else isCameraNetwork(it.first) }
                if (found != null) {
                    stopSearch()
                    LogBus.log("Wi-Fi scan: camera network ${CameraModel.parse(found.first).name} seen after ${(now - started) / 1000} s")
                    onResult(found.first.trim('"') to (found.second.ifEmpty { null } ?: known?.second), true)
                    return
                }
                if (!reported) {
                    reported = true
                    LogBus.log("Wi-Fi scan: ${results.size} networks, no camera network yet${results.minOfOrNull { it.third }?.let { " (newest $it s old)" } ?: ""}; looking for up to ${timeoutMs / 1000} s")
                }
                if (now - started >= timeoutMs) { stopSearch(); LogBus.log("Wi-Fi scan: no camera network within ${timeoutMs / 1000} s"); onResult(null, true); return }
                handler.postDelayed(this, pollMs)
            }
        }
        search = step
        // any finished scan (also one of the system or another app, e.g. the Wi-Fi settings) is looked at at once
        if (scanResults == null) scanDone = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) { if (search === step) step.run() }
        }.also { androidx.core.content.ContextCompat.registerReceiver(context, it, android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED) }
        step.run()
    }

    private var scanDone: android.content.BroadcastReceiver? = null

    private fun stopSearch() {
        search?.let { handler.removeCallbacks(it) }
        search = null
        scanDone?.let { runCatching { context.unregisterReceiver(it) } }
        scanDone = null
    }

    /** Stops [findCamera] (Cancel). */
    fun cancelSearch() = stopSearch()

    val searching: Boolean get() = search != null

    private fun scanReadable(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != false

    @Suppress("DEPRECATION")
    private fun requestScan() { if (scanResults == null) runCatching { context.getSystemService(WifiManager::class.java)?.startScan() } }

    /** (name, hardware address, age in seconds), strongest first. */
    private fun readScan(): List<Triple<String, String, Long>> = scanResults?.invoke() ?: runCatching {
        val wm = context.getSystemService(WifiManager::class.java)
        val now = android.os.SystemClock.elapsedRealtime()
        wm?.scanResults.orEmpty().sortedByDescending { it.level }.map { Triple(it.SSID.orEmpty(), it.BSSID.orEmpty(), (now - it.timestamp / 1000) / 1000) }
    }.getOrElse { LogBus.log("Wi-Fi scan not readable: $it"); emptyList() }

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

    /** The camera network's interface (wlan0 or similar), for multicast; null for loopback or when unknown. */
    fun cameraInterface(host: String): java.net.NetworkInterface? {
        if (host == "localhost" || host.startsWith("127.")) return null
        val name = cameraNetwork()?.let { cm.getLinkProperties(it)?.interfaceName } ?: return null
        return runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull()
    }

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
    /** The current Wi-Fi name as tests want it (null = ask Android). */
    var ssidSource: (() -> String?)? = null

    @Suppress("DEPRECATION")
    fun currentSsid(): String? = ssidSource?.let { return it() } ?: runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"') }.getOrNull()
        ?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID && it != WifiManager.UNKNOWN_SSID.trim('<', '>') }

    /** Puts a datagram socket (SSDP notices) on the camera network, where there is one. */
    fun bind(socket: DatagramSocket, host: String): DatagramSocket {
        if (!(host == "localhost" || host.startsWith("127."))) cameraNetwork()?.let { runCatching { it.bindSocket(socket) } }
        return socket
    }

    companion object {
        const val DEFAULT_CAMERA = "192.168.102.1"

        /** How often [findCamera] asks for a fresh Wi-Fi scan (Android drops what exceeds its budget). */
        const val SCAN_EVERY_MS = 10_000L

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
