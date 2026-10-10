package com.alexa.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** The phone-control hands: reads the screen, taps, types, scrolls. Enabled by the user in Android Accessibility settings. */
class AlexaA11y : AccessibilityService() {

    companion object {
        @Volatile var instance: AlexaA11y? = null

        val SENSITIVE = Regex(
            "\\b(pay|send|buy|purchase|order|transfer|delete|remove|uninstall|erase|reset|confirm|submit|post|log ?out|sign ?out|install|call|dial)\\b" +
                "|পাঠা|কিন|মুছ|পেমেন্ট|ডিলিট|কল|ইনস্টল",
            RegexOption.IGNORE_CASE
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Core.init(this)
        instance = this
        Core.status()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Core.status()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun find(node: AccessibilityNodeInfo?, depth: Int, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val r = find(node.getChild(i), depth + 1, pred)
            if (r != null) return r
        }
        return null
    }

    /** Numbered list of what is visible: label, [tap]/[input], centre coordinates. */
    fun screen(): String {
        val root = rootInActiveWindow ?: return "ERROR: স্ক্রিন পড়া যাচ্ছে না (লক স্ক্রিন বা সুরক্ষিত স্ক্রিন?)"
        val sb = StringBuilder("app: ").append(root.packageName).append("\n")
        var n = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || n >= 70 || depth > 40) return
            if (node.isVisibleToUser) {
                val label = (node.text?.toString().orEmpty()).ifBlank { node.contentDescription?.toString().orEmpty() }
                val id = (node.viewIdResourceName ?: "").substringAfterLast('/')
                val edit = node.isEditable
                val click = node.isClickable
                if (label.isNotBlank() || edit || (click && id.isNotBlank())) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    n++
                    sb.append(n).append(". \"").append(label.ifBlank { id }.replace("\n", " ").take(60)).append("\" ")
                    if (edit) sb.append("[input] ") else if (click) sb.append("[tap] ")
                    sb.append("@(").append(r.centerX()).append(",").append(r.centerY()).append(")\n")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return if (n == 0) "app: " + root.packageName + "\n(স্ক্রিনে পড়ার মতো কিছু পাওয়া যায়নি)" else sb.toString()
    }

    fun tapText(query: String): String {
        val root = rootInActiveWindow ?: return "ERROR: স্ক্রিন পড়া যাচ্ছে না"
        val nodes = root.findAccessibilityNodeInfosByText(query)
        if (nodes == null || nodes.isEmpty()) return "ERROR: স্ক্রিনে '" + query + "' পাওয়া যায়নি — screen টুল দিয়ে দেখুন"
        val node = nodes.firstOrNull { it.isVisibleToUser } ?: nodes[0]
        var t: AccessibilityNodeInfo? = node
        var depth = 0
        while (t != null && !t.isClickable && depth < 6) {
            t = t.parent
            depth++
        }
        val target = t ?: node
        val label = (node.text?.toString().orEmpty()).ifBlank { node.contentDescription?.toString().orEmpty() }
        if (SENSITIVE.containsMatchIn(label) && !Confirm.ask("Alexa ট্যাপ করতে চায়: '" + label + "'")) return "User denied this action."
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "tapped '" + label + "'"
        val r = Rect()
        node.getBoundsInScreen(r)
        return tapXY(r.centerX(), r.centerY(), false)
    }

    fun labelAt(x: Int, y: Int): String {
        var best = ""
        var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                val l = (n.text?.toString().orEmpty()).ifBlank { n.contentDescription?.toString().orEmpty() }
                val area = r.width().toLong() * r.height()
                if (l.isNotBlank() && area < bestArea) {
                    best = l
                    bestArea = area
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
        }
        walk(rootInActiveWindow, 0)
        return best
    }

    fun tapXY(x: Int, y: Int, check: Boolean = true): String {
        if (check) {
            val label = labelAt(x, y)
            if (label.isNotBlank() && SENSITIVE.containsMatchIn(label) && !Confirm.ask("Alexa ট্যাপ করতে চায়: '" + label + "'")) return "User denied this action."
        }
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return if (dispatchGesture(g, null, null)) "tapped (" + x + "," + y + ")" else "ERROR: gesture ব্যর্থ"
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): String {
        val p = Path()
        p.moveTo(x1.toFloat(), y1.toFloat())
        p.lineTo(x2.toFloat(), y2.toFloat())
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return if (dispatchGesture(g, null, null)) "swiped" else "ERROR: gesture ব্যর্থ"
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "ERROR: স্ক্রিন পড়া যাচ্ছে না"
        var node: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) node = find(root, 0) { it.isEditable && it.isVisibleToUser }
        if (node == null) return "ERROR: টাইপ করার কোনো ঘর নেই — আগে tap_text দিয়ে ঘরটা চাপুন"
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed" else "ERROR: টাইপ হয়নি"
    }

    /** Presses the keyboard's Enter/Search action on the focused field. */
    fun imeEnter(): String {
        val root = rootInActiveWindow ?: return "ERROR: স্ক্রিন পড়া যাচ্ছে না"
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "ERROR: কোনো ঘর ফোকাসে নেই"
        if (Build.VERSION.SDK_INT >= 30) {
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return "enter"
        }
        return "ERROR: enter কাজ করেনি — স্ক্রিনের Search/Go বাটনে tap_text দিন"
    }

    fun scroll(down: Boolean): String {
        val sc = find(rootInActiveWindow, 0) { it.isScrollable && it.isVisibleToUser }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (sc != null && sc.performAction(action)) return "scrolled"
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val from = (h * (if (down) 0.72 else 0.28)).toInt()
        val to = (h * (if (down) 0.28 else 0.72)).toInt()
        return swipe(w / 2, from, w / 2, to, 350)
    }
}
