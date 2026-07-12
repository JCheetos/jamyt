package com.jamyt.lan

import java.util.UUID

/**
 * Representa un peer descubierto en la LAN.
 *
 * Notas de diseño:
 * - [peerId] es UUID generado la primera vez que se abre la app (guardado en SharedPreferences).
 *   Así, si dos peers con la misma app se encuentran, podemos distinguirlos.
 * - [name] es el nombre "humano" que el usuario puso (o el modelo del dispositivo por defecto).
 * - [ip] es la IP del peer en la LAN. La usamos para abrir conexión TCP.
 * - [port] es donde escucha el servidor TCP del peer (7777 por defecto).
 * - [isCoordinator] indica si este peer se proclamó coordinador de la cola.
 */
data class PeerInfo(
    val peerId: String,
    val name: String,
    val ip: String,
    val port: Int,
    val isCoordinator: Boolean = false,
    val queueSize: Int = 0,
    val queueHash: String = "",
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun newLocal(displayName: String, ip: String, port: Int = DEFAULT_PORT): PeerInfo =
            PeerInfo(
                peerId = UUID.randomUUID().toString(),
                name = displayName,
                ip = ip,
                port = port,
                isCoordinator = false,
            )
        const val DEFAULT_PORT = 7777
        const val SERVICE_TYPE = "_jamyt._tcp."
    }
}