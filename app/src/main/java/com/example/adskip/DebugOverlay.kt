package com.example.adskip

/**
 * Debug visualization surface. The real implementation (a semi-transparent,
 * non-touchable overlay shown only while YouTube is foregrounded) lives in
 * app/src/debug/java/.../DebugOverlayFactory.kt.
 *
 * app/src/release/java/.../DebugOverlayFactory.kt provides a no-op version.
 * This means the overlay code, the SYSTEM_ALERT_WINDOW permission
 * (declared only in app/src/debug/AndroidManifest.xml), and any overlay UI
 * are completely absent from release builds — not just disabled at
 * runtime. Verify with:
 *   aapt dump permissions app-release.apk   # no SYSTEM_ALERT_WINDOW listed
 */
interface DebugOverlay {
    fun show()
    fun hide()
    fun log(message: String)
}
