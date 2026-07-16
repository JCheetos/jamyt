package com.jamyt.viewmodel

import com.jamyt.cast.CastDebugInfo
import com.jamyt.lan.PeerInfo
import com.jamyt.queue.JamQueue
import com.jamyt.queue.JamQueue.Companion.EMPTY

/**
 * Snapshot completo del estado de la pantalla principal.
 *
 * Es el ÚNICO dato que la UI consume. Se actualiza cuando cambia cualquiera
 * de los flujos que la componen (cola / peers / cast). Ver
 * [MainViewModel.state] para el wiring de los flows.
 *
 * Mantener esta clase `data class` simple (no anidar) para que el Compose
 * recomponga de forma predecible cuando cualquier campo cambia.
 */
data class MainUiState(
    val queue: JamQueue = EMPTY,
    val peers: List<PeerInfo> = emptyList(),
    val isCastConnected: Boolean = false,
    val castDebug: CastDebugInfo = CastDebugInfo(),
)

/**
 * Intenciones que la UI dispara hacia el ViewModel. Usamos `sealed interface`
 * en vez de funciones nombradas para que el VM tenga un único punto de
 * entrada (`onIntent`) — más fácil de testear y de evolucionar.
 *
 * Los intents de TV (Pause/Resume/SkipNext) están acá aunque el VM aún los
 * delega a CastManager; tras Fase 2 pasarán por un UseCase.
 */
sealed interface MainIntent {
    /** El usuario quiere añadir un video a la cola desde el diálogo "Añadir video". */
    data class AddItem(
        val url: String,
        val title: String,
    ) : MainIntent

    /** El usuario descartó un item (botón X en una card). */
    data class RemoveItem(val itemId: String) : MainIntent

    /** El usuario pulsó "Limpiar cola". */
    data object ClearQueue : MainIntent

    /** El usuario pulsó pausa en el reproductor del TV. */
    data object PausePlayback : MainIntent

    /** El usuario pulsó play en el reproductor del TV. */
    data object ResumePlayback : MainIntent

    /** El usuario pulsó siguiente capítulo. */
    data object SkipNext : MainIntent
}
