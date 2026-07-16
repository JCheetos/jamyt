package com.jamyt.lan

import android.util.Log
import com.jamyt.queue.JamQueue
import com.jamyt.queue.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinator P2P mesh: cada peer mantiene conexiones TCP con todos los demás peers
 * descubiertos por NSD. No hay líder ni elección; cualquier peer puede hacer add/remove
 * y el cambio se propaga por el mesh.
 *
 * Topología:
 *  - Cada peer abre un ServerSocket en :7777 (lo gestiona LanSyncServer).
 *  - Cada peer abre un LanSyncClient hacia CADA peer descubierto (uno por peer).
 *  - Cuando un peer hace un cambio local (add/remove/clear), MeshCoordinator.broadcast()
 *    lo envía a TODOS los clientes abiertos y al server local (que lo reenvía a sus
 *    clientes entrantes).
 *  - Cuando llega un mensaje remoto, MeshCoordinator:
 *      1. Lo guarda localmente vía repository.mergeFromRemote() (con notify=false para
 *         evitar re-broadcast infinito).
 *      2. Lo reenvía a los OTROS peers del mesh (gossip) para acelerar la convergencia.
 *         Nunca lo reenvía al peer que lo envió (evita eco).
 *
 * Auto-recuperación:
 *  - Si un peer desaparece (NSD lo pierde), su LanSyncClient se cierra.
 *  - Si un peer nuevo aparece (NSD lo descubre), se crea un LanSyncClient y se le pide
 *    el estado actual (Hello + RequestState).
 *
 * Por qué P2P mesh y no coordinador:
 *  - No hay bug de elección (el problema que nos hizo migrar).
 *  - Cualquier peer puede caer sin afectar a los demás.
 *  - Convergencia eventual garantizada por CRDT (merge idempotente).
 */
class MeshCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val repository: QueueRepository,
    private val localPeerId: String,
    /**
     * Nombre legible del dispositivo en LAN. Público para que el ViewModel
     * pueda firmar los items añadidos con "quién lo añadió" sin tener que
     * pasar por el dominio (mantenemos la firma simple hasta Fase 3, cuando
     * un UseCase reciba el dispatcher `addedBy` inyectado).
     */
    val localName: String,
    private val discovery: PeerDiscovery,
    private val server: LanSyncServer,
) {
    private val connections = mutableMapOf<String, LanSyncClient>()
    private var peersJob: Job? = null
    private var expiryJob: Job? = null

    /**
     * Snapshot observable del mapa de peers descubiertos en LAN. Se reenvía
     * directamente desde [discovery.peers] para que la UI pueda verlo sin
     * tocar la API privada del coordinator. El mapa es inmutable para que
     * cualquier compositor que lo observe detecte cambios por referencia.
     *
     * Tras Fase 4 este flow vivirá detrás de un UseCase `ObservePeersUseCase`;
     * de momento exponerlo aquí es el puente mínimo hacia la UI sin
     * introducir más capas antes de tiempo.
     */
    val peers: StateFlow<Map<String, PeerInfo>> = discovery.peers

    fun start() {
        if (peersJob?.isActive == true) return

        // 0. Conectar el callback de cambios locales: cada vez que la cola cambia
        //    localmente (add/remove/clear), broadcast al mesh.
        //    Los cambios remotos (mergeFromRemote con notify=false) NO disparan broadcast,
        //    evitando bucles de retransmisión.
        repository.onLocalChange = { queue ->
            broadcast(LanSyncServer.Message.QueueUpdate(queue))
        }

        // 1. Reaccionar a cambios en el mapa de peers: abrir/cerrar conexiones.
        peersJob = scope.launch {
            discovery.peers.collect { peersMap ->
                syncConnections(peersMap)
            }
        }

        // 2. Limpieza periódica de cola expirada (cada 60s).
        expiryJob = scope.launch {
            while (isActive) {
                delay(60_000)
                repository.clearIfExpired()
            }
        }
    }

    /**
     * Compara el mapa actual de peers con las conexiones que tenemos abiertas:
     *  - Cierra conexiones a peers que ya no están en NSD.
     *  - Abre conexiones a peers nuevos (con Hello + RequestState inicial).
     */
    private fun syncConnections(peersMap: Map<String, PeerInfo>) {
        val currentIds = connections.keys
        val desiredIds = peersMap.keys - localPeerId  // nunca conectarnos a nosotros mismos

        // Cerrar conexiones obsoletas
        (currentIds - desiredIds).forEach { peerId ->
            connections.remove(peerId)?.stop()
            Log.i(TAG, "Conexión cerrada: $peerId")
        }

        // Abrir conexiones nuevas
        (desiredIds - currentIds).forEach { peerId ->
            val peer = peersMap[peerId] ?: return@forEach
            val client = LanSyncClient(
                peerIp = peer.ip,
                peerPort = peer.port,
                scope = scope,
                localPeerId = localPeerId,
                localName = localName,
                onMessage = { msg -> handleIncoming(msg, fromPeerId = peerId) },
            )
            client.start()
            connections[peerId] = client
            Log.i(TAG, "Conexión abierta a ${peer.name} (${peer.ip}:${peer.port})")
        }
    }

    /**
     * Envía un mensaje a TODOS los peers del mesh:
     *  - A cada cliente TCP abierto por nosotros.
     *  - A los clientes conectados a nuestro server local (via server.broadcast()).
     *
     * Se llama desde el colector de cambios locales y desde retransmisiones.
     */
    fun broadcast(message: LanSyncServer.Message) {
        // A clientes salientes
        connections.values.forEach { client ->
            client.send(message)
        }
        // A clientes entrantes (los que se conectaron a nuestro server)
        server.broadcast(message)
    }

    /**
     * Maneja un mensaje entrante desde un peer específico:
     *  - Lo persiste vía repository (con notify=false para evitar bucle).
     *  - Si es un cambio de cola, lo retransmite al resto del mesh (gossip).
     */
    private fun handleIncoming(msg: LanSyncServer.Message, fromPeerId: String) {
        when (msg) {
            is LanSyncServer.Message.QueueUpdate -> {
                // Guardar localmente. notify=false para no re-emitir nuestro propio cambio.
                repository.mergeFromRemote(msg.queue, notify = false)
                // Gossip: reenviar a OTROS peers (no al que nos lo envió).
                connections.forEach { (peerId, client) ->
                    if (peerId != fromPeerId) {
                        client.send(msg)
                    }
                }
            }
            is LanSyncServer.Message.Hello -> {
                // Solo informativo; NSD ya nos notificó del peer.
                Log.d(TAG, "Hello recibido de ${msg.name} (${msg.peerId})")
            }
            is LanSyncServer.Message.RequestState -> {
                // Alguien nos pide nuestra cola. Respondemos por el server (que
                // tiene el socket con el solicitante).
                server.broadcast(LanSyncServer.Message.QueueUpdate(repository.queue.value))
            }
            is LanSyncServer.Message.Heartbeat -> {
                // No retransmitir heartbeats (no aportan estado).
            }
            is LanSyncServer.Message.Byebye -> {
                // No retransmitir.
            }
        }
    }

    /**
     * Punto de entrada público para mensajes recibidos por el LanSyncServer
     * (es decir, conexiones entrantes). El server nos pasa el mensaje junto con
     * el peerId del emisor (extraído del socket que envió el Hello).
     *
     * Esto es necesario porque antes el handler del server en MainActivity
     * ignoraba todos los mensajes (else -> Unit), y los peers que se
     * reconectaban como clientes entrantes nunca recibían respuesta a su
     * RequestState ni se enteraban de los QueueUpdate que llegaban por esa vía.
     */
    fun handleServerMessage(msg: LanSyncServer.Message, fromPeerId: String) {
        handleIncoming(msg, fromPeerId)
    }

    fun stop() {
        peersJob?.cancel()
        peersJob = null
        expiryJob?.cancel()
        expiryJob = null
        repository.onLocalChange = null
        connections.values.forEach { it.stop() }
        connections.clear()
    }

    /** Para depuración: cuántos peers tengo conectados ahora mismo. */
    fun activeConnectionCount(): Int = connections.size

    companion object {
        private const val TAG = "MeshCoordinator"
    }
}