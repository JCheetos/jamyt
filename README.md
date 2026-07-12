# JamYT

**YouTube TV "Jam" para Android, sin servidor, sin nube, 100% LAN local.**

Inspirado en el Jam de Spotify pero apuntando a videos de YouTube en la TV de tu casa.

## ¿Qué hace?

1. Abres la app en tu celular (mientras estás en el WiFi de tu casa).
2. Todos los que tengan la app abierta en la misma WiFi se descubren automáticamente (NSD/mDNS).
3. Cualquier peer abre un socket TCP hacia todos los demás y todos escuchan conexiones entrantes (mesh P2P completo, sin coordinador).
4. Cualquier persona puede pegar una URL de YouTube y agregarla a la cola compartida; el cambio se propaga por el mesh en <3s.
5. Cualquier persona puede eliminar un video o limpiar la cola; la sincronización se mantiene.

Sin cuentas, sin login, sin servidor en la nube, sin pagar hosting.

## Estado del proyecto

### Implementado y verificado en dispositivos reales

- ✅ Descubrimiento de peers en LAN vía NSD/mDNS
- ✅ Cola compartida con CRDT (OR-Set con tombstones)
- ✅ Sincronización mesh P2P sin coordinador
- ✅ Persistencia local (Room) y restauración al reabrir la app
- ✅ Expiración 24h de cola y tombstones
- ✅ UI con Jetpack Compose Material 3
- ✅ PeerId persistente en SharedPreferences (evita duplicados al reconectar)
- ✅ Detección robusta de TVs para Cast (MediaRouteButton en TopBar)
- ✅ SDK de Cast integrado (`play-services-cast-framework:22.1.0`)

### Stack técnico

| Componente | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.0.20 |
| Build | Gradle 8.7, AGP 8.5.0, KSP 2.0.20-1.0.24 |
| JVM target | 17 |
| UI | Jetpack Compose + Material 3 (Compose BOM 2024.06.00) |
| Async | Kotlin Coroutines + StateFlow |
| Persistencia | Room 2.6.1 |
| Descubrimiento LAN | Android NSD (`NsdManager`) |
| Sync mesh | TCP directo puerto 7777, JSON line-delimited |
| Cast SDK | `play-services-cast-framework:22.1.0` |
| MediaRouter | `androidx.mediarouter:mediarouter:1.7.0` |
| Min SDK | 24 (Android 7.0) |
| Target/Compile SDK | 34 |

### Arquitectura

```
jamyt/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/ (themes.xml, colors.xml, strings.xml)
│       └── java/com/jamyt/
│           ├── MainActivity.kt              ← Activity + Composable raíz (FragmentActivity)
│           ├── lan/
│           │   ├── PeerInfo.kt              ← modelo de peer
│           │   ├── PeerDiscovery.kt         ← NSD/mDNS wrapper + multicast lock
│           │   ├── LanSyncServer.kt         ← TCP server (puerto 7777, escucha)
│           │   ├── LanSyncClient.kt         ← TCP client hacia un peer
│           │   └── MeshCoordinator.kt       ← mesh P2P (sin coordinador)
│           ├── queue/
│           │   ├── QueueItem.kt             ← modelo + extractYoutubeId()
│           │   ├── JamQueue.kt              ← OR-Set CRDT + expiración 24h
│           │   └── QueueRepository.kt       ← StateFlow + Room + persistencia tombstones
│           ├── cast/
│           │   ├── CastManager.kt           ← wrapper Google Cast SDK
│           │   └── PlaybackController.kt    ← coordina cola local ↔ TV
│           └── ui/
│               └── MainScreen.kt            ← Compose UI (banner + cola + Cast + diagnóstico)
├── build.gradle.kts                         ← Gradle raíz (versiones centralizadas)
├── settings.gradle.kts                      ← Settings (incluye :app)
├── gradle.properties                        ← AndroidX, JVM args
└── gradlew + gradle/wrapper/                ← wrapper de Gradle 8.7
```

### Cómo compilar

```bash
# Pre-requisitos:
# - JDK 17
# - Android SDK 34, build-tools 34.0.0 (instalados vía sdkmanager)
# - adb para instalación (opcional, también se puede enviar el APK por otros medios, p.ej. correo, Telegram, etc.)

# Compilar APK debug:
cd jamyt
./gradlew assembleDebug

# Instalar en 2+ dispositivos: (requiere adb y que los dispositivos estén conectados por USB o WiFi)
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Cómo probar

1. Conectar 2+ dispositivos Android a la misma WiFi (sin AP isolation).
2. Abrir la app en ambos.
3. En uno, pulsar "Añadir video" → pegar URL de YouTube → "Añadir".
4. El otro debería verlo aparecer en menos de 3 segundos.
5. Probar eliminar/limpiar cola — la sincronización es bidireccional.

**Importante:** WSL2 bloquea multicast UDP por defecto. Las pruebas de sincronización deben hacerse con dispositivos físicos en la misma WiFi, o con emuladores AVD (que tienen red compartida nativa).

### Limitaciones conocidas

| Limitación | Detalle |
|---|---|
| WiFi con AP isolation | Los peers no se descubren. Documentado en código; sin workaround. |
| WSL2 + multicast | Solo permite build, no pruebas de sync. Usar dispositivos reales o AVD. |
| Cast al TV | SDK integrado, sesión Cast funciona, pero la carga de videos en TVs modernos (Google TV, Chromecast) tiene issues conocidos. Diagnóstico en pantalla implementado (`CastDebugPanel`). |
| Sin deduplicación de videos | Si dos personas añaden el mismo video, son 2 items distintos (UUIDs diferentes). Decisión consciente para MVP. |

### Cómo extender (Sujetos a revisión futura)

#### Añadir búsqueda de YouTube

```kotlin
// Endpoint: https://www.googleapis.com/youtube/v3/search?part=snippet&q=...&key=...
// Requiere API key propia (Google Cloud Console → YouTube Data API v3).
```

#### Soporte para iOS

Multipeer Connectivity (iOS) es compatible con Bonjour/mDNS (que usa Android NSD). Se puede implementar el mismo protocolo JSON sobre mesh P2P en Swift.

#### Cambiar la duración de expiración

```kotlin
// En queue/JamQueue.kt
companion object {
    const val EXPIRATION_MS = 24L * 60L * 60L * 1000L  // ← cambia aquí
}
```

## Licencia

MIT. Úsalo, modifícalo, hazlo tuyo.