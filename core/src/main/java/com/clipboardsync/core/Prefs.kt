package com.clipboardsync.core

import android.content.Context

/** Настройки приложения. */
object Prefs {
    private const val NAME = "cs_prefs"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", v).apply()
    }

    fun manualTargets(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("manual_targets", emptySet()) ?: emptySet()

    fun addManualTarget(ctx: Context, value: String) {
        val cur = prefs(ctx).getStringSet("manual_targets", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        cur.add(value)
        prefs(ctx).edit().putStringSet("manual_targets", cur).apply()
    }
}
