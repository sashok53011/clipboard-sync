package com.clipboardsync.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Доступ к буферу обмена. Чтение в фоне работает, только если у приложения
 *  включён сервис доступности (ограничение Android 10+). */
object ClipboardStore {

    fun readText(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(ctx)?.toString()
        } else {
            null
        }
    } catch (e: SecurityException) {
        null
    }

    fun writeText(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("ClipboardSync", text))
        } catch (e: Exception) {
            // Буфер может быть недоступен в фоне на части прошивок — не падаем.
        }
    }

    fun canRead(ctx: Context): Boolean = try {
        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip != null
        true
    } catch (e: SecurityException) {
        false
    }
}
