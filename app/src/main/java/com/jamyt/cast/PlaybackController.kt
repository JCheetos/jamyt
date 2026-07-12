package com.jamyt.cast

import android.util.Log
import com.jamyt.queue.QueueItem
import com.jamyt.queue.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controla el ciclo de vida del Cast: sincroniza la cola local con el TV y
 * reacciona a los eventos de reproducción.
 *
 * Responsabilidades:
 *  - Cuando el usuario inicia una sesión Cast (conecta un TV), enviarle la
 *    cola actual del repositorio.
 *  - Cuando el TV termina un video, quitar el item que terminó de la cola y
 *    enviar la cola actualizada al TV.
 *  - Cuando la cola cambia mientras hay Cast activo, recargar el TV con la
 *    nueva cola.
 *
 * Diseño: el TV es "tonto" — solo reproduce lo que el celular le manda. Toda
 * la lógica de "qué sigue" vive aquí. Esto es coherente con el mesh P2P:
 * el celular "host" del Cast tiene su copia local de la cola (que está
 * sincronizada con el mesh); si otros peers añaden videos, el mesh los
 * propaga al host, que recarga el TV.
 */
class PlaybackController(
    private val repository: QueueRepository,
    private val castManager: CastManager,
    // Dispatchers.Main.immediate (no IO):
    // - El trabajo de este controller es observar StateFlow y llamar al SDK
    //   de Cast, que exige main thread (`Session.isConnected()` etc.).
    // - Antes usábamos Dispatchers.IO como presunción incorrecta; el bug
    //   quedó latente hasta que Paso 1 hizo que onSessionStarted disparara
    //   por primera vez, exponiendo `IllegalStateException: Must be called
    //   from the main thread` desde el watcher.
    // - `.immediate` evita un re-post al main looper cuando ya estamos en él.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /**
     * Llamado por el CastManager cuando cambia la sesión Cast
     * (true = TV conectado, false = desconectado).
     *
     * Cuando se conecta: enviamos la cola actual.
     * Cuando se desconecta: cancelamos el watcher de cambios.
     */
    fun onCastSessionChanged(isConnected: Boolean) {
        if (isConnected) {
            // Enviamos la cola actual inmediatamente al TV.
            scope.launch { sendCurrentQueueToTv() }
            // Y nos suscribimos a cambios futuros: si la cola cambia (porque
            // otro peer añadió algo), recargar el TV con la nueva cola.
            scope.launch {
                repository.queue.collectLatest { queue ->
                    // Evitamos un bucle: no recargar si la cola está vacía.
                    if (queue.items.isEmpty()) return@collectLatest
                    sendCurrentQueueToTv()
                }
            }
        }
        // Si se desconecta, el scope del launch sigue vivo pero los envíos
        // serán no-ops (castManager.isConnected() == false). Lo dejamos así
        // para simplificar: cuando se reconecte, collectLatest se re-evaluará
        // y enviará.
    }

    /**
     * Llamado por el CastManager cuando el TV termina un video (callback de
     * RemoteMediaClient.Callback).
     *
     * Estrategia: quitar el primer item de la cola local (asumimos que es el
     * que acaba de terminar), y recargar el TV con la cola actualizada.
     */
    fun handleVideoFinished() {
        val current = repository.queue.value
        if (current.items.isEmpty()) {
            Log.d(TAG, "Cola vacía; nada que cargar en TV")
            return
        }
        // Quitamos el primero (FIFO). Si era el último, el TV se quedará sin
        // cola y la próxima vez que el usuario presione Cast, deberá pulsar
        // de nuevo o añadir más videos.
        val itemQueTermino = current.items.first()
        val colaRestante = current.items.drop(1)
        Log.i(TAG, "TV terminó '${itemQueTermino.title}'. Quedan ${colaRestante.size} en cola.")

        // Quitamos localmente (esto dispara onLocalChange → broadcast mesh).
        repository.remove(itemQueTermino.itemId)
        // El watcher onCastSessionChanged detectará el cambio y recargará el TV.
    }

    private suspend fun sendCurrentQueueToTv() {
        val current = repository.queue.value
        if (current.items.isEmpty()) return
        // Defensive: aunque hoy el scope del controller corre en Main.immediate,
        // el SDK de Cast exige main thread para `Session.isConnected()` y
        // operaciones de RemoteMediaClient. Si en el futuro alguien llama a este
        // método desde otro thread, este `withContext` lo sigue manteniendo
        // thread-safe sin propagar el crash.
        if (!withContext(Dispatchers.Main.immediate) { castManager.isConnected() }) return
        castManager.loadQueue(current.items, startIndex = 0)
    }

    companion object {
        private const val TAG = "PlaybackController"
    }
}