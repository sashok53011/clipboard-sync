package com.clipboardsync.core

import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

/** Активное соединение с одним устройством (TCP или Bluetooth). */
class Connection(
    val input: InputStream,
    val output: OutputStream,
    val transport: String,
    val address: String,
    /** true — соединение инициировано нами, false — принято от собеседника. */
    val dialedByMe: Boolean,
    private val writer: Writer
) {
    @Volatile var deviceId: String? = null
    @Volatile var name: String = ""
    @Volatile var platform: String = ""
    @Volatile var lastSeen: Long = System.currentTimeMillis()

    private val lock = Any()

    fun send(msg: Msg) {
        synchronized(lock) {
            writer.write(msg.toJson())
            writer.write("\n")
            writer.flush()
        }
    }

    fun close() {
        runCatching { writer.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
