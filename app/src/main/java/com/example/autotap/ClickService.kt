package com.example.autotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class ClickService : AccessibilityService() {
    companion object { var instance: ClickService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun run(path: Path, durationMs: Long, cb: ((Boolean) -> Unit)?) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { cb?.invoke(true) }
            override fun onCancelled(d: GestureDescription?) { cb?.invoke(false) }
        }, null)
        if (!ok) cb?.invoke(false)
    }

    fun tap(x: Float, y: Float, cb: ((Boolean) -> Unit)? = null) {
        run(Path().apply { moveTo(x, y) }, 80, cb)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 400, cb: ((Boolean) -> Unit)? = null) {
        run(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms, cb)
    }
}
