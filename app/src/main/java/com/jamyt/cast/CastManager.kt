package com.jamyt.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.jamyt.domain.repository.CastingGateway
import com.jamyt.queue.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
 * Implementación concreta de [CastingGateway] sobre Google Cast SDK v22.x.
 *
 * **Esta clase NO debería ser referenciada directamente desde Composables,
 * Composables VM o UseCases** — usar `CastingGateway` (interface). Solo esta
 * implementación sabe de `com.google.android.gms.*`.
 *
 * **Modelo de mensajes custom** sobre namespace `urn:x-cast:com.jamyt.cola`:
 *   - Sender → Receiver: `{"op":"loadQueue","items":[...],"startIndex":N}` /
 *     `{"op":"play"}` / `{"op":"pause"}` / `{"op":"next"}` / `{"op":"stop"}`.
 *   - Receiver → Sender: `{"op":"queue", size, index, videoId}` /
 *     `{"op":"status", event, index, videoId}`.
 *
 * **Por qué custom messages y no `RemoteMediaClient`**:
 *   CAF v3 eliminó `PlayerManager.setMediaElementRequestHandler` y la DMR
 *   rechaza YouTube URLs. Usamos el canal custom + YouTube IFrame Player
 *   en el receptor. El TV reproduce `youtube.com/embed/<videoId>` directamente.
 */
class CastManager(
    private val context: Context,
) : CastingGateway {

    // ── Recursos internos (declarados ANTES de los state observables porque
    //    `isConnected` usa `scope` en su `stateIn`, y Kotlin inicializa las
    //    `val` en orden textual) ────────────────────────────────────────────
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── CastingGateway: state observables ─────────────────────────────────
    private val _debugInfo = MutableStateFlow(CastDebugInfo())
    override val debugInfo: StateFlow<CastDebugInfo> = _debugInfo.asStateFlow()

    override val isConnected: StateFlow<Boolean> = _debugInfo
        .map { it.isConnected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Hot SharedFlow (replay=0) — un 'ended' es un evento, no un estado.
    private val _tvItemFinished = MutableSharedFlow<String>(replay = 0)
    override val tvItemFinished: SharedFlow<String> = _tvItemFinished.asSharedFlow()

    // ── Casting SDK v22.x ─────────────────────────────────────────────────
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null

    /**
     * Inicializa el Cast SDK y registra los listeners del framework.
     * Llamar una sola vez en `onCreate` de la Activity.
     */
    override fun initialize() {
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
     * Carga la cola de items en el TV. `suspend` porque la API del SDK
     * Cast exige Main thread; internamente serializamos con
     * `withContext(Main.immediate)`.
     */
    override suspend fun loadQueueOnTv(items: List<QueueItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val session = castSession ?: run {
            Log.w(TAG, "No hay sesión Cast activa; loadQueueOnTv ignorado")
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
                    lastEvent = "sent loadQueue(${items.size}) ok=$ok",
                )
            }
        } catch (e: Exception) {
            val err = e.message ?: "unknown"
            Log.e(TAG, "Error sending loadQueue: $err")
            updateDebug { it.copy(lastEvent = "sendMessage ERROR: $err") }
        }
    }

    /** Pausa la reproducción en el TV. No-op si no hay sesión. */
    override fun pause() = sendOp("pause")

    /** Reanuda la reproducción en el TV. No-op si no hay sesión. */
    override fun resume() = sendOp("play")

    /** Salta al siguiente item. No-op si no hay sesión. */
    override fun skipToNext() = sendOp("next")

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

    override fun shutdown() {
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

    // ── Internos ────────────────────────────────────────────────────────

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
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "onSessionStartFailed: error=$error")
            updateDebug { it.copy(lastEvent = "✗ onSessionStartFailed err=$error") }
        }

        override fun onSessionEnding(session: CastSession) {
            Log.i(TAG, "onSessionEnding (tid=${Thread.currentThread().id})")
            updateDebug { it.copy(lastEvent = "… onSessionEnding") }
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
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "onSessionResumeFailed: error=$error")
            updateDebug { it.copy(lastEvent = "✗ onSessionResumeFailed err=$error") }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.i(TAG, "onSessionSuspended: reason=$reason")
            updateDebug { it.copy(lastEvent = "⏸ onSessionSuspended reason=$reason") }
        }
    }

    /**
     * Callback invocado cuando el Custom Receiver envía un mensaje de status
     * de vuelta al sender (namespace `urn:x-cast:com.jamyt.cola`).
     *
     * Eventos relevantes que el VM consume:
     *   - `op=status, event=ended, videoId` → emite a `_tvItemFinished`
     *   - resto → actualiza `CastDebugInfo` para el panel de la UI
     */
    private val messageCallback = Cast.MessageReceivedCallback { device, namespace, message ->
        // Firma real en v22.x: onMessageReceived(CastDevice device, String namespace, String message)
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
                    // El TV terminó un video. Si el receptor nos mandó el videoId,
                    // lo emitimos al flow para que el VM lo quite de la cola.
                    if (videoId.isNotEmpty()) {
                        _tvItemFinished.tryEmit(videoId)
                        "TV terminó $videoId"
                    } else {
                        "TV terminó video (sin videoId)"
                    }
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
