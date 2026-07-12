package com.jamyt.cast

import android.util.Log
import com.jamyt.queue.QueueItem
import com.jamyt.queue.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    // Job del watcher de queue. La reemplazamos en cada nueva sesión Cast para
    // evitar acumulación: sin esto, cada start/resume del Cast disparaba un
    // `scope.launch { collectLatest ... }` adicional que se quedaba vivo; con
    // 4 ciclos suspend/resume teníamos 4 watchers emitiendo sendCurrentQueueToTv
    // (4-6 mensajes `Sent loadQueue` por cada `repository.add()`).
    private var queueWatcherJob: Job? = null,
) {
    /**
     * Llamado por el CastManager cuando cambia la sesión Cast
     * (true = TV conectado, false = desconectado).
     *
     * Cuando se conecta: cancela el watcher anterior (si existe) y lanza uno
     * nuevo. El nuevo watcher hace DOS cosas en una sola coroutine:
     *   1. Lee el valor actual del StateFlow y lo envía.
     *   2. Se suscribe a emits futuros (collectLatest) para reenviar la cola.
     *
     * Esto evita la duplicación de envíos y elimina la acumulación de watchers.
     */
    fun onCastSessionChanged(isConnected: Boolean) {
        if (isConnected) {
            // Cancelar el anterior evita acumulación de watchers tras varios
            // suspend/resume (cada watcher dispara un envío por emit del queue).
            queueWatcherJob?.cancel()
            queueWatcherJob = scope.launch {
                // (1) Enviar estado actual primero (collectLatest re-evalúa este
                //     lambda en cada suscripción y con el valor inicial vigente).
                sendCurrentQueueToTv()
                // (2) Estar atento a cambios futuros.
                repository.queue.collectLatest { queue ->
                    if (queue.items.isEmpty()) return@collectLatest
                    sendCurrentQueueToTv()
                }
            }
        } else {
            // Sesión cerrada: cancela watcher. Cuando se reconecte crearemos
            // uno nuevo con el estado fresco del StateFlow.
            queueWatcherJob?.cancel()
            queueWatcherJob = null
        }
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