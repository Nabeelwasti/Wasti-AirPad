package com.wasti.airpad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import java.io.Closeable
import java.util.ArrayDeque
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
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var timeout: Runnable? = null
    private val finished = AtomicBoolean(false)

    fun discover(onComplete: (List<WifiDiscoveredHost>) -> Unit) {
        closeDiscovery()
        finished.set(false)
        val hosts = LinkedHashMap<String, WifiDiscoveredHost>()
        val pending = ArrayDeque<NsdServiceInfo>()
        val seen = HashSet<String>()
        var resolving = false

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            closeDiscovery()
            val discovered = synchronized(hosts) { hosts.values.toList() }
            handler.post { onComplete(discovered) }
        }

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            if (!finished.get() && !resolving && pending.isNotEmpty()) {
                val next = pending.removeFirst()
                resolving = true
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(next, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolving = false
                            resolveNext()
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress
                            val fingerprint = info.attributes["fingerprint"]
                                ?.toString(Charsets.UTF_8)
                            if (address != null && fingerprint != null &&
                                fingerprint.matches(Regex("[0-9a-fA-F]{64}"))
                            ) {
                                synchronized(hosts) {
                                    val key = address + ":" + info.port
                                    hosts[key] = WifiDiscoveredHost(
                                        name = info.serviceName,
                                        address = address,
                                        port = info.port,
                                        fingerprint = fingerprint
                                    )
                                }
                            }
                            resolving = false
                            resolveNext()
                        }
                    })
                } catch (_: Exception) {
                    resolving = false
                    resolveNext()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains(SERVICE_TYPE_PREFIX)) return
                if (seen.add(serviceInfo.serviceName)) {
                    pending.addLast(serviceInfo)
                    resolveNext()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(hosts) {
                    hosts.entries.removeAll { it.value.name == serviceInfo.serviceName }
                }
                pending.removeAll { it.serviceName == serviceInfo.serviceName }
                seen.remove(serviceInfo.serviceName)
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
            acquireMulticastLockIfRequired()
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            timeout = Runnable { finish() }.also { handler.postDelayed(it, DISCOVERY_WINDOW_MS) }
        } catch (_: Exception) {
            finish()
        }
    }

    private fun acquireMulticastLockIfRequired() {
        val requiresManualLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7
        } else {
            true
        }
        if (requiresManualLock && multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("WastiAirPadNsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun closeDiscovery() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        listener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) { }
        }
        listener = null
        multicastLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) { }
        }
        multicastLock = null
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
