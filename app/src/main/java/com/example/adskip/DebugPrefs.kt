package com.example.adskip

import android.content.Context

/**
 * Persists whether the debug overlay should be shown. Lets you leave the
 * overlay permission granted but flip visualization off/on without
 * reinstalling or revoking permissions. Only consulted by the debug
 * build's overlay — has no effect on release, and no effect on the actual
 * skip-click logic either way (that keeps running regardless of this
 * setting).
 */
object DebugPrefs {
    private const val PREFS_NAME = "adskip_debug_prefs"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_ENABLED, true)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_ENABLED, enabled)
            .apply()
    }
}
