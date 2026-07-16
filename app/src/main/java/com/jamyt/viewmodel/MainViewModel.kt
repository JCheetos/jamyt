package com.jamyt.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.jamyt.domain.repository.CastingGateway
import com.jamyt.lan.MeshCoordinator
import com.jamyt.queue.QueueItem
import com.jamyt.queue.QueueRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel principal de JamYT.
 *
 * Responsabilidades:
 *   1. Combinar los flujos reactivos de las capas inferiores en un único
 *      [MainUiState] que la UI consume — `state` es la única superficie.
 *   2. Traducir [MainIntent]s en acciones sobre las dependencias
 *      (QueueRepository, CastingGateway, MeshCoordinator).
 *   3. Orquestar el flujo "cola local → Cast" cuando hay sesión activa.
 *      Mantenemos esa lógica en el VM (no en un UseCase todavía) porque es
 *      el único caso donde el VM necesita coordinarse con un evento externo
 *      (cambio de queue + cambio de session state); en Fase 3 lo movemos
 *      a `LoadQueueOnTvUseCase` para tener un test JVM puro de este flow.
 *   4. Reaccionar al evento `tvItemFinished` del gateway para quitar el
 *      videoId correspondiente de la cola local (esto lo hacía
 *      `PlaybackController` en versiones anteriores, ahora absorbido por
 *      el VM como orquestador natural).
 *
 * **Lo que el VM ya NO hace tras Fase 2:**
 *   - No referencia directamente `CastManager` (solo `CastingGateway`)
 *   - No mantiene `PlaybackController` (eliminado)
 *   - No recibe callbacks `onSessionChanged` / `onPlaybackFinished`
 *     (reemplazados por observación reactiva de flows)
 */
class MainViewModel(
    private val repository: QueueRepository,
    private val meshCoordinator: MeshCoordinator,
    private val castingGateway: CastingGateway,
) : ViewModel() {

    /**
     * Estado único expuesto a la UI. Se reconstruye automáticamente cuando
     * cualquiera de los flujos fuente emite un nuevo valor.
     */
    val state: StateFlow<MainUiState> = combine(
        repository.queue,
        meshCoordinator.peers,
        castingGateway.debugInfo,
    ) { queue, peers, debug ->
        val peersList = peers.values.sortedWith(compareBy({ it.name }, { it.ip }))
        MainUiState(
            queue = queue,
            peers = peersList,
            isCastConnected = debug.isConnected,
            castDebug = debug,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        // ── Auto-sync de la cola hacia el TV ─────────────────────────────
        // Cuando hay sesión Cast activa y la cola local cambia (add, remove,
        // sync desde mesh, etc.), reenviamos la cola completa al receiver.
        //
        // El smart loadQueue del receptor evita reiniciar si el videoId
        // actualmente en reproducción sigue presente en la nueva cola.
        viewModelScope.launch {
            combine(repository.queue, castingGateway.isConnected) { queue, connected ->
                if (connected) queue.items else null
            }.collect { items ->
                if (items != null) {
                    castingGateway.loadQueueOnTv(items, startIndex = 0)
                }
            }
        }

        // ── Reacción a "TV terminó video" ────────────────────────────────
        // El receptor (player.js) envía un status `ended` con videoId cuando
        // YouTube dispara ENDED. Lo quitamos de la cola local; el `combine`
        // de arriba re-enviará la cola actualizada al TV automáticamente.
        viewModelScope.launch {
            castingGateway.tvItemFinished.collect { videoId ->
                repository.remove(videoId)
            }
        }
    }

    /**
     * Único punto de entrada de la UI. Sealed `MainIntent` permite que cada
     * llamada sea atómica y testeable de forma aislada.
     */
    fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.AddItem -> handleAddItem(intent)
            is MainIntent.RemoveItem -> handleRemoveItem(intent)
            MainIntent.ClearQueue -> handleClearQueue()
            MainIntent.PausePlayback -> castingGateway.pause()
            MainIntent.ResumePlayback -> castingGateway.resume()
            MainIntent.SkipNext -> castingGateway.skipToNext()
        }
    }

    private fun handleAddItem(intent: MainIntent.AddItem) {
        val item = QueueItem.fromYoutubeUrl(
            rawUrl = intent.url,
            title = intent.title,
            addedBy = meshCoordinator.localName,
        ) ?: return
        // repository.add() → onLocalChange → MeshCoordinator decide si
        // hacer broadcast al mesh (Fase 3 moverá esto a un UseCase).
        viewModelScope.launch { repository.add(item) }
    }

    private fun handleRemoveItem(intent: MainIntent.RemoveItem) {
        viewModelScope.launch { repository.remove(intent.itemId) }
    }

    private fun handleClearQueue() {
        viewModelScope.launch { repository.clear() }
    }

    /**
     * Factory que inyecta las dependencias. Vivir aquí (no en `MainActivity`)
     * facilita reemplazar las dependencias en tests sin modificar la Activity.
     */
    class Factory(
        private val repository: QueueRepository,
        private val meshCoordinator: MeshCoordinator,
        private val castingGateway: CastingGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return MainViewModel(
                repository = repository,
                meshCoordinator = meshCoordinator,
                castingGateway = castingGateway,
            ) as T
        }

        /**
         * Variante para `viewModel(factory = ...)` que recibe `CreationExtras`.
         * Por ahora delegamos a [create]; cuando tengamos SavedStateHandle (no hoy),
         * se integraría aquí.
         */
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            create(modelClass)
    }
}
