package com.jamyt.domain.usecase

import com.jamyt.domain.repository.CastingGateway
import com.jamyt.queue.QueueItem

/**
 * UseCases de control remoto del TV a través del [CastingGateway].
 *
 * Mantiene la misma justificación que [QueueUseCases]: el VM no debería
 * referenciar `CastingGateway` directamente para "un único método trivial",
 * porque rompe la simetría conceptual con el resto de intents (que pasan
 * por UseCases). Aquí la diferencia es estética — el VM pasa a tener una
 * forma uniforme `castUseCases.X(...)` en vez de `castingGateway.X(...)`.
 *
 * El observador de queue y de `tvItemFinished` se separa en
 * [ObserveCastReactions]. Aquí solo viven las **acciones discretas** que
 * el usuario dispara una vez (pause/play/skipNext) y la carga explícita de
 * cola (que el operador no dispara directamente — ver su doc).
 */
object CastUseCases {

    /**
     * Carga una lista de items en el TV. Suspend porque internamente el
     * gateway serializa al Main thread.
     *
     * Si `items` está vacío, no hace nada (el TV no aceptaría una carga vacía).
     * `startIndex` por defecto 0.
     */
    suspend fun loadQueueOnTv(
        gateway: CastingGateway,
        items: List<QueueItem>,
        startIndex: Int = 0,
    ) {
        if (items.isEmpty()) return
        gateway.loadQueueOnTv(items, startIndex)
    }

    /** Pausa la reproducción en el TV. No-op si no hay sesión. */
    fun pause(gateway: CastingGateway) = gateway.pause()

    /** Reanuda la reproducción en el TV. No-op si no hay sesión. */
    fun resume(gateway: CastingGateway) = gateway.resume()

    /** Salta al siguiente item en el TV. No-op si no hay sesión. */
    fun skipNext(gateway: CastingGateway) = gateway.skipToNext()
}
