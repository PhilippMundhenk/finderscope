package org.mundhenk.finderscope.app

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import org.mundhenk.finderscope.app.databinding.ActivityLogBinding

/** Everything the camera answered: the first place to look when a camera behaves differently. */
class LogActivity : AppCompatActivity() {
    private lateinit var b: ActivityLogBinding
    private val listener: (String) -> Unit = { line -> b.log.append(line + "\n"); b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_log, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_log -> { LogBus.clear(); b.log.text = ""; true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        b.log.text = LogBus.snapshot().joinToString("\n", postfix = "\n")
        LogBus.addListener(listener)
    }

    override fun onPause() { LogBus.removeListener(listener); super.onPause() }
}
