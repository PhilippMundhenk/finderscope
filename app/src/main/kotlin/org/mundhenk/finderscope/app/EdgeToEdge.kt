package org.mundhenk.finderscope.app

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** The black brand bar runs up under the (transparent) status bar; the content keeps clear of the system bars. */
object EdgeToEdge {
    fun apply(activity: Activity, root: View, topBar: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val barTop = topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            topBar.setPadding(topBar.paddingLeft, barTop + bars.top, topBar.paddingRight, topBar.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}
