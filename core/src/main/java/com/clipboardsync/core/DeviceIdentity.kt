package com.clipboardsync.core

import android.content.Context
import android.os.Build
import java.util.UUID

/** Постоянная идентичность устройства: UUID, имя, платформа. */
class DeviceIdentity(context: Context, platform: String) {

    val deviceId: String
    val name: String
    val platform: String = platform

    init {
        val prefs = context.getSharedPreferences("cs_identity", Context.MODE_PRIVATE)
        deviceId = prefs.getString("id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        name = prefs.getString("name", null)
            ?: Build.MODEL.also { prefs.edit().putString("name", it).apply() }
    }
}
