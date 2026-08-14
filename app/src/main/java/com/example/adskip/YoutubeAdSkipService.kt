package com.example.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This is the file you update when YouTube changes its UI and skip
 * detection stops working. Everything relevant lives in this one file:
 * SKIP_TEXT_PATTERNS below, and the matching logic in findSkipButton().
 */
class YoutubeAdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        private const val YOUTUBE_PKG = "com.google.android.youtube"

        // Fallback text patterns since resource IDs get renamed/obfuscated
        // by YouTube across app updates. Case-insensitive substring match.
        // Add new patterns here if YouTube changes button wording.
        private val SKIP_TEXT_PATTERNS = listOf(
            "skip ad", "skip ads", "skip advertisement"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf(YOUTUBE_PKG)
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100 // ms, keep low for responsiveness
        }
        serviceInfo = info
        Log.d(TAG, "Service connected, watching $YOUTUBE_PKG")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != YOUTUBE_PKG) return

        val root = rootInActiveWindow ?: return
        val skipNode = findSkipButton(root)
        skipNode?.let {
            val clicked = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Skip button clicked=$clicked")
            it.recycle()
        }
        root.recycle()
    }

    /**
     * Recursively search the node tree for a clickable skip-ad control.
     * Matches on text, content-description, or view-id substring "skip".
     */
    private fun findSkipButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val viewId = node.viewIdResourceName?.lowercase()

        val matches = SKIP_TEXT_PATTERNS.any { pattern ->
            text?.contains(pattern) == true || desc?.contains(pattern) == true
        } || viewId?.contains("skip") == true

        if (matches && node.isClickable) {
            return AccessibilityNodeInfo.obtain(node)
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
}
