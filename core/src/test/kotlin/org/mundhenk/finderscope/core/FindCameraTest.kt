package org.mundhenk.finderscope.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Finding the camera after joining its network (seen on a real NX1000: the description port
 * refused the first connection right after the phone joined). The session keeps trying and asks
 * SSDP in between until the camera answers.
 */
class FindCameraTest {
    private val sims = ArrayList<RvfCameraSimulator>()
    private val sessions = ArrayList<CameraSession>()

    @After
    fun tearDown() { sessions.forEach { it.stop() }; sims.forEach { it.stop() } }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Events : CameraSession.Listener {
        val log = CopyOnWriteArrayList<String>()
        override fun onState(state: CameraSession.State, message: String) { log += "state:$state:$message" }
    }

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) { if (System.currentTimeMillis() > deadline) throw AssertionError("timeout"); Thread.sleep(50) }
    }

    private fun camera(port: Int = 0, delay: Long = 0) = RvfCameraSimulator(RvfCameraSimulator.Profile.NX1000, controlPort = port, startDelayMs = delay, fps = 30).start().also { sims += it }

    /** The camera's server comes up 2.5 s after the phone joined: the session waits and connects. */
    @Test
    fun cameraStartsLate() {
        val port = freePort()
        val s = camera(port, delay = 2_500)
        val ev = Events()
        val cs = CameraSession("127.0.0.1", port, "id", ev, modePort = freePort(), findTimeoutMs = 20_000).start(); sessions += cs
        waitFor { ev.log.any { it.startsWith("state:LIVE") } }
        assertTrue(ev.log.toString(), ev.log.contains("state:CONNECTING:${CameraSession.WAITING}"))
        assertEquals(listOf("GetInfomation"), s.calls.toList().take(1))
    }

    /** Nothing at the default port, but SSDP names the camera's description: the session goes there. */
    @Test
    fun ssdpFindsTheCamera() {
        val s = camera()
        val ev = Events()
        val logs = CopyOnWriteArrayList<String>()
        val discover: (Long) -> CameraLocation? = { t -> DatagramSocket().use { SsdpSearch.search(it, t, InetSocketAddress("127.0.0.1", s.ssdpPort)) } }
        val cs = CameraSession("127.0.0.1", freePort(), "id", ev, modePort = freePort(), log = { logs += it }, discover = discover).start(); sessions += cs
        waitFor { ev.log.any { it.startsWith("state:LIVE") } }
        assertTrue(logs.toString(), logs.any { it.contains("camera found at CameraLocation(host=127.0.0.1, port=${s.controlPort}, path=/smp_2_)") })
    }

    /** No camera at all: a clear failure after the search time, not a hang. */
    @Test
    fun noCamera() {
        val ev = Events()
        val cs = CameraSession("127.0.0.1", freePort(), "id", ev, modePort = freePort(), findTimeoutMs = 1_500).start(); sessions += cs
        waitFor { cs.isClosed }
        waitFor { ev.log.any { it.startsWith("state:FAILED") } }
        assertTrue(ev.log.toString(), ev.log.any { it.contains("did not answer at 127.0.0.1") })
    }

    @Test
    fun ssdpParsing() {
        fun msg(vararg l: String) = l.joinToString("\r\n") + "\r\n\r\n"
        assertEquals(CameraLocation("192.168.102.1", 7676, "/smp_2_"),
            SsdpSearch.locationOf(msg("NOTIFY * HTTP/1.1", "LOCATION: http://192.168.102.1:7676/smp_2_", "NT: urn:schemas-upnp-org:service:ConnectionManager:1", "NTS: ssdp:alive")))
        assertEquals(CameraLocation("10.0.0.5", 80, "/desc.xml"),
            SsdpSearch.locationOf(msg("HTTP/1.1 200 OK", "LOCATION: http://10.0.0.5/desc.xml", "ST: urn:schemas-upnp-org:device:MediaServer:1")))
        assertNull(SsdpSearch.locationOf(msg("NOTIFY * HTTP/1.1", "LOCATION: http://h/smp_2_", "NTS: ssdp:byebye")))
        assertNull(SsdpSearch.locationOf(msg("M-SEARCH * HTTP/1.1", "ST: ssdp:all")))
        assertNull(SsdpSearch.locationOf(msg("HTTP/1.1 200 OK", "ST: urn:dial-multiscreen-org:service:dial:1", "LOCATION: http://tv/dial.xml")))
        assertNull(SsdpSearch.locationOf(msg("HTTP/1.1 200 OK", "ST: upnp:rootdevice")))
        assertNull(CameraLocation.parse("not a url"))
        assertNull(CameraLocation.parse("relative/path"))
        // nobody answers: null after the time
        DatagramSocket().use { assertNull(SsdpSearch.search(it, 300, InetSocketAddress("127.0.0.1", freePort()))) }
        // a closed socket: null, no exception
        val closed = DatagramSocket().apply { close() }
        assertNull(SsdpSearch.search(closed, 300, InetSocketAddress("127.0.0.1", 9), log = {}))
    }
}
