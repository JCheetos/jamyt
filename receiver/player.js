/* =====================================================================
 * JamYT — Custom Cast Receiver (CAF + YouTube IFrame Player)
 *
 * Responsabilidades:
 *   1. CAF nos entrega "media requests" (load, queueLoad, next, etc.).
 *   2. Por cada media request, extraemos el videoId YouTube del contentId
 *      (formato acordado: "ytvideo:<videoId>") y se lo pasamos al IFrame Player
 *      de YouTube que se inyecta en #player.
 *   3. Mantenemos sincronizado el estado de CAF (paused, ended, currentTime)
 *      con lo que reporta el IFrame Player. Sin este puente, CAF no sabe
 *      cuándo avanzar al siguiente item de la cola.
 *   4. Reportamos errores al sender via standardPlayerMediaStatus.
 *
 * Por qué no usar el Default Media Receiver (DMR):
 *   El DMR recibe el MediaInfo, intenta reproducir el URL y reporta
 *   IDLE/ERROR (idleReason=4) cuando el URL no es un stream directo.
 *   YouTube usa URLs firmadas que requieren extracción previa. El DMR
 *   no sabe hacerlo. Por eso necesitamos un receiver que entienda
 *   el formato "ytvideo:<videoId>" y use YouTube IFrame Player.
 * ===================================================================== */

(function () {
  'use strict';

  /* ====================================================================
   * Constantes y estado del receiver
   * ==================================================================== */

  // Estado del YouTube IFrame Player
  let ytPlayer = null;
  let ytApiReady = false;
  let currentVideoId = null;

  // "Fake media element" para CAF: CAF requiere un elemento de medios para
  // reportar estado. Usamos un <video> oculto como contract. CAF lee
  // paused/currentTime/duration de él y se lo pasamos vía Object.defineProperty.
  const mediaElement = document.createElement('video');
  mediaElement.style.display = 'none';
  Object.defineProperty(mediaElement, 'duration', {
    value: 0, writable: true, configurable: true,
  });
  Object.defineProperty(mediaElement, 'currentTime', {
    value: 0, writable: true, configurable: true,
  });
  Object.defineProperty(mediaElement, 'paused', {
    value: true, writable: true, configurable: true,
  });

  // Ticker para avanzar currentTime mientras YT reporta PLAYING. CAF lo lee
  // periódicamente para reportar al sender. Sin esto, currentTime se queda
  // en 0 y el sender nunca sabe cuánto ha avanzado.
  let ticking = false;
  let tickLastMs = 0;

  /* ====================================================================
   * CAF: hooks de ciclo de vida
   * ==================================================================== */

  const context = cast.framework.CastReceiverContext.getInstance();
  const playerManager = context.getPlayerManager();

  // setMediaElementRequestHandler: este es EL hook crítico. CAF lo llama cuando
  // el sender pide cargar media (load, queueLoad, playNext). Devolvemos el
  // mediaElement "fake" y, además, le pedimos al YT Player que cargue el videoId.
  playerManager.setMediaElementRequestHandler((loadRequestData) => {
    const media = loadRequestData.media;
    const contentId = media && media.contentId;
    const videoId = parseVideoId(contentId);

    if (!videoId) {
      console.warn('[JamYT] contentId inválido:', contentId);
      // Devolver null hace que CAF muestre el estado de error estándar.
      // Mejora la diagnóstica para el sender (ver logcat).
      return null;
    }

    console.log('[JamYT] Solicitado videoId:', videoId, 'autoplay=' + loadRequestData.autoplay);
    loadOrUpdateYtPlayer(videoId);

    // Reset del "fake" media element para el nuevo item
    mediaElement.currentTime = 0;
    mediaElement.duration = 0;
    stopTicking();
    mediaElement.paused = true;

    return mediaElement;
  });

  // Cuando YT Player reporta cambio de estado, sincronizamos con CAF.
  function onYtStateChange(event) {
    const state = event.data;
    console.log('[JamYT] YT state:', state);

    if (!ytPlayer) return;

    switch (state) {
      case window.YT_PLAYING:
        // PLAYING: CAF necesita saber. Update fake duration y empieza el ticker.
        try {
          mediaElement.duration = ytPlayer.getDuration();
        } catch (_) { /* getDuration puede lanzar si aún no cargó */ }
        mediaElement.paused = false;
        startTicking();
        mediaElement.dispatchEvent(new Event('play'));
        mediaElement.dispatchEvent(new Event('playing'));
        break;

      case window.YT_PAUSED:
        stopTicking();
        mediaElement.paused = true;
        mediaElement.dispatchEvent(new Event('pause'));
        break;

      case window.YT_BUFFERING:
        stopTicking();
        mediaElement.paused = true;
        mediaElement.dispatchEvent(new Event('waiting'));
        break;

      case window.YT_ENDED:
        // CRÍTICO: este es el avance. CAF, al recibir el evento 'ended'
        // sobre mediaElement, llama a queueAdvance / playNext automáticamente.
        stopTicking();
        mediaElement.paused = true;
        mediaElement.currentTime = mediaElement.duration;
        mediaElement.dispatchEvent(new Event('ended'));
        console.log('[JamYT] → ENDED: CAF avanzará al siguiente item');
        break;

      case window.YT_CUED:
      case window.YT_UNSTARTED:
        // Estados transitorios, no requieren acción.
        break;
    }
  }

  /* ====================================================================
   * YouTube IFrame Player: creación y actualización
   * ==================================================================== */

  function loadOrUpdateYtPlayer(videoId) {
    if (!ytApiReady) {
      // Esperar a que la API esté lista (poll simple; YT no expone Promise).
      const waitId = setInterval(() => {
        if (!ytApiReady) return;
        clearInterval(waitId);
        loadOrUpdateYtPlayer(videoId);
      }, 50);
      return;
    }

    if (ytPlayer && currentVideoId === videoId) {
      // Mismo video: solo play.
      try { ytPlayer.playVideo(); } catch (_) {}
      return;
    }

    if (ytPlayer) {
      // YT ya creado, video distinto: cargamos el nuevo.
      try {
        ytPlayer.loadVideoById(videoId);
        currentVideoId = videoId;
      } catch (e) {
        console.error('[JamYT] loadVideoById error:', e);
      }
      return;
    }

    // Crear instancia inicial.
    ytPlayer = new window.YT.Player('player', {
      width: '100%',
      height: '100%',
      videoId: videoId,
      playerVars: {
        autoplay: 1,
        controls: 0,         // sin controles (el sender controla todo)
        disablekb: 1,
        fs: 0,
        modestbranding: 1,
        rel: 0,              // sin "videos relacionados" al final
        showinfo: 0,
        iv_load_policy: 3,   // sin anotaciones
        playsinline: 1,
      },
      events: {
        onReady: () => console.log('[JamYT] YT Player ready:', videoId),
        onStateChange: onYtStateChange,
        onError: (e) => {
          console.error('[JamYT] YT error code:', e && e.data);
          // CAF reportará MEDIA_ERROR al sender, lo verá en CastDebugPanel.
          mediaElement.dispatchEvent(new Event('error'));
        },
      },
    });
    currentVideoId = videoId;
  }

  /* ====================================================================
   * Ticker: avanza currentTime mientras PLAYING
   * ==================================================================== */

  function startTicking() {
    if (ticking) return;
    ticking = true;
    tickLastMs = Date.now();
    function tick() {
      if (!ticking) return;
      const now = Date.now();
      const dt = (now - tickLastMs) / 1000;
      tickLastMs = now;
      if (mediaElement.duration > 0) {
        mediaElement.currentTime = Math.min(
          (mediaElement.currentTime || 0) + dt,
          mediaElement.duration,
        );
        mediaElement.dispatchEvent(new Event('timeupdate'));
      }
      setTimeout(tick, 250);
    }
    setTimeout(tick, 250);
  }

  function stopTicking() {
    ticking = false;
  }

  /* ====================================================================
   * Util: extraer videoId del contentId
   *
   * Formatos aceptados (en orden de prioridad):
   *   - "ytvideo:<videoId>"          (formato preferido, acordado con el sender)
   *   - "https://www.youtube.com/watch?v=<videoId>"
   *   - "<videoId>"                 (identificador crudo)
   * ==================================================================== */

  function parseVideoId(contentId) {
    if (!contentId || typeof contentId !== 'string') return null;

    let m = contentId.match(/^ytvideo:([\w-]{6,20})$/);
    if (m) return m[1];

    m = contentId.match(/[?&]v=([\w-]{6,20})/);
    if (m) return m[1];

    if (/^[\w-]{6,20}$/.test(contentId)) return contentId;

    return null;
  }

  /* ====================================================================
   * YouTube IFrame API: cargar y exponer constantes de estado
   * ==================================================================== */

  const ytScript = document.createElement('script');
  ytScript.src = 'https://www.youtube.com/iframe_api';
  document.head.appendChild(ytScript);

  // YouTube llama a esta función global cuando la API está lista.
  window.onYouTubeIframeAPIReady = function () {
    ytApiReady = true;
    // Exponemos los nombres legibles como aliases globales para usar arriba.
    window.YT_PLAYING = window.YT.PlayerState.PLAYING;
    window.YT_PAUSED = window.YT.PlayerState.PAUSED;
    window.YT_BUFFERING = window.YT.PlayerState.BUFFERING;
    window.YT_CUED = window.YT.PlayerState.CUED;
    window.YT_UNSTARTED = window.YT.PlayerState.UNSTARTED;
    window.YT_ENDED = window.YT.PlayerState.ENDED;
    console.log('[JamYT] YouTube IFrame API ready');
  };

  /* ====================================================================
   * Start
   * ==================================================================== */

  context.start();
  console.log('[JamYT] Receiver started');
})();
