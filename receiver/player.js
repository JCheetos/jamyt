/* =====================================================================
 * JamYT — Custom Cast Receiver (CAF + YouTube IFrame Player)
 * v1.1 (con logging defensivo y CastOptions explícitas)
 *
 * Responsabilidades:
 *   1. CAF nos entrega "media requests" (load, queueLoad, next, etc.).
 *   2. Por cada media request, extraemos el videoId YouTube del contentId
 *      (formato acordado: "ytvideo:<videoId>") y se lo pasamos al IFrame Player
 *      de YouTube que se inyecta en #player.
 *   3. Mantenemos sincronizado el estado de CAF (paused, ended, currentTime)
 *      con lo que reporta el IFrame Player. Sin este puente, CAF no sabe
 *      cuándo avanzar al siguiente item de la cola.
 *   4. Reportamos errores al sender via PlayerManager.
 *
 * Por qué no usar el Default Media Receiver (DMR):
 *   El DMR recibe el MediaInfo, intenta reproducir el URL y reporta
 *   IDLE/ERROR (idleReason=4) cuando el URL no es un stream directo.
 *   YouTube usa URLs firmadas que requieren extracción previa. El DMR
 *   no sabe hacerlo. Por eso necesitamos un receiver que entienda
 *   el formato "ytvideo:<videoId>" y use YouTube IFrame Player.
 *
 * Orden de boot (CRÍTICO para no reproducir el síntoma de "60s negro"):
 *   1. CAF detection guard (init)
 *   2. cast.framework.CastReceiverContext.getInstance()
 *   3. playerManager.setMediaElementRequestHandler(...)
 *   4. CastOptions + context.start(options) ← si esto FALLA, el TV hace timeout
 *   5. YouTube IFrame API (asíncrono; ya no bloquea el boot del receiver)
 * ===================================================================== */

(function () {
  'use strict';

  /* ====================================================================
   * Boot: espera a que la página esté cargada y verifica que CAF esté
   * presente antes de iniciar. Si no está, mostramos un diagnóstico útil.
   * ==================================================================== */

  function init() {
    // CAF (window.cast.framework.CastReceiverContext) solo expone su API
    // completa cuando la página corre en un dispositivo Cast (Chromecast,
    // Google TV, Android TV) o cuando un Cast Debugger simula ese contexto.
    if (
      typeof cast === 'undefined' ||
      !cast.framework ||
      !cast.framework.CastReceiverContext
    ) {
      console.warn(
        '%c[JamYT Receiver]',
        'background:#e0a;color:white;padding:2px 6px;border-radius:3px;font-weight:bold',
        'CAF (Cast Application Framework) no está disponible.',
      );
      console.warn(
        '[JamYT Receiver] Causa probable: abriste la URL del receiver en un ' +
          'navegador normal, sin contexto Cast. La API `cast.framework.*` solo ' +
          'se expone dentro de un dispositivo Cast o simulando el contexto.',
      );
      console.warn(
        '[JamYT Receiver] Cómo validar en navegador: instala la extensión ' +
          '"Cast Debugger" o "Google Cast Debugger" para Chrome, actívala y ' +
          'luego carga esta URL.',
      );
      console.warn(
        '[JamYT Receiver] Cómo validar en TV: castea desde la app Android ' +
          'hacia el TV (Chromecast / Google TV).',
      );
      return;
    }

    console.log(
      '%c[JamYT Receiver]',
      'background:#0a7;color:white;padding:2px 6px;border-radius:3px;font-weight:bold',
      'CAF detectado, inicializando receiver…',
    );

    startReceiver();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    // DOMContentLoaded ya disparó (script cargado tarde); ejecutar ahora.
    init();
  }

  /* ====================================================================
   * Lógica real del receiver
   * ==================================================================== */
  function startReceiver() {
    /* ---- Logging helpers ---- */
    function log(msg) {
      console.log('[JamYT] ' + msg);
    }
    function logError(msg, e) {
      console.error('[JamYT] ERROR ' + msg, e && (e.stack || e.message || e));
    }

    log('v1.1 startReceiver() begin');

    /* ---- Estado (declarado aquí para que las closures lo alcancen) ---- */
    let ytPlayer = null;
    let ytApiReady = false;
    let currentVideoId = null;
    let ticking = false;
    let tickLastMs = 0;
    let playerManager = null;

    /* ---- "Fake media element" que CAF usa para reportar estado ---- */
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

    /* ====================================================================
     * 1. CAF bootstrap
     * ==================================================================== */

    let context;
    try {
      context = cast.framework.CastReceiverContext.getInstance();
      log('cast.framework.CastReceiverContext.getInstance() OK');
    } catch (e) {
      logError('CastReceiverContext.getInstance() FAILED', e);
      return;
    }

    try {
      playerManager = context.getPlayerManager();
      log('PlayerManager obtained');
    } catch (e) {
      logError('getPlayerManager() FAILED', e);
      return;
    }

    /* ====================================================================
     * 2. Registrar setMediaElementRequestHandler ANTES de context.start()
     *    (CAF revisa este handler cuando hay un media request entrante).
     * ==================================================================== */

    try {
      playerManager.setMediaElementRequestHandler((loadRequestData) => {
        const media = loadRequestData.media;
        const contentId = media && media.contentId;
        const videoId = parseVideoId(contentId);

        if (!videoId) {
          log('contentId inválido: ' + contentId);
          // Devolver null hace que CAF muestre el estado de error estándar.
          return null;
        }

        log('Solicitado videoId: ' + videoId + ' (autoplay=' + loadRequestData.autoplay + ')');
        loadOrUpdateYtPlayer(videoId);

        // Reset del "fake" media element para el nuevo item
        mediaElement.currentTime = 0;
        mediaElement.duration = 0;
        stopTicking();
        mediaElement.paused = true;

        return mediaElement;
      });
      log('setMediaElementRequestHandler registered');
    } catch (e) {
      logError('setMediaElementRequestHandler FAILED', e);
      return;
    }

    /* ====================================================================
     * 3. CRÍTICO: context.start(options)
     *
     *    Si esta llamada falla o nunca se ejecuta, el TV se queda esperando
     *    60+ segundos y termina con error 2473. Por eso va con try/catch
     *    DETALLADO y se hace ANTES de cargar YouTube IFrame API (no podemos
     *    permitir que un fallo de YT bloquee el anuncio del receiver).
     * ==================================================================== */

    try {
      const options = new cast.framework.CastOptions();
      options.playbackConfig = new cast.framework.PlaybackConfig();
      context.start(options);
      log('====== context.start(options) OK — receiver is now Cast-ready ======');
    } catch (e) {
      logError('context.start(options) FAILED — el TV hará timeout (60s) y la sesión morirá', e);
      return;
    }

    /* ====================================================================
     * 4. Ahora que el receiver es Cast-ready, cargamos YouTube IFrame API
     *    en background. Si YT falla, el receiver sigue operativo para
     *    comandos del sender; los items no se reproducirán, pero podemos
     *    diagnosticar el problema sin afectar la sesión.
     * ==================================================================== */

    log('Cargando YouTube IFrame API (async, no bloqueante)...');
    loadYouTubeIFrameApi();

    /* ====================================================================
     * Funciones auxiliares
     * ==================================================================== */

    function onYtStateChange(event) {
      try {
        const state = event.data;
        log('YT state: ' + state);

        if (!ytPlayer) return;

        switch (state) {
          case window.YT_PLAYING:
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
            log('→ ENDED: CAF avanzará al siguiente item');
            break;

          case window.YT_CUED:
          case window.YT_UNSTARTED:
            break;
        }
      } catch (e) {
        logError('onYtStateChange FAILED', e);
      }
    }

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

      try {
        if (ytPlayer && currentVideoId === videoId) {
          ytPlayer.playVideo();
          return;
        }

        if (ytPlayer) {
          ytPlayer.loadVideoById(videoId);
          currentVideoId = videoId;
          return;
        }

        // Crear instancia inicial.
        ytPlayer = new window.YT.Player('player', {
          width: '100%',
          height: '100%',
          videoId: videoId,
          playerVars: {
            autoplay: 1,
            controls: 0,
            disablekb: 1,
            fs: 0,
            modestbranding: 1,
            rel: 0,
            showinfo: 0,
            iv_load_policy: 3,
            playsinline: 1,
          },
          events: {
            onReady: () => log('YT Player ready: ' + videoId),
            onStateChange: onYtStateChange,
            onError: (e) => {
              logError('YT error code: ' + (e && e.data), null);
              mediaElement.dispatchEvent(new Event('error'));
            },
          },
        });
        currentVideoId = videoId;
      } catch (e) {
        logError('loadOrUpdateYtPlayer FAILED for videoId=' + videoId, e);
      }
    }

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

    function parseVideoId(contentId) {
      if (!contentId || typeof contentId !== 'string') return null;

      let m = contentId.match(/^ytvideo:([\w-]{6,20})$/);
      if (m) return m[1];

      m = contentId.match(/[?&]v=([\w-]{6,20})/);
      if (m) return m[1];

      if (/^[\w-]{6,20}$/.test(contentId)) return contentId;

      return null;
    }

    function loadYouTubeIFrameApi() {
      try {
        const tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        const firstScriptTag = document.getElementsByTagName('script')[0];
        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
        log('YouTube IFrame API script injected');
      } catch (e) {
        logError('Fallo al inyectar script de YouTube IFrame API', e);
        return;
      }

      // YouTube llama a esta función global cuando la API está lista.
      window.onYouTubeIframeAPIReady = function () {
        try {
          ytApiReady = true;
          if (!window.YT || !window.YT.PlayerState) {
            logError('YT o YT.PlayerState undefined dentro de onYouTubeIframeAPIReady', null);
            return;
          }
          window.YT_PLAYING = window.YT.PlayerState.PLAYING;
          window.YT_PAUSED = window.YT.PlayerState.PAUSED;
          window.YT_BUFFERING = window.YT.PlayerState.BUFFERING;
          window.YT_CUED = window.YT.PlayerState.CUED;
          window.YT_UNSTARTED = window.YT.PlayerState.UNSTARTED;
          window.YT_ENDED = window.YT.PlayerState.ENDED;
          log('YouTube IFrame API ready');
        } catch (e) {
          logError('onYouTubeIframeAPIReady FAILED', e);
        }
      };
    }
  }
})();
