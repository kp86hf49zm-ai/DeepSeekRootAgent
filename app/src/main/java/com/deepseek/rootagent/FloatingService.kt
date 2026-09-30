package com.deepseek.rootagent

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private var floatView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var statusText: TextView
    private var externallyHidden = false

    companion object {
        var isShowing = false
        var onStopClick: (() -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "hide" -> { externallyHidden = true; hide() }
            "show" -> { externallyHidden = false; show() }
            "update" -> { intent.getStringExtra("text")?.let { updateText(it) } }
            else -> if (floatView == null) create()
        }
        return START_STICKY
    }

    private fun updateText(t: String) {
        if (::statusText.isInitialized) statusText.post { statusText.text = t }
    }

    private fun create() {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        fun dp(v: Float) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6222222.toInt())
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(this).apply { text = "🤖 AI"; setTextColor(0xFF4CAF50.toInt()); textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val btnStop = ImageView(this).apply {
            setImageResource(R.drawable.ic_stop)
            setBackgroundColor(0xCCE53935.toInt())
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { onStopClick?.invoke() }
        }
        topRow.addView(label); topRow.addView(btnStop); container.addView(topRow)

        val divider = View(this).apply { setBackgroundColor(0x33FFFFFF); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) }
        container.addView(divider)

        statusText = TextView(this).apply {
            text = "运行中..."
            setTextColor(0xFFE0E0E0.toInt()); textSize = 11f; maxLines = 6
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setPadding(0, dp(4f), 0, 0) }
        scroll.addView(statusText); container.addView(scroll)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val w = maxOf(dp(220f), screenW / 3)

        params = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(8f); y = dp(80f) }

        try {
            try { params!!.javaClass.getMethod("setSkipCapture", Boolean::class.javaPrimitiveType).invoke(params, true) } catch (_: Exception) {}
            wm.addView(container, params)
            floatView = container; isShowing = true
            LogManager.log("FloatingService: created ${w}px")
        } catch (e: Exception) {
            LogManager.log("FloatingService: addView failed: ${e.message}")
        }
    }

    private fun hide() { floatView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; floatView = null; isShowing = false }
    private fun show() { if (floatView == null) create() }
    override fun onDestroy() { super.onDestroy(); hide() }
}
