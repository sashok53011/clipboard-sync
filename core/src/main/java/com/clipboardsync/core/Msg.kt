package com.clipboardsync.core

import org.json.JSONObject

/** Протокол обмена: JSON, по одному сообщению на строку. */
sealed class Msg {

    data class Hello(val deviceId: String, val name: String, val platform: String) : Msg()
    data class Clipboard(val text: String, val sender: String, val ts: Long) : Msg()
    data object Ping : Msg()
    data object Bye : Msg()

    fun toJson(): String = when (this) {
        is Hello -> JSONObject()
            .put("type", "hello")
            .put("deviceId", deviceId)
            .put("name", name)
            .put("platform", platform)
            .toString()

        is Clipboard -> JSONObject()
            .put("type", "clipboard")
            .put("text", text)
            .put("sender", sender)
            .put("ts", ts)
            .toString()

        Ping -> JSONObject().put("type", "ping").toString()
        Bye -> JSONObject().put("type", "bye").toString()
    }

    companion object {
        fun fromJson(line: String): Msg? = try {
            val o = JSONObject(line)
            when (o.optString("type")) {
                "hello" -> Hello(
                    o.getString("deviceId"),
                    o.optString("name", "device"),
                    o.optString("platform", "phone")
                )

                "clipboard" -> Clipboard(
                    o.optString("text", ""),
                    o.optString("sender", ""),
                    o.optLong("ts", 0L)
                )

                "ping" -> Ping
                "bye" -> Bye
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
