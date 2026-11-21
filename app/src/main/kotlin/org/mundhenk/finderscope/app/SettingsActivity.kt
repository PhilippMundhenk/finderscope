package org.mundhenk.finderscope.app

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.mundhenk.finderscope.app.databinding.ActivitySettingsBinding

/** Two choices: copy pictures to the phone, tag them with the phone's location. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted.values.any { it }
        prefs.gpsTag = ok
        b.gpsTag.isChecked = ok
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.savePictures.isChecked = prefs.savePictures
        b.savePictures.setOnCheckedChangeListener { _, on -> prefs.savePictures = on }
        b.gpsTag.isChecked = prefs.gpsTag
        b.gpsTag.setOnCheckedChangeListener { _, on ->
            if (on && !prefs.gpsTag) askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            else prefs.gpsTag = on
        }
        b.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)
    }
}
