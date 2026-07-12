package com.jamyt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jamyt.lan.PeerInfo
import com.jamyt.queue.JamQueue
import com.jamyt.queue.QueueItem
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pantalla principal: muestra la cola, los peers conectados, y botones para añadir/eliminar.
 *
 * El header indica:
 * - Si somos coordinador ("Yo soy el host de la cola")
 * - Si hay otro coordinador (muestra nombre + IP)
 * - Cantidad de peers conectados
 * - Si la cola está vacía o próxima a expirar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    queue: JamQueue,
    peers: List<PeerInfo>,
    isCastConnected: StateFlow<Boolean> = MutableStateFlow(false),
    castDebugInfo: StateFlow<com.jamyt.cast.CastDebugInfo> = MutableStateFlow(com.jamyt.cast.CastDebugInfo()),
    onAddItem: (String, String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onClearQueue: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(true) }

    val debugInfo by castDebugInfo.collectAsState()

    // Selector de rutas Cast: descubre TVs/Chromecasts en la LAN.
    // El MediaRouteButton abre un dialog estándar de Android con los TVs disponibles.
    // Importante: usamos categoryForCast(APP_ID) y NO CATEGORY_REMOTE_PLAYBACK.
    // La categoría genérica establecía una sesión "generic remote playback"
    // (volume funciona, media load no llega a ningún receiver, y el
    // SessionManagerListener<CastSession> no dispara). Con categoryForCast(APP_ID)
    // la sesión queda vinculada al APP_ID de Jamyt y es una CastSession real.
    val routeSelector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(com.jamyt.cast.JamytCastOptionsProvider.APP_ID))
            .build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JamYT") },
                actions = {
                    // MediaRouteButton: icono estándar de Cast. Al pulsarlo,
                    // Android muestra el dialog con TVs disponibles en la WiFi.
                    // Como MediaRouteButton es una View tradicional, lo envolvemos
                    // con AndroidView para usarlo dentro de Compose.
                    AndroidView(
                        factory = { ctx ->
                            MediaRouteButton(ctx).apply {
                                setRouteSelector(routeSelector)
                            }
                        },
                        update = { /* Sin cambios dinámicos por ahora */ },
                    )
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = queue.items.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Limpiar cola")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Añadir video") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Bug corregido: se leía `.value` directamente, lo que NO disparaba
            // recomposición cuando cambiaba el StateFlow. Ahora lo envolvemos
            // con `collectAsState()` para que el banner reaccione en vivo.
            val isConnected by isCastConnected.collectAsState()
            StatusBanner(peerCount = peers.size, isCastConnected = isConnected)

            if (queue.items.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(queue.items, key = { it.itemId }) { item ->
                        QueueItemCard(item = item, onRemove = { onRemoveItem(item.itemId) })
                    }
                }
            }

            if (peers.isNotEmpty()) {
                PeersBar(peers = peers)
            }

            // Panel de diagnóstico del Cast. Muestra el estado del TV en tiempo
            // real sin necesidad de adb/logcat. Útil para depurar problemas
            // con STREAM_TYPE_NONE y carga de cola.
            CastDebugPanel(
                debug = debugInfo,
                expanded = showDebugPanel,
                onToggle = { showDebugPanel = !showDebugPanel },
            )
        }
    }

    if (showAddDialog) {
        AddVideoDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, title ->
                onAddItem(url, title)
                showAddDialog = false
            },
        )
    }

    if (showClearDialog) {
        ConfirmClearDialog(
            itemCount = queue.items.size,
            onDismiss = { showClearDialog = false },
            onConfirm = {
                onClearQueue()
                showClearDialog = false
            },
        )
    }
}

@Composable
private fun ConfirmClearDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Limpiar toda la cola?") },
        text = {
            Text("Se eliminarán los $itemCount video${if (itemCount == 1) "" else "s"} de la cola compartida. " +
                 "El resto de peers conectados verá el cambio en unos segundos.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Sí, limpiar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun StatusBanner(peerCount: Int, isCastConnected: Boolean = false) {
    // Banner neutro: muestra cuántos peers están conectados y si hay Cast activo.
    // No hay distinción host/cliente en P2P mesh; cualquier peer puede añadir y
    // el cambio se sincroniza con todos.
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (peerCount == 0) "Sin peers conectados"
                           else "Conectado a $peerCount peer${if (peerCount == 1) "" else "s"}",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isCastConnected)
                        "Reproduciendo en TV. Los cambios se sincronizan automáticamente."
                    else
                        "Los cambios en la cola se sincronizan automáticamente con todos.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("La cola está vacía", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pega una URL de YouTube o usa el buscador para añadir el primer video.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun QueueItemCard(item: QueueItem, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Placeholder para miniatura: usa la URL directa al thumbnail de YouTube
            // (carga perezosa en producción; aquí solo mostramos un icono).
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                Text(
                    "añadido por ${item.addedBy}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Quitar")
            }
        }
    }
}

@Composable
private fun PeersBar(peers: List<PeerInfo>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                peers.joinToString(" · ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * Panel de diagnóstico del Cast. Muestra el estado del TV en tiempo real para
 * depurar problemas sin necesidad de adb/logcat.
 *
 * Se muestra siempre (colapsable) en la parte inferior de la pantalla. Cuando
 * hay problemas con la carga de videos en TV, este panel muestra exactamente
 * qué pasó: si fue IDLE sin items, si fue ERROR, cuántos items se intentaron
 * cargar, etc.
 */
@Composable
private fun CastDebugPanel(
    debug: com.jamyt.cast.CastDebugInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val connectionColor = if (debug.isConnected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (debug.isConnected) "🟢 Cast conectado" else "⚪ Cast desconectado",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = connectionColor,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Ocultar" else "Diagnóstico")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Estado TV: ${castStateName(debug.tvPlayerState)} (${debug.tvPlayerState}) · " +
                           "IdleReason: ${castIdleReasonName(debug.tvIdleReason)} (${debug.tvIdleReason})",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Items en TV: ${debug.tvQueueSize} · Último intento: ${debug.lastLoadAttemptItems} items",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Último evento: ${debug.lastEvent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Helpers locales para mostrar los nombres legibles de los códigos del SDK.
// Replican los de CastManager.kt pero aquí son privados y solo para UI.
private fun castStateName(state: Int): String = when (state) {
    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_UNKNOWN -> "UNKNOWN"
    -1 -> "—"
    else -> "STATE_$state"
}

private fun castIdleReasonName(reason: Int): String = when (reason) {
    com.google.android.gms.cast.MediaStatus.IDLE_REASON_NONE -> "NONE"
    com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
    com.google.android.gms.cast.MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
    com.google.android.gms.cast.MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
    com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR -> "ERROR"
    -1 -> "—"
    else -> "REASON_$reason"
}

@Composable
private fun AddVideoDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir video de YouTube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL o ID de YouTube") },
                    placeholder = { Text("https://youtu.be/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, title) },
                enabled = url.isNotBlank(),
            ) { Text("Añadir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}