package com.jamyt.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.jamyt.queue.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Snapshot del estado del Cast para mostrar en la UI (debug en pantalla).
 * Se actualiza con cada cambio relevante (conexión, status del TV, errores).
 */
data class CastDebugInfo(
    val isConnected: Boolean = false,
    val tvPlayerState: Int = -1,           // MediaStatus.PLAYER_STATE_*
    val tvIdleReason: Int = -1,            // MediaStatus.IDLE_REASON_*
    val tvQueueSize: Int = 0,              // items cargados en TV
    val lastLoadAttemptItems: Int = 0,     // cuántos items intentamos cargar
    val lastEvent: String = "—",           // descripción legible del último evento
    val lastEventAtMs: Long = System.currentTimeMillis(),
)

/** Mapea los códigos numéricos del SDK a strings legibles para mostrar en pantalla. */
private fun playerStateName(state: Int): String = when (state) {
    MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
    MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
    MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
    MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
    MediaStatus.PLAYER_STATE_UNKNOWN -> "UNKNOWN"
    -1 -> "—"
    else -> "STATE_$state"
}

private fun idleReasonName(reason: Int): String = when (reason) {
    MediaStatus.IDLE_REASON_NONE -> "NONE"
    MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
    MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
    MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
    MediaStatus.IDLE_REASON_ERROR -> "ERROR"
    -1 -> "—"
    else -> "REASON_$reason"
}

/**
 * Wrapper del Google Cast SDK (CAF v22.x).
 *
 * Estrategia: usamos el **Default Media Receiver** con `STREAM_TYPE_NONE`.
 * Esto significa:
 *  - No necesitamos registrar la app en Cast Developer Console.
 *  - Pasamos URLs de YouTube al TV; la app YouTube TV nativa del TV se encarga
 *    de la reproducción (con sus anuncios según la cuenta del TV).
 *  - El Cast NO participa en la lógica del mesh P2P. Es ortogonal: cualquier
 *    peer puede castear, los demás siguen sincronizando la cola por su cuenta.
 *
 * Si en el futuro migramos a un Custom Receiver (para ocultar anuncios o
 * personalizar UI), basta con cambiar `APP_ID` por el ID registrado en la
 * consola de Cast.
 *
 * Nota sobre la versión del SDK: play-services-cast-framework:22.1.0.
 *  - CastContext.getSharedInstance() ahora retorna Task<CastContext> (async).
 *  - SessionManagerListener requiere onSessionEnding() implementado.
 *  - MediaMetadata/MediaQueueItem siguen disponibles con la API clásica.
 */
class CastManager(
    private val context: Context,
    private val onSessionChanged: (Boolean) -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {},
) {
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var remoteMediaClient: RemoteMediaClient? = null

    // Estado del Cast expuesto a la UI para diagnóstico en pantalla.
    private val _debugInfo = MutableStateFlow(CastDebugInfo())
    val debugInfo: StateFlow<CastDebugInfo> = _debugInfo.asStateFlow()

    // Executor dedicado para inicializar CastContext (la API v22 lo requiere).
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Inicializa el Cast SDK. Llamar una sola vez en onCreate de la Activity
     * (o cada vez que el proceso se reconstruya tras onDestroy/onCreate).
     *
     * APP_ID y CastOptions vienen ahora de `JamytCastOptionsProvider` (declarado
     * en AndroidManifest con `OPTIONS_PROVIDER_CLASS_NAME`). NO hace falta
     * `setReceiverApplicationId` programático: el provider es la fuente de verdad.
     */
    fun initialize() {
        try {
            updateDebug { it.copy(lastEvent = "Cast init: empezando getSharedInstance…") }
            Log.i(TAG, "initialize() start, pid=${android.os.Process.myPid()}, tid=${Thread.currentThread().id}")
            val task = CastContext.getSharedInstance(context, executor)
            updateDebug { it.copy(lastEvent = "Cast init: Task obtenido, esperando…") }
            task.addOnCompleteListener { completedTask ->
                if (completedTask.isSuccessful) {
                    castContext = completedTask.result?.apply {
                        sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                    }
                    val ok = castContext != null
                    Log.i(TAG, "CastContext inicializado (APP_ID=${JamytCastOptionsProvider.APP_ID}, listenerRegistrado=$ok)")
                    updateDebug {
                        it.copy(
                            lastEvent = if (ok) "CastContext OK + listener registrado"
                                        else "CastContext FAIL (null result)",
                        )
                    }
                } else {
                    val err = completedTask.exception?.message ?: "unknown"
                    Log.w(TAG, "CastContext no pudo inicializarse: $err")
                    updateDebug { it.copy(lastEvent = "CastContext FAIL: $err") }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "unknown"
            Log.w(TAG, "Cast no disponible en este dispositivo: $msg")
            updateDebug { it.copy(lastEvent = "Cast init EXCEPTION: $msg") }
        }
    }

    /**
     * Carga una lista de videos en el TV. Cada item se construye con
     * `STREAM_TYPE_NONE` y un `contentId` en formato `ytvideo:<videoId>`
     * que el Custom Receiver (ver `receiver/README.md`) sabe parsear y
     * pasar a YouTube IFrame Player. Sin este acuerdo con el receiver,
     * el Default Media Receiver rechazaría los videos con `idleReason=4`.
     */
    fun loadQueue(items: List<QueueItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val client = remoteMediaClient ?: run {
            Log.w(TAG, "No hay sesión Cast activa; loadQueue ignorado")
            return
        }

        val queueItems = items.map { item ->
            val url = buildYoutubeUrl(item.videoId)
            val mediaInfo = MediaInfo.Builder("ytvideo:${item.videoId}")
                // STREAM_TYPE_NONE: el TV NO hace streaming directo. Espera que
                // el receiver (o el TV mismo) sepa qué hacer con la URL.
                .setStreamType(MediaInfo.STREAM_TYPE_NONE)
                // setContentUrl conserva la URL canónica de YouTube; la usan
                // los TVs y receivers que NO son el nuestro para navegar al
                // video vía app nativa YouTube.
                .setContentUrl(url)
                .setMetadata(
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
                        .also {
                            it.putString(MediaMetadata.KEY_TITLE, item.title)
                            // Algunos TVs (Google TV especialmente) usan un campo
                            // "entity" con formato "ytvideo:<id>" para reconocer
                            // el contenido como YouTube y abrir la app nativa.
                            // No hay constante KEY_ENTITY en el SDK, usamos el
                            // string literal directamente.
                            it.putString("entity", "ytvideo:${item.videoId}")
                        }
                )
                .build()

            MediaQueueItem.Builder(mediaInfo)
                .setAutoplay(true)
                .setPreloadTime(5.0)
                .build()
        }

        try {
            // Cargamos la cola COMPLETA en el TV usando queueLoad().
            // Esta es la API legacy pero estable para cargar varios items.
            // Los modos de repeat son: 0 = REPEAT_OFF, 1 = REPEAT_ALL, 2 = REPEAT_SINGLE.
            // Usamos 0 (sin loop).
            val safeStartIndex = startIndex.coerceIn(0, queueItems.lastIndex)
            client.queueLoad(queueItems.toTypedArray(), safeStartIndex, /* repeatMode */ 0, /* customData */ null)
            Log.i(TAG, "Cargando cola con ${queueItems.size} item(s) en TV (startIndex=$safeStartIndex)")
            updateDebug {
                it.copy(
                    lastLoadAttemptItems = queueItems.size,
                    lastEvent = "queueLoad(${queueItems.size} items)",
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "unknown"
            Log.e(TAG, "Error al cargar la cola en el TV: $msg")
            updateDebug { it.copy(lastEvent = "queueLoad ERROR: $msg") }
        }
    }

    /** Salta al siguiente item de la cola. */
    fun skipToNext() {
        remoteMediaClient?.queueNext(null)
    }

    /** Pausa la reproducción en el TV. */
    fun pause() {
        remoteMediaClient?.pause()
    }

    /** Reanuda la reproducción en el TV. */
    fun resume() {
        remoteMediaClient?.play()
    }

    /** True si hay un TV conectado y la sesión Cast está activa. */
    fun isConnected(): Boolean = castSession?.isConnected == true

    /**
     * Libera recursos. Llamar en onStop de la Activity.
     * No cerramos la sesión Cast (queremos que el TV siga reproduciendo aunque
     * el celular pase a background o se desconecte temporalmente).
     */
    fun shutdown() {
        try {
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionManagerListener,
                CastSession::class.java
            )
        } catch (_: Exception) {}
        remoteMediaClient = null
        castSession = null
        executor.shutdown()
    }

    // ---- Privados ----

    // Cada callback del SessionManagerListener dispara Log.i (visible en
    // logcat filtrando por 'CastManager') Y updateDebug (visible en el
    // CastDebugPanel en pantalla). Esto es diagnóstico temporal — cuando
    // validemos el flujo completo, podemos dejar solo los updateDebug que
    // informen al usuario.
    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            Log.i(TAG, "onSessionStarting(tid=${Thread.currentThread().id})")
            updateDebug { it.copy(lastEvent = "▶ onSessionStarting") }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "onSessionStarted sessionId=$sessionId (tid=${Thread.currentThread().id})")
            castSession = session
            remoteMediaClient = session.remoteMediaClient?.apply {
                registerCallback(mediaClientCallback)
            }
            updateDebug { it.copy(isConnected = true, lastEvent = "▶ TV conectado (sid=${sessionId.take(6)})") }
            onSessionChanged(true)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "onSessionStartFailed: error=$error")
            updateDebug { it.copy(lastEvent = "✗ onSessionStartFailed err=$error") }
        }

        // API v22: este método es ahora obligatorio.
        override fun onSessionEnding(session: CastSession) {
            Log.i(TAG, "onSessionEnding (tid=${Thread.currentThread().id})")
            updateDebug { it.copy(lastEvent = "… onSessionEnding") }
            // No-op: el cleanup real ocurre en onSessionEnded.
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "onSessionEnded: error=$error")
            castSession = null
            remoteMediaClient = null
            updateDebug {
                it.copy(
                    isConnected = false,
                    tvPlayerState = -1,
                    tvIdleReason = -1,
                    tvQueueSize = 0,
                    lastEvent = "■ TV desconectado (err=$error)",
                )
            }
            onSessionChanged(false)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            Log.i(TAG, "onSessionResuming sessionId=$sessionId")
            updateDebug { it.copy(lastEvent = "↻ onSessionResuming") }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "onSessionResumed wasSuspended=$wasSuspended")
            castSession = session
            remoteMediaClient = session.remoteMediaClient?.apply {
                registerCallback(mediaClientCallback)
            }
            updateDebug { it.copy(isConnected = true, lastEvent = "↻ TV reconectado (wasSuspended=$wasSuspended)") }
            onSessionChanged(true)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "onSessionResumeFailed: error=$error")
            updateDebug { it.copy(lastEvent = "✗ onSessionResumeFailed err=$error") }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.i(TAG, "onSessionSuspended: reason=$reason")
            updateDebug { it.copy(lastEvent = "⏸ onSessionSuspended reason=$reason") }
            // Mantenemos referencia para reanudar.
        }
    }

    // API v22: solo onStatusUpdated() es público (los demás están obfuscados).
    // onStatusUpdated se dispara en cualquier cambio de estado del TV.
    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = remoteMediaClient?.mediaStatus ?: return
            val queueSize = status.queueItems?.size ?: 0

            // Actualizar diagnóstico para mostrar en pantalla.
            updateDebug {
                it.copy(
                    tvPlayerState = status.playerState,
                    tvIdleReason = status.idleReason,
                    tvQueueSize = queueSize,
                )
            }

            Log.i(TAG, "Estado TV: playerState=${status.playerState} (${playerStateName(status.playerState)}), idleReason=${status.idleReason} (${idleReasonName(status.idleReason)}), items=$queueSize")

            when (status.playerState) {
                MediaStatus.PLAYER_STATE_IDLE -> {
                    when (status.idleReason) {
                        MediaStatus.IDLE_REASON_FINISHED -> {
                            updateDebug { it.copy(lastEvent = "TV terminó video") }
                            // El TV terminó el video actual. Avisamos al PlaybackController.
                            onPlaybackFinished()
                        }
                        MediaStatus.IDLE_REASON_ERROR -> {
                            val msg = "TV ERROR (STREAM_TYPE_NONE rechazado?)"
                            Log.e(TAG, "$msg — revisar si este TV soporta carga externa de YouTube.")
                            updateDebug { it.copy(lastEvent = msg) }
                        }
                        MediaStatus.IDLE_REASON_CANCELED -> {
                            updateDebug { it.copy(lastEvent = "TV canceló reproducción") }
                        }
                        MediaStatus.IDLE_REASON_INTERRUPTED -> {
                            updateDebug { it.copy(lastEvent = "TV interrumpido") }
                        }
                        MediaStatus.IDLE_REASON_NONE -> {
                            // IDLE sin razón específica suele significar que la cola
                            // nunca llegó a cargarse. Es el caso típico cuando
                            // STREAM_TYPE_NONE no funciona en este TV.
                            val msg = if (queueSize == 0) "TV IDLE sin items cargados"
                                      else "TV IDLE con $queueSize items (no reproduce)"
                            updateDebug { it.copy(lastEvent = msg) }
                        }
                    }
                }
                MediaStatus.PLAYER_STATE_PLAYING -> {
                    updateDebug { it.copy(lastEvent = "Reproduciendo en TV") }
                }
                MediaStatus.PLAYER_STATE_BUFFERING -> {
                    updateDebug { it.copy(lastEvent = "TV cargando...") }
                }
                MediaStatus.PLAYER_STATE_PAUSED -> {
                    updateDebug { it.copy(lastEvent = "TV pausado") }
                }
            }
        }
    }

    private fun updateDebug(transform: (CastDebugInfo) -> CastDebugInfo) {
        val current = _debugInfo.value
        _debugInfo.value = transform(current).copy(lastEventAtMs = System.currentTimeMillis())
    }

    private fun buildYoutubeUrl(videoId: String): String {
        // Formato canónico para que la app YouTube TV reconozca el video.
        return "https://www.youtube.com/watch?v=$videoId"
    }

    companion object {
        private const val TAG = "CastManager"

        /**
         * Alias local del APP_ID para no importar la clase del provider en cada
         * sitio. La fuente de verdad es `JamytCastOptionsProvider.APP_ID`.
         */
        private val APP_ID = JamytCastOptionsProvider.APP_ID
    }
}