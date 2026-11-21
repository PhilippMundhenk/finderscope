package org.mundhenk.finderscope.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A camera in Remote Viewfinder mode: UPnP description / SCPD / SOAP control on one port, the
 * endless AVI live stream on another, pictures (and their `_SM` previews) served after a Shot,
 * which ends the running stream like the real cameras do. It is as strict as the real cameras were
 * reported to be, and records every rule a client breaks in [violations] instead of hanging like
 * the NX1000 would: a description request without the pairing User-Agent (401), control requests
 * from a client that never paired, the pairing User-Agent on anything but the description
 * (Samsung's apps never do that), an undeclared action or argument, two commands at once, AF or
 * Shot while nobody reads the live stream, a second stream reader.
 *
 * Used by the JVM tests and by the app's instrumented tests (it runs on the phone itself).
 */
class RvfCameraSimulator(
    val profile: Profile = Profile.NX1000,
    /** Frames the live view loops over (H.264 access units or JPEGs, matching [codec]). */
    private val frames: List<ByteArray> = listOf(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)),
    private val codec: VideoCodec = VideoCodec.H264,
    private val width: Int = 640,
    private val height: Int = 480,
    private val fps: Int = 15,
    /** JPEG served for every picture taken. */
    private val photo: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
    /** The small copy served as `<picture>_SM`. */
    private val preview: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x53, 0xFF.toByte(), 0xD9.toByte()),
    /** Host name put into the URLs the camera hands out. */
    private val advertisedHost: String = "127.0.0.1",
    controlPort: Int = 0,
    streamPort: Int = 0,
    /** The user's answer on the camera's "allow this device?" prompt. */
    var allow: (String) -> Boolean = { true },
    /** The control server comes up this long after start() (the real cameras need a moment after the phone joined). */
    private val startDelayMs: Long = 0,
    private val log: (String) -> Unit = {},
) {
    enum class Profile(
        val model: String,
        val actions: Map<String, List<String>>,
        val maxZoom: Int,
        val uaPrefix: String,
        /** `<Version>` in GetInfomation; null = the 2012 firmware (no version, stream URL in GETIPRESULT). */
        val version: String?,
        /** Answers `/mode/control` on its own port (with MVERSION: a "new" camera). */
        val modeServer: Boolean,
    ) {
        /**
         * 2012 NX. The SCPD Samsung's Remote Viewfinder 2.2.11 requires before it connects: zoom,
         * resolution, flash, LED timer, Shot without arguments, ShotWithGPS. Zoom is declared but the
         * NX1000 zooms with the lens ring only.
         */
        NX1000("NX1000", mapOf(
            "Browse" to listOf("ObjectID", "BrowseFlag", "Filter", "StartingIndex", "RequestedCount", "SortCriteria"),
            "GetIP" to emptyList(), "GetInfomation" to emptyList(), "SetResolution" to listOf("RESOLUTION"),
            "ZoomIN" to emptyList(), "ZoomOUT" to emptyList(), "MULTIAF" to emptyList(), "Shot" to emptyList(),
            "ShotWithGPS" to listOf("GPSINFO"), "ReleaseSelfTimer" to listOf("RELEASETIMER"), "SetLED" to listOf("LEDTIME"),
            "SetFlash" to listOf("FLASHMODE"),
        ), 1, RvfClient.UA_PREFIX, null, false),
        /** 2013 compact (WB250F-like): optical zoom, flash, stream quality, full-size download. */
        WB250F("WB250F", mapOf(
            "GetInfomation" to emptyList(), "AF" to emptyList(), "AFRELEASE" to emptyList(), "Shot" to listOf("REQUESTIMAGE"),
            "ZoomIN" to emptyList(), "ZoomOUT" to emptyList(), "SetFlash" to listOf("FLASHMODE"), "SetStreamQuality" to listOf("Quality"),
        ), 18, RvfClient.UA_PREFIX, "DSC_1.0", false),
        /** 2014 NX (NX3000/NX mini): /mode/control with MVERSION, MobileLink-style pairing, GPSINFO, HeartBeat. */
        NX3000("NX3000", mapOf(
            "GetInfomation" to listOf("GPSINFO"), "MULTIAF" to emptyList(), "touchAF" to listOf("AFPOSITION"), "Shot" to emptyList(),
            "HeartBeat" to listOf("Duration"), "SetFlash" to listOf("FLASHMODE"), "StartZoom" to listOf("ZoomDirection"), "StopZoom" to listOf("ZoomDirection"),
        ), 0, RvfClient.UA_PREFIX_ML, "CSC_1.1", true),
        /** finderscope's demo camera: a compact with everything (zoom, flash, sizes, timer, GPS). */
        DEMO("DEMO", mapOf(
            "GetInfomation" to emptyList(), "SetResolution" to listOf("RESOLUTION"), "ZoomIN" to emptyList(), "ZoomOUT" to emptyList(),
            "MULTIAF" to emptyList(), "Shot" to emptyList(), "ShotWithGPS" to listOf("GPSINFO"), "ReleaseSelfTimer" to listOf("RELEASETIMER"),
            "SetLED" to listOf("LEDTIME"), "SetFlash" to listOf("FLASHMODE"),
        ), 10, RvfClient.UA_PREFIX, "DSC_1.0", false),
    }

    private val control = HttpServer(controlPort, { handle(it) }, log)
    private var streamSocket: ServerSocket? = null
    private var modeSocket: ServerSocket? = null
    private val paired = Collections.synchronizedSet(HashSet<String>())
    private val streams = Collections.synchronizedSet(HashSet<Socket>())
    private val wantedStreamPort = streamPort
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "rvf-sim").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val allowed = Collections.synchronizedSet(HashSet<String>())
    private val shots = AtomicInteger(0)
    @Volatile private var sessionStarted = false
    @Volatile private var zoom = 0
    /** What the next AF reports: true = in focus, false = AFFAIL. */
    @Volatile var focusOk = true
    /** Reply to Shot with UPnP error 803 ("capturing failed") instead. */
    @Volatile var failShots = false
    /** Shots left on the card, as GetInfomation / Shot / SetResolution report them (0 = full, -1 = numbering exhausted). */
    @Volatile var availShots = 999
    /** Answer Shot with an empty AFSHOTRESULT (the picture is taken but not named). */
    @Volatile var emptyShotResult = false
    /**
     * As seen on an NX1000: Shot does not end the live-view connection; the camera sends no frames
     * for this long (it shows the picture) and then goes on on the same connection. 0 = the stream
     * is ended for the picture.
     */
    @Volatile var shotPauseMs = 0L
    /** After the picture the restarted encoder begins a new AVI header on the open connection. */
    @Volatile var headerOnRestart = false
    /**
     * The NX1000 firmware waits without a timeout for its encoder when a stream connection ends
     * while the encoder is stopped for a picture: closing or opening the live view then hangs the
     * camera for good. Set when that happened; the simulator then stops answering, like the camera.
     */
    @Volatile var hung = false
    /** Like a starting NX1000: the web server is up but answers 404 for this long before Remote Viewfinder is ready. */
    @Volatile var notReadyMs = 0L
    @Volatile private var controlUpAt = 0L
    /** Description requests seen (to check that a client does not flood a starting camera). */
    val descriptionRequests = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var pausedUntil = 0L
    @Volatile private var restartPending = false
    /** The live view is ended this long after the Shot answer (frames still come meanwhile). */
    @Volatile var endStreamDelayMs = 0L
    /** Keep the live-view connection open but stop sending frames. */
    @Volatile var stallStream = false
    /** GetInfomation ROTATION. */
    @Volatile var rotation = 0
    /** Last values set by the client. */
    @Volatile var led: String? = null
    @Volatile var resolution: String? = null
    @Volatile var flash: String? = null
    @Volatile var gps: String? = null
    val udn = "uuid:6a9f6c47-0c58-4f2e-9c1b-${profile.model.lowercase().padStart(12, '0').takeLast(12)}"

    /** Every rule a client broke; a correct client leaves this empty. */
    val violations: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** SOAP actions in the order they arrived. */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** User-Agents the camera saw on its control port. */
    val userAgents: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** Frames sent over the live stream so far. */
    val framesSent = AtomicInteger(0)
    /** How often the live stream was opened. */
    val streamOpens = AtomicInteger(0)
    /** Picture paths downloaded, in order. */
    val downloads: MutableList<String> = Collections.synchronizedList(ArrayList())
    val shotCount: Int get() = shots.get()
    val streamReaders: Int get() = streams.size

    val controlPort: Int get() = control.boundPort
    val streamPort: Int get() = streamSocket?.localPort ?: wantedStreamPort
    /** The /mode/control port, -1 for models without one. */
    val modePort: Int get() = modeSocket?.localPort ?: -1

    private var ssdpSocket: java.net.DatagramSocket? = null
    /** Port of the SSDP responder: answers M-SEARCH with the description's LOCATION. */
    val ssdpPort: Int get() = ssdpSocket?.localPort ?: -1

    fun start(): RvfCameraSimulator {
        if (running.getAndSet(true)) return this
        if (startDelayMs > 0) pool.execute { Thread.sleep(startDelayMs); if (running.get()) { control.start(); controlUpAt = System.currentTimeMillis() } }
        else { control.start(); controlUpAt = System.currentTimeMillis() }
        val ssdp = java.net.DatagramSocket(0); ssdpSocket = ssdp
        pool.execute {
            val buf = ByteArray(2048)
            while (running.get()) {
                val p = java.net.DatagramPacket(buf, buf.size)
                try { ssdp.receive(p) } catch (e: IOException) { break }
                if (!String(p.data, 0, p.length, Charsets.ISO_8859_1).startsWith("M-SEARCH")) continue
                if (control.boundPort <= 0 || !control.isRunning) continue // not up yet: the cameras do not answer either
                val answer = listOf("HTTP/1.1 200 OK", "CACHE-CONTROL: max-age=1800", "LOCATION: http://$advertisedHost:${control.boundPort}/smp_2_",
                    "ST: ${Upnp.MEDIA_SERVER_TYPE}", "USN: $udn::${Upnp.MEDIA_SERVER_TYPE}", "", "").joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
                runCatching { ssdp.send(java.net.DatagramPacket(answer, answer.size, p.socketAddress)) }
            }
        }
        val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(wantedStreamPort)); streamSocket = ss
        pool.execute {
            while (running.get()) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                pool.execute { stream(s) }
            }
        }
        if (profile.modeServer) {
            val ms = ServerSocket(0); modeSocket = ms
            pool.execute {
                while (running.get()) {
                    val s = try { ms.accept() } catch (e: IOException) { break }
                    s.use {
                        generateSequence { readLine(s) }.takeWhile { it.isNotEmpty() }.toList()
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nMVERSION: 1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                }
            }
        }
        log("RVF simulator (${profile.model}): control ${control.boundPort}, stream ${ss.localPort}")
        return this
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        control.stop()
        runCatching { streamSocket?.close() }
        runCatching { modeSocket?.close() }
        runCatching { ssdpSocket?.close() }
        endStreams()
        pool.shutdownNow()
    }

    private fun endStreams() { synchronized(streams) { streams.forEach { runCatching { it.close() } }; streams.clear() } }

    private fun handle(r: HttpRequest): HttpResponse {
        if (hung) { Thread.sleep(5_000); return HttpResponse(503, ByteArray(0), "text/plain") } // a hung camera answers nothing useful
        val ua = r.header("User-Agent") ?: ""
        if (r.path.startsWith("/DCIM/")) {
            val file = r.path.substringAfterLast('/')
            if (!file.startsWith("SAM_")) return HttpResponse.notFound()
            downloads += r.path
            return HttpResponse(200, if (file.endsWith("_SM")) preview else photo, "image/jpeg")
        }
        if (r.path != "/smp_2_") {
            if (ua.startsWith("SEC_")) violations += "pairing User-Agent on ${r.path}"
            if (r.remoteAddress !in paired) return HttpResponse(401, "Unauthorized".toByteArray(), "text/plain")
        }
        return when (r.path) {
            "/smp_2_" -> {
                descriptionRequests.incrementAndGet()
                if (System.currentTimeMillis() - controlUpAt < notReadyMs) return HttpResponse.notFound()
                userAgents += ua
                if (!ua.startsWith(profile.uaPrefix) || (profile.uaPrefix == RvfClient.UA_PREFIX && ua.startsWith(RvfClient.UA_PREFIX_ML))) {
                    return HttpResponse(401, "Unauthorized".toByteArray(), "text/plain")
                }
                val id = ua.removePrefix(profile.uaPrefix)
                if (id !in allowed) {
                    if (!allow(id)) { log("user said no to $id"); return HttpResponse(401, "Unauthorized".toByteArray(), "text/plain") }
                    allowed += id
                    log("user allowed $id")
                }
                paired += r.remoteAddress
                HttpResponse.xml(description())
            }
            "/smp_3_" -> HttpResponse.xml(scpd())
            "/smp_4_" -> soap(r)
            else -> HttpResponse.notFound()
        }
    }

    private fun soap(r: HttpRequest): HttpResponse {
        val action = r.header("SOAPACTION")?.trim('"', ' ')?.substringAfter('#') ?: ""
        val body = r.readBodyText()
        calls += action
        if (!busy.compareAndSet(false, true)) violations += "$action sent while another command was running"
        try {
            val declared = profile.actions[action] ?: run { violations += "undeclared action $action"; return fault(401, "Invalid Action") }
            for (arg in Regex("<u:$action[^>]*>(.*)</u:$action>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?.let { Regex("<([A-Za-z_]+)>").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()) {
                if (arg !in declared) { violations += "$action with undeclared argument $arg"; return fault(501, "Action Failed") }
            }
            for (arg in declared) if (!body.contains("<$arg>") && !body.contains("<$arg/>")) { violations += "$action without $arg"; return fault(402, "Invalid Args") }
            if (action != "GetInfomation" && !sessionStarted) violations += "$action before GetInfomation"
            if ((action == "MULTIAF" || action == "AF" || action == "Shot" || action == "ShotWithGPS") && streams.isEmpty()) {
                violations += "$action while nobody reads the live stream"
                return fault(501, "Action Failed")
            }
            if ((action == "Shot" || action == "ShotWithGPS") && failShots) return fault(803, "Capture Failed")
            val reply = answer(action, body)
            if (action == "Shot" || action == "ShotWithGPS") {
                if (shotPauseMs > 0) {
                    // NX1000: encoder stopped, picture taken, encoder started again, and only then the answer
                    pausedUntil = System.currentTimeMillis() + shotPauseMs
                    Thread.sleep(shotPauseMs)
                    if (headerOnRestart) restartPending = true
                }
                else if (endStreamDelayMs > 0) Thread { Thread.sleep(endStreamDelayMs); endStreams() }.start()
                else endStreams() // the camera stops the live view to take the picture
            }
            return HttpResponse.xml(Upnp.soapResponse(action, reply))
        } finally { busy.set(false) }
    }

    private fun answer(action: String, body: String): String = when (action) {
        "GetInfomation" -> {
            sessionStarted = true
            val s = "http://$advertisedHost:$streamPort"
            val urls = if (profile.version == null) "<GETIPRESULT>$s/livestream.avi</GETIPRESULT>"
                else "<StreamUrl><QualityHighUrl>$s/livestream.avi</QualityHighUrl><QualityLowUrl>$s/qvga_livestream.avi</QualityLowUrl></StreamUrl>"
            "<GETINFORMATIONRESULT>${profile.version?.let { "<Version>$it</Version>" } ?: ""}<Resolutions><Resolution><Width>5472</Width><Height>3648</Height></Resolution><Resolution><Width>3888</Width><Height>2592</Height></Resolution><Resolution><Width>1920</Width><Height>1080</Height></Resolution></Resolutions>" +
                "<Flash><Supports><Support>off</Support><Support>auto</Support><Support>fill</Support></Supports><Defaultflash>auto</Defaultflash></Flash>" +
                "<ZoomInfo><DefaultZoom>0</DefaultZoom><MaxZoom>${profile.maxZoom}</MaxZoom></ZoomInfo><AVAILSHOTS>$availShots</AVAILSHOTS><ROTATION>$rotation</ROTATION>" +
                "<StreamQuality><Quality><Option>high</Option><Option>low</Option></Quality><Default>high</Default></StreamQuality></GETINFORMATIONRESULT>" +
                urls
        }
        // frames in this model's own numbering: two on the right-hand side of the 3x5 NX grid (camera column 3 sits on the left), the centre column on the compacts
        "MULTIAF", "AF" -> if (!focusOk) "<AFSTATUS>AFFAIL</AFSTATUS>" else if (CameraModel(profile.model).isNx2012) "<AFSTATUS>1x3,2x3,</AFSTATUS>" else "<AFSTATUS>0x1,1x1,</AFSTATUS>"
        "Shot", "ShotWithGPS" -> {
            val n = shots.incrementAndGet()
            if (action == "ShotWithGPS") gps = arg(body, "GPSINFO")
            if (availShots > 0) availShots--
            if (emptyShotResult || body.contains("<REQUESTIMAGE>0</REQUESTIMAGE>")) "<AFSHOTRESULT>${if (emptyShotResult) "" else "none"}</AFSHOTRESULT><AVAILSHOTS>$availShots</AVAILSHOTS>"
            else "<AFSHOTRESULT>http://$advertisedHost:$controlPort/DCIM/100PHOTO/SAM_%04d.JPG</AFSHOTRESULT><AVAILSHOTS>$availShots</AVAILSHOTS>".format(n)
        }
        "SetResolution" -> { resolution = arg(body, "RESOLUTION"); "<AVAILSHOTS>$availShots</AVAILSHOTS>" }
        "SetLED" -> { led = arg(body, "LEDTIME"); "" }
        "ZoomIN" -> { zoom = minOf(profile.maxZoom, zoom + 1); "<CURRENTZOOM>$zoom</CURRENTZOOM>" }
        "ZoomOUT" -> { zoom = maxOf(0, zoom - 1); "<CURRENTZOOM>$zoom</CURRENTZOOM>" }
        "SetFlash" -> { flash = arg(body, "FLASHMODE"); "<CurrentFlashDisplay>$flash</CurrentFlashDisplay>" }
        else -> ""
    }

    private fun arg(body: String, name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: ""

    /** Sends the camera's SSDP notice [nts] about its ConnectionManager (rotation, byebye) to [to]. */
    /**
     * A NOTIFY like the camera's. [from] is the sender address (another camera = another address);
     * [usnUuid] / [nt] as the NX1000 sends its turn notices: a fixed UUID that need not be the one
     * in its description, for the root device as well as for the services.
     */
    fun sendNotice(nts: String, to: java.net.InetSocketAddress, from: String = "127.0.0.1", usnUuid: String = udn, nt: String = Upnp.CMS_SERVICE_TYPE) {
        val bytes = SsdpNotices.notify(nt, nts, "$usnUuid::$nt", "http://$advertisedHost:$controlPort/smp_2_")
        java.net.DatagramSocket(java.net.InetSocketAddress(from, 0)).use { it.send(java.net.DatagramPacket(bytes, bytes.size, to)) }
    }

    private fun fault(code: Int, text: String) = HttpResponse.xml(
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
        500,
    )

    private fun stream(s: Socket) {
        s.use {
            val head = generateSequence { readLine(s) }.takeWhile { it.isNotEmpty() }.toList()
            if (!sessionStarted) { violations += "live stream requested before GetInfomation"; s.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()); return }
            val admitted = synchronized(streams) { if (streams.isEmpty()) { streams += s; true } else false }
            if (!admitted) {
                violations += "second live stream reader"
                s.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
                return
            }
            streamOpens.incrementAndGet()
            // hangs a real NX1000: a live view opened while the encoder is stopped for a picture
            if (System.currentTimeMillis() < pausedUntil) { violations += "live stream opened while the camera takes the picture"; hung = true }
            try {
                val out = s.getOutputStream()
                out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/avi\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(AviFrameReader.header(width, height, codec))
                var i = 0
                while (running.get() && !s.isClosed) {
                    if (restartPending && System.currentTimeMillis() >= pausedUntil) {
                        restartPending = false
                        out.write(AviFrameReader.header(width, height, codec))
                    }
                    if (hung) { Thread.sleep(50); continue }
                    if (stallStream || System.currentTimeMillis() < pausedUntil) {
                        // no frames, but notice when the client gives up on the connection
                        s.soTimeout = 50
                        val gone = try { s.getInputStream().read() < 0 } catch (_: java.net.SocketTimeoutException) { false }
                        if (gone && System.currentTimeMillis() < pausedUntil) { violations += "live stream closed while the camera takes the picture"; hung = true }
                        if (gone) break else continue
                    }
                    out.write(AviFrameReader.chunk(frames[i % frames.size]))
                    out.flush()
                    framesSent.incrementAndGet()
                    i++
                    Thread.sleep(1000L / fps)
                }
            } catch (_: IOException) { // reader went away
            } catch (_: InterruptedException) {
            } finally { streams -= s }
        }
    }

    private fun readLine(s: Socket): String? {
        val b = StringBuilder()
        val input = s.getInputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            b.append(c.toChar())
        }
    }

    private fun description() = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device><dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC><deviceType>${Upnp.MEDIA_SERVER_TYPE}</deviceType>
<friendlyName>[Camera]${profile.model}</friendlyName><manufacturer>Samsung Electronics</manufacturer>
<modelDescription>Samsung Camera DMS</modelDescription><modelName>SP1</modelName><modelNumber>1.0</modelNumber>
<serialNumber>20081113 Folderview</serialNumber><UDN>$udn</UDN>
<serviceList>
<service><serviceType>${Upnp.CDS_SERVICE_TYPE}</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId><controlURL>/smp_4_</controlURL><eventSubURL>/smp_5_</eventSubURL><SCPDURL>/smp_3_</SCPDURL></service>
<service><serviceType>${Upnp.CMS_SERVICE_TYPE}</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><controlURL>/smp_7_</controlURL><eventSubURL>/smp_8_</eventSubURL><SCPDURL>/smp_6_</SCPDURL></service>
</serviceList></device></root>"""

    private fun scpd(): String = buildString {
        append("<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for ((name, ins) in profile.actions) {
            append("<action><name>$name</name><argumentList>")
            for (a in ins) append("<argument><name>$a</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_$a</relatedStateVariable></argument>")
            append("<argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>")
            append("</argumentList></action>")
        }
        append("</actionList></scpd>")
    }
}
