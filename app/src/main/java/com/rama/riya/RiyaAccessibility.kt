package com.rama.riya

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 1) Presses WhatsApp's Send button, only for ~20 seconds after Riya opens a prefilled chat.
 * 2) Works offline: home / back / recents / notifications / screenshot, and "close all apps".
 */
class RiyaAccessibility : AccessibilityService() {

    companion object {
        @Volatile var armedUntil = 0L
        @Volatile var instance: RiyaAccessibility? = null
        fun arm() { armedUntil = System.currentTimeMillis() + 20000 }
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    override fun onDestroy() { instance = null; worker.shutdownNow(); super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val root = rootInActiveWindow ?: return
        val ids = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && click(nodes[0])) { armedUntil = 0; return }
        }
        val byText = root.findAccessibilityNodeInfosByText("Send")
        if (byText.isNotEmpty() && click(byText[0])) armedUntil = 0
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return false
    }

    // ---------- offline phone controls ----------

    /** Opens the recent-apps screen and presses "Clear all". Blocks, so call it from a background thread. */
    fun clearRecents(): Boolean {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Thread.sleep(1500)
        var cleared = false
        for (attempt in 0 until 5) {
            if (pressClearAll()) { cleared = true; break }
            swipeRight()          // scroll the recents carousel so the Clear all button shows up
            Thread.sleep(700)
        }
        Thread.sleep(600)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return cleared
    }

    private fun pressClearAll(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try { for (w in windows) { w.root?.let { roots.add(it) } } } catch (e: Exception) { }
        for (root in roots) {
            for (label in listOf("Clear all", "Close all")) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                for (n in nodes) if (click(n)) return true
            }
        }
        return false
    }

    private fun swipeRight() {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.45f
        val p = Path()
        p.moveTo(dm.widthPixels * 0.2f, y)
        p.lineTo(dm.widthPixels * 0.85f, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 250))
            .build()
        val latch = CountDownLatch(1)
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (ok) latch.await(1500, TimeUnit.MILLISECONDS)
    }

    override fun onInterrupt() {}
}
