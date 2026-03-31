package com.gamertranslate.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service - captures selected text from ANY app
 * When user selects text in any app (game, browser, WhatsApp),
 * this sends it to the floating service for translation.
 */
class TranslateAccessibilityService : AccessibilityService() {

    private var lastCapturedText = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Capture text selection events
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source ?: return
                val selectedText = getSelectedText(source)
                if (!selectedText.isNullOrBlank() && selectedText != lastCapturedText && selectedText.length > 1) {
                    lastCapturedText = selectedText
                    sendToTranslator(selectedText)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // For games that use custom text rendering
                val text = event.text.joinToString(" ").trim()
                // Only capture if it looks like user-selected (short, meaningful text)
                if (text.isNotBlank() && text.length in 2..500 && text != lastCapturedText) {
                    // Don't auto-send window content — only send on selection
                }
            }
        }
    }

    private fun getSelectedText(node: AccessibilityNodeInfo): String? {
        // Get text selection
        if (node.textSelectionStart != -1 && node.textSelectionEnd != -1 &&
            node.textSelectionStart != node.textSelectionEnd) {
            val text = node.text?.toString() ?: return null
            val start = node.textSelectionStart.coerceIn(0, text.length)
            val end = node.textSelectionEnd.coerceIn(0, text.length)
            if (start < end) return text.substring(start, end)
        }
        return null
    }

    private fun sendToTranslator(text: String) {
        // Send selected text to FloatingService
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_translate", true)) return

        // Broadcast to FloatingService
        val intent = Intent("com.gamertranslate.TRANSLATE_TEXT")
        intent.putExtra("text", text)
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
    }
}
