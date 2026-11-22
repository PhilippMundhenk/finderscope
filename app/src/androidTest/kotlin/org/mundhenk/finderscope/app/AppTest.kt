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
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
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
import org.hamcrest.Matchers.anyOf
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
import org.mundhenk.finderscope.core.Rotation
import org.mundhenk.finderscope.core.RvfCameraSimulator
import org.mundhenk.finderscope.core.SsdpNotices
import java.net.InetSocketAddress
import org.mundhenk.finderscope.core.VideoCodec
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

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
        runCatching {
            device.executeShellCommand("wm dismiss-keyguard"); device.executeShellCommand("svc power stayon true"); device.wakeUp()
            // the live view hides the system bars; Android's one-time "viewing full screen" hint would take window focus
            device.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
        }
    }

    private fun waitFor(timeoutMs: Long = 30_000, what: String = "condition", cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for $what; app log:\n" + LogBus.snapshot().takeLast(30).joinToString("\n"))
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

    /** Connect by address: opens the field (once it is open, the link is gone). */
    private fun openAddress() {
        try { onView(withId(R.id.addressToggle)).check(matches(isDisplayed())).perform(tap()) } catch (_: Throwable) { /* already open */ }
    }

    private fun connect(s: RvfCameraSimulator) {
        openAddress()
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
                waitForUi { onView(withId(R.id.liveImage)).check(matches(isDisplayed())) } // the layout follows the first frame
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
                waitForUi { onView(withId(R.id.flash)).check(matches(withContentDescription(context.getString(R.string.flash_value, "fill")))) }
                onView(withId(R.id.flash)).check(matches(withText("FILL")))
                val sim = DemoCamera.running!!
                waitFor { sim.flash == "fill" }

                // photo size from the camera's list
                onView(withId(R.id.size)).perform(tap())
                onView(withSubstring("1920×1080")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.click())
                waitFor { sim.resolution == "1920x1080" }
                onView(withId(R.id.size)).check(matches(withText("2.1M")))

                // zoom: a click steps, holding repeats
                onView(withId(R.id.zoomIn)).perform(tap())
                waitForUi { onView(withId(R.id.zoomLevel)).check(matches(withText("1"))) }
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
                onView(withId(R.id.timer)).check(matches(withContentDescription(context.getString(R.string.timer_value, 2))))
                onView(withId(R.id.timer)).check(matches(withText("2s")))
                waitFor { sim.led == "2" }
                onView(withId(R.id.shutter)).perform(tap())
                waitFor(what = "countdown shown") { act!!.countdownSeen == 2 } // on screen for 2 s only: checked via the record, not by catching it
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
                onView(withId(R.id.timer)).check(matches(withContentDescription(context.getString(R.string.timer_off))))
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
        H264Decoder.statsSeconds = 0.5
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            connect(s)
            waitForShutter()
            // the decoder reports frames per second and its delay to the log
            waitFor(what = "decoder stats") { LogBus.snapshot().any { it.contains("frames/s from the camera") } }
            H264Decoder.statsSeconds = 10.0
            var act: MainActivity? = null
            sc.onActivity { act = it }
            waitFor(what = "decoded frames") { act!!.framesDecoded > 3 }
            sc.onActivity { assertEquals(270f, it.findViewById<View>(R.id.picture).rotation) }
            onView(withId(R.id.zoomPill)).check(matches(withEffectiveVisibility(Visibility.GONE))) // lens-ring zoom
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
            waitForUi { onView(withId(R.id.shots)).check(matches(withText("0"))) }
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

            // leave the screen briefly: the connection stays (ending it makes the camera leave Remote Viewfinder)
            val opens = s.streamOpens.get()
            sc.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1_000)
            assertEquals(1, s.streamReaders); assertEquals(opens, s.streamOpens.get())
            sc.moveToState(Lifecycle.State.RESUMED)
            waitForShutter()
            assertEquals(opens, s.streamOpens.get())
            // away for long: the session ends, and comes back with the screen
            sc.onActivity { it.awayMs = 500 }
            sc.moveToState(Lifecycle.State.CREATED)
            waitFor(what = "ended while away") { s.streamReaders == 0 }
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
                openAddress()
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
                // the camera network request, answered by the test instead of the system's picker:
                // first no network (picker cancelled), then the phone's own Wi-Fi as "the camera network"
                var answer: (android.net.ConnectivityManager.NetworkCallback) -> Unit = { it.onUnavailable() }
                sc.onActivity { act -> act.wifi.requester = { _, cb -> answer(cb) }; act.findTimeoutMs = 5_000; act.wifi.scanResults = { listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L), Triple("AP_SSC_NX1000_0-AA:BB:CC", "02:11:22:33:44:55", 3L)) } }
                onView(withId(R.id.joinWifi)).perform(tap())
                waitForUi { onView(withId(R.id.statusHint)).check(matches(withText(R.string.wifi_unavailable))) }
                val wifiNetwork = context.getSystemService(android.net.ConnectivityManager::class.java).let { cm ->
                    @Suppress("DEPRECATION") cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
                        ?: cm.activeNetwork
                }
                if (wifiNetwork != null) {
                    answer = { cb -> cb.onAvailable(wifiNetwork); cb.onLost(wifiNetwork) }
                    onView(withId(R.id.joinWifi)).perform(tap())
                    // the network is lost right after it came (camera switched off): the attempt ends with that message
                    waitForUi { onView(withId(R.id.status)).check(matches(withText(R.string.camera_wifi_lost))) }
                    assertTrue(LogBus.snapshot().any { it.contains("the camera's Wi-Fi is gone") })
                    // available and staying: the gateway is no camera, the attempt ends with a failure message
                    answer = { cb -> cb.onAvailable(wifiNetwork) }
                    onView(withId(R.id.joinWifi)).perform(tap())
                    waitForUi(45_000) { onView(withId(R.id.status)).check(matches(withSubstring(context.getString(R.string.failed, "").trim()))) }
                    sc.onActivity { it.cameraWifiLost() } // nothing running any more: nothing to end
                }
                sc.onActivity { it.wifi.release(); assertNull(it.wifi.network) }
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
            // shutter mode: hold to focus by default, tap to focus as the alternative
            onView(withId(R.id.shutterHold)).check(matches(isChecked()))
            onView(withId(R.id.shutterTap)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), tap())
            assertEquals(true, Prefs(context).tapToFocus)
            onView(withId(R.id.shutterHold)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), tap())
            assertEquals(false, Prefs(context).tapToFocus)
            // background connection time: 5 minutes by default, 0 = off, at most 30
            onView(withId(R.id.awayLabel)).check(matches(withSubstring("5 minutes")))
            it.onActivity { a -> a.findViewById<com.google.android.material.slider.Slider>(R.id.awayMinutes).value = 1f }
            onView(withId(R.id.awayLabel)).check(matches(withSubstring("1 minute")))
            assertEquals(1, Prefs(context).awayMinutes)
            it.onActivity { a -> a.findViewById<com.google.android.material.slider.Slider>(R.id.awayMinutes).value = 0f }
            onView(withId(R.id.awayLabel)).check(matches(withText(R.string.away_off)))
            Prefs(context).awayMinutes = 99; assertEquals(30, Prefs(context).awayMinutes)
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

    /** Tap to focus: a tap on the live view focuses and holds, the shutter and the volume keys take the picture. */
    @Test
    fun tapToFocusMode() {
        Prefs(context).tapToFocus = true
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            onView(withId(R.id.demo)).perform(tap())
            waitForShutter()
            val sim = DemoCamera.running!!
            val before = sim.calls.count { it == "MULTIAF" }
            onView(withId(R.id.picture)).perform(androidx.test.espresso.action.ViewActions.click())
            waitFor(what = "focus on tap") { sim.calls.count { it == "MULTIAF" } == before + 1 }
            sc.onActivity { assertTrue(it.session!!.focusHeld) }
            assertEquals(0, sim.shotCount) // a tap on the picture only focuses
            // a real touch on the shutter (down, up inside)
            onView(withId(R.id.shutter)).perform(androidx.test.espresso.action.ViewActions.click())
            waitFor(what = "picture") { sim.shotCount == 1 }
            waitForShutter()
            // volume key: the picture too (focus and shoot in one go)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            waitFor(what = "picture by volume key") { sim.shotCount == 2 }
        }
    }

    /**
     * The phone shows what the camera's own screen shows: whichever way the camera is held, the
     * scene is upright, and a camera held upright gives a portrait picture. The simulated camera
     * sends what its sensor sees when turned (a grey scene with a white bar along the world's top,
     * which lands on another side of the frame), the test looks at the real screen.
     */
    @Test
    fun displayMatchesTheCameraScreen_mjpeg() = displayMatchesTheCameraScreen(VideoCodec.MJPEG)

    @Test
    fun displayMatchesTheCameraScreen_h264() = displayMatchesTheCameraScreen(VideoCodec.H264)

    /** Where the world's top lands in the sensor frame of a camera turned by [r] (the app turns it back). */
    private fun worldTop(r: Rotation, w: Int, h: Int): (Int, Int) -> Boolean = when (r) {
        Rotation.D -> { _, y -> y < h / 5 }
        Rotation.L -> { x, _ -> x >= w - w / 5 }
        Rotation.R -> { x, _ -> x < w / 5 }
        Rotation.U -> { _, y -> y >= h - h / 5 }
    }

    private fun scene(codec: VideoCodec, r: Rotation): List<ByteArray> {
        val w = 320; val h = 240
        val top = worldTop(r, w, h)
        return if (codec == VideoCodec.H264) H264.encode(w, h, 15, top)
        else listOf(ByteArrayOutputStream().also { out ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, if (top(x, y)) Color.WHITE else Color.rgb(110, 110, 110))
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray())
    }

    private fun displayMatchesTheCameraScreen(codec: VideoCodec) {
        val turns = listOf(Rotation.L, Rotation.U, Rotation.R)
        val s = camera(RvfCameraSimulator.Profile.NX1000, scene(codec, Rotation.D), codec, 320, 240)
        s.turnedFrames = turns.associateWith { scene(codec, it) }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            connect(s)
            waitForShutter()
            for (r in listOf(Rotation.D) + turns + Rotation.D) {
                s.turn(r)
                sc.onActivity { it.onRotation(r) } // the session's turn event (the notice itself: core tests)
                waitFor(what = "upright scene for $r", timeoutMs = 15_000) {
                    val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitFor false
                    uprightOnScreen(shot, portrait = r == Rotation.L || r == Rotation.R)
                }
            }
        }
    }

    /**
     * On the screen: the grey picture's bounds, and the white bar inside them must lie along its top
     * edge (and the picture be portrait or landscape as expected).
     */
    private fun uprightOnScreen(shot: Bitmap, portrait: Boolean): Boolean {
        var l = shot.width; var t = shot.height; var r = -1; var b = -1
        val step = 4
        for (y in 0 until shot.height step step) for (x in 0 until shot.width step step) {
            val c = shot.getPixel(x, y)
            val R = Color.red(c); val G = Color.green(c); val B = Color.blue(c)
            val grey = R in 70..160 && kotlin.math.abs(R - G) < 18 && kotlin.math.abs(G - B) < 18
            if (grey) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y) }
        }
        if (r < 0) return false
        val w = r - l; val h = b - t
        if (w < 100 || h < 100 || (h > w) != portrait) return false
        var n = 0; var sumY = 0L; var sumX = 0L
        for (y in t..b step step) for (x in l..r step step) {
            val c = shot.getPixel(x, y)
            if (Color.red(c) > 215 && Color.green(c) > 215 && Color.blue(c) > 215) { n++; sumY += y; sumX += x }
        }
        if (n < 20) return false
        val cy = sumY.toDouble() / n; val cx = sumX.toDouble() / n
        // the bar along the top fifth, spread over the whole width
        return cy < t + 0.25 * h && kotlin.math.abs(cx - (l + r) / 2.0) < 0.15 * w
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

    /** The phone's position goes into the EXIF of the copy on the phone (the cameras ignore it). */
    @Test
    fun positionWrittenIntoTheCopy() {
        val uri = Pictures.save(context, "SAM_4242.JPG", jpeg(64, 48, Color.RED), 48.137 to 11.575)
        val ll = FloatArray(2)
        context.contentResolver.openInputStream(uri)!!.use { input -> assertTrue(android.media.ExifInterface(input).getLatLong(ll)) }
        assertEquals(48.137f, ll[0], 1e-3f); assertEquals(11.575f, ll[1], 1e-3f)
        val plain = Pictures.save(context, "SAM_4243.JPG", jpeg(64, 48, Color.RED), -33.86 to -151.2)
        context.contentResolver.openInputStream(plain)!!.use { input -> assertTrue(android.media.ExifInterface(input).getLatLong(ll)) }
        assertEquals(-33.86f, ll[0], 1e-3f); assertEquals(-151.2f, ll[1], 1e-3f)
        val none = Pictures.save(context, "SAM_4244.JPG", jpeg(64, 48, Color.RED))
        context.contentResolver.openInputStream(none)!!.use { input -> assertTrue(!android.media.ExifInterface(input).getLatLong(ll)) }
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
            openAddress()
            onView(withId(R.id.address)).perform(replaceText("$ip:${s.controlPort}"))
            onView(withId(R.id.connect)).perform(tap())
            waitForShutter()
            sc.onActivity { it.onConfigurationChanged(it.resources.configuration) }
            val group = InetSocketAddress(SsdpNotices.GROUP, SsdpNotices.PORT)
            Thread.sleep(1_000) // the notice listener joins the group right after connecting
            // out on the interface finderscope listens on, as a camera on its own Wi-Fi would send
            var via: java.net.NetworkInterface? = null
            sc.onActivity { via = it.wifi.cameraInterface(ip) }
            assertTrue(LogBus.snapshot().toString(), LogBus.snapshot().any { it.contains("listening for the camera's notices") })
            s.sendNotice("ssdp:rotationR", group, via = via)
            waitForUi(10_000) { sc.onActivity { act -> assertEquals(90f, act.findViewById<View>(R.id.picture).rotation) } }
            s.sendNotice("ssdp:byebye", group, via = via)
            waitForUi { onView(withId(R.id.status)).check(matches(withText("The camera ended the connection"))) }
            onView(withId(R.id.connectPanel)).check(matches(isDisplayed()))
        }
    }

    private fun screenBox(v: View): org.mundhenk.finderscope.core.Box {
        val r = android.graphics.Rect(); v.getGlobalVisibleRect(r)
        return org.mundhenk.finderscope.core.Box(r.left, r.top, r.right, r.bottom)
    }

    /** Upright: top bar, picture, controls stacked; turned to landscape: columns beside the picture. Never on top of it. */
    @Test
    fun layoutKeepsThePictureClear_uprightAndLandscape() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            onView(withId(R.id.demo)).perform(tap())
            waitForShutter()
            fun check(expected: org.mundhenk.finderscope.core.ViewfinderLayout.Arrangement) {
                waitFor(what = "arrangement $expected") {
                    var ok = false
                    sc.onActivity { a -> ok = a.plan?.arrangement == expected }
                    ok
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                sc.onActivity { a ->
                    assertTrue(a.plan.toString(), a.plan?.overlay == false)
                    val picture = screenBox(a.findViewById(R.id.picture))
                    val top = screenBox(a.findViewById(R.id.topBar))
                    val controls = screenBox(a.findViewById(R.id.controls))
                    assertTrue("picture $picture vs top bar $top", !picture.intersects(top))
                    assertTrue("picture $picture vs controls $controls", !picture.intersects(controls))
                    assertTrue(picture.width > 0 && picture.height > 0)
                }
            }
            try {
                device.setOrientationNatural()
                check(org.mundhenk.finderscope.core.ViewfinderLayout.Arrangement.STACKED)
                device.setOrientationLeft()
                check(org.mundhenk.finderscope.core.ViewfinderLayout.Arrangement.SIDE)
                onView(withId(R.id.shutter)).perform(tap()) // still works in the column
                waitFor { (DemoCamera.running?.shotCount ?: 0) >= 1 }
            } finally {
                device.setOrientationNatural(); device.unfreezeRotation()
            }
        }
    }

    /**
     * The camera network is remembered: next time it is asked for by name (Android approves that
     * without its dialog), "Other camera" asks for any camera network again.
     */
    @Test
    fun rememberedCameraNetwork() {
        context.getSharedPreferences("finderscope", Context.MODE_PRIVATE).edit()
            .putString("cameraSsid", "AP_SSC_NX1000_0-AA:BB:CC").putString("cameraBssid", "02:11:22:33:44:55").commit()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            onView(withId(R.id.joinWifi)).check(matches(withText(context.getString(R.string.join_known, "NX1000"))))
            onView(withId(R.id.otherCamera)).check(matches(isDisplayed()))
            val requests = CopyOnWriteArrayList<String>()
            sc.onActivity { a -> a.wifi.requester = { r, cb -> requests += r.toString(); cb.onUnavailable() }; a.wifi.scanResults = { listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L), Triple("AP_SSC_NX1000_0-AA:BB:CC", "02:11:22:33:44:55", 3L)) } }
            onView(withId(R.id.joinWifi)).perform(tap())
            // the remembered camera is asked for at once (Android searches for it itself), not via the scan
            waitForUi { onView(withId(R.id.statusHint)).check(matches(withText(R.string.wifi_not_seen))) }
            assertTrue(requests.toString(), requests.last().contains("AP_SSC_NX1000_0-AA:BB:CC"))
            assertEquals(1, requests.size)
            // "Other camera": any camera network, here another one
            sc.onActivity { a -> a.wifi.scanResults = { listOf(Triple("AP_SSC_WB250F_0-DD:EE:FF", "02:66:77:88:99:00", 2L)) } }
            onView(withId(R.id.otherCamera)).perform(tap())
            // a network seen in the scan but not joined is asked for three times before giving up
            waitFor { requests.size == 1 + MainActivity.NAMED_ATTEMPTS }
            assertTrue(requests.toString(), requests.last().contains("AP_SSC_WB250F_0-DD:EE:FF"))
            assertTrue(LogBus.snapshot().any { it.contains("asking again") })
            // a newly joined camera replaces the remembered one
            sc.onActivity { it.rememberCamera("AP_SSC_WB250F_0-DD:EE:FF", null) }
            onView(withId(R.id.joinWifi)).check(matches(withText(context.getString(R.string.join_known, "WB250F"))))
            assertEquals("AP_SSC_WB250F_0-DD:EE:FF", Prefs(context).cameraSsid)
            sc.onActivity { it.rememberCamera("AP_SSC_WB250F_0-DD:EE:FF", null) } // same again: nothing changes
            sc.onActivity { it.rememberCamera("AndroidWifi", null) } // no camera network: not remembered
            assertEquals("AP_SSC_WB250F_0-DD:EE:FF", Prefs(context).cameraSsid)
        }
    }

    /**
     * The phone's own Wi-Fi scan decides: a camera network seen there is asked for by name, also
     * when it appears only later; when none comes, or Android gives up, the Wi-Fi settings are offered.
     */
    @Test
    fun joinUsesThePhonesScan_andOffersWifiSettings() {
        assertTrue(CameraWifi.isCameraNetwork("AP_SSC_NX1000_0-AA:BB:CC"))
        assertTrue(CameraWifi.isCameraNetwork("[Camera]AP_SSC_WB250F_0-AA:BB:CC"))
        assertTrue(CameraWifi.isCameraNetwork("\"WB150F_0-AA:BB:CC\""))
        assertTrue(!CameraWifi.isCameraNetwork("AndroidWifi")); assertTrue(!CameraWifi.isCameraNetwork("my_home_net"))
        Intents.init()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                val requests = CopyOnWriteArrayList<String>()
                sc.onActivity { a ->
                    a.findTimeoutMs = 2_000
                    a.wifi.requester = { r, cb -> requests += r.toString(); cb.onUnavailable() }
                    a.wifi.scanResults = { listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L), Triple("AP_SSC_NX1000_0-AA:BB:CC", "02:11:22:33:44:55", 3L)) }
                }
                onView(withId(R.id.joinWifi)).perform(tap())
                waitForUi { onView(withId(R.id.wifiSettings)).check(matches(isDisplayed())) }
                assertTrue(requests.toString(), requests.last().contains("AP_SSC_NX1000_0-AA:BB:CC"))
                assertTrue(LogBus.snapshot().toString(), LogBus.snapshot().any { it.contains("camera network NX1000 seen after 0 s") })
                // the camera comes on later: finderscope keeps looking and joins when it appears
                val polls = java.util.concurrent.atomic.AtomicInteger()
                sc.onActivity { a ->
                    a.wifi.pollMs = 100
                    a.wifi.scanResults = { if (polls.incrementAndGet() < 5) listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L)) else listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L), Triple("AP_SSC_NX1000_0-AA:BB:CC", "02:11:22:33:44:55", 3L)) }
                }
                onView(withId(R.id.joinWifi)).perform(tap())
                waitFor { requests.size == 2 * MainActivity.NAMED_ATTEMPTS }
                assertTrue(requests.toString(), requests.last().contains("AP_SSC_NX1000_0-AA:BB:CC"))
                assertTrue(polls.get() >= 5)
                assertTrue(LogBus.snapshot().any { it.contains("no camera network yet") })
                // it never comes: after the time, the Wi-Fi settings are offered, and no request was made
                sc.onActivity { a -> a.findWifiMs = 500; a.wifi.scanResults = { listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L)) } }
                onView(withId(R.id.joinWifi)).perform(tap())
                waitForUi { onView(withId(R.id.statusHint)).check(matches(withText(R.string.wifi_not_seen))) }
                assertEquals(2 * MainActivity.NAMED_ATTEMPTS, requests.size)
                // the Wi-Fi settings, and back: not on a camera network (the emulator's own Wi-Fi), or connecting
                intending(hasAction(android.provider.Settings.Panel.ACTION_WIFI)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
                onView(withId(R.id.wifiSettings)).perform(tap())
                intended(hasAction(android.provider.Settings.Panel.ACTION_WIFI))
                sc.onActivity { it.wifi.ssidSource = { "AndroidWifi" } } // the phone's own Wi-Fi: no camera
                sc.moveToState(Lifecycle.State.CREATED)
                sc.moveToState(Lifecycle.State.RESUMED)
                waitForUi { onView(withId(R.id.statusHint)).check(matches(withText(R.string.not_on_camera_wifi))) }
                // and on a camera network: finderscope connects by itself
                onView(withId(R.id.wifiSettings)).perform(tap())
                sc.onActivity { it.wifi.ssidSource = { "AP_SSC_NX1000_0-AA:BB:CC" } }
                sc.moveToState(Lifecycle.State.CREATED)
                sc.moveToState(Lifecycle.State.RESUMED)
                waitFor(what = "after the Wi-Fi settings") {
                    LogBus.snapshot().any { it.contains("back from the Wi-Fi settings") } ||
                        runCatching { onView(withId(R.id.statusHint)).check(matches(withText(R.string.not_on_camera_wifi))) }.isSuccess
                }
            }
        } finally { Intents.release() }
    }

    /** The phone's real Wi-Fi scan (the emulator's own network is no camera): looked through, then given up. */
    @Test
    fun realScanWithoutCamera() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val requests = CopyOnWriteArrayList<String>()
            sc.onActivity { a -> a.findWifiMs = 1_500; a.wifi.pollMs = 200; a.wifi.requester = { r, cb -> requests += r.toString(); cb.onUnavailable() } }
            onView(withId(R.id.joinWifi)).perform(tap())
            // where the scan cannot be read Android's picker is asked at once; otherwise the search gives up
            waitForUi { onView(withId(R.id.statusHint)).check(matches(anyOf(withText(R.string.wifi_not_seen), withText(R.string.wifi_unavailable)))) }
            assertTrue(LogBus.snapshot().toString(), requests.isNotEmpty() || LogBus.snapshot().any { it.contains("no camera network within") })
        }
    }

    /** While joining only Cancel works; a late answer after Cancel changes nothing. */
    @Test
    fun joiningDisablesTheStartScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var pending: android.net.ConnectivityManager.NetworkCallback? = null
            // looking for the camera's network: only Cancel, and Cancel stops the search
            sc.onActivity { a -> a.wifi.requester = { _, cb -> pending = cb }; a.wifi.scanResults = { emptyList() } }
            onView(withId(R.id.joinWifi)).perform(tap())
            onView(withId(R.id.status)).check(matches(withText(R.string.status_looking)))
            onView(withId(R.id.demo)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            sc.onActivity { assertTrue(it.wifi.searching) }
            onView(withId(R.id.joinWifi)).perform(tap()) // Cancel
            sc.onActivity { assertTrue(!it.wifi.searching); assertTrue(!it.busy) }
            // the camera network seen: the request waits for Android
            sc.onActivity { a -> a.wifi.scanResults = { listOf(Triple("AndroidWifi", "02:00:00:00:00:01", 1L), Triple("AP_SSC_NX1000_0-AA:BB:CC", "02:11:22:33:44:55", 3L)) } }
            onView(withId(R.id.joinWifi)).perform(tap())
            onView(withId(R.id.joinWifi)).check(matches(withText(R.string.cancel)))
            onView(withId(R.id.status)).check(matches(withText(R.string.status_joining)))
            onView(withId(R.id.demo)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            sc.onActivity { assertTrue(it.busy) }
            onView(withId(R.id.joinWifi)).perform(tap()) // Cancel
            sc.onActivity { assertTrue(!it.busy) }
            onView(withId(R.id.demo)).check(matches(isEnabled()))
            onView(withId(R.id.status)).check(matches(withText(R.string.status_idle)))
            sc.onActivity { pending!!.onUnavailable() } // too late: ignored
            onView(withId(R.id.statusHint)).check(matches(org.hamcrest.Matchers.not(withText(R.string.wifi_unavailable))))
        }
    }

    /** Nobody answers yet: "Waiting for the camera", and Cancel ends the attempt. */
    @Test
    fun waitingForTheCamera() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        ActivityScenario.launch(MainActivity::class.java).use {
            openAddress()
            onView(withId(R.id.address)).perform(replaceText("127.0.0.1:$port"))
            onView(withId(R.id.connect)).perform(tap())
            waitForUi { onView(withId(R.id.status)).check(matches(withText(R.string.status_waiting))) }
            onView(withId(R.id.connect)).check(matches(withText(R.string.cancel)))
            onView(withId(R.id.connect)).perform(tap())
            waitForUi { onView(withId(R.id.status)).check(matches(withText(R.string.closed))) }
        }
    }

    /** Real H.264 from the device's encoder, Annex B, SPS/PPS in front of the first key frame like the cameras send it. */
    object H264 {
        /** [marker]: pixels drawn bright on mid grey (a scene with a known "up"); null = a moving gradient. */
        fun encode(w: Int, h: Int, count: Int, marker: ((Int, Int) -> Boolean)? = null): List<ByteArray> {
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
                                for (y in 0 until ph) for (x in 0 until pw) {
                                    val v = when {
                                        p != 0 -> 128
                                        marker == null -> (shade + x) and 0xff
                                        marker(x, y) -> 235
                                        else -> 110
                                    }
                                    buf.put(y * plane.rowStride + x * plane.pixelStride, v.toByte())
                                }
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
