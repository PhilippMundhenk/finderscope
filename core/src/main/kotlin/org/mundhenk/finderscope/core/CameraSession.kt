package org.mundhenk.finderscope.core

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Things the session tells the user about; the app turns them into text. */
enum class Notice {
    /** The camera could not focus; on NX models no picture was taken. */
    FOCUS_FAILED,
    /** The card is full. */
    CARD_FULL,
    /** Folder / file numbering on the card is exhausted (remaining shots -1). */
    NUMBERING_FULL,
    /** The camera reported that it could not take the picture (UPnP 803 or another fault). */
    SHOT_FAILED,
    /** The camera took a picture but did not say where it is (nothing to fetch). */
    NO_PICTURE,
    /** The picture is on the card; it was not copied (copying is off, or the camera offers none). */
    ON_CARD_ONLY,
    /** A setting or zoom command failed; detail says which. */
    COMMAND_FAILED,
    /** The live view stalled or ended and was opened again. */
    RECONNECTED,
}

/**
 * One Remote Viewfinder session, all behaviour and no UI. Behaves like the original apps where
 * the cameras need it (see docs/BEHAVIOR.md) and better where they did not:
 *
 * * one command at a time, ever (a single command thread), so focus, shutter, zoom and settings
 *   never overlap on the camera;
 * * the live stream is opened right after GetInfomation, read on its own thread, watched for
 *   stalls and opened again after every picture and after a stall or a dropped connection;
 * * shutter semantics of the camera: [press] focuses, [release] takes the picture (or, released
 *   outside the button, cancels), with the self-timer counted down here; [tap] does both;
 * * camera notices (rotation, goodbye) from SSDP, when a [notices] factory is given.
 *
 * Listener calls come from the session's threads; the app hands them to its UI thread.
 */
class CameraSession(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val listener: Listener,
    private val http: RvfHttp = RvfHttp(),
    /** Starts a notice listener for this session, or null for none. */
    private val notices: ((onNotice: (SsdpNotice) -> Unit) -> SsdpNotices?)? = null,
    private val stallMs: Long = 5_000,
    private val reopenTimeoutMs: Long = 60_000,
    /** After a picture the NX1000 keeps the stream open but silent while it shows the picture: no stall for this long. */
    private val afterShotQuietMs: Long = 20_000,
    private val modePort: Int = RvfClient.MODE_PORT,
    private val log: (String) -> Unit = {},
    /** Finds the camera by SSDP for up to the given time (null: not available); see [SsdpSearch]. */
    private val discover: ((timeoutMs: Long) -> CameraLocation?)? = null,
    /** How long to keep looking for the camera after joining its network (its servers start late). */
    private val findTimeoutMs: Long = 60_000,
) {
    enum class State { CONNECTING, LIVE, CLOSED, FAILED }

    companion object {
        /** [Listener.onState] message while the camera does not answer yet. */
        const val WAITING = "waiting for the camera"
    }

    /** What the user can set on the shutter side. */
    data class Options(
        /** Copy every picture to the phone (the camera's own bytes). */
        val savePictures: Boolean = true,
        /** Where the phone is, for ShotWithGPS; null = no GPS tag. */
        val gps: (() -> String?)? = null,
    )

    interface Listener {
        fun onState(state: State, message: String) {}
        fun onConnected(client: RvfClient, info: RvfInfo, model: CameraModel) {}
        fun onStream(codec: VideoCodec, width: Int, height: Int) {}
        /** A live-view frame, on the stream thread. */
        fun onFrame(frame: VideoFrame, codec: VideoCodec) {}
        /** Whether the shutter can be used now. */
        fun onReady(ready: Boolean) {}
        fun onFocus(ok: Boolean, cells: List<AfCell>) {}
        fun onCountdown(secondsLeft: Int) {}
        fun onPicture(picture: RvfPicture) {}
        fun onShots(available: Int) {}
        fun onRotation(rotation: Rotation) {}
        fun onZoom(position: Int) {}
        fun onNotice(notice: Notice, detail: String = "") {}
    }

    @Volatile var options = Options()
    @Volatile var client: RvfClient? = null; private set
    @Volatile var model = CameraModel(""); private set
    @Volatile var timerSeconds = 0; private set
    val streamOpens = AtomicInteger(0)
    val frames = AtomicLong(0)

    private val commands: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "camera-commands").apply { isDaemon = true } }
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "camera-timers").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val captureDone = Object()
    private val pressed = AtomicBoolean(false)
    private val zoomPending = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0)
    /** The live view is open and being read: AF and Shot only work then (NX1000). */
    private val live = AtomicBoolean(false)
    private val liveChanged = Object()
    /** The last stream end was the camera's own, for a picture (no "reconnected" notice). */
    private val endedByShot = AtomicBoolean(false)
    @Volatile private var focus: RvfFocus? = null
    @Volatile private var countdown: ScheduledFuture<*>? = null
    @Volatile private var stream: RvfHttpResponse? = null
    @Volatile private var noticeListener: SsdpNotices? = null

    fun start(): CameraSession {
        listener.onState(State.CONNECTING, "Connecting to $host")
        Thread({ run() }, "camera-stream").apply { isDaemon = true }.start()
        return this
    }

    /**
     * The camera's servers are not up the moment the phone joins its network, and some firmware
     * announces its description elsewhere: keep trying the address we have, and ask SSDP in between,
     * until the camera answers or [findTimeoutMs] is over.
     */
    private fun connect(): Pair<RvfClient, RvfInfo> {
        var at = CameraLocation(host, port, "/smp_2_")
        val deadline = System.currentTimeMillis() + findTimeoutMs
        var waiting = false
        while (true) {
            val c = RvfClient(at.host, at.port, clientId, http, log, modePort = modePort, descriptionPath = at.path)
            try { return c to c.connect() } catch (e: IOException) {
                if (e is RvfException || !notYetThere(e) || closed.get()) throw e
                if (System.currentTimeMillis() > deadline) throw RvfException("the camera did not answer at ${at.host}. Is Remote Viewfinder on, and the phone on the camera's network?")
                if (!waiting) { waiting = true; listener.onState(State.CONNECTING, WAITING); log("camera not answering yet (${e.message}), looking for it") }
                val found = discover?.invoke(1_500)
                if (found != null && found != at) { log("camera found at $found"); at = found } else if (discover == null) Thread.sleep(1_000)
            }
        }
    }

    /** Errors that mean "nobody there (yet)", as opposed to a camera that answered badly. */
    private fun notYetThere(e: IOException): Boolean =
        e is java.net.ConnectException || e is java.net.NoRouteToHostException || e is java.net.SocketTimeoutException ||
            (e is java.net.SocketException && e.message.orEmpty().contains("refused", ignoreCase = true))

    private fun run() {
        try {
            val (c, info) = connect()
            client = c
            model = CameraModel.parse(c.friendlyName.ifBlank { c.modelName })
            noticeListener = notices?.invoke { onNotice(it) }
            info.availShots?.let { shotsChanged(it) }
            listener.onRotation(Rotation.fromInfo(info.rotation, model.name))
            listener.onConnected(c, info, model)
            listener.onState(State.LIVE, c.friendlyName.ifBlank { host })
            if (c.has("HeartBeat")) commands.scheduleWithFixedDelay({ if (!capturing.get()) runCatching { c.heartBeat() } }, 3, 3, TimeUnit.SECONDS)
            timers.scheduleWithFixedDelay({ watchStall() }, 1, 1, TimeUnit.SECONDS)
            var first = true
            while (!closed.get()) {
                val reader = if (first) open(c) else reopen(c) ?: break
                if (first) {
                    // The 2013+ firmware (stream URL in StreamUrl) was driven with a half-press and release right away; the 2012 one was not.
                    if (!info.fromGetIp && c.canFocus) command { c.focus(); c.releaseFocus() }
                } else if (!endedByShot.getAndSet(false)) command { listener.onNotice(Notice.RECONNECTED) }
                command { listener.onReady(shutterUsable()) }
                first = false
                try { pump(reader) } catch (e: IOException) { if (closed.get()) break else log("live view interrupted: ${e.message}") }
                setLive(false)
            }
        } catch (e: Exception) {
            if (!closed.get()) fail(e.message ?: e.toString())
        }
    }

    @Volatile private var available: Int? = null

    private fun shutterUsable() = available != 0 && available != -1

    private fun setLive(on: Boolean) { live.set(on); synchronized(liveChanged) { liveChanged.notifyAll() } }

    /** On the command thread: hold AF / Shot until the live view is back (after a picture or a stall). */
    private fun awaitLive() {
        val deadline = System.currentTimeMillis() + reopenTimeoutMs
        synchronized(liveChanged) {
            while (!live.get() && !closed.get() && System.currentTimeMillis() < deadline) liveChanged.wait(100)
        }
    }

    private fun shotsChanged(n: Int) {
        available = n
        listener.onShots(n)
        when (n) {
            0 -> listener.onNotice(Notice.CARD_FULL)
            -1 -> listener.onNotice(Notice.NUMBERING_FULL)
        }
    }

    @Volatile private var reader: AviFrameReader? = null
    @Volatile private var quietUntil = 0L
    /** Stalls in a row without a frame: each one waits longer before the stream is dropped again. */
    private val stallsInRow = AtomicInteger(0)

    private fun open(c: RvfClient): AviFrameReader {
        val (resp, reader) = c.openStream()
        this.reader = reader
        quietUntil = 0 // a new connection: the usual stall rule
        stream = resp
        streamOpens.incrementAndGet()
        lastFrameAt.set(System.currentTimeMillis())
        if (closed.get()) resp.close() else setLive(true)
        return reader
    }

    /** Waits for a picture in progress, then opens the live view again, for up to [reopenTimeoutMs]. Null = gave up. */
    private fun reopen(c: RvfClient): AviFrameReader? {
        runCatching { stream?.close() }
        synchronized(captureDone) { while (capturing.get() && !closed.get()) captureDone.wait(200) }
        val deadline = System.currentTimeMillis() + reopenTimeoutMs
        var last: Exception? = null
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            try { return open(c) } catch (e: Exception) { last = e; Thread.sleep(500) }
        }
        if (!closed.get()) fail("The live view did not come back${last?.message?.let { " ($it)" } ?: ""}")
        return null
    }

    private fun pump(reader: AviFrameReader) {
        var announced = false
        while (!closed.get()) {
            val frame = reader.next() ?: return
            lastFrameAt.set(System.currentTimeMillis())
            stallsInRow.set(0)
            frames.incrementAndGet()
            if (!live.get()) {
                // the same connection goes on after a picture (NX1000): the shutter is back
                setLive(true)
                if (endedByShot.getAndSet(false)) command { listener.onReady(shutterUsable()) }
            }
            if (!announced) { announced = true; listener.onStream(reader.codec, reader.width, reader.height) }
            listener.onFrame(frame, reader.codec)
        }
    }

    /** No frame for [stallMs] while no picture is being taken: drop the stream, the pump opens it again. */
    private fun watchStall() {
        if (closed.get() || capturing.get() || stream == null) return
        val now = System.currentTimeMillis()
        if (now < quietUntil) return
        val limit = stallMs * (1 + minOf(stallsInRow.get(), 5))
        val silent = now - maxOf(lastFrameAt.get(), quietUntil)
        if (silent > limit) {
            stallsInRow.incrementAndGet()
            log("live view stalled: no picture for ${silent / 1000} s (${reader?.bytesRead ?: 0} bytes on this connection), opening it again")
            lastFrameAt.set(now)
            runCatching { stream?.close() }
        }
    }

    private fun onNotice(n: SsdpNotice) {
        val c = client ?: return
        if (c.udn.isEmpty() || !n.usn.contains(c.udn.removePrefix("uuid:"))) return
        when {
            n.nts.equals("ssdp:byebye", ignoreCase = true) -> end("The camera ended the connection")
            n.nt.contains("ConnectionManager") -> Rotation.fromNotice(n.nts)?.let { listener.onRotation(it) }
        }
    }

    // ---- shutter -------------------------------------------------------------------------

    /** Half-press: focus. Ignored while a picture is being taken or before the session is live. */
    fun press() {
        val c = client ?: return
        if (capturing.get() || countdown != null || !pressed.compareAndSet(false, true)) return
        focus = null
        command {
            awaitLive()
            val f = if (c.canFocus) c.focus() else RvfFocus(true, null)
            focus = f
            listener.onFocus(f.ok, model.afGrid.cells(f.status))
        }
    }

    /**
     * Full press after [press]. [inside] = false (finger left the button) cancels. A focus that
     * failed on an NX cancels too, like on the camera; compacts take the picture anyway.
     */
    fun release(inside: Boolean = true) {
        val c = client ?: return
        if (!pressed.compareAndSet(true, false)) return
        command {
            val f = focus ?: RvfFocus(true, null)
            when {
                !inside -> c.releaseFocus()
                !f.ok && model.cancelsShotOnFocusFail -> { c.releaseFocus(); listener.onNotice(Notice.FOCUS_FAILED) }
                timerSeconds > 0 -> startCountdown(c)
                else -> { if (!f.ok) listener.onNotice(Notice.FOCUS_FAILED); shoot(c) }
            }
        }
    }

    /** Press and release in one go. */
    fun tap() { press(); release(true) }

    /** Stops a running self-timer countdown; the camera's focus lock is released. */
    fun cancelCountdown() {
        val c = client ?: return
        val cd = countdown ?: return
        cd.cancel(false)
        countdown = null
        listener.onCountdown(0)
        command { c.releaseFocus() }
    }

    val countingDown: Boolean get() = countdown != null

    private fun startCountdown(c: RvfClient) {
        val left = AtomicInteger(timerSeconds)
        listener.onCountdown(left.get())
        countdown = timers.scheduleAtFixedRate({
            val n = left.decrementAndGet()
            listener.onCountdown(n)
            if (n <= 0) { countdown?.cancel(false); countdown = null; command { shoot(c) } }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** On the command thread. The camera ends the live view for the picture; it is opened again afterwards. */
    private fun shoot(c: RvfClient) {
        awaitLive()
        capturing.set(true)
        listener.onReady(false)
        var streamEnded = false
        try {
            val shot = try { c.shoot(toPhone = options.savePictures, gps = options.gps?.invoke()) } catch (e: RvfException) {
                listener.onNotice(Notice.SHOT_FAILED, e.message ?: "")
                return
            }
            // the camera ends the live view for every picture; the shutter comes back with it
            streamEnded = true
            endedByShot.set(true)
            setLive(false)
            quietUntil = System.currentTimeMillis() + afterShotQuietMs
            val url = shot.url
            when {
                url == null -> listener.onNotice(if (options.savePictures) Notice.NO_PICTURE else Notice.ON_CARD_ONLY)
                !options.savePictures -> listener.onNotice(Notice.ON_CARD_ONLY)
                else -> try { listener.onPicture(c.downloadShot(url)) } catch (e: IOException) { listener.onNotice(Notice.ON_CARD_ONLY, e.message ?: "") }
            }
            // after the picture: "card full" must not be covered by the "saved" message
            shot.availShots?.let { shotsChanged(it) }
        } finally {
            capturing.set(false)
            synchronized(captureDone) { captureDone.notifyAll() }
            if (!streamEnded) listener.onReady(shutterUsable())
        }
    }

    // ---- settings ------------------------------------------------------------------------

    /** 0 (off), 2 or 10 seconds; the camera's LED is told too, where it has one. */
    fun setTimer(seconds: Int) {
        timerSeconds = seconds
        val c = client ?: return
        if (c.has("SetLED")) command { tryCommand("timer") { c.setTimer(seconds) } }
    }

    fun setFlash(mode: String) { val c = client ?: return; command { tryCommand("flash") { c.setFlash(mode) } } }

    fun setResolution(width: Int, height: Int) {
        val c = client ?: return
        command { tryCommand("photo size") { c.setResolution(width, height)?.let { shotsChanged(it) } } }
    }

    /** One zoom step; dropped while the previous one is still on its way (holding the button repeats). */
    fun zoom(tele: Boolean) {
        val c = client ?: return
        if (!zoomPending.compareAndSet(false, true)) return
        command { try { tryCommand("zoom") { c.zoom(tele)?.let { listener.onZoom(it) } } } finally { zoomPending.set(false) } }
    }

    private fun tryCommand(what: String, block: () -> Unit) {
        try { block() } catch (e: IOException) { listener.onNotice(Notice.COMMAND_FAILED, "$what: ${e.message}") }
    }

    private fun command(block: () -> Unit) {
        if (closed.get()) return
        try {
            commands.execute {
                try { block() } catch (e: Exception) { listener.onNotice(Notice.COMMAND_FAILED, e.message ?: e.toString()) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* closed meanwhile */ }
    }

    // ---- end -----------------------------------------------------------------------------

    private fun fail(message: String) {
        if (closed.getAndSet(true)) return
        log(message)
        shutdown()
        listener.onState(State.FAILED, message)
    }

    private fun end(message: String) {
        if (closed.getAndSet(true)) return
        shutdown()
        listener.onState(State.CLOSED, message)
    }

    /** Ends the session; the cameras need no goodbye, closing the live view is enough. */
    fun stop() = end("Disconnected")

    private fun shutdown() {
        commands.shutdownNow(); timers.shutdownNow()
        countdown = null
        synchronized(captureDone) { captureDone.notifyAll() }
        setLive(false)
        noticeListener?.stop()
        val s = stream
        Thread { runCatching { s?.close() } }.start()
    }

    val isClosed: Boolean get() = closed.get()
}
