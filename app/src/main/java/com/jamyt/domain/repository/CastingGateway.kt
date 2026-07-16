package com.jamyt.domain.repository

import com.jamyt.cast.CastDebugInfo
import com.jamyt.queue.QueueItem
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstracción del medio de Cast (Chromecast, Google TV, etc.) para que la
 * capa de presentación y de orquestación NO dependan del SDK de Google.
 *
 * **Motivación.** Hasta Fase 1, `MainViewModel` referenciaba
 * `com.jamyt.cast.CastManager` directamente. Eso rompía la promesa del
 * refactor: si Google cambia el SDK o migramos a otro medio (DLNA, AirPlay,
 * Smart TV nativo), el VM y todas las pantallas tendrían que reescribirse.
 *
 * Esta interface vive en `domain.repository` y no debe importar nada de
 * `com.google.android.gms.*` — todo lo relativo al SDK queda en la capa
 * `data.cast.CastManager` (que implementa esta interface).
 *
 * **Lo que el dominio pide al medio de Cast:**
 *
 *  - **Estado observable**: `debugInfo` (snapshot completo para el DebugPanel)
 *    e `isConnected` derivado (para el StatusBanner).
 *
 *  - **Comandos síncronos**: `pause / resume / skipToNext` se mapean al
 *    canal custom del Cast session. Se asume que el gateway maneja su
 *    propio dispatching a Main thread internamente.
 *
 *  - **Carga de cola**: `loadQueueOnTv` es `suspend` porque la API del SDK
 *    exige Main thread; el gateway se encarga de `withContext(Main.immediate)`
 *    por lo que el VM no necesita saber esto.
 *
 *  - **Eventos reactivos del TV**: `tvItemFinished` emite el `videoId` del
 *    item que el TV acaba de terminar de reproducir. Es la única señal
 *    que el dominio necesita para decidir cuándo quitar un item de la cola.
 *    `SharedFlow` (no StateFlow) porque son eventos, no estado.
 *
 * **Lo que NO hace esta interface (lo hace Fase 3 con UseCases):**
 *  - Observar la cola local y emitir `loadQueueOnTv` automáticamente cuando
 *    cambian sus items. Hoy eso lo hace `MainViewModel.combine` con
 *    `repository.queue`.
 *  - Validar que `loadQueueOnTv` se llame solo cuando hay sesión activa.
 *
 * **Reglas de implementación (`CastManager`):**
 *  - `initialize()` debe llamarse una vez en `onCreate` de la Activity.
 *  - `shutdown()` libera el `SessionManagerListener` y el executor.
 *  - Los callbacks `onPlaybackFinished` que vivían como lambdas ahora son
 *    el flow público `tvItemFinished`.
 */
interface CastingGateway {

    /**
     * Snapshot completo del estado Cast expuesto al DebugPanel. StateFlow porque
     * refleja un estado, no un evento; siempre hay un valor actual.
     */
    val debugInfo: StateFlow<CastDebugInfo>

    /**
     * Atajo derivado de `debugInfo.isConnected` para que la UI no tenga que
     * abrir todo el objeto solo para mostrar un punto verde/rojo en el banner.
     * Mantener como field independiente evita recomposiciones innecesarias
     * cuando cambian otros campos de `debugInfo` (lastEvent, lastLoadAttemptItems…).
     */
    val isConnected: StateFlow<Boolean>

    /**
     * Emite el `videoId` del item que el TV acaba de terminar. El receptor
     * incluye este campo en su status `ended` (ver `receiver/player.js`).
     *
     * SharedFlow y no StateFlow: cada `ended` es un evento discreto, no un
     * estado que el VM necesite consultar más tarde.
     */
    val tvItemFinished: SharedFlow<String>

    /**
     * Carga `items` en el TV, empezando por `startIndex`. Suspend porque
     * internamente el gateway serializa la llamada al SDK Cast en Main thread;
     * el VM puede llamarla directamente desde cualquier coroutine.
     *
     * Idempotente: llamadas repetidas con la misma cola son seguras (el
     * receiver usa `smart loadQueue` para evitar reinicios si el videoId
     * actualmente en reproducción sigue presente).
     */
    suspend fun loadQueueOnTv(items: List<QueueItem>, startIndex: Int = 0)

    /** Pausa la reproducción en el TV. No-op si no hay sesión. */
    fun pause()

    /** Reanuda la reproducción en el TV. No-op si no hay sesión. */
    fun resume()

    /** Salta al siguiente item. No-op si no hay sesión. */
    fun skipToNext()

    /**
     * Inicializa el Cast SDK y registra los listeners del framework.
     * Llamar una sola vez en `onCreate` de la Activity.
     */
    fun initialize()

    /**
     * Libera recursos. NO debe llamarse en `onStop` (escuché al usuario
     * quejarse de esto cuando aún existía esa llamada — el listener del
     * SessionManager debe sobrevivir al ciclo de vida de la Activity porque
     * está atado al `CastContext` singleton del proceso). En Fase 4 quizás
     * llamemos en `onDestroy`.
     */
    fun shutdown()
}
