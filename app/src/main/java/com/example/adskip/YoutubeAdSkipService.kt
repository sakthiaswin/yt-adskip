package com.example.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This is the file you update when YouTube changes its UI and skip
 * detection stops working. Everything relevant lives in this one file:
 * SKIP_TEXT_PATTERNS below, and the matching logic in findSkipButton().
 *
 * Detection strategy (in priority order, all run per event):
 *   1. event.source directly, and event.source's OWN WINDOW root — tied
 *      to the exact window that fired the event, so it can't desync from
 *      "active window" tracking. This is the primary path and is what
 *      makes detection near-instant.
 *   2. rootInActiveWindow + other interactive windows() — kept as a
 *      fallback for content changes that don't carry a usable source.
 *   Field logs on a Samsung S24 Ultra showed rootInActiveWindow/windows
 *   returning nothing for an entire ad's duration despite
 *   TYPE_WINDOW_CONTENT_CHANGED events firing continuously — i.e. path 2
 *   alone silently misses ads on some OEM builds. Path 1 is the actual
 *   fix for that; path 2 stays as a safety net.
 *
 * Debug visualization: when running a debug build with overlay permission
 * granted and the in-app "Show Debug Overlay" toggle on (MainActivity), a
 * semi-transparent, non-interactive overlay appears whenever YouTube is
 * foregrounded, showing a pinned status line plus a deduped event trail.
 * Release builds compile the overlay out entirely (see DebugOverlayFactory
 * in app/src/release/java/...).
 */
class YoutubeAdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        private const val YOUTUBE_PKG = "com.google.android.youtube"
        private const val POLL_INTERVAL_MS = 300L

        // Fallback text patterns since resource IDs get renamed/obfuscated
        // by YouTube across app updates. Case-insensitive substring match.
        // Add new patterns here if YouTube changes button wording.
        private val SKIP_TEXT_PATTERNS = listOf(
            "skip ad", "skip ads", "skip advertisement", "skip"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val debugOverlay: DebugOverlay by lazy { DebugOverlayFactory.create(applicationContext) }

    private val pollRunnable = object : Runnable {
        override fun run() {
            tryClickSkipFallback()
            if (polling) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf(YOUTUBE_PKG)
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50 // ms, keep low for responsiveness
        }
        serviceInfo = info
        Log.d(TAG, "Service connected, watching $YOUTUBE_PKG")
    }

    // ---------------------------------------------------------------
    // Debug overlay gating: overlay is only ever touched if this is a
    // debug build AND the in-app toggle is on. The click-detection logic
    // below is completely unaffected by this either way.
    // ---------------------------------------------------------------
    private fun overlayAllowed(): Boolean =
        DebugOverlayFactory.isDebugBuild && DebugPrefs.isOverlayEnabled(applicationContext)

    private fun dbgShow() {
        if (overlayAllowed()) debugOverlay.show() else debugOverlay.hide()
    }

    private fun dbgStatus(line: String) {
        if (overlayAllowed()) debugOverlay.status(line)
    }

    private fun dbgLog(message: String) {
        if (overlayAllowed()) debugOverlay.log(message)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != YOUTUBE_PKG) {
            stopPolling()
            debugOverlay.hide()
            return
        }
        dbgShow()
        dbgLog("EVT ${AccessibilityEvent.eventTypeToString(event.eventType)}")
        startPolling()

        // Fast path: handle directly from the event's own source/window.
        val source = event.source
        if (source != null) {
            val handled = handleFromNode(source, originLabel = "event.source")
            if (!handled) {
                val windowRoot = source.window?.root
                if (windowRoot != null) {
                    handleFromNode(windowRoot, originLabel = "event.source.window")
                    windowRoot.recycle()
                } else {
                    dbgStatus("root: event.source has no window")
                }
            }
            source.recycle()
        } else {
            dbgStatus("root: event carried no source, using fallback scan")
            tryClickSkipFallback()
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    /**
     * Fallback path used by the poll loop and when an event carries no
     * usable source. Scans rootInActiveWindow plus any other interactive
     * windows reported by the OS.
     */
    private fun tryClickSkipFallback() {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { w -> if (!w.isActive) w.root?.let { roots.add(it) } }
        } catch (e: Exception) {
            dbgLog("windows scan failed: ${e.message}")
        }

        if (roots.isEmpty()) {
            dbgStatus("root: unavailable (rootInActiveWindow + windows both empty)")
            dbgLog("fallback scan: nothing available this poll")
            return
        }

        var handled = false
        for (root in roots) {
            if (handleFromNode(root, originLabel = "fallback scan")) {
                handled = true
            }
            root.recycle()
            if (handled) break
        }
        if (!handled) dbgLog("fallback scan: no match in ${roots.size} window(s)")
    }

    /**
     * Searches the subtree rooted at [node] for a skip button; if found,
     * attempts to click it and reports through the overlay. Returns true
     * if a match was found (regardless of whether the click succeeded),
     * so callers can skip redundant fallback scans.
     */
    private fun handleFromNode(node: AccessibilityNodeInfo, originLabel: String): Boolean {
        val skipNode = findSkipButton(node) ?: return false

        val text = skipNode.text?.toString().orEmpty()
        val desc = skipNode.contentDescription?.toString().orEmpty()
        dbgStatus("MATCH via $originLabel: \"$text$desc\" clickable=${skipNode.isClickable} enabled=${skipNode.isEnabled}")
        dbgLog("MATCH ($originLabel) text=\"$text\" desc=\"$desc\"")

        if (!skipNode.isEnabled) {
            dbgLog("node disabled (likely still in countdown), skipping this attempt")
            skipNode.recycle()
            return true
        }

        val clicked = clickNode(skipNode)
        dbgStatus("CLICK via $originLabel: ${if (clicked) "OK" else "FAILED"}")
        skipNode.recycle()
        return true
    }

    /**
     * Try the normal accessibility click first. If the node reports
     * itself clickable/enabled but performAction() returns false (or
     * silently does nothing — some Compose-rendered buttons don't
     * respond correctly to ACTION_CLICK), fall back to dispatching a
     * real synthetic tap gesture at the node's on-screen center.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val viaAction = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        dbgLog("performAction(ACTION_CLICK) result=$viaAction")
        if (viaAction) return true

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            dbgLog("gesture fallback skipped: empty bounds")
            return false
        }
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        dbgLog("gesture fallback at (${bounds.centerX()},${bounds.centerY()}) dispatched=$dispatched")
        return dispatched
    }

    /**
     * Recursively search the node tree for a clickable skip-ad control.
     * Matches on text, content-description, or view-id substring "skip".
     * Also checks the node's parent for clickability, since some skip
     * buttons are icon-only children inside a clickable container.
     */
    private fun findSkipButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val viewId = node.viewIdResourceName?.lowercase()

        val textMatches = SKIP_TEXT_PATTERNS.any { pattern ->
            text?.contains(pattern) == true || desc?.contains(pattern) == true
        } || viewId?.contains("skip") == true

        if (textMatches) {
            if (node.isClickable) {
                return AccessibilityNodeInfo.obtain(node)
            }
            // Walk up to find the nearest clickable ancestor (icon-only buttons
            // are often a non-clickable text/icon inside a clickable parent).
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) {
                if (parent.isClickable) {
                    return AccessibilityNodeInfo.obtain(parent)
                }
                val next = parent.parent
                parent.recycle()
                parent = next
                depth++
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSkipButton(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        debugOverlay.hide()
    }
}
