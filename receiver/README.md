# JamYT — Custom Cast Receiver

Aplicación de Cast Receiver basada en el **Cast Application Framework (CAF)** que
reproduce videos de YouTube vía la **YouTube IFrame Player API**.

> Por qué existe este receiver: el Default Media Receiver (DMR) integrado en
> el SDK de Cast rechaza URLs de YouTube (`idleReason=4` ERROR) porque YouTube
> no expone URLs de stream directas. Este receiver entiende el formato
> `ytvideo:<videoId>` acordado con el sender y delega la reproducción a
> `youtube.com/embed/<videoId>`.

---

## 1. Estructura

```
receiver/
├── index.html     # entry point (CAF + YouTube IFrame API)
├── player.js      # puente CAF ↔ YouTube IFrame Player + cola
├── style.css      # full-screen black background
└── README.md      # este archivo
```

---

## 2. Setup en GitHub Pages

1. Sube `receiver/` (todos los archivos) al root del repo `jamyt` en GitHub.
2. `Settings` → `Pages` → `Build and deployment`
3. `Source`: `Deploy from a branch`
4. `Branch`: `main` · `Folder`: `/ (root)`
5. URL esperada: `https://jcheetos.github.io/jamyt/receiver/`

Verifica abriendo esa URL en navegador → debe mostrar fondo negro sin errores
de consola (Chrome DevTools → Console).

> Si quieres evitar que GitHub Pages ejecute Jekyll (que a veces rompe
> archivos con `_` al inicio), asegúrate de que hay un archivo `.nojekyll`
> en el root del repo.

---

## 3. Registro en Cast Developer Console

1. Entra a <https://cast.google.com/publish/> (con tu cuenta de Google).
2. `Add Application` → `Custom Receiver`.
3. Nombre: **JamYT** (visible en el TV al castear).
4. URL: `https://jcheetos.github.io/jamyt/receiver/index.html`
   - La URL puede terminar en `/` o en `/index.html`; ambas funcionan.
5. `Save` → Google te asigna un **Application ID** único, formato
   `XXXXXXXX` (8 caracteres alfanuméricos).
6. Copia ese APP_ID y envíamelo: lo pongo en
   `JamytCastOptionsProvider.APP_ID` y en `strings.xml`.

### Mensaje custom (opcional, no usado por ahora)

`Namespace`: déjalo en blanco para v1. Si más adelante queremos que el
sender envíe un JSON con título + thumbnail + duración al receiver,
definiremos aquí un namespace propio (p.ej. `urn:x-cast:com.jamyt.cola`).

---

## 4. Validación local antes de probar en TV

### ⚠️ IMPORTANTE: no abras esta URL en navegador normal

Si abres `https://jcheetos.github.io/jamyt/receiver/` en Chrome (o cualquier
navegador) **sin contexto Cast**, la consola mostrará:

```
Uncaught ReferenceError: cast is not defined
```

Esto es **esperado, no es un bug tuyo**. CAF (Cast Application Framework)
solo expone el namespace `cast.framework.*` cuando la página corre en:

1. Un dispositivo Cast real (Chromecast, Google TV, Android TV).
2. La extensión **Google Cast Debugger** para Chrome.
3. La extensión **Cast Player Debug** para Chrome.

`player.js` tiene un guard defensivo al inicio que detecta este caso y
loggea un diagnóstico útil en lugar de crashear (`[JamYT Receiver] CAF no
está disponible`).

### Opción A: HTTP server local con Cast Debugger (validación en navegador)

1. Instala la extensión **Google Cast Debugger** desde Chrome Web Store
   (búscala por nombre; varias hacen lo mismo).
2. Activa la extensión (debe abrir su ventana).
3. Servir el receiver en local:
   ```bash
   cd receiver
   python3 -m http.server 8000
   ```
4. Abre `http://localhost:8000` en navegador.
5. En la consola debe aparecer:
   ```
   [JamYT Receiver] CAF detectado, inicializando receiver…
   ```
6. Ahora puedes usar la consola del Cast Debugger para inyectar media:
   ```json
   {
     "requestId": 1,
     "media": [{
       "contentId": "ytvideo:aOKAtVAVtDw",
       "contentType": "video/mp4",
       "streamType": "NONE",
       "metadata": { "title": "test", "type": 0 }
     }],
     "currentTime": 0,
     "autoplay": true
   }
   ```
   → El iframe de YouTube debería reproducir `aOKAtVAVtDw`.

### Opción B: Validar directo en TV (validación real)

Esta es la prueba que importa:

1. Compila y reinstala la app: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Asegúrate de que el TV (Chromecast / Google TV) está encendido y en la misma WiFi.
3. Añade un video a la cola.
4. Tap Cast → selecciona el TV.
5. El video debería reproducir **dentro de la app YouTube del TV**,
   no en una pestaña del navegador, gracias al IFrame embed.

Si el TV muestra el video correctamente en `https://www.youtube.com/embed/<videoId>`,
la integración Cast → Custom Receiver → YouTube IFrame está completa.

---

## 5. Pruebas cruzadas con la app Android

Una vez tengas el APP_ID y esté puesto en `JamytCastOptionsProvider.kt` /
`strings.xml`:

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Abre la app → añade un video a la cola → botón Cast → selecciona el TV.
3. **Comportamiento esperado**: tras 5-7s el TV reproduce el video
   directamente desde YouTube (no la URL firmada).
4. `adb logcat -s JamYT:* CastManager:*` debe mostrar el flujo esperado.

---

## 6. Notas técnicas

### `STREAM_TYPE_NONE` en el sender

El sender envía `MediaInfo` con:
```kotlin
val mediaInfo = MediaInfo.Builder("ytvideo:$videoId")  // contentId
    .setStreamType(MediaInfo.STREAM_TYPE_NONE)         // <- clave
    .setContentUrl("https://www.youtube.com/watch?v=$videoId")
    .setMetadata(...)
    .build()
```

`STREAM_TYPE_NONE` indica al CAF que **no intente hacer streaming del
contenido**. El receiver es responsable de toda la lógica de playback.

### Por qué un "fake media element"

CAF requiere que `setMediaElementRequestHandler` devuelva un elemento de
medios (con `paused`, `currentTime`, `duration`). Devolvemos un `<video>`
oculto al que escribimos estados vía `Object.defineProperty` y le
disparamos eventos (`play`, `pause`, `ended`, `timeupdate`).

Cuando disparamos `ended` al terminar un video, CAF llama
automáticamente a `queueAdvance` y vuelve a invocar nuestro handler con
el siguiente item de la cola. Así avanza la cola "gratis".

### Limitaciones conocidas

- **Anuncios**: YouTube muestra anuncios (los del video en sí, no de
  Cast). El Custom Receiver NO los evita porque la API IFrame de YouTube
  no permite eso (es contra sus TOS). Si quieres cero anuncios, solo es
  posible usar YouTube Premium desde la app nativa del TV.
- **Edad/region restrictions**: YouTube puede bloquear algunos videos
  por región o edad. El IFrame Player respetará esas restricciones.
- **DRM**: DRM-protected content (YouTube Premium exclusivo) NO se
  reproduce vía IFrame API.
