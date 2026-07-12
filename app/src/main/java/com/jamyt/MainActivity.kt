package com.jamyt

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.jamyt.cast.CastManager
import com.jamyt.cast.PlaybackController
import com.jamyt.lan.LanSyncServer
import com.jamyt.lan.MeshCoordinator
import com.jamyt.lan.PeerDiscovery
import com.jamyt.queue.QueueItem
import com.jamyt.queue.QueueRepository
import com.jamyt.ui.MainScreen
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var repository: QueueRepository
    private lateinit var discovery: PeerDiscovery
    private lateinit var server: LanSyncServer
    private lateinit var mesh: MeshCoordinator
    private lateinit var castManager: CastManager
    private lateinit var playbackController: PlaybackController

    /**
     * Estado reactivo: ¿hay un TV conectado vía Cast?
     * Lo consume el composable para mostrar el MediaRouteButton con el
     * estado adecuado (icono activo vs. inactivo).
     */
    private val isCastConnected = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = QueueRepository.get(applicationContext)
        val localName = android.os.Build.MODEL ?: "Android"

        discovery = PeerDiscovery(this, serviceName = localName)
        server = LanSyncServer(onMessage = { msg, sock ->
            // Identificamos al peer que envió el mensaje (si ya recibimos su Hello).
            // Si aún no hay Hello, fromPeerId queda vacío y el coordinator decide.
            val fromPeerId = server.getPeerIdForSocket(sock)
            mesh.handleServerMessage(msg, fromPeerId)
        })

        mesh = MeshCoordinator(
            repository = repository,
            localPeerId = discovery.localPeerId,
            localName = localName,
            discovery = discovery,
            server = server,
        )

        // Cast: inicializamos el SDK y conectamos los callbacks.
        // El PlaybackController coordina entre la cola local y el TV.
        castManager = CastManager(
            context = applicationContext,
            onSessionChanged = { connected ->
                isCastConnected.value = connected
                playbackController.onCastSessionChanged(connected)
            },
            onPlaybackFinished = {
                // El TV terminó un video; quitamos el item de la cola local.
                playbackController.handleVideoFinished()
            },
        )
        castManager.initialize()

        playbackController = PlaybackController(repository, castManager)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    JamApp(
                        repository = repository,
                        discovery = discovery,
                        isCastConnected = isCastConnected,
                        castDebugInfo = castManager.debugInfo,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Arrancamos server y discovery primero; el mesh espera a que
        // load() haya terminado para evitar broadcasts con estado vacío
        // cuando en realidad tenemos items + tombstones persistidos.
        server.start()
        discovery.start()
        lifecycleScope.launch {
            repository.load()
            mesh.start()
        }
    }

    override fun onStop() {
        super.onStop()
        // Cast: NO cerramos la sesión ni desregistramos el listener cuando la
        // Activity pasa a background. Razones:
        //  - El TV sigue reproduciendo aunque el celular esté en background —
        //    eso ya estaba bien.
        //  - El listener del SessionManager está atado al CastContext singleton
        //    del proceso, no a la Activity. Quitarlo en onStop era incorrecto
        //    y dejaba el CastDebugPanel ciego al volver a foreground.
        //  - Si el proceso entero muere, el OS libera todos los listeners.
        // Por eso NO llamamos castManager.shutdown() aquí. Si en el futuro
        // necesitamos liberar algo (ej. el executor), se hace en onDestroy.
        mesh.stop()
        discovery.stop()
        server.stop()
    }
}

@Composable
private fun JamApp(
    repository: QueueRepository,
    discovery: PeerDiscovery,
    isCastConnected: StateFlow<Boolean>,
    castDebugInfo: StateFlow<com.jamyt.cast.CastDebugInfo>,
) {
    val queue by repository.queue.collectAsState()
    val peers by discovery.peers.collectAsState()

    MainScreen(
        queue = queue,
        peers = peers.values.toList(),
        isCastConnected = isCastConnected,
        // Bug corregido: faltaba pasar el castDebugInfo del StateHolder al UI.
        // Sin esto, MainScreen usaba su default MutableStateFlow(CastDebugInfo())
        // — un StateFlow vacío que updateDebug nunca toca. Por eso el panel
        // se quedaba en "—" pese a que el listener SÍ disparaba los logs.
        castDebugInfo = castDebugInfo,
        onAddItem = { url, title ->
            val item = QueueItem.fromYoutubeUrl(url, title, addedBy = android.os.Build.MODEL ?: "Yo")
            if (item != null) {
                // add() invoca onLocalChange → MeshCoordinator hace broadcast al mesh.
                repository.add(item)
            }
        },
        onRemoveItem = { itemId -> repository.remove(itemId) },
        onClearQueue = { repository.clear() },
    )
}