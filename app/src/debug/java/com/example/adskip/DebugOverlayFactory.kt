package com.example.adskip

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DebugOverlayFactory {
    const val isDebugBuild = true

    fun create(context: Context): DebugOverlay = RealDebugOverlay(context.applicationContext)
}

/**
 * Semi-transparent, NON-TOUCHABLE overlay shown only while YouTube is the
 * foreground app. It exists purely to visualize what the accessibility
 * service is doing in real time (received events, node matches, click
 * results) so "service is running but nothing happens" can be diagnosed
 * on-device without adb.
 *
 * FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE is intentional and required:
 * an overlay that COULD intercept touches, combined with an accessibility
 * service that injects clicks, is the exact tapjacking signature Play
 * Protect scans for. This overlay can only draw text; it cannot receive
 * or block input, so it doesn't carry that risk. Do not remove those flags.
 */
private class RealDebugOverlay(private val context: Context) : DebugOverlay {

    companion object {
        private const val TAG = "DebugOverlay"
        private const val MAX_LINES = 40
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = android.os.Handler(context.mainLooper)
    private val logLines = ArrayDeque<String>()
    private var overlayView: TextView? = null

    override fun show() {
        mainHandler.post {
            if (overlayView != null) return@post
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, skipping overlay")
                return@post
            }
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = 80
            }
            val view = TextView(context).apply {
                setBackgroundColor(0x99000000.toInt()) // ~60% opaque black
                setTextColor(Color.GREEN)
                textSize = 10.5f
                setPadding(20, 12, 20, 12)
                typeface = Typeface.MONOSPACE
                maxLines = MAX_LINES
            }
            try {
                windowManager.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                Log.e(TAG, "addView failed: ${e.message}")
            }
        }
    }

    override fun hide() {
        mainHandler.post {
            overlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.w(TAG, "removeView failed: ${e.message}")
                }
            }
            overlayView = null
        }
    }

    override fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts  $message"
        Log.d(TAG, message)
        synchronized(logLines) {
            if (logLines.size >= MAX_LINES) logLines.removeFirst()
            logLines.addLast(line)
        }
        mainHandler.post {
            overlayView?.text = synchronized(logLines) { logLines.joinToString("\n") }
        }
    }
}
