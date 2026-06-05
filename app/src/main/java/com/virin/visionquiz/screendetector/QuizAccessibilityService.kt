package com.virin.visionquiz.screendetector

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class QuizAccessibilityService : AccessibilityService() {

    interface Callback {
        fun onAccessibilityContentChanged()
    }

    data class TextNode(
        val text: String,
        val rect: Rect
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        callback?.onAccessibilityContentChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> callback?.onAccessibilityContentChanged()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun collectVisibleTextNodes(excludedPackageName: String, screenBounds: Rect): List<TextNode> {
        val nodes = mutableListOf<TextNode>()
        val interactiveWindows = windows.orEmpty()
            .sortedByDescending { it.layer }
        if (interactiveWindows.isNotEmpty()) {
            for (window in interactiveWindows) {
                collectWindowTextNodes(window, excludedPackageName, screenBounds, nodes)
            }
        } else {
            val root = rootInActiveWindow ?: return emptyList()
            try {
                traverseNode(root, excludedPackageName, screenBounds, nodes)
            } finally {
                root.recycle()
            }
        }
        return nodes
    }

    private fun collectWindowTextNodes(
        window: AccessibilityWindowInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outNodes: MutableList<TextNode>
    ) {
        val root = window.root ?: return
        try {
            traverseNode(root, excludedPackageName, screenBounds, outNodes)
        } finally {
            root.recycle()
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outNodes: MutableList<TextNode>
    ) {
        addNodeTextIfNeeded(node, excludedPackageName, screenBounds, outNodes)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                traverseNode(child, excludedPackageName, screenBounds, outNodes)
            } finally {
                child.recycle()
            }
        }
    }

    private fun addNodeTextIfNeeded(
        node: AccessibilityNodeInfo,
        excludedPackageName: String,
        screenBounds: Rect,
        outNodes: MutableList<TextNode>
    ) {
        if (!node.isVisibleToUser) {
            return
        }
        if (node.packageName?.toString() == excludedPackageName) {
            return
        }

        val text = node.text?.toString()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.intersect(screenBounds)) {
            return
        }
        if (rect.width() <= MIN_TEXT_RECT_SIZE || rect.height() <= MIN_TEXT_RECT_SIZE) {
            return
        }
        outNodes.add(TextNode(text, Rect(rect)))
    }

    companion object {
        @Volatile
        var instance: QuizAccessibilityService? = null
            private set

        @Volatile
        var callback: Callback? = null

        private const val MIN_TEXT_RECT_SIZE = 3
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
