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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mundhenk.finderscope.app.databinding.ActivityMainBinding
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import org.mundhenk.finderscope.core.AfCell
import org.mundhenk.finderscope.core.Box
import org.mundhenk.finderscope.core.CameraLocation
import org.mundhenk.finderscope.core.SsdpSearch
import org.mundhenk.finderscope.core.ViewfinderLayout
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
    @Volatile private var streamSize = 0 to 0
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
        supportActionBar?.setDisplayShowTitleEnabled(false) // the start screen shows the name large
        // edge to edge: the picture runs under the system bars, the controls keep clear of them
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.connectPanel.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            systemInsets = Box(bars.left, bars.top, bars.right, bars.bottom)
            layoutPicture()
            WindowInsetsCompat.CONSUMED
        }

        b.joinWifi.setOnClickListener { if (busy) cancelJoin() else joinWifi() }
        b.connect.setOnClickListener { if (session == null) connectToTyped() else stopSession() }
        b.demo.setOnClickListener { startDemo() }
        b.disconnect.setOnClickListener { resumeTarget = null; stopSession() }
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.addressToggle.setOnClickListener { showAddress() }
        b.otherCamera.setOnClickListener { joinWifi(other = true) }
        b.wifiSettings.setOnClickListener { openWifiSettings() }
        b.lastPicture.setOnClickListener { openLastPicture() }
        b.flash.setOnClickListener { cycleFlash() }
        b.timer.setOnClickListener { cycleTimer() }
        b.size.setOnClickListener { chooseSize() }
        setUpShutter()
        setUpZoom(b.zoomIn, true)
        setUpZoom(b.zoomOut, false)
        // no surface while away: the decoder goes with it and is made again at the next key frame
        b.liveSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { dropDecoder() }
        })
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
        main.removeCallbacks(endWhileAway)
        if (session != null) decoder?.resync()
        b.shutter.contentDescription = getString(if (prefs.tapToFocus) R.string.shutter_hint_tap else R.string.shutter_hint)
        if (fromWifiSettings) backFromWifiSettings()
        if (b.address.text.isNullOrBlank()) b.address.setText(prefs.address.ifBlank { wifi.gateway() ?: CameraWifi.DEFAULT_CAMERA })
        if (prefs.address.isNotBlank()) showAddress()
        showKnownCamera()
        // back on screen: pick up the session where it was (the camera was released while away)
        val t = resumeTarget
        if (session == null && t != null) { if (demo) startDemo() else connect(t.first, t.second) }
    }

    override fun onStop() {
        // The connection stays: ending it makes the camera leave Remote Viewfinder ("network
        // disconnected"), and closing the live view at the wrong moment can even hang an NX1000.
        // Only the picture is not drawn while away; after a long absence the session ends.
        if (session != null && !isChangingConfigurations) main.postDelayed(endWhileAway, awayMs ?: prefs.awayMinutes * 60_000L)
        super.onStop()
    }

    private val endWhileAway = Runnable { LogBus.log("not on screen for the set time: disconnecting"); stopSession(keepResume = true) }

    /** How long the connection is kept while finderscope is not on screen; null = the setting (tests set it). */
    internal var awayMs: Long? = null

    override fun onDestroy() {
        main.removeCallbacks(endWhileAway)
        if (isFinishing) stopSession()
        wifi.cancelSearch()
        wifi.release()
        DemoCamera.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { layoutPicture() }
    }

    // ---- connecting ----------------------------------------------------------------------

    /** The camera network joined before, if Android told us which it was. */
    private val knownCamera: Pair<String, String?>?
        get() = prefs.cameraSsid.takeIf { it.isNotBlank() }?.let { it to prefs.cameraBssid.ifBlank { null } }

    /** Android tells the joined network's name only with the location permission: asked once, before the first join. */
    private val askLocationForWifi = registerForActivityResult(ActivityResultContracts.RequestPermission()) { joinWifi(other = true, asked = true) }

    /**
     * Joins the camera network: the one remembered from last time directly (Android does not ask
     * again), or, for [other] or the first time, from the system's list of camera networks.
     */
    private fun joinWifi(other: Boolean = false, asked: Boolean = false) {
        val known = if (other) null else knownCamera
        if (known == null && !asked && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            b.statusHint.setText(R.string.location_for_wifi)
            askLocationForWifi.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        joining = true
        applyBusy()
        b.status.setText(R.string.status_looking)
        b.statusHint.setText(R.string.status_looking_hint)
        // the camera may open its network long after Join: wait for it in the phone's own scan
        wifi.findCamera(known, findWifiMs) { target, searched ->
            if (!joining) return@findCamera
            if (searched && target == null) {
                joining = false
                b.status.setText(R.string.status_idle); b.statusHint.setText(R.string.wifi_not_seen)
                b.wifiSettings.visibility = View.VISIBLE
                applyBusy(); return@findCamera
            }
            requestCameraWifi(target)
        }
    }

    /** How long Join waits for the camera's network to appear (tests shorten it). */
    internal var findWifiMs = 3 * 60_000L

    /** Asks Android for the camera network: by name where known, else with its picker. */
    private fun requestCameraWifi(target: Pair<String, String?>?) {
        b.status.setText(R.string.status_joining)
        b.statusHint.setText(R.string.joining_wifi)
        wifi.join(target, onIdentified = { ssid, bssid -> runOnUiThread { rememberCamera(ssid, bssid) } }, onLost = { runOnUiThread { cameraWifiLost() } }) { network ->
            runOnUiThread {
                if (!joining) return@runOnUiThread // cancelled meanwhile
                joining = false
                if (network == null) {
                    b.status.setText(R.string.status_idle); b.statusHint.setText(R.string.wifi_unavailable)
                    b.wifiSettings.visibility = View.VISIBLE
                    applyBusy(); return@runOnUiThread
                }
                val gw = wifi.gateway() ?: CameraWifi.DEFAULT_CAMERA
                b.address.setText(gw)
                connect(gw, 7676)
            }
        }
    }

    /** Joining the camera network, or connecting to the camera: only Cancel works meanwhile. */
    private var joining = false
    private var connecting = false
    internal val busy: Boolean get() = joining || connecting

    /** While busy the join button is Cancel and nothing else on the start screen can be pressed twice. */
    private fun applyBusy() {
        val on = busy
        if (on) b.joinWifi.setText(R.string.cancel) else showKnownCamera()
        for (v in listOf(b.otherCamera, b.addressToggle, b.address, b.demo, b.wifiSettings)) v.isEnabled = !on
        b.connect.isEnabled = !joining
    }

    /** The camera's network went away (camera switched off, out of range): the session ends with a clear message. */
    internal fun cameraWifiLost() {
        LogBus.log("the camera's Wi-Fi is gone")
        if (session == null && !busy) return
        joining = false
        resumeTarget = null
        stopSession()
        showIdle(getString(R.string.camera_wifi_lost), getString(R.string.howto))
    }

    /** Back from the Wi-Fi settings: connect if the phone is now on a camera network. */
    private var fromWifiSettings = false

    private fun openWifiSettings() {
        fromWifiSettings = true
        startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
    }

    private fun backFromWifiSettings() {
        fromWifiSettings = false
        if (session != null) return
        val ssid = wifi.currentSsid()
        if (ssid != null && !CameraWifi.isCameraNetwork(ssid)) { b.statusHint.setText(R.string.not_on_camera_wifi); return }
        b.wifiSettings.visibility = View.GONE
        LogBus.log("back from the Wi-Fi settings on ${ssid ?: "an unknown network"}: connecting")
        connect(wifi.gateway() ?: CameraWifi.DEFAULT_CAMERA, 7676)
    }

    private fun cancelJoin() {
        joining = false
        wifi.cancelSearch()
        resumeTarget = null
        stopSession()
        wifi.release()
        showIdle(getString(R.string.status_idle), getString(R.string.howto))
    }

    /** How long to wait for a camera that is not answering yet (tests shorten it). */
    internal var findTimeoutMs = 60_000L

    internal fun rememberCamera(ssid: String, bssid: String?) {
        if (ssid == prefs.cameraSsid && (bssid ?: "") == prefs.cameraBssid) return
        prefs.cameraSsid = ssid; prefs.cameraBssid = bssid ?: ""
        LogBus.log("Remembered the camera network ${CameraModel.parse(ssid).name}")
        showKnownCamera()
    }

    /** "Connect to NX1000" when a camera network is remembered, with "Other camera" under it. */
    private fun showKnownCamera() {
        if (busy) return
        val known = knownCamera
        if (known == null) { b.joinWifi.setText(R.string.join_wifi); b.otherCamera.visibility = View.GONE; return }
        b.joinWifi.text = getString(R.string.join_known, CameraModel.parse(known.first).name)
        b.otherCamera.visibility = View.VISIBLE
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
        // the camera may need a moment after the phone joined, and may announce itself elsewhere: SSDP on its network
        val discover: ((Long) -> CameraLocation?)? = if (loopback) null else { t ->
            runCatching {
                if (multicastLock == null) multicastLock = getSystemService(WifiManager::class.java).createMulticastLock("finderscope").apply { setReferenceCounted(false); acquire() }
                wifi.bind(SsdpNotices.multicast(), host).use { SsdpSearch.search(it, t, log = { m -> LogBus.log(m) }) }
            }.getOrNull()
        }
        val s = CameraSession(host, port, prefs.clientId, this, RvfHttp(wifi.socketFactory(host)), notices, log = { LogBus.log(it) }, discover = discover, findTimeoutMs = findTimeoutMs)
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

    /** The phone's last known position for the camera's GPS argument; null without permission or fix. */
    private fun location(): String? = position()?.let { gpsInfo(it.first, it.second) }

    /** The phone's last known position (latitude, longitude); null without permission or fix. */
    @SuppressLint("MissingPermission")
    private fun position(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return loc.latitude to loc.longitude
    }

    // ---- session events (from the session's threads) ------------------------------------

    override fun onState(state: CameraSession.State, message: String) = ui {
        when (state) {
            CameraSession.State.CONNECTING ->
                if (message == CameraSession.WAITING) showIdle(getString(R.string.status_waiting), getString(R.string.status_waiting_hint), connecting = true)
                else showIdle(getString(R.string.status_connecting, message.removePrefix("Connecting to ")), getString(R.string.status_connecting_hint), connecting = true)
            CameraSession.State.LIVE -> {
                b.connectPanel.visibility = View.GONE
                b.picture.visibility = View.VISIBLE
                b.topBar.visibility = View.VISIBLE
                b.controls.visibility = View.VISIBLE
                b.shutter.isEnabled = false
                layoutPicture()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // like a camera app: the picture alone on the screen, system bars on a swipe
                WindowCompat.getInsetsController(window, b.root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            CameraSession.State.CLOSED, CameraSession.State.FAILED -> {
                // the end of a session already replaced (left the screen and came back): not this one's
                if (session?.isClosed == false) return@ui
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
        b.zoomPill.visibility = if (client.canZoom && model.zoomsOverWifi) View.VISIBLE else View.GONE
        b.zoomLevel.text = info.zoom.toString()
        b.flash.visibility = if (client.has("SetFlash") && info.flashModes.isNotEmpty()) View.VISIBLE else View.GONE
        flashIndex = info.flash?.let { info.flashModes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        showFlash(info.flashModes.getOrElse(flashIndex) { "" })
        b.size.visibility = if (client.has("SetResolution") && info.resolutions.isNotEmpty()) View.VISIBLE else View.GONE
        info.resolutions.firstOrNull()?.let { (w, h) -> b.size.text = megapixels(w, h) }
        showTimer(0)
        showInfo()
        layoutPicture()
    }

    override fun onStream(codec: VideoCodec, width: Int, height: Int) {
        // on the stream thread, which also feeds the decoder: a new size needs a new decoder
        val changed = streamSize != (width to height)
        streamSize = width to height
        if (changed) dropDecoder() else decoder?.resync()
        ui {
            b.liveImage.visibility = if (codec == VideoCodec.MJPEG) View.VISIBLE else View.GONE
            b.liveSurface.visibility = if (codec == VideoCodec.MJPEG) View.GONE else View.VISIBLE
            layoutPicture()
        }
    }

    /** The size the decoder really produces wins over the AVI header (the NX1000 header always says 320x240). */
    private fun pictureSizeFromDecoder(w: Int, h: Int) {
        if (decodedSize == w to h) return
        decodedSize = w to h
        LogBus.log("live view is ${w}x$h")
        ui { layoutPicture() }
    }

    @Volatile private var decodedSize: Pair<Int, Int>? = null

    /** The decoder is made again at the next key frame. */
    private fun dropDecoder() { val d = decoder; decoder = null; runCatching { d?.release() } }

    override fun onFrame(frame: VideoFrame, codec: VideoCodec) {
        if (codec == VideoCodec.MJPEG) {
            val bmp = BitmapFactory.decodeStream(ByteArrayInputStream(frame.data)) ?: return
            framesShown++
            ui { b.liveImage.setImageBitmap(bmp) }
            return
        }
        parameterSets.observe(frame.data) // also while there is no surface yet
        val d = decoder ?: b.liveSurface.holder.surface?.takeIf { it.isValid }?.let { H264Decoder(it, streamSize.first, streamSize.second, parameterSets, onSize = { w, h -> pictureSizeFromDecoder(w, h) }, onStats = { LogBus.log(it) }) }?.also { decoder = it } ?: return
        try { d.feed(frame, System.nanoTime() / 1000) } catch (e: IllegalStateException) {
            // MediaCodec.CodecException included: start over with a fresh decoder at the next key frame
            LogBus.log("decoder error, starting over: $e")
            dropDecoder()
        }
    }

    @Volatile var framesShown = 0L; private set
    val framesDecoded: Long get() = decoder?.decoded?.get() ?: framesShown

    override fun onReady(ready: Boolean) = ui { b.shutter.isEnabled = ready; if (ready) b.afOverlay.clear() }

    override fun onFocus(ok: Boolean, cells: List<AfCell>) = ui { b.afOverlay.show(ok, cells) }

    /** The highest countdown value shown since the session started (for the tests). */
    @Volatile var countdownSeen = 0; private set

    override fun onCountdown(secondsLeft: Int) = ui {
        countdownSeen = maxOf(countdownSeen, secondsLeft)
        b.countdown.visibility = if (secondsLeft > 0) View.VISIBLE else View.GONE
        b.countdown.text = secondsLeft.toString()
    }

    override fun onPicture(picture: RvfPicture) {
        val name = Pictures.nameFor(picture.name, picture.preview)
        try {
            val uri = Pictures.save(this, name, picture.bytes, if (prefs.gpsTag) position() else null)
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
        this.connecting = connecting
        applyBusy()
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

    private var systemInsets = Box(0, 0, 0, 0)
    /** The arrangement currently on screen (for the tests). */
    var plan: ViewfinderLayout.Plan? = null; private set

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /**
     * Where the picture and the controls go for this window ([ViewfinderLayout]): stacked in tall
     * windows, side by side in wide ones, the controls over the picture only where keeping it
     * clear would make it too small.
     */
    private fun layoutPicture() {
        val w = b.root.width; val h = b.root.height
        if (w == 0 || h == 0) return
        val (sw, sh) = (decodedSize ?: streamSize).takeIf { it.first > 0 && it.second > 0 } ?: (4 to 3)
        val turned = rotation == Rotation.L || rotation == Rotation.R
        val aspect = if (turned) sh.toDouble() / sw else sw.toDouble() / sh
        val zoom = b.zoomPill.visibility == View.VISIBLE
        val p = ViewfinderLayout.plan(
            Box(0, 0, w, h), systemInsets, aspect,
            barStacked = px(BAR_STACKED), barSide = px(BAR_SIDE),
            controlsStacked = px(if (zoom) CONTROLS_ZOOM else CONTROLS_STACKED), controlsSide = px(if (zoom) CONTROLS_ZOOM else CONTROLS_SIDE),
            minNotice = px(48),
        )
        if (p == plan && b.picture.rotation == rotation.degrees.toFloat()) return
        plan = p
        applyPlan(p, turned, zoom)
    }

    private fun applyPlan(p: ViewfinderLayout.Plan, turned: Boolean, zoom: Boolean) {
        val side = p.arrangement == ViewfinderLayout.Arrangement.SIDE
        val ins = systemInsets
        val match = ViewGroup.LayoutParams.MATCH_PARENT; val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        fun at(box: Box) = FrameLayout.LayoutParams(box.width, box.height, Gravity.TOP or Gravity.START).apply { leftMargin = box.left; topMargin = box.top }

        // the picture is laid out unturned and rotated around its centre
        val pic = p.picture
        val (lw, lh) = if (turned) pic.height to pic.width else pic.width to pic.height
        b.picture.layoutParams = FrameLayout.LayoutParams(lw, lh, Gravity.TOP or Gravity.START).apply {
            leftMargin = pic.left + (pic.width - lw) / 2; topMargin = pic.top + (pic.height - lh) / 2
        }
        b.picture.rotation = rotation.degrees.toFloat()
        b.pictureOverlay.layoutParams = at(pic)
        // notices in free space where there is some, else along the top of the picture
        b.noticeArea.layoutParams = at(p.noticeSpace ?: Box(pic.left, pic.top, pic.right, pic.top + px(72)))

        // settings: a bar on top, or a column on the left
        val top = b.topBar
        top.orientation = if (side) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        top.gravity = if (side) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        top.layoutParams = if (side) FrameLayout.LayoutParams(px(BAR_SIDE) + ins.left, match, Gravity.START)
            else FrameLayout.LayoutParams(match, px(BAR_STACKED) + ins.top, Gravity.TOP)
        if (side) top.setPadding(ins.left, ins.top + px(8), 0, ins.bottom + px(8)) else top.setPadding(ins.left + px(8), ins.top, ins.right + px(8), 0)
        top.background = if (p.overlay) ContextCompat.getDrawable(this, if (side) R.drawable.scrim_start else R.drawable.scrim_top) else null
        b.info.layoutParams = if (side) LinearLayout.LayoutParams(wrap, 0, 1f) else LinearLayout.LayoutParams(0, wrap, 1f)
        // in the narrow column the chips put their icon above the label instead of beside it
        for (chip in listOf(b.flash, b.timer)) chip.iconGravity = if (side) MaterialButton.ICON_GRAVITY_TOP else MaterialButton.ICON_GRAVITY_TEXT_START

        // shutter, last picture, shots left (and zoom): a row at the bottom, or a column on the right
        val c = b.controls
        c.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        c.layoutParams = if (side) FrameLayout.LayoutParams(px(if (zoom) CONTROLS_ZOOM else CONTROLS_SIDE) + ins.right, match, Gravity.END)
            else FrameLayout.LayoutParams(match, px(if (zoom) CONTROLS_ZOOM else CONTROLS_STACKED) + ins.bottom, Gravity.BOTTOM)
        if (side) c.setPadding(0, ins.top + px(12), ins.right, ins.bottom + px(12)) else c.setPadding(ins.left + px(24), 0, ins.right + px(24), ins.bottom + px(8))
        c.background = if (p.overlay) ContextCompat.getDrawable(this, if (side) R.drawable.scrim_end else R.drawable.scrim_bottom) else null
        b.shutterRow.orientation = if (side) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        b.shutterRow.layoutParams = if (side) LinearLayout.LayoutParams(px(CONTROLS_SIDE), match) else LinearLayout.LayoutParams(match, wrap)
        b.zoomPill.orientation = if (side) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        b.zoomPill.layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply {
            gravity = Gravity.CENTER
            if (side) marginStart = px(8) else bottomMargin = px(12)
        }
        // tele on top when the pill stands upright, on the right when it lies
        val order = if (side) listOf(b.zoomIn, b.zoomLevel, b.zoomOut) else listOf(b.zoomOut, b.zoomLevel, b.zoomIn)
        if ((0 until 3).any { b.zoomPill.getChildAt(it) !== order[it] }) { b.zoomPill.removeAllViews(); order.forEach { b.zoomPill.addView(it) } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpShutter() {
        // touch: press = focus, release inside = picture, release outside = cancel (like the camera's button)
        b.shutter.setOnTouchListener { v, e ->
            val s = session ?: return@setOnTouchListener false
            if (!v.isEnabled) return@setOnTouchListener true
            if (prefs.tapToFocus) {
                // tap to focus: the shutter only takes the picture (with the held focus, if any)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.isPressed = true
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                        if (s.countingDown) s.cancelCountdown() else if (inside) s.shootNow()
                    }
                    MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { if (s.countingDown) s.cancelCountdown() else s.press(); v.isPressed = true }
                MotionEvent.ACTION_UP -> { v.isPressed = false; s.release(e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height) }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; s.release(false) }
            }
            true
        }
        // accessibility services and keyboards click instead of touching
        b.shutter.setOnClickListener { session?.let { if (it.countingDown) it.cancelCountdown() else if (prefs.tapToFocus) it.shootNow() else it.tap() } }
        // tap to focus: a tap on the live view focuses and keeps the focus
        b.picture.setOnClickListener { val s = session; if (s != null && prefs.tapToFocus && b.shutter.isEnabled) s.focusHold() }
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
            if (event.repeatCount == 0 && !prefs.tapToFocus) { if (s.countingDown) s.cancelCountdown() else s.press() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val s = session
        if (s != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (!prefs.tapToFocus) s.release(true) else if (b.shutter.isEnabled) { if (s.countingDown) s.cancelCountdown() else s.shootNow() }
            return true
        }
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

    private fun releaseDecoder() { decoder?.release(); decoder = null; decodedSize = null }

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    companion object {
        const val ZOOM_REPEAT_MS = 600L
        // the space the controls take, in dp (ViewfinderLayout plans with these)
        const val BAR_STACKED = 56
        const val BAR_SIDE = 72
        const val CONTROLS_STACKED = 120
        const val CONTROLS_SIDE = 112
        const val CONTROLS_ZOOM = 176
        const val NOTICE_MS = 4_000L
    }
}
