package com.deepseek.rootagent

import android.content.Context

object Prefs {
    private const val NAME = "dsa_prefs"
    const val KEY_API_KEY = "api_key"
    const val KEY_BASE_URL = "base_url"
    const val KEY_MODEL = "model"
    const val KEY_TIMEOUT = "timeout"
    const val KEY_MAX_STEPS = "max_steps"
    const val KEY_CHECK_ROOT = "check_root"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(ctx: Context, k: String, def: String): String = sp(ctx).getString(k, def) ?: def
    fun set(ctx: Context, k: String, v: String) = sp(ctx).edit().putString(k, v).apply()
    fun getInt(ctx: Context, k: String, def: Int): Int = sp(ctx).getInt(k, def)
    fun setInt(ctx: Context, k: String, v: Int) = sp(ctx).edit().putInt(k, v).apply()
    fun getBool(ctx: Context, k: String, def: Boolean): Boolean = sp(ctx).getBoolean(k, def)
    fun setBool(ctx: Context, k: String, v: Boolean) = sp(ctx).edit().putBoolean(k, v).apply()
}
