package dev.skiphelper.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.skiphelper.engine.Element
import dev.skiphelper.engine.Screen

object ScreenReader {
    sealed interface ReadResult {
        data class Available(val screen: Screen) : ReadResult
        data class Pending(val reason: String) : ReadResult
        data class Blocked(val reason: String) : ReadResult
    }

    @Suppress("DEPRECATION")
    fun read(service: AccessibilityService, includeElements: Boolean = true): ReadResult {
        val windows = service.windows
        try {
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            // Do not operate behind a system dialog, IME, overlay, split screen or PiP.
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return ReadResult.Blocked("键盘已打开")
            val window = apps.singleOrNull { it.isActive && it.isFocused }
                ?: return ReadResult.Pending("启动窗口尚未就绪")
            if (windows.any { it.id != window.id && (it.isActive || it.isFocused) }) return ReadResult.Pending("窗口焦点切换中")
            val root = window.root ?: return ReadResult.Pending("界面控件尚未就绪")
            try {
                val packageName = root.packageName?.toString() ?: return ReadResult.Pending("窗口所属应用未就绪")
                // Multiple windows from one app can coexist during splash/activity transitions.
                // A second app window (including PiP) is still unsafe to act through.
                for (other in apps.filter { it.id != window.id }) {
                    val otherRoot = other.root ?: return ReadResult.Pending("附加窗口尚未就绪")
                    try {
                        if (otherRoot.packageName?.toString() != packageName) return ReadResult.Blocked("多个应用窗口同时可见")
                    } finally { otherRoot.recycle() }
                }
                val metrics = android.util.DisplayMetrics()
                (service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.getRealMetrics(metrics)
                val windowBounds = Rect().also(window::getBoundsInScreen)
                if (windowBounds.width() < metrics.widthPixels * .85f || windowBounds.height() < metrics.heightPixels * .8f) return ReadResult.Blocked("非全屏窗口")
                val elements = ArrayList<Element>()
                var visited = 0
                fun visit(node: AccessibilityNodeInfo, path: List<Int>, ancestorsEnabled: Boolean, depth: Int) {
                    if (++visited > 600 || depth > 40) return
                    val enabled = ancestorsEnabled && node.isEnabled
                    if (node.isVisibleToUser) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                            ?: node.contentDescription?.toString().orEmpty()
                        if (text.isNotBlank() || !node.viewIdResourceName.isNullOrBlank()) {
                            elements += Element(text, node.viewIdResourceName.orEmpty(), bounds, enabled, path)
                        }
                    }
                    for (index in 0 until node.childCount) {
                        if (visited >= 600) break
                        val child = node.getChild(index) ?: continue
                        try { visit(child, path + index, enabled, depth + 1) } finally { child.recycle() }
                    }
                }
                if (includeElements) visit(root, emptyList(), true, 0)
                return ReadResult.Available(Screen(packageName, window.id, metrics.widthPixels, metrics.heightPixels, elements))
            } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    fun clickNode(service: AccessibilityService, screen: Screen, element: Element): Boolean {
        var node = service.rootInActiveWindow ?: return false
        try {
            if (node.packageName?.toString() != screen.packageName || node.windowId != screen.windowId) return false
            for (index in element.path ?: return false) {
                val next = node.getChild(index) ?: return false
                node.recycle()
                node = next
            }
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds != element.bounds || !node.isEnabled || !node.isVisibleToUser) return false
            repeat(3) {
                val clickBounds = Rect().also(node::getBoundsInScreen)
                if (!node.isEnabled || !node.isVisibleToUser) return false
                if (node.isClickable && clickBounds.contains(element.bounds) &&
                    clickBounds.width() < screen.width * .5f && clickBounds.height() < screen.height * .18f) {
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val parent = node.parent ?: return false
                node.recycle()
                node = parent
            }
            return false
        } finally { node.recycle() }
    }
}
