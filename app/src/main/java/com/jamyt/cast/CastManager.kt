package com.jamyt.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.jamyt.queue.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * Estrategia v2 del archivo: usábamos `RemoteMediaClient.queueLoad(...)`
 * con `MediaInfo` y `STREAM_TYPE_NONE` para que el Default Media Receiver
 * rechazase con `idleReason=4 ERROR` y todo el control pasara por nuestro
 * Custom Receiver. Esto ya no funciona porque:
 *
 *   1. El DMR rechaza las URLs de YouTube (firma, no stream directo).
 *   2. El Custom Receiver nuevo (CAF v3) requiere `cast-media-player` en
 *      el DOM y eliminó `playerManager.setMediaElementRequestHandler`
 *      en favor de mensajes custom sobre namespace propio.
 *
 * Estrategia v3 del archivo (la actual): hablamos con el receiver vía
 * `CastSession.sendMessage(NAMESPACE, json)` directamente. Saltamos
 * completamente `RemoteMediaClient` y `MediaInfo`. El receiver:
 *   - Recibe las instrucciones (loadQueue, play, pause, next, prev)
 *   - Crea YouTube IFrame Player directamente con los videoIds
 *   - Gestiona la cola de forma autónoma (auto-advance en ENDED)
 *   - Reporta status al sender vía messages de vuelta sobre el mismo
 *     namespace.
 *
 * Por qué funciona para YouTube: YouTube IFrame Player reproduce desde
 * `youtube.com/embed/<videoId>` — el navegador del TV puede hacer esto
 * directamente, sin que el SDK de Cast intente procesar el URL.
 *
 * Notas sobre la versión del SDK: play-services-cast-framework:22.1.0.
 * Las APIs custom (sendMessage, setMessageReceivedCallback, getCastSession)
 * funcionan en CAF v22.x y CAF v3 del Receiver.
 */
class CastManager(
    private val context: Context,
    private val onSessionChanged: (Boolean) -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {},
) {
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null

    // Estado del Cast expuesto a la UI para diagnóstico en pantalla.
    private val _debugInfo = MutableStateFlow(CastDebugInfo())
    val debugInfo: StateFlow<CastDebugInfo> = _debugInfo.asStateFlow()

    // Executor dedicado para inicializar CastContext (la API v22 lo requiere).
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Inicializa el Cast SDK. Llamar una sola vez en onCreate de la Activity
     * (o cada vez que el proceso se reconstruya tras onDestroy/onCreate).
     *
     * APP_ID y CastOptions vienen de `JamytCastOptionsProvider` (declarado
     * en AndroidManifest con `OPTIONS_PROVIDER_CLASS_NAME`).
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
     * Envía la cola de items al TV vía mensaje custom (NAMESPACE + JSON).
     *
     * Formato: `{"op":"loadQueue","items":[{"videoId","title"}],"startIndex":0}`
     *
     * El receiver los parsea, crea YouTube IFrame Players y reproduce
     * el item en `startIndex`. Auto-advance es responsabilidad del receiver
     * (cuando YouTube reporta ENDED).
     *
     * Suspendimos porque `CastSession.sendMessage` debe invocarse en main
     * thread (es una llamada al binder del SDK Cast) — `withContext(Main.immediate)`
     * lo asegura si estamos en una coroutine que ya corre allí, o cambia
     * contexto si estamos en IO.
     */
    suspend fun loadQueue(items: List<QueueItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val session = castSession ?: run {
            Log.w(TAG, "No hay sesión Cast activa; loadQueue ignorado")
            return
        }

        val itemsJson = JSONArray()
        items.forEach { item ->
            itemsJson.put(JSONObject().apply {
                put("videoId", item.videoId)
                put("title", item.title)
            })
        }
        val message = JSONObject().apply {
            put("op", "loadQueue")
            put("items", itemsJson)
            put("startIndex", startIndex)
        }.toString()

        try {
            val ok = withContext(Dispatchers.Main.immediate) {
                session.sendMessage(NAMESPACE, message)
            }
            Log.i(TAG, "Sent loadQueue(${items.size} items) via $NAMESPACE, ok=$ok")
            updateDebug {
                it.copy(
                    lastLoadAttemptItems = items.size,
                    lastEvent = "sent loadQueue(${items.size} items) ok=$ok",
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "unknown"
            Log.e(TAG, "Error sending loadQueue: $err")
            updateDebug { it.copy(lastEvent = "sendMessage ERROR: $err") }
        }
    }

    /** Salta al siguiente item en la cola (en el receiver). */
    fun skipToNext() = sendOp("next")

    /** Pausa la reproducción en el TV (en el receiver). */
    fun pause() = sendOp("pause")

    /** Reanuda la reproducción en el TV (en el receiver). */
    fun resume() = sendOp("play")

    private fun sendOp(op: String) {
        val session = castSession ?: return
        val message = JSONObject().apply { put("op", op) }.toString()
        try {
            val ok = session.sendMessage(NAMESPACE, message)
            Log.i(TAG, "Sent $op op via $NAMESPACE, ok=$ok")
            updateDebug { it.copy(lastEvent = "sent $op ok=$ok") }
        } catch (e: Exception) {
            Log.w(TAG, "sendOp($op) failed: ${e.message}")
            updateDebug { it.copy(lastEvent = "sendOp($op) failed: ${e.message}") }
        }
    }

    /** True si hay un TV conectado y la sesión Cast está activa. */
    fun isConnected(): Boolean = castSession?.isConnected == true

    /**
     * Libera recursos. Llamar en onDestroy de la Activity si quieres
     * ser proactivo. NO debe llamarse en onStop — el listener del
     * SessionManager está atado al CastContext singleton, no a la
     * Activity (si lo quitamos aquí, el CastDebugPanel quedaría ciego
     * al volver a foreground).
     */
    fun shutdown() {
        try {
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionManagerListener,
                CastSession::class.java
            )
            try {
                castSession?.removeMessageReceivedCallbacks(NAMESPACE)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        castSession = null
        try {
            executor.shutdown()
        } catch (_: Exception) {}
    }

    // ---- Privados ----

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            Log.i(TAG, "onSessionStarting(tid=${Thread.currentThread().id})")
            updateDebug { it.copy(lastEvent = "▶ onSessionStarting") }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "onSessionStarted sessionId=$sessionId (tid=${Thread.currentThread().id})")
            castSession = session
            try {
                session.setMessageReceivedCallbacks(NAMESPACE, messageCallback)
                Log.i(TAG, "setMessageReceivedCallbacks OK")
            } catch (e: Exception) {
                Log.w(TAG, "setMessageReceivedCallbacks failed: ${e.message}")
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
            try {
                castSession?.removeMessageReceivedCallbacks(NAMESPACE)
            } catch (_: Exception) {}
            castSession = null
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
            try {
                session.setMessageReceivedCallbacks(NAMESPACE, messageCallback)
            } catch (e: Exception) {
                Log.w(TAG, "setMessageReceivedCallbacks failed on resume: ${e.message}")
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

    /**
     * Callback invocado cuando el Custom Receiver envía un mensaje de status
     * de vuelta al sender (namespace `urn:x-cast:com.jamyt.cola`).
     *
     * Payload esperado desde el receiver (enviado desde `player.js`):
     *   {"op":"status","event":"playing"|"paused"|"buffering"|"ended"|"queue"|"yt_ready","index":N}
     */
    private val messageCallback = Cast.MessageReceivedCallback { device, namespace, message ->
        // Firma real en v22.x: onMessageReceived(CastDevice device, String namespace, String message)
        // NOTA: el `message` ya es String (no ByteArray); `device` es el CastDevice que lo envió.
        Log.i(TAG, "Message from ${device?.friendlyName ?: "?"} @ $namespace: $message")
        try {
            val json = JSONObject(message)
            val op = json.optString("op", "?")
            val event = json.optString("event", "")
            val videoId = json.optString("videoId", "")
            val index = json.optInt("index", -1)
            val size = json.optInt("size", -1)
            val status = when {
                op == "yt_ready" -> "YT IFrame Player listo"
                op == "queue" -> "Cola: ${if (size >= 0) "$size items" else "?"} idx=$index ${if (videoId.isNotEmpty()) "→ $videoId" else ""}".trim()
                op == "status" && event == "playing" ->
                    "Reproduciendo en TV (item ${index})"
                op == "status" && event == "paused" -> "TV pausado"
                op == "status" && event == "buffering" -> "TV cargando..."
                op == "status" && event == "ended" -> {
                    onPlaybackFinished()
                    "TV terminó video"
                }
                op == "status" && event == "error" -> "TV error: ${json.optString("detail", "")}"
                else -> "[receiver] $op/$event"
            }
            updateDebug {
                it.copy(
                    tvQueueSize = if (size >= 0) size else it.tvQueueSize,
                    tvPlayerState = if (op == "status") stateFromEvent(event) else it.tvPlayerState,
                    lastEvent = status,
                )
            }
        } catch (e: Exception) {
            updateDebug { it.copy(lastEvent = "[receiver] ${message.take(80)}") }
        }
    }

    private fun stateFromEvent(event: String): Int = when (event) {
        "playing" -> MediaStatus.PLAYER_STATE_PLAYING
        "paused" -> MediaStatus.PLAYER_STATE_PAUSED
        "buffering" -> MediaStatus.PLAYER_STATE_BUFFERING
        "idle" -> MediaStatus.PLAYER_STATE_IDLE
        "ended" -> MediaStatus.PLAYER_STATE_IDLE
        else -> -1
    }

    private fun updateDebug(transform: (CastDebugInfo) -> CastDebugInfo) {
        val current = _debugInfo.value
        _debugInfo.value = transform(current).copy(lastEventAtMs = System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "CastManager"

        /**
         * Custom Cast namespace para mensajes entre la app Android y el
         * Custom Receiver. Debe coincidir exactamente con el constante
         * `NAMESPACE` en `receiver/player.js`. Empieza con `urn:x-cast:`
         * (requisito del protocolo Cast).
         */
        const val NAMESPACE = "urn:x-cast:com.jamyt.cola"

        /**
         * Alias local al APP_ID para no importar la clase del provider
         * en cada sitio. La fuente de verdad es `JamytCastOptionsProvider.APP_ID`.
         */
        private val APP_ID = JamytCastOptionsProvider.APP_ID
    }
}
