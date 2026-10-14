package com.clipboardsync.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Обнаружение устройств в локальной сети через mDNS (NsdManager). */
class NsdDiscovery(
    context: Context,
    private val selfName: String,
    private val onPeerFound: (host: String, port: Int, name: String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val appCtx = context.applicationContext
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val serviceType = "_clipboardsync._tcp."

    private fun str(id: Int, vararg args: Any): String = try {
        appCtx.getString(id, *args)
    } catch (e: Exception) {
        ""
    }

    @Volatile private var registered = false
    @Volatile private var discovering = false

    private val regListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            onLog(str(R.string.log_mdns_registered, info.serviceName))
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            onLog(str(R.string.log_mdns_register_failed, errorCode))
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            if (host.startsWith("fe80")) return // link-local шум
            onPeerFound(host, info.port, info.serviceName)
        }

        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    private val discListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discovering = true
        }

        override fun onServiceFound(found: NsdServiceInfo) {
            if (found.serviceName == selfName) return
            try {
                nsd.resolveService(found, resolveListener)
            } catch (e: Exception) {
                Log.w(TAG, "resolveService failed", e)
            }
        }

        override fun onServiceLost(found: NsdServiceInfo) {
            onLog(str(R.string.log_mdns_lost, found.serviceName))
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start(port: Int) {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = selfName
                serviceType = this@NsdDiscovery.serviceType
                setPort(port)
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        } catch (e: Exception) {
            onLog(str(R.string.log_mdns_error, e.message ?: "?"))
        }
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discListener)
        } catch (e: Exception) {
            onLog(str(R.string.log_mdns_error, e.message ?: "?"))
        }
    }

    fun stop() {
        try {
            if (discovering) nsd.stopServiceDiscovery(discListener)
        } catch (e: Exception) {
        }
        try {
            if (registered) nsd.unregisterService(regListener)
        } catch (e: Exception) {
        }
        registered = false
        discovering = false
    }

    companion object {
        private const val TAG = "ClipSyncNsd"
    }
}
