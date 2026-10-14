package com.clipboardsync.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Сервис доступности: системное разрешение, которое позволяет приложению
 * читать буфер обмена в фоне (ограничение Android 10+). Сам по себе он
 * ничего не делает — только сообщает движку, что разрешение активно.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        SyncEngine.onAccessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Не используется.
    }

    override fun onInterrupt() {
        // Не используется.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        SyncEngine.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }
}
