package com.deepseek.rootagent

import android.app.Activity
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

class ClipActivity : Activity() {
    companion object {
        var pendingText: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = pendingText ?: intent.getStringExtra("text") ?: ""
        Log.e("ClipActivity", "onCreate text len=${text.length}")
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("agent", text))
            Log.e("ClipActivity", "clipboard set ok")
            LogManager.log("ClipActivity: clipboard set ok, len=${text.length}")
        } catch (e: Exception) {
            Log.e("ClipActivity", "failed: ${e.message}")
            LogManager.log("ClipActivity: failed: ${e.message}")
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 200)
    }
}