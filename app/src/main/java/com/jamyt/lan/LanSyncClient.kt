package com.jamyt.lan

import com.jamyt.queue.JamQueue
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente TCP que se conecta al peer coordinador y mantiene conexión viva.
 *
 * Estrategia:
 * - Reintento cada 3s si falla la conexión.
 * - Al conectar, envía "hello" + "request_state" para sincronizar inmediatamente.
 * - Escucha mensajes entrantes hasta que la conexión se cierra.
 */
class LanSyncClient(
    private val peerIp: String,
    private val peerPort: Int = PeerInfo.DEFAULT_PORT,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val localPeerId: String,
    private val localName: String,
    private val onMessage: suspend (LanSyncServer.Message) -> Unit = {},
) {
    private var job: Job? = null
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null

    // @Volatile garantiza visibilidad entre threads. send() puede ser invocado desde
    // cualquier coroutine (mesh broadcast) mientras connectAndRun() asigna writer.
    // El lock interno de PrintWriter (BufferedWriter) ya protege las escrituras
    // concurrentes, así que no necesitamos synchronized adicional.
    @Volatile
    private var writer: PrintWriter? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            while (running.get()) {
                try {
                    connectAndRun()
                } catch (e: Exception) {
                    // Reintentar tras el delay
                }
                if (running.get()) delay(3000)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        job?.cancel()
    }

    fun send(message: LanSyncServer.Message) {
        // Capturamos la referencia actual; puede cambiar si reconectamos a mitad.
        val w = writer ?: return
        runCatching { w.println(LanSyncServer.encode(message)) }
    }

    private suspend fun connectAndRun() {
        socket = Socket(peerIp, peerPort)
        val s = socket!!
        val out = PrintWriter(s.getOutputStream(), true)
        writer = out
        // Saludo + solicitud de estado
        out.println(LanSyncServer.encode(LanSyncServer.Message.Hello(
            peerId = localPeerId,
            name = localName,
            isCoordinator = false,
            queueSize = 0,
            queueHash = "",
        )))
        out.println(LanSyncServer.encode(LanSyncServer.Message.RequestState))

        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        while (running.get() && !s.isClosed) {
            val line = withTimeoutOrNull(30_000) { reader.readLine() } ?: break
            val msg = LanSyncServer.decode(line) ?: continue
            onMessage(msg)
        }
    }
}