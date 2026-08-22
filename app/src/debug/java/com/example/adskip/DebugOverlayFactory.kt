package com.example.adskip

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
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
 * foreground app (and only while DebugPrefs.isOverlayEnabled is true).
 * Two parts:
 *   - a single pinned status line (current root/match/click state)
 *   - a scrolling log trail below it, with consecutive duplicate lines
 *     collapsed so a real match doesn't get buried in poll spam
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
        private const val MAX_LINES = 30
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(context.mainLooper)
    private val logLines = ArrayDeque<String>()
    private var lastRawMessage: String? = null
    private var repeatCount = 0

    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var logView: TextView? = null

    override fun show() {
        mainHandler.post {
            if (rootView != null) return@post
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

            val status = TextView(context).apply {
                setBackgroundColor(0xCC1B5E20.toInt()) // dark green, more opaque than log
                setTextColor(Color.WHITE)
                textSize = 11.5f
                setPadding(20, 10, 20, 10)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            }
            val log = TextView(context).apply {
                setBackgroundColor(0x99000000.toInt()) // ~60% opaque black
                setTextColor(Color.GREEN)
                textSize = 10f
                setPadding(20, 8, 20, 12)
                typeface = Typeface.MONOSPACE
                maxLines = MAX_LINES
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(log)
            }

            try {
                windowManager.addView(container, params)
                rootView = container
                statusView = status
                logView = log
            } catch (e: Exception) {
                Log.e(TAG, "addView failed: ${e.message}")
            }
        }
    }

    override fun hide() {
        mainHandler.post {
            rootView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.w(TAG, "removeView failed: ${e.message}")
                }
            }
            rootView = null
            statusView = null
            logView = null
        }
    }

    override fun status(line: String) {
        mainHandler.post { statusView?.text = line }
    }

    override fun log(message: String) {
        Log.d(TAG, message)
        synchronized(logLines) {
            if (message == lastRawMessage) {
                repeatCount++
                if (logLines.isNotEmpty()) logLines.removeLast()
            } else {
                lastRawMessage = message
                repeatCount = 1
                if (logLines.size >= MAX_LINES) logLines.removeFirst()
            }
            logLines.addLast(formatLine(message, repeatCount))
        }
        mainHandler.post {
            logView?.text = synchronized(logLines) { logLines.joinToString("\n") }
        }
    }

    private fun formatLine(msg: String, count: Int): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        return if (count > 1) "$ts  $msg  (x$count)" else "$ts  $msg"
    }
}
