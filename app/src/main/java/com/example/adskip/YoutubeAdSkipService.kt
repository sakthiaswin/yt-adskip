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
 * Debug visualization: when running a debug build with overlay permission
 * granted (MainActivity -> "Enable Debug Overlay"), a semi-transparent,
 * non-interactive overlay appears whenever YouTube is foregrounded,
 * showing live events, node matches, and click results. Release builds
 * compile this out entirely (see DebugOverlayFactory in
 * app/src/release/java/...).
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
            tryClickSkip()
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
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50 // ms, keep low for responsiveness
        }
        serviceInfo = info
        Log.d(TAG, "Service connected, watching $YOUTUBE_PKG")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != YOUTUBE_PKG) {
            stopPolling()
            debugOverlay.hide()
            return
        }
        debugOverlay.show()
        debugOverlay.log("EVT ${AccessibilityEvent.eventTypeToString(event.eventType)}")
        startPolling()
        tryClickSkip()
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
     * Scans rootInActiveWindow first, then any OTHER interactive windows
     * reported by the OS (requires FLAG_RETRIEVE_INTERACTIVE_WINDOWS,
     * already set in onServiceConnected). This matters because some ad
     * surfaces / overlays render in a secondary window rather than the
     * "active" one — rootInActiveWindow alone misses those, which is a
     * likely cause of "service running but nothing happens".
     */
    private fun collectRootsToScan(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { w ->
                if (!w.isActive) {
                    w.root?.let { roots.add(it) }
                }
            }
        } catch (e: Exception) {
            debugOverlay.log("windows scan failed: ${e.message}")
        }
        return roots
    }

    private fun tryClickSkip() {
        val roots = collectRootsToScan()
        if (roots.isEmpty()) {
            debugOverlay.log("no windows/root available this poll")
            return
        }

        var handled = false
        for (root in roots) {
            val skipNode = findSkipButton(root)
            if (skipNode != null) {
                debugOverlay.log(
                    "MATCH text=\"${skipNode.text}\" desc=\"${skipNode.contentDescription}\" " +
                        "clickable=${skipNode.isClickable} enabled=${skipNode.isEnabled}"
                )
                if (skipNode.isEnabled) {
                    handled = clickNode(skipNode)
                } else {
                    debugOverlay.log("node disabled (likely still in countdown), skipping this poll")
                }
                skipNode.recycle()
            }
            root.recycle()
            if (handled) break
        }
        if (!handled && roots.isNotEmpty()) {
            debugOverlay.log("no matching skip node this poll (scanned ${roots.size} window(s))")
        }
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
        debugOverlay.log("performAction(ACTION_CLICK) result=$viaAction")
        if (viaAction) return true

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            debugOverlay.log("gesture fallback skipped: empty bounds")
            return false
        }
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        debugOverlay.log("gesture fallback at (${bounds.centerX()},${bounds.centerY()}) dispatched=$dispatched")
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
