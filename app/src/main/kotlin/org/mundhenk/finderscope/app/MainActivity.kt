package org.mundhenk.finderscope.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mundhenk.finderscope.app.databinding.ActivityMainBinding
import org.mundhenk.finderscope.core.AfCell
import org.mundhenk.finderscope.core.CameraModel
import org.mundhenk.finderscope.core.CameraSession
import org.mundhenk.finderscope.core.Notice
import org.mundhenk.finderscope.core.RvfClient
import org.mundhenk.finderscope.core.RvfHttp
import org.mundhenk.finderscope.core.RvfInfo
import org.mundhenk.finderscope.core.RvfPicture
import org.mundhenk.finderscope.core.Rotation
import org.mundhenk.finderscope.core.SsdpNotices
import org.mundhenk.finderscope.core.VideoCodec
import org.mundhenk.finderscope.core.VideoFrame
import org.mundhenk.finderscope.core.gpsInfo
import org.mundhenk.finderscope.core.megapixels
import java.io.ByteArrayInputStream

/**
 * The one screen: how to connect (camera Wi-Fi, address, demo) until a session is live, then the
 * viewfinder with shutter, focus frames, timer, flash, photo size and zoom. All camera behaviour
 * lives in [CameraSession]; this class only shows it.
 */
class MainActivity : AppCompatActivity(), CameraSession.Listener {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    lateinit var wifi: CameraWifi; private set
    private val main = Handler(Looper.getMainLooper())
    var session: CameraSession? = null; private set
    @Volatile private var decoder: H264Decoder? = null
    private val parameterSets = org.mundhenk.finderscope.core.H264ParameterSets()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var info: RvfInfo? = null
    private var model = CameraModel("")
    private var streamSize = 0 to 0
    private var rotation = Rotation.D
    private var flashIndex = 0
    private var lastPicture: Uri? = null
    /** Where the last live session went, to resume it when the screen comes back. */
    private var resumeTarget: Pair<String, Int>? = null
    private var demo = false
    private val hideNotice = Runnable { b.notice.visibility = View.GONE }
    private val zoomRepeat = object : Runnable {
        var tele = true
        override fun run() { session?.zoom(tele); main.postDelayed(this, ZOOM_REPEAT_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        wifi = CameraWifi(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        // edge to edge: the picture runs under the system bars, the controls keep clear of them
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val topPad = b.topBar.paddingTop; val bottomPad = b.controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.connectPanel.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            b.topBar.setPadding(b.topBar.paddingLeft + 0, topPad + bars.top, b.topBar.paddingRight, b.topBar.paddingBottom)
            b.controls.setPadding(b.controls.paddingLeft, b.controls.paddingTop, b.controls.paddingRight, bottomPad + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        b.joinWifi.setOnClickListener { joinWifi() }
        b.connect.setOnClickListener { if (session == null) connectToTyped() else stopSession() }
        b.demo.setOnClickListener { startDemo() }
        b.disconnect.setOnClickListener { resumeTarget = null; stopSession() }
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.addressToggle.setOnClickListener { showAddress() }
        b.lastPicture.setOnClickListener { openLastPicture() }
        b.flash.setOnClickListener { cycleFlash() }
        b.timer.setOnClickListener { cycleTimer() }
        b.size.setOnClickListener { chooseSize() }
        setUpShutter()
        setUpZoom(b.zoomIn, true)
        setUpZoom(b.zoomOut, false)
        b.finder.addOnLayoutChangeListener { _, l, t, r, btm, ol, ot, or_, ob -> if (r - l != or_ - ol || btm - t != ob - ot) layoutPicture() }
        showIdle(getString(R.string.status_idle), getString(R.string.howto))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_main, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_log -> { startActivity(Intent(this, LogActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        if (b.address.text.isNullOrBlank()) b.address.setText(prefs.address.ifBlank { wifi.gateway() ?: CameraWifi.DEFAULT_CAMERA })
        if (prefs.address.isNotBlank()) showAddress()
        // back on screen: pick up the session where it was (the camera was released while away)
        val t = resumeTarget
        if (session == null && t != null) { if (demo) startDemo() else connect(t.first, t.second) }
    }

    override fun onStop() {
        // the camera's live view must not run on while nobody looks; resumed in onStart
        val live = session != null
        stopSession(keepResume = live)
        super.onStop()
    }

    override fun onDestroy() {
        wifi.release()
        DemoCamera.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { layoutPicture() }
    }

    // ---- connecting ----------------------------------------------------------------------

    private fun joinWifi() {
        b.statusHint.setText(R.string.joining_wifi)
        wifi.join { network ->
            runOnUiThread {
                if (network == null) { b.statusHint.setText(R.string.wifi_unavailable); return@runOnUiThread }
                val gw = wifi.gateway() ?: CameraWifi.DEFAULT_CAMERA
                b.address.setText(gw)
                connect(gw, 7676)
            }
        }
    }

    private fun connectToTyped() {
        val typed = b.address.text?.toString().orEmpty()
        val target = CameraWifi.parse(typed)
        if (target == null) { b.addressLayout.error = getString(R.string.address_invalid); return }
        b.addressLayout.error = null
        prefs.address = if (typed.trim() == wifi.gateway()) "" else typed
        demo = false
        connect(target.first, target.second)
    }

    private fun startDemo() {
        val sim = DemoCamera.start()
        demo = true
        connect("127.0.0.1", sim.controlPort)
    }

    private fun connect(host: String, port: Int) {
        if (session != null) return
        resumeTarget = host to port
        val loopback = host.startsWith("127.")
        val notices: ((onNotice: (org.mundhenk.finderscope.core.SsdpNotice) -> Unit) -> SsdpNotices?)? = if (loopback) null else { cb ->
            runCatching {
                multicastLock = getSystemService(WifiManager::class.java).createMulticastLock("finderscope").apply { setReferenceCounted(false); acquire() }
                SsdpNotices(wifi.bind(SsdpNotices.multicast(), host), cb).start()
            }.getOrNull()
        }
        val s = CameraSession(host, port, prefs.clientId, this, RvfHttp(wifi.socketFactory(host)), notices, log = { LogBus.log(it) })
        s.options = CameraSession.Options(savePictures = prefs.savePictures, gps = if (prefs.gpsTag) ({ location() }) else null)
        session = s
        s.start()
    }

    private fun stopSession(keepResume: Boolean = false) {
        val s = session ?: return
        session = null
        if (!keepResume) resumeTarget = null
        s.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    /** The phone's last known position for the camera's GPS tag; null without permission or fix. */
    @SuppressLint("MissingPermission")
    private fun location(): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return gpsInfo(loc.latitude, loc.longitude)
    }

    // ---- session events (from the session's threads) ------------------------------------

    override fun onState(state: CameraSession.State, message: String) = ui {
        when (state) {
            CameraSession.State.CONNECTING -> showIdle(getString(R.string.status_connecting, message.removePrefix("Connecting to ")), getString(R.string.status_connecting_hint), connecting = true)
            CameraSession.State.LIVE -> {
                b.connectPanel.visibility = View.GONE
                b.picture.visibility = View.VISIBLE
                b.topBar.visibility = View.VISIBLE
                b.controls.visibility = View.VISIBLE
                b.shutter.isEnabled = false
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // like a camera app: the picture alone on the screen, system bars on a swipe
                WindowCompat.getInsetsController(window, b.root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            CameraSession.State.CLOSED, CameraSession.State.FAILED -> {
                if (session?.isClosed == true) { session = null; if (state == CameraSession.State.FAILED || message != "Disconnected") resumeTarget = null }
                releaseDecoder()
                showIdle(if (state == CameraSession.State.FAILED) getString(R.string.failed, message) else if (message == "Disconnected") getString(R.string.closed) else message, getString(R.string.howto))
            }
        }
    }

    override fun onConnected(client: RvfClient, info: RvfInfo, model: CameraModel) = ui {
        this.info = info
        this.model = model
        b.afOverlay.grid = model.afGrid
        b.zoomPill.visibility = if (client.canZoom && model.zoomsOverWifi) View.VISIBLE else View.INVISIBLE
        b.zoomLevel.text = info.zoom.toString()
        b.flash.visibility = if (client.has("SetFlash") && info.flashModes.isNotEmpty()) View.VISIBLE else View.GONE
        flashIndex = info.flash?.let { info.flashModes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        showFlash(info.flashModes.getOrElse(flashIndex) { "" })
        b.size.visibility = if (client.has("SetResolution") && info.resolutions.isNotEmpty()) View.VISIBLE else View.GONE
        info.resolutions.firstOrNull()?.let { (w, h) -> b.size.text = megapixels(w, h) }
        showTimer(0)
        showInfo()
    }

    override fun onStream(codec: VideoCodec, width: Int, height: Int) = ui {
        streamSize = width to height
        b.liveImage.visibility = if (codec == VideoCodec.MJPEG) View.VISIBLE else View.GONE
        b.liveSurface.visibility = if (codec == VideoCodec.MJPEG) View.GONE else View.VISIBLE
        decoder?.resync()
        layoutPicture()
    }

    override fun onFrame(frame: VideoFrame, codec: VideoCodec) {
        if (codec == VideoCodec.MJPEG) {
            val bmp = BitmapFactory.decodeStream(ByteArrayInputStream(frame.data)) ?: return
            framesShown++
            ui { b.liveImage.setImageBitmap(bmp) }
            return
        }
        parameterSets.observe(frame.data) // also while there is no surface yet
        val d = decoder ?: b.liveSurface.holder.surface?.takeIf { it.isValid }?.let { H264Decoder(it, streamSize.first, streamSize.second, parameterSets) }?.also { decoder = it } ?: return
        d.feed(frame, System.nanoTime() / 1000)
    }

    @Volatile var framesShown = 0L; private set
    val framesDecoded: Long get() = decoder?.decoded?.get() ?: framesShown

    override fun onReady(ready: Boolean) = ui { b.shutter.isEnabled = ready; if (ready) b.afOverlay.clear() }

    override fun onFocus(ok: Boolean, cells: List<AfCell>) = ui { b.afOverlay.show(ok, cells) }

    override fun onCountdown(secondsLeft: Int) = ui {
        b.countdown.visibility = if (secondsLeft > 0) View.VISIBLE else View.GONE
        b.countdown.text = secondsLeft.toString()
    }

    override fun onPicture(picture: RvfPicture) {
        val name = Pictures.nameFor(picture.name, picture.preview)
        try {
            val uri = Pictures.save(this, name, picture.bytes)
            LogBus.log("Saved $name (${picture.bytes.size} bytes)")
            val thumb = BitmapFactory.decodeByteArray(picture.bytes, 0, picture.bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            ui {
                lastPicture = uri
                thumb?.let { b.lastPicture.setImageBitmap(it) }
                notice(getString(if (picture.preview) R.string.preview_saved else R.string.picture_saved, name))
            }
        } catch (e: Exception) {
            LogBus.log("Saving $name failed: $e")
            ui { notice(getString(R.string.save_failed, e.message ?: e.toString())) }
        }
    }

    private var shotsLeft: Int? = null

    override fun onShots(available: Int) = ui { shotsLeft = available; b.shots.text = maxOf(available, 0).toString() }

    override fun onRotation(rotation: Rotation) = ui { this.rotation = rotation; layoutPicture() }

    override fun onZoom(position: Int) = ui { b.zoomLevel.text = position.toString() }

    override fun onNotice(notice: Notice, detail: String) = ui {
        notice(when (notice) {
            Notice.FOCUS_FAILED -> getString(if (model.cancelsShotOnFocusFail) R.string.notice_focus_failed_nx else R.string.notice_focus_failed)
            Notice.CARD_FULL -> getString(R.string.notice_card_full)
            Notice.NUMBERING_FULL -> getString(R.string.notice_numbering_full)
            Notice.SHOT_FAILED -> getString(R.string.notice_shot_failed)
            Notice.NO_PICTURE -> getString(R.string.notice_no_picture)
            Notice.ON_CARD_ONLY -> getString(R.string.notice_on_card)
            Notice.COMMAND_FAILED -> getString(R.string.notice_command_failed, detail)
            Notice.RECONNECTED -> getString(R.string.notice_reconnected)
        })
    }

    // ---- view helpers --------------------------------------------------------------------

    private fun showIdle(status: String, hint: String, connecting: Boolean = false) {
        b.connectPanel.visibility = View.VISIBLE
        b.picture.visibility = View.GONE
        b.topBar.visibility = View.GONE
        b.controls.visibility = View.GONE
        b.countdown.visibility = View.GONE
        b.notice.visibility = View.GONE
        WindowCompat.getInsetsController(window, b.root).show(WindowInsetsCompat.Type.systemBars())
        b.status.text = status
        b.statusHint.text = if (hint == getString(R.string.howto)) getText(R.string.howto) else hint
        b.connect.setText(if (connecting) R.string.cancel else R.string.connect)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showInfo() { b.info.text = model.name }

    /** Connect by address: the field appears, the link that opened it goes away. */
    private fun showAddress() {
        b.addressGroup.visibility = View.VISIBLE
        b.addressToggle.visibility = View.GONE
    }

    /** Flash chip: bolt (crossed out for "off") and the camera's own word for the mode. */
    private fun showFlash(mode: String) {
        b.flash.text = mode.uppercase()
        b.flash.setIconResource(if (mode.equals("off", ignoreCase = true)) R.drawable.ic_flash_off else R.drawable.ic_flash)
        b.flash.contentDescription = getString(R.string.flash_value, mode)
    }

    /** Timer chip: the clock, with the seconds when set. */
    private fun showTimer(seconds: Int) {
        b.timer.text = if (seconds == 0) "" else "${seconds}s"
        b.timer.contentDescription = if (seconds == 0) getString(R.string.timer_off) else getString(R.string.timer_value, seconds)
        b.timer.alpha = if (seconds == 0) 0.7f else 1f
    }

    private fun notice(text: String) {
        b.notice.text = text
        b.notice.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, NOTICE_MS)
    }

    /** The picture area: the stream's aspect ratio, turned like the camera, as large as fits. */
    private fun layoutPicture() {
        val (sw, sh) = streamSize.takeIf { it.first > 0 && it.second > 0 } ?: (4 to 3)
        val turned = rotation == Rotation.L || rotation == Rotation.R
        val (aw, ah) = if (turned) sh to sw else sw to sh
        val fw = b.finder.width; val fh = b.finder.height
        if (fw == 0 || fh == 0) return
        val scale = minOf(fw / aw.toFloat(), fh / ah.toFloat())
        val dw = (aw * scale).toInt(); val dh = (ah * scale).toInt()
        val lp = b.picture.layoutParams
        // the view is laid out unturned and then rotated, so it takes the unturned size
        lp.width = if (turned) dh else dw; lp.height = if (turned) dw else dh
        b.picture.layoutParams = lp
        b.picture.rotation = rotation.degrees.toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpShutter() {
        // touch: press = focus, release inside = picture, release outside = cancel (like the camera's button)
        b.shutter.setOnTouchListener { v, e ->
            val s = session ?: return@setOnTouchListener false
            if (!v.isEnabled) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { if (s.countingDown) s.cancelCountdown() else s.press(); v.isPressed = true }
                MotionEvent.ACTION_UP -> { v.isPressed = false; s.release(e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height) }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; s.release(false) }
            }
            true
        }
        // accessibility services and keyboards click instead of touching
        b.shutter.setOnClickListener { session?.let { if (it.countingDown) it.cancelCountdown() else it.tap() } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpZoom(button: View, tele: Boolean) {
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; zoomRepeat.tele = tele; main.removeCallbacks(zoomRepeat); zoomRepeat.run() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(zoomRepeat) }
            }
            true
        }
        button.setOnClickListener { session?.zoom(tele) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session
        if (s != null && b.shutter.isEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) { if (s.countingDown) s.cancelCountdown() else s.press() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val s = session
        if (s != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) { s.release(true); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun cycleFlash() {
        val modes = info?.flashModes.orEmpty()
        if (modes.isEmpty()) return
        flashIndex = (flashIndex + 1) % modes.size
        session?.setFlash(modes[flashIndex])
        showFlash(modes[flashIndex])
    }

    private fun cycleTimer() {
        val s = session ?: return
        val next = when (s.timerSeconds) { 0 -> 2; 2 -> 10; else -> 0 }
        s.setTimer(next)
        showTimer(next)
    }

    private fun chooseSize() {
        val sizes = info?.resolutions.orEmpty()
        val labels = sizes.map { (w, h) -> "${megapixels(w, h)}  ($w×$h)" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.size).setItems(labels) { _, i ->
            val (w, h) = sizes[i]
            session?.setResolution(w, h)
            b.size.text = megapixels(w, h)
        }.show()
    }

    private fun openLastPicture() {
        val uri = lastPicture ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: android.content.ActivityNotFoundException) { notice(getString(R.string.open_failed)) }
    }

    private fun releaseDecoder() { decoder?.release(); decoder = null }

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    companion object {
        const val ZOOM_REPEAT_MS = 600L
        const val NOTICE_MS = 4_000L
    }
}
