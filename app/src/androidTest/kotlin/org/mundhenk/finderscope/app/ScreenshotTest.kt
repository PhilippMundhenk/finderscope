package org.mundhenk.finderscope.app

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Documentation screenshots, taken against the demo camera (no real photos, no private data).
 * The PNGs go to /data/local/tmp/finderscope-shots on the device, where they survive the test
 * APK's uninstall; CI pulls them and publishes them on the `screenshots` branch.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val dir = "/data/local/tmp/finderscope-shots"

    @After
    fun tearDown() { DemoCamera.stop() }

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
        shell("mkdir -p $dir")
        shell("screencap -p $dir/$name.png")
        Thread.sleep(500)
    }

    private fun waitFor(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) { if (System.currentTimeMillis() > deadline) throw AssertionError("timeout"); Thread.sleep(100) }
    }

    private fun waitForUi(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) { try { assertion(); return } catch (e: Throwable) { if (System.currentTimeMillis() > deadline) throw e; Thread.sleep(200) } }
    }

    @Test
    fun documentationScreenshots() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("finderscope", Context.MODE_PRIVATE).edit().clear().commit()
        // a calm status bar for the pictures
        shell("settings put secure immersive_mode_confirmations confirmed")
        shell("settings put global sysui_demo_allowed 1")
        shell("am broadcast -a com.android.systemui.demo -e command enter")
        shell("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200")
        shell("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                shot("start")
                onView(withId(R.id.demo)).perform(androidx.test.espresso.action.ViewActions.click())
                waitForUi { onView(withId(R.id.shutter)).check(matches(allOf(isDisplayed(), isEnabled()))) }
                var act: MainActivity? = null
                sc.onActivity { act = it }
                waitFor { act!!.framesShown > 10 }
                // a picture first, so the thumbnail is filled
                onView(withId(R.id.shutter)).perform(androidx.test.espresso.action.ViewActions.click())
                waitFor { (DemoCamera.running?.shotCount ?: 0) >= 1 }
                waitForUi { onView(withId(R.id.shutter)).check(matches(isEnabled())) }
                Thread.sleep(4_500) // let the "saved" notice fade
                // half-press: the focus frames light up
                val shutter = act!!.findViewById<View>(R.id.shutter)
                val now = SystemClock.uptimeMillis()
                instrumentation.runOnMainSync { shutter.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, shutter.width / 2f, shutter.height / 2f, 0)) }
                waitFor { act!!.findViewById<AfOverlayView>(R.id.afOverlay).cells.isNotEmpty() }
                shot("live")
                instrumentation.runOnMainSync { shutter.dispatchTouchEvent(MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, shutter.width * 3f, 0f, 0)) }
                // the same in landscape: controls in columns beside the picture
                val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                try {
                    device.setOrientationLeft()
                    waitFor { act!!.plan?.arrangement == org.mundhenk.finderscope.core.ViewfinderLayout.Arrangement.SIDE }
                    Thread.sleep(1_500)
                    shot("live-landscape")
                } finally { device.setOrientationNatural(); device.unfreezeRotation() }
            }
            ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings") }
        } finally {
            shell("am broadcast -a com.android.systemui.demo -e command exit")
        }
    }
}
