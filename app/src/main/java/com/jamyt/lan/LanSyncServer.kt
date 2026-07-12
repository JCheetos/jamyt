package com.jamyt.lan

import com.jamyt.queue.JamQueue
import com.jamyt.queue.QueueItem
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Protocolo de sincronización sobre TCP LAN (puerto 7777).
 *
 * Mensajes (JSON line-delimited, una línea por mensaje):
 *   {"type":"hello","peerId":"...","name":"...","isCoordinator":true,"queueSize":N,"queueHash":"..."}
 *   {"type":"queue_update","items":[...],"updatedAt":123}
 *   {"type":"request_state"}
 *   {"type":"heartbeat","ts":123}
 *   {"type":"byebye"}
 *
 * Por qué JSON y no protobuf:
 * - Debug más fácil (puedes telnet al puerto y leer a mano).
 * - Trivial de implementar.
 * - Mensajes son pequeños; la diferencia de bytes es despreciable en LAN.
 */
class LanSyncServer(
    private val port: Int = PeerInfo.DEFAULT_PORT,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Callback por mensaje entrante.
     * @param msg mensaje decodificado
     * @param sock socket del cliente que lo envió (para identificar al peer via
     *             [getPeerIdForSocket])
     */
    private val onMessage: suspend (Message, Socket) -> Unit = { _, _ -> },
) {
    sealed class Message {
        data class Hello(val peerId: String, val name: String, val isCoordinator: Boolean, val queueSize: Int, val queueHash: String) : Message()
        data class QueueUpdate(val queue: JamQueue) : Message()
        object RequestState : Message()
        data class Heartbeat(val ts: Long) : Message()
        object Byebye : Message()
    }

    private var server: ServerSocket? = null
    private val clients = mutableSetOf<Socket>()

    /**
     * Mapa inverso socket → peerId. Se llena cuando llega un mensaje Hello del peer
     * remoto (que incluye su peerId). Permite que el MeshCoordinator sepa qué peer
     * específico envió cada mensaje entrante, lo cual es necesario para:
     *  1. Responder correctamente a RequestState con la cola actual.
     *  2. Evitar el eco en el gossip (no reenviar al peer que nos envió el mensaje).
     *
     * Si llega un mensaje RequestState antes de un Hello, usamos "" como peerId
     * temporal; el siguiente Hello lo actualizará.
     */
    private val socketToPeerId = mutableMapOf<Socket, String>()

    /** peerId → socket, para hacer lookup inverso (quién es el peer X). */
    private val peerIdToSocket = mutableMapOf<String, Socket>()

    fun start() {
        scope.launch {
            try {
                server = ServerSocket(port)
                while (isActive) {
                    val sock = server!!.accept()
                    clients += sock
                    launch { handleClient(sock) }
                }
            } catch (e: Exception) {
                // Server cerrado
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        socketToPeerId.clear()
        peerIdToSocket.clear()
    }

    /**
     * Devuelve el peerId asociado a un socket entrante, o "" si aún no se recibió
     * un Hello de ese peer. Usado por el MeshCoordinator para saber el origen del
     * mensaje entrante.
     */
    fun getPeerIdForSocket(socket: Socket): String = socketToPeerId[socket] ?: ""

    /** Broadcast a todos los clientes conectados. */
    fun broadcast(message: Message) {
        val line = encode(message)
        clients.toList().forEach { sock ->
            scope.launch {
                try {
                    PrintWriter(sock.getOutputStream(), true).println(line)
                } catch (_: Exception) {
                    clients.remove(sock)
                    runCatching { sock.close() }
                }
            }
        }
    }

    private suspend fun handleClient(sock: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                val msg = decode(line) ?: continue
                // Si llega un Hello, registramos el peerId → socket para que el
                // MeshCoordinator pueda identificar al emisor en mensajes
                // posteriores (RequestState, QueueUpdate, etc).
                // Si ya había un peerId previo para este socket (reconexión),
                // limpiamos la entrada inversa anterior.
                if (msg is Message.Hello) {
                    val previous = socketToPeerId[sock]
                    if (previous != null && previous != msg.peerId) {
                        peerIdToSocket.remove(previous)
                    }
                    socketToPeerId[sock] = msg.peerId
                    peerIdToSocket[msg.peerId] = sock
                }
                onMessage(msg, sock)
                // Si piden estado, respondemos con nuestra cola.
                if (msg is Message.RequestState) {
                    // El caller debe llamar a broadcast() desde fuera; aquí solo anotamos.
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(sock)
            // Limpiar también los mapas inversos para no dejar entradas zombies.
            val pid = socketToPeerId.remove(sock)
            if (pid != null) peerIdToSocket.remove(pid)
            runCatching { sock.close() }
        }
    }

    companion object {
        fun encode(message: Message): String = when (message) {
            is Message.Hello -> JSONObject().apply {
                put("type", "hello")
                put("peerId", message.peerId)
                put("name", message.name)
                put("isCoordinator", message.isCoordinator)
                put("queueSize", message.queueSize)
                put("queueHash", message.queueHash)
            }.toString()
            is Message.QueueUpdate -> JSONObject().apply {
                put("type", "queue_update")
                put("updatedAt", message.queue.updatedAt)
                val arr = JSONArray()
                message.queue.items.forEach { item ->
                    arr.put(JSONObject().apply {
                        put("itemId", item.itemId)
                        put("videoId", item.videoId)
                        put("title", item.title)
                        put("addedBy", item.addedBy)
                        put("addedAt", item.addedAt)
                    })
                }
                put("items", arr)
                // Tombstones: ids de items removidos. Permite que remove/clear
                // se propaguen correctamente al mesh (el receptor filtra los
                // items cuyo id esté en este set).
                val removedArr = JSONArray()
                message.queue.removedItemIds.forEach { removedArr.put(it) }
                put("removed", removedArr)
            }.toString()
            Message.RequestState -> JSONObject().put("type", "request_state").toString()
            is Message.Heartbeat -> JSONObject().put("type", "heartbeat").put("ts", message.ts).toString()
            Message.Byebye -> JSONObject().put("type", "byebye").toString()
        }

        fun decode(line: String): Message? = try {
            val o = JSONObject(line)
            when (o.optString("type")) {
                "hello" -> Message.Hello(
                    peerId = o.optString("peerId"),
                    name = o.optString("name"),
                    isCoordinator = o.optBoolean("isCoordinator"),
                    queueSize = o.optInt("queueSize"),
                    queueHash = o.optString("queueHash"),
                )
                "queue_update" -> {
                    val items = mutableListOf<QueueItem>()
                    val arr = o.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val it = arr.getJSONObject(i)
                        items += QueueItem(
                            itemId = it.optString("itemId"),
                            videoId = it.optString("videoId"),
                            title = it.optString("title"),
                            addedBy = it.optString("addedBy"),
                            addedAt = it.optLong("addedAt"),
                        )
                    }
                    // Tombstones: tolerar mensajes antiguos sin el campo "removed".
                    val removedArr = o.optJSONArray("removed")
                    val removedIds: Set<String> = if (removedArr != null) buildSet {
                        for (i in 0 until removedArr.length()) add(removedArr.getString(i))
                    } else emptySet()
                    Message.QueueUpdate(JamQueue(
                        items = items,
                        removedItemIds = removedIds,
                        updatedAt = o.optLong("updatedAt"),
                    ))
                }
                "request_state" -> Message.RequestState
                "heartbeat" -> Message.Heartbeat(o.optLong("ts"))
                "byebye" -> Message.Byebye
                else -> null
            }
        } catch (_: Exception) { null }

        /** SHA256 corto (8 chars) para identificar el "estado" de la cola entre peers. */
        fun shortHash(queue: JamQueue): String {
            val md = MessageDigest.getInstance("SHA-256")
            val payload = queue.items.joinToString("|") { "${it.itemId}:${it.videoId}" } + "@${queue.updatedAt}"
            val digest = md.digest(payload.toByteArray())
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }
    }
}