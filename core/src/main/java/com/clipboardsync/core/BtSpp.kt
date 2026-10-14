package com.clipboardsync.core

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth SPP (RFCOMM): сервер принимает соединения от спаренных устройств,
 * клиент подключается ко всем bonded-устройствам. Работает между устройствами
 * из разных Wi-Fi сетей.
 */
class BtSpp(
    context: Context,
    private val onSocket: (input: InputStream, output: OutputStream, address: String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val appCtx = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun str(id: Int, vararg args: Any): String = try {
        appCtx.getString(id, *args)
    } catch (e: Exception) {
        ""
    }

    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    fun hasBluetooth(): Boolean = adapter != null

    fun hasPermission(): Boolean {
        if (adapter == null) return false
        return Build.VERSION.SDK_INT < 31 ||
            appCtx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun startServer() {
        val ad = adapter ?: return
        if (!hasPermission()) return
        running = true
        val t = Thread({
            while (running) {
                try {
                    val ss = ad.listenUsingRfcommWithServiceRecord(SERVER_NAME, SPP_UUID)
                    server = ss
                    onLog(str(R.string.log_bt_server_started))
                    while (running) {
                        val s = ss.accept()
                        val addr = s.remoteDevice?.address ?: "bt"
                        onSocket(s.inputStream, s.outputStream, addr)
                    }
                } catch (e: Exception) {
                    if (running) {
                        onLog(str(R.string.log_bt_error, e.message ?: "?"))
                        try {
                            Thread.sleep(5000)
                        } catch (ie: InterruptedException) {
                            break
                        }
                    }
                } finally {
                    runCatching { server?.close() }
                    server = null
                }
            }
        }, "ClipSync-BtServer")
        t.isDaemon = true
        t.start()
        threads.add(t)
    }

    /** Адреса спаренных устройств. */
    fun bondedAddresses(): List<String> {
        val ad = adapter ?: return emptyList()
        if (!hasPermission()) return emptyList()
        return try {
            ad.bondedDevices?.map { it.address } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Подключиться к одному спаренному устройству (неблокирующе). */
    fun connectTo(address: String) {
        val ad = adapter ?: return
        if (!hasPermission()) return
        val t = Thread({
            try {
                runCatching { ad.cancelDiscovery() }
                val device = ad.getRemoteDevice(address)
                val s: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                onLog(str(R.string.log_bt_connected_to, address))
                onSocket(s.inputStream, s.outputStream, address)
            } catch (e: Exception) {
                Log.d(TAG, "connectTo $address failed: ${e.message}")
            }
        }, "ClipSync-Bt-$address")
        t.isDaemon = true
        t.start()
        threads.add(t)
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    companion object {
        private const val TAG = "ClipSyncBt"
        private const val SERVER_NAME = "ClipboardSync"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
