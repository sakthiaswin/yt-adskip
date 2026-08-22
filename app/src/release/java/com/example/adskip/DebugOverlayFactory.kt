package com.example.adskip

import android.content.Context

object DebugOverlayFactory {
    const val isDebugBuild = false

    fun create(context: Context): DebugOverlay = NoOpDebugOverlay
}

/**
 * Release builds never draw an overlay and never touch
 * SYSTEM_ALERT_WINDOW — that permission isn't even declared in
 * app/src/release (see app/src/debug/AndroidManifest.xml, which is the
 * only place it's requested). This keeps the release APK's permission
 * set and behavior minimal for Play Protect / Play Store review.
 */
private object NoOpDebugOverlay : DebugOverlay {
    override fun show() {}
    override fun hide() {}
    override fun status(line: String) {}
    override fun log(message: String) {}
}
