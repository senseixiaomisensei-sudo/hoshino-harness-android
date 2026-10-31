package app.hoshino.harness.bench

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * WP-0 delayed-render fixture (test APK only). Home shows three buttons; a tap swaps to a "loaded" page after
 * 0 / 600 / 1200 ms, and nothing on screen changes during that delay. That reproduces the slow page that the
 * current after-action wait (quiet for 400 ms) can misjudge as `no_effect` (mika-phone-gap A-1).
 */
class DelayedRenderActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 160, 48, 48) }

    private fun showHome() {
        handler.removeCallbacksAndMessages(null)
        val col = column()
        col.addView(TextView(this).apply { text = TITLE; textSize = 24f })
        for (d in DELAYS_MS) col.addView(Button(this).apply {
            text = label(d)
            setOnClickListener { handler.postDelayed({ showLoaded(d) }, d) }
        })
        setContentView(col)
    }

    private fun showLoaded(delayMs: Long) {
        val col = column()
        col.addView(TextView(this).apply { text = "$LOADED $delayMs"; textSize = 24f })
        for (i in 1..8) col.addView(TextView(this).apply { text = "条目 $i"; textSize = 18f })
        col.addView(Button(this).apply { text = HOME_BUTTON; setOnClickListener { showHome() } })
        setContentView(col)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val TITLE = "延迟渲染夹具"
        const val LOADED = "已加载"
        const val HOME_BUTTON = "返回首页"
        val DELAYS_MS = listOf(0L, 600L, 1200L)
        fun label(delayMs: Long) = if (delayMs == 0L) "立即" else "延迟${delayMs}毫秒"
    }
}
