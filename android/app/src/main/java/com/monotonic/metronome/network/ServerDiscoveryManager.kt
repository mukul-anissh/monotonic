package com.monotonic.metronome.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.monotonic.metronome.model.DiscoveredServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ServerDiscoveryManager(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SyncMetroNsd").apply { isDaemon = true }
    }

    private val lock = Any()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null

    private val discoveredByServiceName = LinkedHashMap<String, DiscoveredServer>()
    private val activeInfoCallbacks = LinkedHashMap<String, Any>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolvingLegacy = false
    private var isDestroyed = false

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startDiscovery() {
        val manager = nsdManager ?: return
        synchronized(lock) {
            if (isDestroyed || activeDiscoveryListener != null) {
                return
            }
            discoveredByServiceName.clear()
            _servers.value = emptyList()
            acquireMulticastLockLocked()

            val listener = createDiscoveryListener()
            activeDiscoveryListener = listener
            _isScanning.value = true

            try {
                manager.discoverServices(
                    SERVICE_TYPE_QUERY,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (_: Exception) {
                activeDiscoveryListener = null
                _isScanning.value = false
                releaseMulticastLockLocked()
            }
        }
    }

    fun rescan() {
        stopDiscovery()
        startDiscovery()
    }

    fun stopDiscovery() {
        val manager = nsdManager
        val listenerToStop: NsdManager.DiscoveryListener?
        val callbacksToUnregister: List<Any>

        synchronized(lock) {
            listenerToStop = activeDiscoveryListener
            activeDiscoveryListener = null
            resolveQueue.clear()
            isResolvingLegacy = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                callbacksToUnregister = activeInfoCallbacks.values.toList()
                activeInfoCallbacks.clear()
            } else {
                callbacksToUnregister = emptyList()
            }
        }

        try {
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                unregisterApi34Callbacks(manager, callbacksToUnregister)
            }
            if (manager != null && listenerToStop != null) {
                try {
                    manager.stopServiceDiscovery(listenerToStop)
                } catch (_: Exception) {
                    // NsdManager throws if discovery never fully started or already stopped
                }
            }
        } finally {
            synchronized(lock) {
                if (activeDiscoveryListener == null) {
                    releaseMulticastLockLocked()
                    _isScanning.value = false
                }
            }
        }
    }

    fun destroy() {
        synchronized(lock) {
            isDestroyed = true
        }
        stopDiscovery()
        callbackExecutor.shutdownNow()
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                synchronized(lock) {
                    if (activeDiscoveryListener === this) {
                        _isScanning.value = true
                    }
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!matchesServiceType(serviceInfo.serviceType)) return
                synchronized(lock) {
                    if (activeDiscoveryListener !== this) return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        registerApi34ServiceCallbackLocked(serviceInfo)
                    } else {
                        enqueueLegacyResolveLocked(serviceInfo)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val serviceName = serviceInfo.serviceName ?: return
                var callbackToRemove: Any? = null
                synchronized(lock) {
                    discoveredByServiceName.remove(serviceName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        callbackToRemove = activeInfoCallbacks.remove(serviceName)
                    } else {
                        resolveQueue.removeAll { it.serviceName == serviceName }
                    }
                    publishServersLocked()
                }
                val manager = nsdManager
                if (manager != null &&
                    callbackToRemove != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ) {
                    unregisterApi34Callbacks(manager, listOf(callbackToRemove))
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(lock) {
                    if (activeDiscoveryListener === this) {
                        activeDiscoveryListener = null
                        _isScanning.value = false
                        releaseMulticastLockLocked()
                    }
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) {
                    if (activeDiscoveryListener === this) {
                        activeDiscoveryListener = null
                    }
                }
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) {
                    if (activeDiscoveryListener === this) {
                        activeDiscoveryListener = null
                    }
                    _isScanning.value = false
                    releaseMulticastLockLocked()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerApi34ServiceCallbackLocked(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        val serviceName = serviceInfo.serviceName ?: return
        val existing = activeInfoCallbacks.remove(serviceName) as? NsdManager.ServiceInfoCallback
        if (existing != null) {
            try {
                manager.unregisterServiceInfoCallback(existing)
            } catch (_: Exception) {
            }
        }

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                synchronized(lock) {
                    if (activeInfoCallbacks[serviceName] === this) {
                        activeInfoCallbacks.remove(serviceName)
                    }
                }
            }

            override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                val parsed = extractServerFromInfo(
                    serviceName = updatedInfo.serviceName ?: serviceName,
                    addresses = updatedInfo.hostAddresses,
                    port = updatedInfo.port,
                    attributes = updatedInfo.attributes
                ) ?: return
                synchronized(lock) {
                    if (activeDiscoveryListener == null) return
                    discoveredByServiceName[serviceName] = parsed
                    publishServersLocked()
                }
            }

            override fun onServiceLost() {
                synchronized(lock) {
                    discoveredByServiceName.remove(serviceName)
                    if (activeInfoCallbacks[serviceName] === this) {
                        activeInfoCallbacks.remove(serviceName)
                    }
                    publishServersLocked()
                }
            }

            override fun onServiceInfoCallbackUnregistered() {
                synchronized(lock) {
                    if (activeInfoCallbacks[serviceName] === this) {
                        activeInfoCallbacks.remove(serviceName)
                    }
                }
            }
        }

        activeInfoCallbacks[serviceName] = callback
        try {
            manager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
        } catch (_: Exception) {
            if (activeInfoCallbacks[serviceName] === callback) {
                activeInfoCallbacks.remove(serviceName)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterApi34Callbacks(
        manager: NsdManager,
        callbacks: List<Any>
    ) {
        for (rawCallback in callbacks) {
            val callback = rawCallback as? NsdManager.ServiceInfoCallback ?: continue
            try {
                manager.unregisterServiceInfoCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    private fun enqueueLegacyResolveLocked(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        if (!name.isNullOrBlank() && resolveQueue.any { it.serviceName == name }) {
            return
        }
        resolveQueue.addLast(serviceInfo)
        if (!isResolvingLegacy) {
            drainLegacyResolveQueueLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun drainLegacyResolveQueueLocked() {
        val manager = nsdManager ?: run {
            resolveQueue.clear()
            isResolvingLegacy = false
            return
        }
        if (activeDiscoveryListener == null || resolveQueue.isEmpty()) {
            isResolvingLegacy = false
            return
        }

        val next = resolveQueue.removeFirst()
        isResolvingLegacy = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) {
                    isResolvingLegacy = false
                    drainLegacyResolveQueueLocked()
                }
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val serviceName = resolvedInfo.serviceName ?: next.serviceName ?: "Monotonic"
                val hostAddr = resolvedInfo.host
                val parsed = extractServerFromInfo(
                    serviceName = serviceName,
                    addresses = if (hostAddr != null) listOf(hostAddr) else emptyList(),
                    port = resolvedInfo.port,
                    attributes = resolvedInfo.attributes
                )
                synchronized(lock) {
                    if (activeDiscoveryListener != null && parsed != null) {
                        discoveredByServiceName[serviceName] = parsed
                        publishServersLocked()
                    }
                    isResolvingLegacy = false
                    drainLegacyResolveQueueLocked()
                }
            }
        }

        try {
            manager.resolveService(next, resolveListener)
        } catch (_: Exception) {
            isResolvingLegacy = false
            drainLegacyResolveQueueLocked()
        }
    }

    private fun extractServerFromInfo(
        serviceName: String,
        addresses: List<InetAddress>,
        port: Int,
        attributes: Map<String, ByteArray?>?
    ): DiscoveredServer? {
        if (port !in 1..65535) return null
        val ipv4 = selectPreferredIpv4(addresses) ?: return null
        val txtName = parseTxtValue(attributes?.get("name"))
        val txtPath = normalizeTxtPath(parseTxtValue(attributes?.get("path")))
        val displayName = txtName ?: serviceName.ifBlank { ipv4 }
        return DiscoveredServer(
            name = displayName,
            host = ipv4,
            port = port,
            path = txtPath,
            serviceName = serviceName
        )
    }

    private fun publishServersLocked() {
        _servers.value = deduplicateServers(discoveredByServiceName.values)
    }

    private fun acquireMulticastLockLocked() {
        val manager = wifiManager ?: return
        try {
            if (multicastLock == null) {
                multicastLock = manager.createMulticastLock("MonotonicMdnsLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (_: Exception) {
            // Some restricted devices may deny multicast lock; fail gracefully without crashing
        }
    }

    private fun releaseMulticastLockLocked() {
        try {
            val heldLock = multicastLock
            if (heldLock != null && heldLock.isHeld) {
                heldLock.release()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SERVICE_TYPE_PREFIX = "_syncmetro._tcp"
        const val SERVICE_TYPE_QUERY = "_syncmetro._tcp."

        fun matchesServiceType(serviceType: String?): Boolean {
            return serviceType?.startsWith(SERVICE_TYPE_PREFIX) == true
        }

        fun parseTxtValue(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty()) return null
            val decoded = String(bytes, StandardCharsets.UTF_8).trim()
            return decoded.ifEmpty { null }
        }

        fun normalizeTxtPath(rawPath: String?): String {
            val trimmed = rawPath?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "/") return ServerUrlNormalizer.DEFAULT_PATH
            return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        }

        fun selectPreferredIpv4(addresses: List<InetAddress>): String? {
            val ipv4s = addresses.filterIsInstance<Inet4Address>()
            val nonLoopback = ipv4s.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            return (nonLoopback ?: ipv4s.firstOrNull())?.hostAddress
        }

        fun deduplicateServers(servers: Collection<DiscoveredServer>): List<DiscoveredServer> {
            val byHostPort = LinkedHashMap<String, DiscoveredServer>()
            for (server in servers) {
                byHostPort.putIfAbsent(server.hostPortKey, server)
            }
            return byHostPort.values.sortedWith(
                compareBy<DiscoveredServer>(
                    { it.name.lowercase(Locale.US) },
                    { it.host },
                    { it.port }
                )
            )
        }
    }
}
