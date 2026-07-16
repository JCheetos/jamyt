package com.jamyt

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jamyt.cast.CastManager
import com.jamyt.lan.LanSyncServer
import com.jamyt.lan.MeshCoordinator
import com.jamyt.lan.PeerDiscovery
import com.jamyt.queue.QueueRepository
import com.jamyt.ui.MainScreen
import com.jamyt.viewmodel.MainViewModel
import com.jamyt.domain.repository.CastingGateway
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    // Inyectadas por `onCreate` y pasadas al [MainViewModel.Factory]. Mantenemos
    // las referencias como `lateinit` por ergonomía — la actividad es una
    // capa fina de hosting, no la dueña del ciclo de vida de los servicios.
    private lateinit var repository: QueueRepository
    private lateinit var discovery: PeerDiscovery
    private lateinit var server: LanSyncServer
    private lateinit var mesh: MeshCoordinator
    private lateinit var castManager: CastManager
    private lateinit var castingGateway: CastingGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Construcción de dependencias.
        repository = QueueRepository.get(applicationContext)
        val localName = android.os.Build.MODEL ?: "Android"

        discovery = PeerDiscovery(this, serviceName = localName)
        server = LanSyncServer(onMessage = { msg, sock ->
            // Identificamos al peer que envió el mensaje. Si aún no hay Hello,
            // fromPeerId queda vacío y el coordinator decide.
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

        // Cast: implementamos [CastingGateway] directamente aquí. Antes había
        // un `PlaybackController` que orquestaba queue ↔ TV; tras Fase 2 esa
        // orquestación vive en el ViewModel (combine de queue + isConnected).
        castManager = CastManager(context = applicationContext)
        castingGateway = castManager
        castManager.initialize()

        // 2. ViewModel con inyección manual de dependencias (sin Hilt ni
        //    ViewModelProvider.AndroidViewModelFactory — el ViewModel necesita
        //    instancias con scope de Activity, no Application).
        val factory = MainViewModel.Factory(
            repository = repository,
            meshCoordinator = mesh,
            castingGateway = castingGateway,
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // `viewModel(factory = factory)` registra el VM con el
                    // ViewModelStoreOwner de esta Activity y devuelve el mismo
                    // singleton en recomposiciones.
                    val viewModel: MainViewModel = viewModel(factory = factory)
                    JamTheme(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Arrancamos server y discovery primero; el mesh espera a que
        // `repository.load()` haya terminado para evitar broadcasts con
        // estado vacío cuando en realidad tenemos items + tombstones
        // persistidos.
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
        mesh.stop()
        discovery.stop()
        server.stop()
    }
}

@androidx.compose.runtime.Composable
private fun JamTheme(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    MainScreen(state = state, onIntent = viewModel::onIntent)
}
