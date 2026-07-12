package com.jamyt.queue

/**
 * Cola compartida entre peers.
 *
 * Modelo: OR-Set (Observed-Remove Set) con tombstones.
 * - `items`: items vivos (los que se muestran en la UI).
 * - `removedItemIds`: IDs de items removidos (tombstones). Un item con id en este
 *   set está lógicamente borrado, aunque todavía aparezca en `items` durante un
 *   merge parcial.
 * - Un item está "vivo" si su id está en `items` Y NO en `removedItemIds`.
 *
 * Reglas:
 * - `add(item)`: si el id no está vivo, lo añade. Si estaba en removedItemIds,
 *   lo "revive" quitándolo de ahí. Si ya está vivo (mismo itemId), no-op.
 * - `remove(itemId)`: quita de `items` y añade a `removedItemIds`. Si no estaba
 *   vivo, no-op.
 * - `merge(other)`: une `items` por itemId (gana el de addedAt mayor + tie-break
 *   por itemId). Une `removedItemIds` (set union). Filtra los items cuyo id quedó
 *   en el set de removed final. Esto es lo que permite que las eliminaciones se
 *   propaguen entre peers.
 * - `clear()`: marca todos los items actuales como removidos (sin perder el
 *   historial de tombstones previos). Esto permite que un clear remoto se
 *   propague correctamente y que re-añadir después no reviva items viejos.
 *
 * Por qué OR-Set y no "last write wins":
 * - Dos personas pueden añadir el mismo video simultáneamente sin colisión
 *   (UUIDs distintos).
 * - Una persona elimina mientras otra añade: ambos efectos convergen
 *   eventualmente (la eliminación gana porque añade tombstone, y el nuevo item
 *   tiene UUID distinto del eliminado, así que no es revivido por error).
 *
 * Por qué los tombstones se purgan con la expiración 24h:
 * - El campo `removedItemIds` crece monotónicamente. Sin purga, una app con
 *   mucho uso acumularía miles de IDs. Usamos la misma regla de expiración
 *   de la cola: cuando la cola está vacía o vencida (>24h), en
 *   `clearIfExpired()` también se descartan los tombstones.
 */
data class JamQueue(
    val items: List<QueueItem> = emptyList(),
    val removedItemIds: Set<String> = emptySet(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /** Expiración de 24h según requisito del usuario. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (items.isEmpty()) return true
        return (nowMs - updatedAt) > EXPIRATION_MS
    }

    /**
     * Devuelve nueva cola con el item añadido. Si ya existe vivo (mismo itemId),
     * no hace nada. Si estaba en removedItemIds, lo revive quitándolo de ahí.
     */
    fun add(item: QueueItem): JamQueue {
        if (items.any { it.itemId == item.itemId }) return this
        val newRemoved = removedItemIds - item.itemId
        return copy(
            items = items + item,
            removedItemIds = newRemoved,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Devuelve nueva cola sin el item con ese itemId. Si no estaba vivo,
     * no hace nada. En cualquier caso, añade el id a `removedItemIds`
     * para que la eliminación se propague al mesh (los demás peers aprenden
     * del tombstone y filtran el item en su próximo merge).
     */
    fun remove(itemId: String): JamQueue {
        if (items.none { it.itemId == itemId } && itemId !in removedItemIds) return this
        return copy(
            items = items.filterNot { it.itemId == itemId },
            removedItemIds = removedItemIds + itemId,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Limpia toda la cola: marca todos los items actuales como removidos.
     * Mantiene tombstones previos para que un clear remoto no "reviva" items
     * que ya estaban eliminados en este peer.
     */
    fun clear(): JamQueue {
        if (items.isEmpty() && removedItemIds.isEmpty()) return this
        val allIds = items.map { it.itemId }.toSet()
        return copy(
            items = emptyList(),
            removedItemIds = removedItemIds + allIds,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Merge con otra cola: OR-Set completo.
     * - Une items por itemId (gana el de addedAt mayor, tie-break por itemId).
     * - Une removedItemIds (set union).
     * - Filtra items cuyo id quedó en el set de removed final.
     *
     * Esto resuelve el bug original: cuando A hace remove() y B hace merge,
     * B aprende del tombstone y filtra el item en su próxima vista.
     */
    fun merge(other: JamQueue): JamQueue {
        val byId = mutableMapOf<String, QueueItem>()
        for (it in items) byId[it.itemId] = it
        for (it in other.items) {
            val existing = byId[it.itemId]
            if (existing == null || it.addedAt > existing.addedAt ||
                (it.addedAt == existing.addedAt && it.itemId > existing.itemId)) {
                byId[it.itemId] = it
            }
        }
        val mergedRemoved = removedItemIds + other.removedItemIds
        // FIX: filtrar items cuyos IDs estén en el set de tombstones.
        // Sin este filtro, los items eliminados en A nunca desaparecían en B.
        val live = byId.values
            .filterNot { it.itemId in mergedRemoved }
            .sortedWith(compareBy({ it.addedAt }, { it.itemId }))
        return copy(
            items = live,
            removedItemIds = mergedRemoved,
            updatedAt = maxOf(updatedAt, other.updatedAt),
        )
    }

    /** Quita los items más viejos si pasa de maxSize. */
    fun trim(maxSize: Int = 200): JamQueue {
        if (items.size <= maxSize) return this
        return copy(items = items.take(maxSize), updatedAt = System.currentTimeMillis())
    }

    companion object {
        const val EXPIRATION_MS = 24L * 60L * 60L * 1000L  // 24 horas
        val EMPTY = JamQueue()
    }
}