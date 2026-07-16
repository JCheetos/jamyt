package com.jamyt.domain.usecase

import com.jamyt.lan.MeshCoordinator
import com.jamyt.queue.QueueItem
import com.jamyt.queue.QueueRepository

/**
 * UseCases de la cola.
 *
 * **Por qué existen como funciones puras (object) en lugar de métodos de
 * instancia:** porque el cuerpo de cada función es 1-3 líneas (parsing +
 * delegate a `repository`). Convertir esto a clases inyectadas añade
 * boilerplate sin valor de testeabilidad real: testear `addItem` es
 * esencialmente testear que el repo subyacente se llama con el item parseado.
 *
 * Patrón: `QueueUseCases.addItem(repository, meshCoordinator, url, title)`.
 * Los parámetros se pasan explícitamente para que las funciones sean testeables
 * sin mockear un singleton.
 *
 * **El VM los llama en lugar de tocar `repository` directamente.** Esto le da
 * al VM una superficie más pequeña (la pieza "qué hacer" se delega aquí) y
 * centraliza la lógica de "cómo parsear un videoId de YouTube" en un solo
 * archivo testeable.
 *
 * **Lo que NO hacen estas funciones:** propaga los cambios al mesh. Eso
 * ocurre vía el callback `onLocalChange` que `MeshCoordinator` registra
 * sobre `repository`. `QueueRepository` mantiene esa responsabilidad; este
 * UseCase solo orquesta inputs/usuario-local.
 */
object QueueUseCases {

    /**
     * Parsea `rawUrl`, valida y añade el item a la cola si es válido.
     * Devuelve `true` si el item se añadió, `false` si la URL no es de YouTube.
     *
     * La emisión al mesh es implícita: `repository.add()` llama al callback
     * `onLocalChange` que `MeshCoordinator` capturó en su `start()`.
     */
    suspend fun addItem(
        repository: QueueRepository,
        meshCoordinator: MeshCoordinator,
        rawUrl: String,
        title: String,
    ): Boolean {
        val item = QueueItem.fromYoutubeUrl(
            rawUrl = rawUrl,
            title = title,
            addedBy = meshCoordinator.localName,
        ) ?: return false
        repository.add(item)
        return true
    }

    /**
     * Quita un item por id. El cambio se propaga al mesh por el mismo
     * mecanismo que `addItem` (`onLocalChange`).
     */
    suspend fun removeItem(
        repository: QueueRepository,
        itemId: String,
    ) {
        repository.remove(itemId)
    }

    /**
     * Limpia toda la cola. Mismo mecanismo de broadcast que los anteriores.
     */
    suspend fun clearQueue(repository: QueueRepository) {
        repository.clear()
    }
}
