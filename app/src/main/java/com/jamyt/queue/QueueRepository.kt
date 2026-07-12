package com.jamyt.queue

import android.content.Context
import android.content.SharedPreferences
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estado + persistencia local de la cola.
 *
 * Por qué no uso solo Room:
 * - Quiero un StateFlow reactivo para la UI.
 * - Quiero merge con cola remota sin tocar DB en cada heartbeat.
 *
 * Estrategia:
 * - Room es la "fuente de verdad" persistente para los items vivos.
 * - SharedPreferences guarda los tombstones (removedItemIds) como un string
 *   con separador "|". Es trivial y suficiente para el orden de magnitud de
 *   IDs que manejamos (cientos a lo sumo).
 *
 * Los tombstones se purgan junto con la cola cuando se cumple la regla de
 * expiración 24h (ver clearIfExpired). Esto evita que el set crezca
 * indefinidamente con el uso.
 */
class QueueRepository private constructor(private val context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        JamDatabase::class.java,
        "jamyt.db"
    ).build()

    private val dao = db.queueDao()

    // Scope interno para operaciones de persistencia (fire-and-forget).
    // SupervisorJob: que un fallo en una persistencia no cancele las siguientes.
    // Dispatchers.IO: las queries a Room deben correr fuera del hilo principal.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Callback opcional: si se setea, se invoca con la nueva cola después de cada
    // cambio LOCAL (add/remove/clear). No se invoca cuando el cambio viene de un
    // mergeFromRemote con notify=false. Permite al MeshCoordinator hacer broadcast
    // sin acoplar el repositorio al coordinator.
    var onLocalChange: ((JamQueue) -> Unit)? = null

    private val _queue = MutableStateFlow(JamQueue.EMPTY)
    val queue: StateFlow<JamQueue> = _queue.asStateFlow()

    /** Carga inicial desde DB + SharedPreferences. Aplica expiración 24h. */
    suspend fun load() {
        val rows = dao.all()
        val liveItems = rows.map { row ->
            QueueItem(
                itemId = row.itemId,
                videoId = row.videoId,
                title = row.title,
                addedBy = row.addedBy,
                addedAt = row.addedAt,
            )
        }
        val removedIds = loadRemovedIds()
        val loaded = JamQueue(
            items = liveItems,
            removedItemIds = removedIds,
            updatedAt = rows.maxOfOrNull { row -> row.updatedAt } ?: 0L,
        )
        // Si está expirada (vacía o >24h), purga TODO incluyendo tombstones.
        val clean = if (loaded.isExpired()) JamQueue.EMPTY else loaded
        _queue.value = clean
        if (clean != loaded) {
            persist(clean)
            // Si purgó tombstones, reflejarlo en prefs.
            saveRemovedIds(clean.removedItemIds)
        }
    }

    fun add(item: QueueItem) {
        val next = _queue.value.add(item).trim()
        if (next == _queue.value) return
        _queue.value = next
        persist(next)
        // Si el item revivió (estaba en tombstones), reflejar en prefs.
        saveRemovedIds(next.removedItemIds)
        onLocalChange?.invoke(next)
    }

    fun remove(itemId: String) {
        val next = _queue.value.remove(itemId)
        if (next == _queue.value) return
        _queue.value = next
        persist(next)
        saveRemovedIds(next.removedItemIds)
        onLocalChange?.invoke(next)
    }

    /**
     * Merge con cola remota. Si la local estaba expirada, se reemplaza.
     * @param notify si true, también dispara onLocalChange (no debería: los cambios
     *               remotos ya llegaron por la red, no hay que re-emitirlos).
     */
    fun mergeFromRemote(remote: JamQueue, notify: Boolean = false) {
        val current = _queue.value
        val merged = if (current.isExpired()) remote.trim() else current.merge(remote).trim()
        if (merged != current) {
            _queue.value = merged
            persist(merged)
            // Persistir tombstones también cuando vienen del mesh.
            saveRemovedIds(merged.removedItemIds)
            if (notify) onLocalChange?.invoke(merged)
        }
    }

    fun clearIfExpired() {
        if (_queue.value.isExpired()) {
            // Cola expirada (>24h sin cambios O vacía): purga items vivos Y
            // tombstones. Esto evita que removedItemIds crezca indefinidamente.
            _queue.value = JamQueue.EMPTY
            persist(JamQueue.EMPTY)
            saveRemovedIds(emptySet())
            onLocalChange?.invoke(JamQueue.EMPTY)
        }
    }

    /**
     * Limpia la cola manualmente (acción del usuario).
     * El broadcast al mesh ocurre vía onLocalChange. Los tombstones acumulados
     * se mantienen para que un clear remoto posterior no reviva items ya
     * eliminados en este peer.
     */
    fun clear() {
        val next = _queue.value.clear()
        if (next == _queue.value) return
        _queue.value = next
        persist(next)
        saveRemovedIds(next.removedItemIds)
        onLocalChange?.invoke(next)
    }

    private fun persist(q: JamQueue) {
        // En MVP, simple: borrar todas e insertar de nuevo.
        // Si la cola crece o se hace cuello de botella, cambiar a diff.
        val rows = q.items.map {
            QueueRow(
                itemId = it.itemId,
                videoId = it.videoId,
                title = it.title,
                addedBy = it.addedBy,
                addedAt = it.addedAt,
                updatedAt = q.updatedAt,
            )
        }
        // dao.replaceAll es suspend → lo lanzamos en el scope interno.
        scope.launch { dao.replaceAll(rows) }
    }

    // --- Tombstone persistence (SharedPreferences) ---

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadRemovedIds(): Set<String> {
        val raw = prefs().getString(KEY_REMOVED_IDS, "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }

    private fun saveRemovedIds(ids: Set<String>) {
        // Si está vacío, borramos la clave para no acumular prefs basura.
        val editor = prefs().edit()
        if (ids.isEmpty()) {
            editor.remove(KEY_REMOVED_IDS)
        } else {
            editor.putString(KEY_REMOVED_IDS, ids.joinToString(SEPARATOR))
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "jamyt_prefs"
        private const val KEY_REMOVED_IDS = "removed_item_ids"
        private const val SEPARATOR = "|"

        @Volatile private var INSTANCE: QueueRepository? = null
        fun get(context: Context): QueueRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: QueueRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}

@Entity(tableName = "queue_items")
data class QueueRow(
    @PrimaryKey val itemId: String,
    val videoId: String,
    val title: String,
    val addedBy: String,
    val addedAt: Long,
    val updatedAt: Long,
)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY addedAt ASC")
    suspend fun all(): List<QueueRow>

    @Query("DELETE FROM queue_items")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<QueueRow>)

    @Transaction
    suspend fun replaceAll(rows: List<QueueRow>) {
        deleteAll()
        insertAll(rows)
    }
}

@Database(entities = [QueueRow::class], version = 1, exportSchema = false)
abstract class JamDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
}