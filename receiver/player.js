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
      // Mostrar diagnóstico tanto en consola como en el overlay de pantalla.
      console.warn(
        '%c[JamYT Receiver]',
        'background:#e0a;color:white;padding:2px 6px;border-radius:3px;font-weight:bold',
        'CAF (Cast Application Framework) no está disponible.',
      );
      console.warn(
        '[JamYT Receiver] Causa probable: abriste la URL del receiver en un ' +
          'navegador normal, sin contexto Cast.',
      );

      try {
        const detail = document.getElementById('jamyt-status-detail');
        const header = document.getElementById('jamyt-status-header');
        const wrap = document.getElementById('jamyt-status');
        if (wrap) wrap.classList.add('error');
        if (header) {
          header.textContent =
            '[JamYT] CAF no detectado — abre esta URL desde un dispositivo Cast o con Cast Debugger';
        }
        if (detail) {
          const ts = new Date().toLocaleTimeString();
          detail.innerHTML = '';
          const lines = [
            'typeof cast === "undefined" (o sin framework completo)',
            'Causa probable: abriste la URL en un navegador sin contexto Cast.',
            '',
            'Cómo validar en navegador: instala la extensión "Cast Debugger"',
            'para Chrome, actívala y luego carga esta URL.',
            '',
            'Cómo validar en TV: castea desde la app Android hacia el TV.',
            '',
            'Load more info: revisa console.warn de arriba para detalles.',
          ];
          lines.forEach((l) => {
            const el = document.createElement('div');
            el.className = 'jamyt-line ' + (l.startsWith('typeof') || l.startsWith('Causa') ? 'err' : 'warn');
            el.textContent = '[' + ts + '] ' + l;
            detail.appendChild(el);
          });
        }
      } catch (_) {}

      return;
    }

    console.log(
      '%c[JamYT Receiver]',
      'background:#0a7;color:white;padding:2px 6px;border-radius:3px;font-weight:bold',
      'CAF detectado, inicializando receiver…',
    );

    // Escribir al overlay también
    try {
      const header = document.getElementById('jamyt-status-header');
      if (header) header.textContent = '[JamYT] CAF detectado, iniciando…';
      const detail = document.getElementById('jamyt-status-detail');
      if (detail) {
        const line = document.createElement('div');
        line.className = 'jamyt-line boot';
        line.textContent = '[' + new Date().toLocaleTimeString() + '] CAF detectado';
        detail.appendChild(line);
      }
    } catch (_) {}

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
    /* ---- Logging helpers (consola + overlay en pantalla) ---- */
    let overlayHidden = false;

    function setOverlayHeader(text, isError) {
      try {
        const el = document.getElementById('jamyt-status-header');
        const wrap = document.getElementById('jamyt-status');
        if (el) el.textContent = '[JamYT] ' + text;
        if (wrap && isError) wrap.classList.add('error');
      } catch (_) {}
    }

    function appendOverlayLine(text, level) {
      try {
        const detail = document.getElementById('jamyt-status-detail');
        if (!detail) return;
        const line = document.createElement('div');
        line.className = 'jamyt-line ' + (level || '');
        const ts = new Date().toLocaleTimeString();
        line.textContent = '[' + ts + '] ' + text;
        detail.appendChild(line);
        detail.scrollTop = detail.scrollHeight;
      } catch (_) {}
    }

    function showOverlayHint(text) {
      try {
        const el = document.getElementById('jamyt-status-hint');
        if (el) el.textContent = text;
      } catch (_) {}
    }

    function hideOverlay() {
      try {
        const el = document.getElementById('jamyt-status');
        if (el && !overlayHidden) {
          overlayHidden = true;
          el.classList.add('hidden');
        }
      } catch (_) {}
    }

    function log(msg) {
      console.log('[JamYT] ' + msg);
      setOverlayHeader(msg, false);
      appendOverlayLine(msg, 'boot');
    }
    function logOk(msg) {
      console.log('[JamYT] OK  ' + msg);
      setOverlayHeader(msg, false);
      appendOverlayLine('✓ ' + msg, 'ok');
    }
    function logError(msg, e) {
      try {
        console.error('[JamYT] ERROR ' + msg, e && (e.stack || e.message || e));
        const errText = '✗ ' + msg + (e ? ' → ' + (e.message || e) : '');
        setOverlayHeader(errText, true);
        appendOverlayLine(errText, 'err');
      } catch (_) {}
    }

    /* ---- Versión + arranque ---- */
    log('v1.2 startReceiver() begin');

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
      logOk('CastReceiverContext.getInstance() OK');
    } catch (e) {
      logError('CastReceiverContext.getInstance() FAILED', e);
      return;
    }

    try {
      playerManager = context.getPlayerManager();
      logOk('PlayerManager obtained');
    } catch (e) {
      logError('getPlayerManager() FAILED', e);
      return;
    }

    /* ====================================================================
     * 2. Registrar setMediaElementRequestHandler ANTES de context.start()
     * ==================================================================== */

    try {
      playerManager.setMediaElementRequestHandler((loadRequestData) => {
        const media = loadRequestData.media;
        const contentId = media && media.contentId;
        const videoId = parseVideoId(contentId);

        if (!videoId) {
          log('contentId inválido: ' + contentId);
          return null;
        }

        logOk('media request → videoId=' + videoId + ' autoplay=' + loadRequestData.autoplay);
        loadOrUpdateYtPlayer(videoId);

        mediaElement.currentTime = 0;
        mediaElement.duration = 0;
        stopTicking();
        mediaElement.paused = true;

        return mediaElement;
      });
      logOk('setMediaElementRequestHandler registered');
    } catch (e) {
      logError('setMediaElementRequestHandler FAILED', e);
      return;
    }

    /* ====================================================================
     * 3. CRÍTICO: context.start(options)
     *    Si esta llamada falla o nunca se ejecuta, el TV timeout 60s (2473).
     * ==================================================================== */

    try {
      const options = new cast.framework.CastOptions();
      options.playbackConfig = new cast.framework.PlaybackConfig();
      context.start(options);
      logOk('context.start(options) OK — receiver is now Cast-ready');
      showOverlayHint(
        'OK — receiver disponible. La pantalla se ocultará en 8s. Si ves ' +
        'este mensaje más de 60s sin que avance, el TV no está aceptando ' +
        'nuestro receiver (probable: dispositivo en estado "Ready" no ' +
        '"Active" en Cast Console, o URL del receiver inaccesible desde el TV).'
      );
      setTimeout(hideOverlay, 8000);
    } catch (e) {
      logError(
        'context.start(options) FAILED — el TV hará timeout (60s) y la sesión morirá',
        e,
      );
      return;
    }

    /* ====================================================================
     * 4. YouTube IFrame API en background (post-start).
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
