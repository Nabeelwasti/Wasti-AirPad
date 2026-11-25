package com.wasti.airpad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal data class WifiDiscoveredHost(
    val name: String,
    val address: String,
    val port: Int,
    val fingerprint: String
)

/** Short-lived DNS-SD discovery for the AirPad desktop companion. */
internal class WifiHostDiscovery(context: Context) : Closeable {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var timeout: Runnable? = null
    private val finished = AtomicBoolean(false)

    fun discover(onComplete: (List<WifiDiscoveredHost>) -> Unit) {
        closeDiscovery()
        finished.set(false)
        val hosts = LinkedHashMap<String, WifiDiscoveredHost>()

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            closeDiscovery()
            val discovered = synchronized(hosts) { hosts.values.toList() }
            handler.post { onComplete(discovered) }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains(SERVICE_TYPE_PREFIX)) return
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress ?: return
                            val fingerprint = info.getAttribute("fingerprint")
                                ?.toString(Charsets.UTF_8) ?: return
                            if (!fingerprint.matches(Regex("[0-9a-fA-F]{64}"))) return
                            val key = address + ":" + info.port
                            hosts[key] = WifiDiscoveredHost(
                                name = info.serviceName,
                                address = address,
                                port = info.port,
                                fingerprint = fingerprint
                            )
                        }
                    })
                } catch (_: Exception) {
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                hosts.entries.removeAll { it.value.name == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finish()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
            }
        }

        listener = discoveryListener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            timeout = Runnable { finish() }.also { handler.postDelayed(it, DISCOVERY_WINDOW_MS) }
        } catch (_: Exception) {
            finish()
        }
    }

    private fun closeDiscovery() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        listener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) { }
        }
        listener = null
    }

    override fun close() {
        finished.set(true)
        closeDiscovery()
    }

    companion object {
        private const val SERVICE_TYPE = "_wastiairpad._tcp."
        private const val SERVICE_TYPE_PREFIX = "_wastiairpad._tcp"
        private const val DISCOVERY_WINDOW_MS = 6000L
    }
}
