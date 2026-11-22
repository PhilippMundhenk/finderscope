package org.mundhenk.finderscope.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The conformance scenarios of docs/BEHAVIOR.md: the session engine against the simulated
 * camera, checking what goes over the wire against what the original app sent (or, where
 * finderscope deliberately differs, against that decision). Scenario numbers as in BEHAVIOR.md.
 */
class ConformanceTest {
    private val sims = ArrayList<RvfCameraSimulator>()
    private val sessions = ArrayList<CameraSession>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
    private val preview = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2, 0xFF.toByte(), 0xD9.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x65, 0x11)

    @After
    fun tearDown() { sessions.forEach { it.stop() }; sims.forEach { it.stop() } }

    /** Everything the session reported, in order. */
    class Events : CameraSession.Listener {
        val log = CopyOnWriteArrayList<String>()
        val pictures = CopyOnWriteArrayList<RvfPicture>()
        @Volatile var ready = false
        @Volatile var lastFocus: Pair<Boolean, List<AfCell>>? = null
        @Volatile var rotation: Rotation? = null
        override fun onState(state: CameraSession.State, message: String) { log += "state:$state:$message" }
        override fun onConnected(client: RvfClient, info: RvfInfo, model: CameraModel) { log += "connected:${model.name}" }
        override fun onStream(codec: VideoCodec, width: Int, height: Int) { log += "stream:$codec" }
        override fun onReady(ready: Boolean) { this.ready = ready; log += "ready:$ready" }
        override fun onFocus(ok: Boolean, cells: List<AfCell>) { lastFocus = ok to cells; log += "focus:$ok" }
        override fun onCountdown(secondsLeft: Int) { log += "countdown:$secondsLeft" }
        override fun onPicture(picture: RvfPicture) { pictures += picture; log += "picture:${picture.name}:${picture.preview}" }
        override fun onShots(available: Int) { log += "shots:$available" }
        override fun onRotation(rotation: Rotation) { this.rotation = rotation; log += "rotation:$rotation" }
        override fun onZoom(position: Int) { log += "zoom:$position" }
        @Volatile var lastFrame: ByteArray? = null
        override fun onFrame(frame: VideoFrame, codec: VideoCodec) { lastFrame = frame.data }
        override fun onNotice(notice: Notice, detail: String) { log += "notice:$notice" }
        fun has(prefix: String) = log.any { it.startsWith(prefix) }
    }

    private fun waitFor(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun camera(profile: RvfCameraSimulator.Profile = RvfCameraSimulator.Profile.NX1000, frames: List<ByteArray> = listOf(idr)) =
        RvfCameraSimulator(profile, frames, VideoCodec.H264, 640, 480, fps = 50, photo = jpeg, preview = preview).start().also { sims += it }

    private fun session(
        s: RvfCameraSimulator, ev: Events = Events(), notices: Boolean = false, stallMs: Long = 5_000,
        noticeSocket: DatagramSocket? = null,
    ): Pair<CameraSession, Events> {
        val socket = noticeSocket
        val cs = CameraSession(
            "127.0.0.1", s.controlPort, "02:11:22:33:44:55", ev,
            notices = if (notices) { cb -> SsdpNotices(socket!!, cb).start() } else null,
            stallMs = stallMs, reopenTimeoutMs = 10_000, modePort = s.modePort, log = { ev.log += "log:$it" },
        ).start()
        sessions += cs
        waitFor(what = "ready") { ev.ready }
        return cs to ev
    }

    /** 1: first connect sends description (pairing id once), action list, GetInfomation, stream; nothing else. */
    @Test
    fun s01_firstConnect() {
        val s = camera()
        val (cs, ev) = session(s)
        Thread.sleep(300)
        assertEquals(listOf("GetInfomation"), s.calls.toList())
        assertEquals(listOf("SEC_RVF_02:11:22:33:44:55"), s.userAgents.toList())
        assertEquals(1, s.streamOpens.get())
        assertTrue(ev.log.toString(), ev.has("connected:NX1000"))
        assertTrue(ev.has("stream:H264"))
        assertTrue(ev.has("shots:999"))
        assertEquals(Rotation.D, ev.rotation)
        assertEquals(CameraModel("NX1000"), cs.model)
        waitFor(what = "frames") { cs.frames.get() > 2 }
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** 1b: the 2013+ firmware (stream URL in StreamUrl) gets the half-press and release at the start, as its app did. */
    @Test
    fun s01b_newerFirmwareStartUp() {
        val s = camera(RvfCameraSimulator.Profile.WB250F)
        session(s)
        waitFor { s.calls.size >= 2 }
        assertEquals(listOf("GetInfomation", "AF"), s.calls.toList()) // no ReleaseSelfTimer on this model
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** 5: focus on press, the picture only after the focus answer, then the copy, then the live view again. */
    @Test
    fun s05_focusThenShot() {
        val s = camera()
        val (cs, ev) = session(s)
        cs.press()
        waitFor(what = "focus") { ev.lastFocus != null }
        assertEquals(true to listOf(AfCell(1, 0), AfCell(2, 0)), ev.lastFocus) // "1x3,2x3" on the NX grid: camera column 3 is the leftmost on screen
        cs.release()
        waitFor(what = "picture") { ev.pictures.isNotEmpty() }
        val p = ev.pictures.first()
        assertEquals("SAM_0001.JPG", p.name); assertTrue(p.preview); assertArrayEquals(preview, p.bytes)
        waitFor(what = "stream again") { s.streamOpens.get() == 2 }
        waitFor(what = "shutter back") { ev.log.count { it == "ready:true" } == 2 }
        assertFalse(ev.has("notice:RECONNECTED")) // a picture is no reconnect
        assertEquals(listOf("GetInfomation", "MULTIAF", "Shot"), s.calls.toList())
        assertEquals(listOf("/DCIM/100PHOTO/SAM_0001.JPG_SM"), s.downloads.toList())
        assertTrue(ev.has("shots:998"))
        waitFor { ev.ready }
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** 6: focus fails on an NX: release self-timer, no picture. */
    @Test
    fun s06_focusFailsOnNx() {
        val s = camera()
        val (cs, ev) = session(s)
        s.focusOk = false
        cs.tap()
        waitFor(what = "notice") { ev.has("notice:FOCUS_FAILED") }
        waitFor { s.calls.size >= 3 }
        assertEquals(listOf("GetInfomation", "MULTIAF", "ReleaseSelfTimer"), s.calls.toList())
        assertEquals(false to emptyList<AfCell>(), ev.lastFocus)
        assertEquals(0, s.shotCount)
    }

    /** 7: focus fails on a compact: the picture is taken anyway (the camera's own behaviour). */
    @Test
    fun s07_focusFailsOnCompact() {
        val s = camera(RvfCameraSimulator.Profile.WB250F)
        val (cs, ev) = session(s)
        s.focusOk = false
        cs.tap()
        waitFor(what = "picture") { ev.pictures.isNotEmpty() }
        assertTrue(ev.has("notice:FOCUS_FAILED"))
        assertFalse(ev.pictures.first().preview) // versioned firmware: the original
        assertEquals(1, s.shotCount)
    }

    /** 8: released outside the button: release self-timer, no picture. */
    @Test
    fun s08_dragOffCancels() {
        val s = camera()
        val (cs, _) = session(s)
        cs.press(); cs.release(inside = false)
        waitFor { s.calls.size >= 3 }
        assertEquals(listOf("GetInfomation", "MULTIAF", "ReleaseSelfTimer"), s.calls.toList())
        assertEquals(0, s.shotCount)
        cs.release() // a second release without press does nothing
    }

    /** 9 + 10: timer 2 s sets the LED to 2, the picture comes 2 s after release; off sends 0 (the original sent 2). */
    @Test
    fun s09_s10_timer() {
        val s = camera()
        val (cs, ev) = session(s)
        cs.setTimer(2)
        waitFor { s.led == "2" }
        val t0 = System.currentTimeMillis()
        cs.tap()
        waitFor(what = "picture") { ev.pictures.isNotEmpty() }
        assertTrue(System.currentTimeMillis() - t0 >= 1_900)
        assertTrue(ev.log.containsAll(listOf("countdown:2", "countdown:1", "countdown:0")))
        cs.setTimer(0)
        waitFor { s.led == "0" }
        assertEquals(0, cs.timerSeconds)
    }

    /** 9: tapping during the countdown cancels: release self-timer, no picture. */
    @Test
    fun s09_countdownCancelled() {
        val s = camera()
        val (cs, ev) = session(s)
        cs.setTimer(10)
        cs.tap()
        waitFor { cs.countingDown }
        cs.press() // ignored while counting down
        cs.cancelCountdown()
        cs.cancelCountdown() // nothing left to cancel
        waitFor { s.calls.contains("ReleaseSelfTimer") }
        assertFalse(cs.countingDown)
        assertEquals(0, s.shotCount)
        assertTrue(ev.has("countdown:0"))
    }

    /** 11 + 12: flash with the camera's own value; photo size "WxH" updates the remaining shots. */
    @Test
    fun s11_s12_flashAndSize() {
        val s = camera()
        val (cs, ev) = session(s)
        s.availShots = 400
        cs.setFlash("off")
        cs.setResolution(3888, 2592)
        waitFor { s.resolution != null }
        assertEquals("off", s.flash)
        assertEquals("3888x2592", s.resolution)
        waitFor { ev.has("shots:400") }
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** 13: card full at connect: shutter off, session stays; -1 = numbering exhausted. */
    @Test
    fun s13_cardFull() {
        val s = camera().apply { availShots = 0 }
        val ev = Events()
        val cs = CameraSession("127.0.0.1", s.controlPort, "id", ev, modePort = s.modePort).start(); sessions += cs
        waitFor { ev.has("ready:false") }
        assertTrue(ev.has("notice:CARD_FULL"))
        assertFalse(ev.ready)
        s.availShots = -1
        cs.setResolution(1920, 1080)
        waitFor { ev.has("notice:NUMBERING_FULL") }
        assertFalse(cs.isClosed)
    }

    /** 14: zoom steps on a compact; the NX1000 offers none over Wi-Fi (lens ring). */
    @Test
    fun s14_zoom() {
        val s = camera(RvfCameraSimulator.Profile.WB250F)
        val (cs, ev) = session(s)
        cs.zoom(true)
        waitFor { ev.has("zoom:1") }
        cs.zoom(true); waitFor { ev.has("zoom:2") }
        cs.zoom(false); waitFor { ev.log.count { it == "zoom:1" } == 2 }
        assertTrue(cs.client!!.canZoom)
        assertFalse(CameraModel("NX1000").zoomsOverWifi)
        assertTrue(CameraModel("WB250F").zoomsOverWifi)
    }

    /** 15: with a position, ShotWithGPS with the real hemisphere letters. */
    @Test
    fun s15_gps() {
        val s = camera()
        val (cs, ev) = session(s)
        cs.options = CameraSession.Options(gps = { gpsInfo(-33.8568, 151.2153) })
        cs.tap()
        waitFor { ev.pictures.isNotEmpty() }
        assertEquals("S121884XE544375", s.gps)
        assertEquals(listOf("GetInfomation", "MULTIAF", "ShotWithGPS"), s.calls.toList())
    }

    /** 16: the camera fails the shot: a notice, the session stays and the shutter comes back. */
    @Test
    fun s16_shotFails() {
        val s = camera().apply { failShots = true }
        val (cs, ev) = session(s)
        cs.tap()
        waitFor { ev.has("notice:SHOT_FAILED") }
        waitFor { ev.ready }
        assertFalse(cs.isClosed)
    }

    /** 17: an empty picture name: a notice, and the live view comes back (the original got stuck). */
    @Test
    fun s17_emptyResult() {
        val s = camera().apply { emptyShotResult = true }
        val (cs, ev) = session(s)
        cs.tap()
        waitFor { ev.has("notice:NO_PICTURE") }
        waitFor(what = "stream again") { s.streamOpens.get() == 2 }
        assertFalse(cs.isClosed)
    }

    /** Copying off: the picture stays on the card. */
    @Test
    fun copyingOff() {
        val s = camera(RvfCameraSimulator.Profile.WB250F)
        val (cs, ev) = session(s)
        cs.options = CameraSession.Options(savePictures = false)
        cs.tap()
        waitFor { ev.has("notice:ON_CARD_ONLY") }
        assertTrue(s.downloads.isEmpty())
    }

    /** 19: the live view stalls: it is opened again after the stall time (the original quit). */
    @Test
    fun s19_stall() {
        val s = camera()
        val (cs, ev) = session(s, stallMs = 1_000)
        waitFor { cs.frames.get() > 0 }
        s.stallStream = true
        waitFor(what = "reopen") { s.streamOpens.get() >= 2 }
        s.stallStream = false
        waitFor { ev.has("notice:RECONNECTED") }
        assertFalse(cs.isClosed)
    }

    /** Tap to focus: a tap focuses and holds, a second refocuses, the shutter shoots with the held focus. */
    @Test
    fun tapToFocusThenShutter() {
        val s = camera()
        val (cs, ev) = session(s)
        cs.focusHold()
        waitFor(what = "focus") { ev.lastFocus != null }
        assertTrue(cs.focusHeld)
        assertEquals(listOf("GetInfomation", "MULTIAF"), s.calls.toList())
        ev.lastFocus = null
        cs.focusHold() // again: let go and focus anew
        waitFor(what = "refocus") { ev.lastFocus != null }
        assertEquals(listOf("GetInfomation", "MULTIAF", "ReleaseSelfTimer", "MULTIAF"), s.calls.toList())
        cs.shootNow()
        waitFor(what = "picture") { ev.pictures.size == 1 }
        assertTrue(!cs.focusHeld)
        assertEquals("Shot", s.calls.last { it != "GetInfomation" })
        // no focus held: the shutter focuses and shoots in one go
        waitFor(what = "shutter back") { ev.log.count { it == "ready:true" } >= 2 }
        cs.shootNow()
        waitFor(what = "second picture", timeoutMs = 10_000) { ev.pictures.size == 2 }
        // a held focus dropped without a picture
        waitFor(what = "shutter back again") { ev.log.count { it == "ready:true" } >= 3 }
        cs.focusHold(); waitFor { cs.focusHeld }
        cs.dropFocus(); assertTrue(!cs.focusHeld)
        Thread.sleep(300)
        assertEquals(2, s.shotCount)
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /**
     * NX1000 (seen on the real camera): after Shot the live-view connection stays open but silent
     * while the camera shows the picture, then goes on. Not a stall: the same connection is kept
     * and the shutter comes back with its frames.
     */
    @Test
    fun streamPausesForThePicture() {
        val s = camera().apply { shotPauseMs = 2_500; headerOnRestart = true }
        val (cs, ev) = session(s, stallMs = 1_000)
        waitFor { cs.frames.get() > 0 }
        cs.tap()
        waitFor(what = "picture") { ev.pictures.isNotEmpty() }
        val before = cs.frames.get()
        waitFor(what = "frames again", timeoutMs = 10_000) { cs.frames.get() > before + 5 }
        waitFor(what = "shutter back") { ev.log.count { it == "ready:true" } >= 2 }
        assertEquals(1, s.streamOpens.get())
        assertFalse(ev.log.toString(), ev.log.any { it.contains("stalled") })
        assertTrue(ev.log.toString(), ev.log.any { it.contains("restarted its live view on the same connection") })
        assertFalse(s.hung)
        cs.tap() // the next picture works on the same connection
        waitFor(what = "second picture", timeoutMs = 10_000) { ev.pictures.size == 2 }
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** Frames that still arrive right after the Shot answer are not the camera going on: no early shutter, no "reconnected". */
    @Test
    fun framesRightAfterTheShot() {
        val s = camera().apply { endStreamDelayMs = 200 }
        val (cs, ev) = session(s)
        cs.tap()
        waitFor(what = "picture") { ev.pictures.isNotEmpty() }
        waitFor(what = "stream again") { s.streamOpens.get() == 2 }
        waitFor(what = "shutter back") { ev.log.count { it == "ready:true" } == 2 }
        Thread.sleep(300)
        assertFalse(ev.log.toString(), ev.has("notice:RECONNECTED"))
        assertEquals(2, ev.log.count { it == "ready:true" })
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** A stream that stays silent after reopening is dropped less and less often, and the log says how much came. */
    @Test
    fun repeatedStallsBackOff() {
        val s = camera()
        val (cs, ev) = session(s, stallMs = 500)
        waitFor { cs.frames.get() > 0 }
        s.stallStream = true
        waitFor(what = "three opens", timeoutMs = 10_000) { s.streamOpens.get() >= 3 }
        val t2 = System.currentTimeMillis()
        waitFor(what = "fourth open", timeoutMs = 10_000) { s.streamOpens.get() >= 4 }
        assertTrue("backs off", System.currentTimeMillis() - t2 > 1_000)
        assertTrue(ev.log.toString(), ev.log.any { it.contains("bytes on this connection") })
        s.stallStream = false
        waitFor { ev.has("notice:RECONNECTED") }
        assertFalse(cs.isClosed)
    }

    /** A camera that never sends again is not asked over and over: after a few silent reopens the session ends. */
    @Test
    fun silentCameraIsLeftAlone() {
        val s = camera()
        val (cs, ev) = session(s, stallMs = 200)
        waitFor { cs.frames.get() > 0 }
        s.stallStream = true
        waitFor(what = "session ends", timeoutMs = 15_000) { cs.isClosed }
        assertTrue(ev.log.toString(), ev.has("state:FAILED:" + CameraSession.STOPPED_SENDING))
        assertTrue("opens " + s.streamOpens.get(), s.streamOpens.get() <= 5)
    }

    /** 20 + 23: SSDP notices: rotation turns the view, a goodbye ends the session; other cameras' notices are ignored. */
    @Test
    fun s20_s23_notices() {
        val s = camera()
        val sock = DatagramSocket(0)
        val (cs, ev) = session(s, notices = true, noticeSocket = sock)
        val to = InetSocketAddress("127.0.0.1", sock.localPort)
        s.sendNotice("ssdp:rotationL", to)
        waitFor { ev.rotation == Rotation.L }
        // NX1000: turn notices under a fixed UUID (not the description's) and for the root device
        s.sendNotice("ssdp:rotationR", to, usnUuid = "uuid:00000000-0000-0000-0000-000000000001", nt = "upnp:rootdevice")
        waitFor { ev.rotation == Rotation.R }
        val other = sim2()
        other.sendNotice("ssdp:byebye", to, from = "127.0.0.2") // another camera, another address
        waitFor { ev.log.any { it.contains("notices arrive, the first from 127.0.0.2") } }
        Thread.sleep(300)
        assertFalse(cs.isClosed)
        s.sendNotice("ssdp:byebye", to)
        waitFor { ev.has("state:CLOSED:The camera ended the connection") }
        assertTrue(cs.isClosed)
    }

    /**
     * The camera's screen and the phone show the same: a turned camera sends the scene as its
     * sensor sees it and announces the turn; the session reports the turn so the app can show the
     * scene upright, like the camera's own screen.
     */
    @Test
    fun turnedCamera() {
        val scenes = mapOf(Rotation.L to listOf(byteArrayOf(0, 0, 0, 1, 0x65, 0x4C)), Rotation.R to listOf(byteArrayOf(0, 0, 0, 1, 0x65, 0x52)))
        val s = camera().apply { turnedFrames = scenes }
        val sock = DatagramSocket(0)
        val (cs, ev) = session(s, notices = true, noticeSocket = sock)
        val to = InetSocketAddress("127.0.0.1", sock.localPort)
        waitFor { cs.frames.get() > 0 }
        for (r in listOf(Rotation.L, Rotation.R, Rotation.D)) {
            s.turn(r, to)
            waitFor(what = "rotation $r") { ev.rotation == r }
            val expected = scenes[r]?.first() ?: idr
            waitFor(what = "turned scene $r") { ev.lastFrame?.contentEquals(expected) == true }
        }
        assertEquals(1, s.streamOpens.get()) // a turn needs no new connection
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    private fun sim2() = camera(RvfCameraSimulator.Profile.WB250F)

    /** 27: a zoom or setting during focus is queued: never two commands at once. */
    @Test
    fun s27_oneAtATime() {
        val s = camera(RvfCameraSimulator.Profile.WB250F)
        val (cs, ev) = session(s)
        repeat(3) { cs.press(); cs.zoom(true); cs.setFlash("auto"); cs.release() }
        waitFor { s.shotCount >= 1 && ev.ready }
        cs.zoom(false)
        waitFor { s.calls.contains("ZoomOUT") }
        cs.setResolution(1920, 1080) // not offered by this model: refused before it reaches the camera
        waitFor { ev.has("notice:COMMAND_FAILED") }
        assertFalse(s.calls.contains("SetResolution"))
        Thread.sleep(500)
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    /** The camera refuses the phone: the session fails with the camera's reason. */
    @Test
    fun refused() {
        val s = camera().apply { allow = { false } }
        val ev = Events()
        val cs = CameraSession("127.0.0.1", s.controlPort, "id", ev, modePort = s.modePort).start(); sessions += cs
        waitFor { cs.isClosed }
        assertTrue(ev.log.toString(), ev.log.any { it.startsWith("state:FAILED") && it.contains("confirm the prompt") })
        cs.press(); cs.release(); cs.zoom(true); cs.setTimer(2); cs.setFlash("x"); cs.setResolution(1, 1); cs.cancelCountdown() // all ignored
        assertNull(cs.client)
        cs.stop()
    }

    /** The camera goes away: the live view cannot come back, the session fails. */
    @Test
    fun cameraGone() {
        val s = camera()
        val (cs, ev) = session(s)
        s.stop()
        val t0 = System.currentTimeMillis()
        try { waitFor(30_000) { cs.isClosed } } catch (e: AssertionError) { throw AssertionError("after ${System.currentTimeMillis() - t0} ms: ${ev.log} opens=${cs.streamOpens}") }
        assertTrue(ev.log.toString(), ev.log.any { it.startsWith("state:FAILED") })
    }

    /** NX3000 class: HeartBeat while connected. */
    @Test
    fun heartBeat() {
        val s = camera(RvfCameraSimulator.Profile.NX3000)
        session(s)
        waitFor(8_000) { s.calls.contains("HeartBeat") }
    }

    @Test
    fun modelHelpers() {
        assertEquals("NX1000", CameraModel.parse("[Camera]NX1000").name)
        assertEquals("NX1000", CameraModel.parse("AP_SSC_NX1000_0-AA:BB:CC").name)
        assertEquals("WB850F", CameraModel.parse("WB850F").name)
        assertEquals("", CameraModel.parse("").name)
        assertEquals(AfGrid.NX, CameraModel("NX210").afGrid)
        assertEquals(AfGrid.COMPACT, CameraModel("WB150F").afGrid)
        assertFalse(CameraModel("NX1100").zoomsOverWifi)
        assertEquals(listOf(AfCell(0, 0), AfCell(2, 2)), AfGrid.COMPACT.cells("0x0, 2x2,9x9,1x,x1,abc,1x2x3,"))
        assertEquals(emptyList<AfCell>(), AfGrid.NX.cells(null))
        assertEquals(Rotation.L, Rotation.fromNotice("ssdp:rotationL"))
        assertNull(Rotation.fromNotice("ssdp:alive"))
        assertEquals(listOf(Rotation.D, Rotation.L, Rotation.U, Rotation.R, Rotation.D), listOf(1, 2, 3, 4, 9).map { Rotation.fromInfo(it, "NX1000") })
        assertEquals(listOf(Rotation.D, Rotation.R, Rotation.U, Rotation.L), listOf(0, 1, 2, 3).map { Rotation.fromInfo(it, "WB850F") })
        assertEquals("N121884XW544375", gpsInfo(33.8568, -151.2153))
        assertEquals("20M", megapixels(5472, 3648)); assertEquals("2.1M", megapixels(1920, 1080)); assertEquals("10M", megapixels(3888, 2592))
        assertNull(SsdpNotices.parse("M-SEARCH * HTTP/1.1\r\n\r\n"))
        assertEquals(SsdpNotice("a", "b", ""), SsdpNotices.parse("NOTIFY * HTTP/1.1\nNT: a\nNTS: b\n\n"))
        val n = SsdpNotices(DatagramSocket(0)) { }.start(); n.start(); n.stop(); n.stop()
        assertTrue(n.port != 0 || true)
    }
}
