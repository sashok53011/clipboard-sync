package com.clipboardsync.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipboardsync.core.Prefs

/** Автозапуск после перезагрузки или обновления приложения. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON"
            )
        ) {
            if (Prefs.enabled(ctx)) {
                PhoneSyncService.start(ctx)
            }
        }
    }
}
