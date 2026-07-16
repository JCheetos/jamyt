package com.jamyt.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.jamyt.domain.repository.CastingGateway
import com.jamyt.domain.usecase.CastUseCases
import com.jamyt.domain.usecase.ObserveTvItemFinished
import com.jamyt.domain.usecase.ObserveQueueSyncOnTv
import com.jamyt.domain.usecase.QueueUseCases
import com.jamyt.lan.MeshCoordinator
import com.jamyt.queue.QueueRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel principal de JamYT.
 *
 * **Responsabilidades:** observar el state combinado y traducir [MainIntent]s
 * en llamadas a [UseCases][com.jamyt.domain.usecase].
 *
 * **Lo que NO hace este VM (es importante):**
 *   - No orquesta queue↔TV: eso vive en [ObserveQueueSyncOnTv] +
 *     [ObserveTvItemFinished], iniciados/parados por este VM.
 *   - No parsea URLs de YouTube: eso vive en [QueueUseCases.addItem].
 *   - No tiene lógica de control Cast (pause/resume/skipNext): eso vive
 *     en [CastUseCases].
 *   - No referencia directamente clases Cast de Google ([CastingGateway] es
 *     la única dependencia de Cast-related, vía interface).
 *
 * Después de la Fase 3 cada dependencia es lógica testeable en JVM puro.
 * Las clases de tests vendrán al final del refactor (Fase 4 / cierre).
 *
 * **Lo que el VM sí hace:**
 *   - Combinar los flujos reactivos para producir un [MainUiState] único.
 *   - Iniciar/parar los observadores de reacciones en `init`/`onCleared`.
 *   - Mantener un punto de entrada único para intents: [onIntent].
 */
class MainViewModel(
    private val repository: QueueRepository,
    private val meshCoordinator: MeshCoordinator,
    private val castingGateway: CastingGateway,
) : ViewModel() {

    // ── Operadores reactivos: cycle de vida atado al VM ─────────────────
    private val syncOp = ObserveQueueSyncOnTv(repository, castingGateway)
    private val finishedOp = ObserveTvItemFinished(repository, castingGateway)

    // ── State único para la UI ──────────────────────────────────────────
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
        syncOp.start()
        finishedOp.start()
    }

    override fun onCleared() {
        super.onCleared()
        syncOp.stop()
        finishedOp.stop()
    }

    /**
     * Único punto de entrada de la UI. El cuerpo es esencialmente un
     * dispatch table; la lógica vive en los UseCases y Operators.
     */
    fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.AddItem -> handleAddItem(intent)
            is MainIntent.RemoveItem -> handleRemoveItem(intent)
            MainIntent.ClearQueue -> handleClearQueue()
            MainIntent.PausePlayback -> CastUseCases.pause(castingGateway)
            MainIntent.ResumePlayback -> CastUseCases.resume(castingGateway)
            MainIntent.SkipNext -> CastUseCases.skipNext(castingGateway)
        }
    }

    private fun handleAddItem(intent: MainIntent.AddItem) {
        viewModelScope.launch {
            QueueUseCases.addItem(
                repository = repository,
                meshCoordinator = meshCoordinator,
                rawUrl = intent.url,
                title = intent.title,
            )
            // Devuelve Boolean, pero la UI no necesita saber si fue éxito:
            // si la URL no era YouTube, el item simplemente no se añade y
            // el queue no cambia. La UI no muestra nada especial.
        }
    }

    private fun handleRemoveItem(intent: MainIntent.RemoveItem) {
        viewModelScope.launch {
            QueueUseCases.removeItem(repository, intent.itemId)
        }
    }

    private fun handleClearQueue() {
        viewModelScope.launch {
            QueueUseCases.clearQueue(repository)
        }
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
