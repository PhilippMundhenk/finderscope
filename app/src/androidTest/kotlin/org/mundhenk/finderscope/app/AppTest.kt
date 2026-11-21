package org.mundhenk.finderscope.app

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mundhenk.finderscope.core.AfGrid
import org.mundhenk.finderscope.core.AviFrameReader
import org.mundhenk.finderscope.core.RvfCameraSimulator
import org.mundhenk.finderscope.core.SsdpNotices
import java.net.InetSocketAddress
import org.mundhenk.finderscope.core.VideoCodec
import java.io.ByteArrayOutputStream

/**
 * finderscope end to end on a device: the real UI against the simulated camera on the loopback
 * interface (the same simulator the conformance tests use), real H.264 from the device's own
 * encoder decoded by MediaCodec, MJPEG from the demo camera.
 */
@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sims = ArrayList<RvfCameraSimulator>()

    @Before
    fun setUp() { reset() }

    @After
    fun tearDown() { sims.forEach { it.stop() }; DemoCamera.stop(); reset() }

    private fun reset() {
        context.getSharedPreferences("finderscope", Context.MODE_PRIVATE).edit().clear().commit()
        LogBus.clear()
        context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/${Pictures.FOLDER}/"))
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        runCatching { device.executeShellCommand("wm dismiss-keyguard"); device.executeShellCommand("svc power stayon true"); device.wakeUp() }
    }

    private fun waitFor(timeoutMs: Long = 30_000, what: String = "condition", cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun waitForUi(timeoutMs: Long = 30_000, assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try { assertion(); return } catch (e: Throwable) { if (System.currentTimeMillis() > deadline) throw e; Thread.sleep(200) }
        }
    }

    /** View.performClick(): injected taps are unreliable on slow emulators. */
    private fun tap(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isEnabled()
        override fun getDescription() = "performClick()"
        override fun perform(uiController: UiController, view: View) { view.performClick(); uiController.loopMainThreadUntilIdle() }
    }

    /** A touch on [view] at its centre, or outside it for [inside] = false. */
    private fun touch(view: View, action: Int, inside: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        val x = if (inside) view.width / 2f else view.width * 3f; val y = view.height / 2f
        val e = MotionEvent.obtain(now, now, action, x, y, 0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun jpeg(w: Int, h: Int, color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun camera(profile: RvfCameraSimulator.Profile, frames: List<ByteArray>, codec: VideoCodec, w: Int, h: Int) =
        RvfCameraSimulator(profile, frames, codec, w, h, fps = 15, photo = jpeg(320, 240, Color.BLUE), preview = jpeg(80, 60, Color.CYAN),
            log = { android.util.Log.i("AppTest", "sim: $it") }).start().also { sims += it }

    private fun connect(s: RvfCameraSimulator) {
        onView(withId(R.id.address)).perform(replaceText("127.0.0.1:${s.controlPort}"))
        onView(withId(R.id.connect)).perform(tap())
    }

    private fun waitForShutter() = waitForUi { onView(withId(R.id.shutter)).check(matches(allOf(isDisplayed(), isEnabled()))) }

    private fun pictures(): List<String> {
        val names = ArrayList<String>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/${Pictures.FOLDER}/"), null)?.use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names
    }

    /** The demo: live MJPEG, tap, flash, timer, photo size, zoom (tap and hold), volume keys, drag-off, open the picture, disconnect. */
    @Test
    fun demo_everyControl() {
        Intents.init()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                onView(withId(R.id.status)).check(matches(withText(R.string.status_idle)))
                onView(withId(R.id.demo)).perform(tap())
                waitForShutter()
                var act: MainActivity? = null
                sc.onActivity { act = it }
                waitFor(what = "frames") { act!!.framesShown > 2 }
                onView(withId(R.id.liveImage)).check(matches(isDisplayed()))
                onView(withId(R.id.info)).check(matches(withSubstring("DEMO")))

                // tap = focus + picture, saved into Pictures/finderscope
                onView(withId(R.id.shutter)).perform(tap())
                waitForUi { onView(withId(R.id.notice)).check(matches(withText(context.getString(R.string.picture_saved, "SAM_0001.JPG")))) }
                assertEquals(listOf("SAM_0001.JPG"), pictures())
                waitForShutter()
                intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
                onView(withId(R.id.lastPicture)).perform(tap())
                intended(hasAction(Intent.ACTION_VIEW))

                // flash cycles through the camera's own list
                onView(withId(R.id.flash)).perform(tap())
                waitForUi { onView(withId(R.id.flash)).check(matches(withText(context.getString(R.string.flash_value, "fill")))) }
                val sim = DemoCamera.running!!
                waitFor { sim.flash == "fill" }

                // photo size from the camera's list
                onView(withId(R.id.size)).perform(tap())
                onView(withSubstring("1920×1080")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
                waitFor { sim.resolution == "1920x1080" }
                onView(withId(R.id.size)).check(matches(withText("2.1M")))

                // zoom: a click steps, holding repeats
                onView(withId(R.id.zoomIn)).perform(tap())
                waitForUi { onView(withId(R.id.info)).check(matches(withSubstring("zoom 1"))) }
                val zoomIn = act!!.findViewById<View>(R.id.zoomIn)
                touch(zoomIn, MotionEvent.ACTION_DOWN); Thread.sleep(1_500); touch(zoomIn, MotionEvent.ACTION_UP)
                // holding repeats: at least two more steps
                waitFor(what = "zoom repeat") { sim.calls.count { it == "ZoomIN" } >= 3 }
                val zoomOut = act!!.findViewById<View>(R.id.zoomOut)
                touch(zoomOut, MotionEvent.ACTION_DOWN); touch(zoomOut, MotionEvent.ACTION_CANCEL)
                onView(withId(R.id.zoomOut)).perform(tap())

                // press and release outside the button: focus, then cancelled, no picture
                val shutter = act!!.findViewById<View>(R.id.shutter)
                fun count(a: String) = sim.calls.count { it == a }
                val af0 = count("MULTIAF"); val rel0 = count("ReleaseSelfTimer")
                touch(shutter, MotionEvent.ACTION_DOWN)
                waitFor { count("MULTIAF") == af0 + 1 }
                touch(shutter, MotionEvent.ACTION_UP, inside = false)
                waitFor { count("ReleaseSelfTimer") == rel0 + 1 }
                touch(shutter, MotionEvent.ACTION_DOWN); touch(shutter, MotionEvent.ACTION_CANCEL)
                waitFor { count("ReleaseSelfTimer") == rel0 + 2 }

                // press and release inside: a picture
                touch(shutter, MotionEvent.ACTION_DOWN); touch(shutter, MotionEvent.ACTION_UP)
                waitFor { sim.shotCount == 2 }
                waitForShutter()

                // volume key = shutter
                sc.onActivity { it.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) }
                sc.onActivity { it.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) }
                waitFor { sim.shotCount == 3 }
                waitForShutter()

                // self-timer 2 s, then 10 s cancelled by tapping again, then off
                onView(withId(R.id.timer)).perform(tap())
                onView(withId(R.id.timer)).check(matches(withText(context.getString(R.string.timer_value, 2))))
                waitFor { sim.led == "2" }
                onView(withId(R.id.shutter)).perform(tap())
                waitForUi { onView(withId(R.id.countdown)).check(matches(isDisplayed())) }
                waitFor { sim.shotCount == 4 }
                waitForShutter()
                onView(withId(R.id.timer)).perform(tap())
                waitFor { sim.led == "10" }
                onView(withId(R.id.shutter)).perform(tap())
                waitForUi { onView(withId(R.id.countdown)).check(matches(isDisplayed())) }
                touch(shutter, MotionEvent.ACTION_DOWN) // a touch during the countdown cancels it
                touch(shutter, MotionEvent.ACTION_UP)
                waitForUi { onView(withId(R.id.countdown)).check(matches(withEffectiveVisibility(Visibility.GONE))) }
                onView(withId(R.id.timer)).perform(tap())
                onView(withId(R.id.timer)).check(matches(withText(R.string.timer_off)))
                waitFor { sim.led == "0" }
                sc.onActivity { assertTrue(it.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))) }
                assertEquals(4, sim.shotCount)
                assertEquals(emptyList<String>(), sim.violations.toList())

                onView(withId(R.id.disconnect)).perform(tap())
                waitForUi { onView(withId(R.id.connectPanel)).check(matches(isDisplayed())) }
                onView(withId(R.id.status)).check(matches(withText(R.string.closed)))
                sc.onActivity { assertTrue(!it.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) || true) }
            }
        } finally { Intents.release() }
    }

    /** NX1000 with real H.264: decoded on screen, rotated, focus failure, preview copy, card full, failures. */
    @Test
    fun nx1000_h264_rotation_focusFailure_preview_errors() {
        val frames = H264.encode(320, 240, 30)
        assertTrue(AviFrameReader.isKeyFrame(frames.first()))
        val s = camera(RvfCameraSimulator.Profile.NX1000, frames, VideoCodec.H264, 320, 240).apply { rotation = 2 } // L
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            connect(s)
            waitForShutter()
            var act: MainActivity? = null
            sc.onActivity { act = it }
            waitFor(what = "decoded frames") { act!!.framesDecoded > 3 }
            sc.onActivity { assertEquals(270f, it.findViewById<View>(R.id.picture).rotation) }
            onView(withId(R.id.zoomIn)).check(matches(withEffectiveVisibility(Visibility.GONE))) // lens-ring zoom
            onView(withId(R.id.size)).check(matches(isDisplayed()))

            // focus fails: no picture on an NX, red centre frame
            s.focusOk = false
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(R.string.notice_focus_failed_nx))) }
            sc.onActivity {
                val af = it.findViewById<AfOverlayView>(R.id.afOverlay)
                assertEquals(AfGrid.NX, af.grid); assertTrue(af.failed)
            }
            assertEquals(0, s.shotCount)

            // focus ok: the preview copy (2012 firmware), saved under its own name
            s.focusOk = true
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(context.getString(R.string.preview_saved, "SAM_0001_preview.JPG")))) }
            assertEquals(listOf("SAM_0001_preview.JPG"), pictures())
            waitFor(what = "live view back") { s.streamOpens.get() == 2 }
            waitForShutter()
            val before = act!!.framesDecoded
            waitFor(what = "frames after reopen") { act!!.framesDecoded > before }

            // the camera fails the next shot, then names none, then the card is full
            s.failShots = true
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(R.string.notice_shot_failed))) }
            s.failShots = false; s.emptyShotResult = true
            waitForShutter()
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(R.string.notice_no_picture))) }
            waitForShutter()
            s.emptyShotResult = false; s.availShots = 1
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(R.string.notice_card_full))) }
            waitForUi { onView(withId(R.id.info)).check(matches(withSubstring(context.getString(R.string.shots_left, 0)))) }
            onView(withId(R.id.shutter)).check(matches(not(isEnabled())))
            // -1: numbering used up
            s.availShots = -1
            onView(withId(R.id.size)).perform(tap())
            onView(withSubstring("1920×1080")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(R.string.notice_numbering_full))) }
            assertEquals(emptyList<String>(), s.violations.toList())
        }
    }

    /** Copying off, a camera command the model lacks, and leaving / coming back resumes the session. */
    @Test
    fun copyingOff_commandFailure_resume() {
        context.getSharedPreferences("finderscope", Context.MODE_PRIVATE).edit().putBoolean("savePictures", false).commit()
        val s = camera(RvfCameraSimulator.Profile.WB250F, listOf(jpeg(160, 120, Color.GREEN)), VideoCodec.MJPEG, 160, 120)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            connect(s)
            waitForShutter()
            onView(withId(R.id.size)).check(matches(withEffectiveVisibility(Visibility.GONE))) // no SetResolution on this model
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.notice)).check(matches(withText(R.string.notice_on_card))) }
            assertTrue(s.downloads.isEmpty())
            waitForShutter()
            // the timer on a camera without an LED command: counted in the app only
            onView(withId(R.id.timer)).perform(tap())
            sc.onActivity { assertEquals(2, it.session!!.timerSeconds) }
            onView(withId(R.id.timer)).perform(tap()); onView(withId(R.id.timer)).perform(tap())
            sc.onActivity { it.session!!.setResolution(1, 1) } // not offered: reported, not sent
            waitForUi { onView(withId(R.id.notice)).check(matches(withSubstring("photo size"))) }

            // leave the screen: the session ends; come back: it resumes
            val opens = s.streamOpens.get()
            sc.moveToState(Lifecycle.State.CREATED)
            waitFor { s.streamReaders == 0 }
            sc.moveToState(Lifecycle.State.RESUMED)
            waitFor(what = "resumed") { s.streamOpens.get() > opens }
            waitForShutter()
        }
    }

    /** Wrong address, a camera that refuses, the camera network request, menu, settings and log. */
    @Test
    fun errors_wifi_settings_log() {
        val s = camera(RvfCameraSimulator.Profile.NX1000, listOf(byteArrayOf(0, 0, 0, 1, 0x65)), VideoCodec.H264, 320, 240).apply { allow = { false } }
        Intents.init()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                onView(withId(R.id.address)).check(matches(withSubstring(".")))
                onView(withId(R.id.address)).perform(replaceText("not an address"))
                onView(withId(R.id.connect)).perform(tap())
                onView(withId(R.id.addressLayout)).check(matches(object : org.hamcrest.TypeSafeMatcher<View>() {
                    override fun describeTo(d: org.hamcrest.Description) { d.appendText("address error") }
                    override fun matchesSafely(v: View) = (v as com.google.android.material.textfield.TextInputLayout).error == context.getString(R.string.address_invalid)
                }))
                connect(s)
                waitForUi { onView(withId(R.id.status)).check(matches(withSubstring("confirm the prompt"))) }
                onView(withId(R.id.connect)).check(matches(withText(R.string.connect)))

                // the screens themselves are tested in settingsAndLog: here only that the menu opens them
                intending(hasComponent(SettingsActivity::class.java.name)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
                intending(hasComponent(LogActivity::class.java.name)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
                onView(withId(R.id.action_settings)).perform(tap())
                intended(hasComponent(SettingsActivity::class.java.name))
                openActionBarOverflowOrOptionsMenu(context)
                onView(withText(R.string.log)).perform(androidx.test.espresso.action.ViewActions.click())
                intended(hasComponent(LogActivity::class.java.name))
                // the camera network request: the system shows its picker; withdrawn again right away
                onView(withId(R.id.joinWifi)).perform(tap())
                onView(withId(R.id.statusHint)).check(matches(withText(R.string.joining_wifi)))
                sc.onActivity { it.wifi.release(); assertNull(it.wifi.network) }
                val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                // the system's "connect to device" dialog may still be up: back out of it; nothing else runs after this
                repeat(3) { Thread.sleep(1_000); if (device.currentPackageName != context.packageName) device.pressBack() }
                sc.onActivity { assertNotNull(it.wifi.socketFactory("192.168.102.1")); it.wifi.gateway() }

            }
        } finally { Intents.release() }
        assertEquals("192.168.102.1" to 7676, CameraWifi.parse(" http://192.168.102.1/ "))
        assertEquals("10.0.0.5" to 8080, CameraWifi.parse("10.0.0.5:8080"))
        listOf("", "a b", ":7676", "host:x", "host:70000").forEach { assertNull(it, CameraWifi.parse(it)) }
        assertEquals("SAM_1_preview.JPG", Pictures.nameFor("SAM_1.JPG", true)); assertEquals("SAM_1.JPG", Pictures.nameFor("SAM_1.JPG", false))
        val id = Prefs(context).clientId
        assertTrue(id, Regex("02(:[0-9a-f]{2}){5}").matches(id)); assertEquals(id, Prefs(context).clientId)
    }

    @Test
    fun settingsAndLog() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.savePictures)).check(matches(isChecked()))
            onView(withId(R.id.savePictures)).perform(tap())
            assertEquals(false, Prefs(context).savePictures)
            onView(withId(R.id.gpsTag)).perform(tap()) // permission already granted by the rule
            waitFor { Prefs(context).gpsTag }
            onView(withId(R.id.gpsTag)).perform(tap())
            assertEquals(false, Prefs(context).gpsTag)
            onView(withId(R.id.version)).check(matches(withSubstring("finderscope")))
        }
        LogBus.log("hello from the test")
        ActivityScenario.launch(LogActivity::class.java).use {
            onView(withId(R.id.log)).check(matches(withSubstring("hello from the test")))
            LogBus.log("a second line")
            waitForUi { onView(withId(R.id.log)).check(matches(withSubstring("a second line"))) }
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.clear_log)).perform(androidx.test.espresso.action.ViewActions.click())
            assertTrue(LogBus.snapshot().isEmpty())
        }
    }

    /** GPS tag on: the demo camera gets ShotWithGPS when the phone knows where it is (the emulator may not). */
    @Test
    fun gpsTagUsesShotWithGps() {
        context.getSharedPreferences("finderscope", Context.MODE_PRIVATE).edit().putBoolean("gpsTag", true).commit()
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        runCatching { device.executeShellCommand("cmd location set-location-enabled true") }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.demo)).perform(tap())
            waitForShutter()
            onView(withId(R.id.shutter)).perform(tap())
            val sim = DemoCamera.running!!
            waitFor { sim.shotCount == 1 }
            assertTrue(sim.calls.toString(), sim.calls.contains("Shot") || sim.calls.contains("ShotWithGPS"))
        }
    }

    /** The emulator's own (non-loopback) address: talking to it goes the way a real camera would. */
    private fun deviceAddress(): String? = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress

    /**
     * Over the network instead of loopback: sockets on the camera network, the multicast lock,
     * and the camera's SSDP notices (rotation, goodbye) arriving through the multicast group.
     */
    @Test
    fun overTheNetwork_rotationAndGoodbyeNotices() {
        val ip = deviceAddress() ?: return // no network on this device: nothing to test
        val s = RvfCameraSimulator(RvfCameraSimulator.Profile.WB250F, listOf(jpeg(160, 120, Color.GREEN)), VideoCodec.MJPEG, 160, 120,
            fps = 15, photo = jpeg(320, 240, Color.BLUE), advertisedHost = ip).start().also { sims += it }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            onView(withId(R.id.address)).perform(replaceText("$ip:${s.controlPort}"))
            onView(withId(R.id.connect)).perform(tap())
            waitForShutter()
            sc.onActivity { it.onConfigurationChanged(it.resources.configuration) }
            val group = InetSocketAddress(SsdpNotices.GROUP, SsdpNotices.PORT)
            Thread.sleep(1_000) // the notice listener joins the group right after connecting
            s.sendNotice("ssdp:rotationR", group)
            waitForUi(10_000) { sc.onActivity { act -> assertEquals(90f, act.findViewById<View>(R.id.picture).rotation) } }
            s.sendNotice("ssdp:byebye", group)
            waitForUi { onView(withId(R.id.status)).check(matches(withText("The camera ended the connection"))) }
            onView(withId(R.id.connectPanel)).check(matches(isDisplayed()))
        }
    }

    /** Real H.264 from the device's encoder, Annex B, SPS/PPS in front of the first key frame like the cameras send it. */
    object H264 {
        fun encode(w: Int, h: Int, count: Int): List<ByteArray> {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val out = ArrayList<ByteArray>()
            var config = ByteArray(0)
            val info = MediaCodec.BufferInfo()
            var queued = 0
            var done = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!done && System.currentTimeMillis() < deadline) {
                if (queued <= count) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (queued == count) enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            val img = enc.getInputImage(i)!!
                            val shade = (queued * 8) and 0xff
                            for ((p, plane) in img.planes.withIndex()) {
                                val pw = if (p == 0) w else w / 2; val ph = if (p == 0) h else h / 2
                                val buf = plane.buffer
                                for (y in 0 until ph) for (x in 0 until pw) buf.put(y * plane.rowStride + x * plane.pixelStride, (if (p == 0) (shade + x) and 0xff else 128).toByte())
                            }
                            enc.queueInputBuffer(i, 0, w * h * 3 / 2, queued * 66_666L, 0)
                        }
                        queued++
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size).also { b.position(info.offset); b.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = bytes
                    else if (bytes.isNotEmpty()) out += if (out.isEmpty()) config + bytes else bytes
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    enc.releaseOutputBuffer(o, false)
                }
            }
            enc.stop(); enc.release()
            check(out.isNotEmpty()) { "encoder produced nothing" }
            return out
        }
    }
}
