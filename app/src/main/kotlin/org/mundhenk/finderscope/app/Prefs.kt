package org.mundhenk.finderscope.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("finderscope", Context.MODE_PRIVATE)

    /**
     * The id this phone pairs with ("allow this device?" on the camera). A locally administered
     * pseudo-MAC, fixed per installation: Android hides the real MAC, and a shared fake one would
     * let every phone pass as every other.
     */
    val clientId: String
        get() {
            sp.getString("clientId", null)?.let { return it }
            val r = java.security.SecureRandom()
            val fresh = "02:" + (1..5).joinToString(":") { "%02x".format(r.nextInt(256)) }
            sp.edit().putString("clientId", fresh).apply()
            return fresh
        }

    /** Camera address typed by the user ("" = the Wi-Fi gateway). */
    var address: String
        get() = sp.getString("address", "") ?: ""
        set(v) = sp.edit().putString("address", v.trim()).apply()

    /** The camera network joined last (name and hardware address): asked for directly next time, which Android approves without a dialog. */
    var cameraSsid: String
        get() = sp.getString("cameraSsid", "") ?: ""
        set(v) = sp.edit().putString("cameraSsid", v).apply()

    var cameraBssid: String
        get() = sp.getString("cameraBssid", "") ?: ""
        set(v) = sp.edit().putString("cameraBssid", v).apply()

    var savePictures: Boolean
        get() = sp.getBoolean("savePictures", true)
        set(v) = sp.edit().putBoolean("savePictures", v).apply()

    var gpsTag: Boolean
        get() = sp.getBoolean("gpsTag", false)
        set(v) = sp.edit().putBoolean("gpsTag", v).apply()

    /** Minutes the connection is kept while finderscope is not on screen (0 = disconnect right away). */
    var awayMinutes: Int
        get() = sp.getInt("awayMinutes", 5)
        set(v) = sp.edit().putInt("awayMinutes", v.coerceIn(0, 30)).apply()
}
