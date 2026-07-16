package com.jamyt.domain.usecase

import com.jamyt.domain.repository.CastingGateway
import com.jamyt.queue.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Operadores reactivos: suscriben flujos y disparan acciones side-effect.
 *
 * **Por qué son clases (no funciones top-level):** cada uno requiere un
 * [CoroutineScope] propio + un [Job] que pueda cancelarse si el VM se
 * destruye. Encapsular esto en clases nos da:
 *   - Una superfície de `start()` / `stop()` clara.
 *   - Una referencia de Job testeable desde JVM (no se necesita Android).
 *   - No exponer `Job` ni scope al resto del código.
 *
 * **Por qué NO viven en el VM:** porque son lógica de negocio, no
 * orquestación de UI. Testearlos por separado (con un repository y gateway
 * fake) nos permite verificar "cuando el queue cambia y hay sesión Cast,
 * ¿se llama loadQueueOnTv con la lista correcta?" — sin tener que
 * instanciar un ViewModel ni un Context Android.
 */

/**
 * Mantiene la cola del TV sincronizada con la cola local:
 * cuando hay sesión Cast activa y la cola local cambia (add, remove, sync
 * desde mesh, etc.), re-envía la cola al receiver.
 *
 * El smart loadQueue del receptor evita reiniciar si el videoId actualmente
 * en reproducción sigue presente en la nueva cola.
 */
class ObserveQueueSyncOnTv(
    private val repository: QueueRepository,
    private val gateway: CastingGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Arranca la observación. Llamar una sola vez por ciclo de vida
     * (ej. en el `init {}` del VM). Si ya está activo, no hace nada.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(repository.queue, gateway.isConnected) { queue, connected ->
                if (connected) queue.items else null
            }.collect { items ->
                if (items != null) {
                    gateway.loadQueueOnTv(items, startIndex = 0)
                }
            }
        }
    }

    /** Detiene la observación. Llamar en `onCleared()` del VM. */
    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Reacciona al evento `tvItemFinished` del gateway: cuando el TV termina un
 * video, quita el `videoId` correspondiente de la cola local.
 *
 * Como el smart loadQueue del receptor no reinicia si el videoId actual
 * sigue presente, esto es la única forma de "avanzar la cola" sin perder
 * posición. Y tras la eliminación, el `ObserveQueueSyncOnTv` re-envía
 * automáticamente la cola acortada al TV.
 *
 * Cambia respecto a la versión pre-Fase-3 (en `PlaybackController`):
 *   - Antes: FIFO assumption (`current.items.first()`) — incorrecto si
 *     varios peers modifican la cola concurrentemente.
 *   - Ahora: removal por videoId directo — el receptor incluye el
 *     `videoId` en el status `ended` (ver `receiver/player.js`).
 */
class ObserveTvItemFinished(
    private val repository: QueueRepository,
    private val gateway: CastingGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            gateway.tvItemFinished.collect { videoId ->
                repository.remove(videoId)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
