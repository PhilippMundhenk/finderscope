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

    var savePictures: Boolean
        get() = sp.getBoolean("savePictures", true)
        set(v) = sp.edit().putBoolean("savePictures", v).apply()

    var gpsTag: Boolean
        get() = sp.getBoolean("gpsTag", false)
        set(v) = sp.edit().putBoolean("gpsTag", v).apply()
}
