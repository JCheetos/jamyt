package com.jamyt.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Descubrimiento de peers en LAN usando NSD (Network Service Discovery, mDNS).
 *
 * Por qué NSD y no broadcast UDP manual:
 * - Es la API oficial de Android.
 * - Usa mDNS (puerto 5353), que atraviesa NAT/routers domésticos sin config.
 * - No requiere permisos especiales (más allá de INTERNET).
 *
 * Limitación conocida: si el router tiene "AP isolation" activado, los peers no se ven.
 * Es un requisito del entorno, no algo que la app pueda arreglar.
 */
class PeerDiscovery(
    private val context: Context,
    private val serviceName: String,
    private val port: Int = PeerInfo.DEFAULT_PORT,
) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock: WifiManager.MulticastLock? = run {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("jamyt_nsd")?.also { it.setReferenceCounted(false) }
    }

    /**
     * Identidad estable del peer, persistida en SharedPreferences.
     * Si la app se cierra y reabre, este ID se mantiene, evitando que el resto
     * del mesh vea al mismo dispositivo como un peer "nuevo" (y causando
     * duplicados en el mapa de peers y conexiones TCP redundantes).
     *
     * Si el usuario reinstala la app (lo cual borra SharedPreferences), se
     * generará un nuevo ID. En ese caso, los demás peers verán dos entradas
     * (la vieja sin respuesta + la nueva) hasta que NSD purgue la vieja o
     * pase el timeout del socket TCP.
     */
    val localPeerId: String = run {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_LOCAL_PEER_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_LOCAL_PEER_ID, it).apply()
        }
    }

    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

    private val knownServices = ConcurrentHashMap<String, NsdServiceInfo>()

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Servicio registrado: ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Registro NSD falló: $errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.i(TAG, "Descubrimiento iniciado: $regType")
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            Log.i(TAG, "Encontrado: ${service.serviceName}")
            if (!service.serviceType.contains(PeerInfo.SERVICE_TYPE.trimEnd('.'))) return

            // Crear un ResolveListener NUEVO para cada resolución. Android no
            // permite que el mismo listener esté activo en dos resolveService()
            // simultáneos (lanza IllegalArgumentException("listener already in use")
            // si lo intentamos). Como onServiceFound puede dispararse para varios
            // peers casi simultáneamente (especialmente cuando hay >2 peers en LAN
            // o cuando NSD re-anuncia tras un onServiceLost), necesitamos un
            // listener único por resolución.
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode for ${s.serviceName}")
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    handleResolvedService(s)
                }
            }
            try {
                nsdManager.resolveService(service, listener)
            } catch (e: IllegalArgumentException) {
                // Defensa adicional por si Android aún rechaza el listener.
                Log.w(TAG, "resolveService rechazó el listener (raro): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "resolveService falló por ${service.serviceName}: ${e.message}")
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            Log.i(TAG, "Perdido: ${service.serviceName}")
            knownServices.remove(service.serviceName)
            val host = service.serviceName.substringBefore(".")
            _peers.update { it - host }
        }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed: $errorCode")
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            handleResolvedService(serviceInfo)
        }
    }

    /**
     * Procesa un servicio NSD ya resuelto. Se llama desde [resolveListener]
     * (compatibilidad) y desde listeners anónimos creados en [onServiceFound]
     * para evitar el bug "listener already in use" cuando hay varias
     * resoluciones simultáneas.
     */
    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host?.hostAddress ?: return
        val txt = serviceInfo.attributes
        val peerId = txt["peerId"]?.let { String(it) } ?: serviceInfo.serviceName
        val name = txt["name"]?.let { String(it) } ?: serviceInfo.serviceName
        val isCoord = txt["coordinator"]?.let { String(it) } == "true"
        val qSize = txt["queueSize"]?.let { String(it).toIntOrNull() } ?: 0
        val qHash = txt["queueHash"]?.let { String(it) } ?: ""
        val peer = PeerInfo(
            peerId = peerId,
            name = name,
            ip = host,
            port = serviceInfo.port,
            isCoordinator = isCoord,
            queueSize = qSize,
            queueHash = qHash,
            lastSeenMs = System.currentTimeMillis(),
        )
        knownServices[serviceInfo.serviceName] = serviceInfo
        _peers.update { it + (peer.peerId to peer) }
    }

    fun start() {
        multicastLock?.acquire()
        val info = NsdServiceInfo().apply {
            serviceName = this@PeerDiscovery.serviceName
            serviceType = PeerInfo.SERVICE_TYPE
            this.port = this@PeerDiscovery.port
            // TXT records: NSD Manager acepta strings en la firma estable.
            setAttribute("peerId", localPeerId)
            setAttribute("name", this@PeerDiscovery.serviceName)
            setAttribute("coordinator", "false")
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        nsdManager.discoverServices(PeerInfo.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        runCatching { nsdManager.unregisterService(registrationListener) }
        multicastLock?.release()
        // Limpieza adicional: purgar peers cuyo lastSeenMs sea muy viejo. Esto
        // cubre casos donde NSD no notificó onServiceLost correctamente (por
        // ejemplo, tras un crash del peer remoto que no da tiempo a NSD de
        // enviar el mensaje de pérdida).
        val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
        _peers.update { current ->
            current.filterValues { it.lastSeenMs >= cutoff }
        }
        knownServices.clear()
    }

    companion object {
        private const val TAG = "PeerDiscovery"

        // SharedPreferences: usamos el mismo archivo que QueueRepository para
        // tombstones, centralizando el estado persistente de la app.
        private const val PREFS_NAME = "jamyt_prefs"
        private const val KEY_LOCAL_PEER_ID = "local_peer_id"

        /**
         * Timeout para considerar un peer como "muerto". Si no hemos visto
         * noticias suyas en este tiempo, lo purgamos del mapa. Defensa en
         * profundidad contra NSD que no notifique correctamente la pérdida.
         */
        private const val PEER_TIMEOUT_MS = 60_000L

        /** IP local en la red WiFi (best-effort). */
        fun localIp(context: Context): String {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifi?.connectionInfo?.ipAddress?.let { intToIp(it) }
            if (wifiIp != null && wifiIp != "0.0.0.0") return wifiIp

            // Fallback: enumerar interfaces
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                    if (iface.isLoopback || !iface.isUp) return@forEach
                    iface.inetAddresses?.toList()?.forEach { addr ->
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            } catch (_: Exception) { }
            return "0.0.0.0"
        }

        private fun intToIp(ip: Int): String =
            "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}

// Helper de extensión para StateFlow.update (no viene en todas las versiones de coroutines)
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}