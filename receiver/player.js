/* =====================================================================
 * JamYT — Custom Cast Receiver v1.4
 *
 * Modelo v3 CAF + custom messages:
 *   - CAF expone `cast.framework.CastReceiverContext.getInstance()` en la TV.
 *   - NO usamos `setMediaElementRequestHandler` (eliminado en v3).
 *   - Para YouTube usamos la ruta mensajes custom:
 *       1. La app Android llama `castSession.sendMessage(NAMESPACE, json)`
 *          con `{"op":"loadQueue","items":[...]}`.
 *       2. El receiver registra `addCustomMessageListener(NAMESPACE, handler)`
 *          y procesa cada `op` (loadQueue/play/pause/next/prev).
 *       3. El receiver crea YouTube IFrame Player con `youtube.com/embed/<id>`.
 *       4. Cuando YT dispara ENDED, el receptor auto-avanza al siguiente item.
 *   - El receptor reporta status al sender vía `sendCustomMessage(NAMESPACE)`.
 *
 * Stack:
 *   <cast-media-player class="castMediaElement"> (hidden, requerido por v3)
 *     └── aunque exista, no se usa para nada; toda la lógica del reproductor
 *         pasa por mensajes custom y la API de YouTube IFrame Player.
 *
 * Namespace: 'urn:x-cast:com.jamyt.cola' (debe coincidir con CastManager.kt).
 * ===================================================================== */

(function () {
  'use strict';

  /** Custom namespace para mensajes entre sender Android y este receiver. */
  const NAMESPACE = 'urn:x-cast:com.jamyt.cola';

  /* ====================================================================
   * Boot
   * ==================================================================== */

  function init() {
    if (
      typeof cast === 'undefined' ||
      !cast.framework ||
      !cast.framework.CastReceiverContext
    ) {
      // Mostrar diagnóstico tanto en consola como en overlay de pantalla.
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
          const lines = [
            'Causa: abriste la URL en un navegador normal.',
            '',
            'Validar en navegador: extensión Cast Debugger para Chrome.',
            'Validar en TV: castea desde la app Android hacia el TV.',
          ];
          lines.forEach((l) => {
            const el = document.createElement('div');
            el.className = 'jamyt-line ' + (l.startsWith('Causa') ? 'err' : 'warn');
            el.textContent = l;
            detail.appendChild(el);
          });
        }
      } catch (_) {}
      return;
    }

    console.log(
      '%c[JamYT Receiver]',
      'background:#0a7;color:white;padding:2px 6px;border-radius:3px;font-weight:bold',
      'CAF detectado, iniciando receiver…',
    );

    try {
      const header = document.getElementById('jamyt-status-header');
      if (header) header.textContent = '[JamYT] CAF detectado, iniciando…';
    } catch (_) {}

    startReceiver();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }

  /* ====================================================================
   * Receiver
   * ==================================================================== */
  function startReceiver() {
    /* ---- Logging helpers (consola + overlay) ---- */
    let overlayHidden = false;
    function overlayHeader(text, isError) {
      try {
        const el = document.getElementById('jamyt-status-header');
        const wrap = document.getElementById('jamyt-status');
        if (el) el.textContent = '[JamYT] ' + text;
        if (wrap && isError) wrap.classList.add('error');
      } catch (_) {}
    }
    function overlayLine(text, level) {
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
    function overlayHint(text) {
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
    function logOk(msg) {
      console.log('[JamYT] OK  ' + msg);
      overlayHeader(msg, false);
      overlayLine('✓ ' + msg, 'ok');
    }
    function log(msg) {
      console.log('[JamYT] ' + msg);
      overlayHeader(msg, false);
      overlayLine(msg, 'boot');
    }
    function logError(msg, e) {
      try {
        const err = msg + (e ? ' → ' + (e.message || e) : '');
        console.error('[JamYT] ERROR ' + err, e && e.stack);
        overlayHeader('✗ ' + err, true);
        overlayLine(err, 'err');
      } catch (_) {}
    }

    overlayLine('v1.4 startReceiver() begin', 'boot');

    /* ---- Estado del receiver ---- */
    let ytPlayer = null;
    let ytApiReady = false;
    let queue = [];            // cola actual [{videoId, title}, ...]
    let currentIndex = -1;
    let context = null;

    /* ====================================================================
     * 1. CastReceiverContext.getInstance()  (v3 requiere <cast-media-player>)
     * ==================================================================== */

    try {
      context = cast.framework.CastReceiverContext.getInstance();
      logOk('CastReceiverContext.getInstance() OK');
    } catch (e) {
      logError('CastReceiverContext.getInstance() FAILED', e);
      return;
    }

    let playerManager;
    try {
      playerManager = context.getPlayerManager();
      logOk('PlayerManager obtained');
    } catch (e) {
      logError('getPlayerManager() FAILED', e);
      return;
    }

    /* ====================================================================
     * 2. addCustomMessageListener(namespace, handler)
     *
     * ⚠️ CRÍTICO: debe llamarse ANTES de context.start(). Después de start,
     * el sistema no acepta nuevos namespaces (error "New namespaces can not
     * be requested after start has been called").
     * ==================================================================== */

    try {
      context.addCustomMessageListener(NAMESPACE, (message) => {
        // message: cast.framework.system.Message
        // message.data es lo que el sender envió (String JSON en nuestro caso)
        const data = message.data;
        let obj = null;
        try {
          obj = (typeof data === 'string') ? JSON.parse(data) : data;
        } catch (e) {
          logError('Message JSON parse failed: ' + e.message, null);
          sendStatus({ op: 'status', event: 'error', detail: 'json_parse_failed' });
          return;
        }
        if (!obj || !obj.op) {
          logError('Empty/unknown message: ' + JSON.stringify(obj), null);
          return;
        }
        handleCommand(obj, playerManager);
      });
      logOk('addCustomMessageListener registered for ' + NAMESPACE);
    } catch (e) {
      logError('addCustomMessageListener FAILED', e);
      return;
    }

    /* ====================================================================
     * 3. start(options) — anunciar el receiver al framework Cast
     * ==================================================================== */

    try {
      const options = new cast.framework.CastReceiverOptions();
      // disableIdleTimeout: con el playback siendo YouTube IFrame (no media
      // Cast directo), el framework cuenta 5 minutos de inactividad y cierra
      // la sesión (`error=2055`). El Cast framework pide que solo se use
      // para apps non-media, que es exactamente nuestro caso (el playback
      // real va por IFrame; nada de comandos CAF de media).
      options.disableIdleTimeout = true;
      // No necesitamos un PlayerManager custom; el <cast-media-player>
      // existe en el DOM pero no lo usamos (display:none). El cast framework
      // solo requiere su presencia para inicializarse.
      context.start(options);
      logOk('context.start(options) OK — receiver is now Cast-ready (disableIdleTimeout=true)');
      overlayHint(
        'Receptor listo. Auto-hide en 6s. Si recibes mensajes del sender, ' +
        'verás: load, play, pause, status events.',
      );
      setTimeout(hideOverlay, 6000);
    } catch (e) {
      logError('context.start FAILED', e);
      return;
    }

    /* ====================================================================
     * 4. Cargar YouTube IFrame API
     * ==================================================================== */

    log('Cargando YouTube IFrame API (post-start)...');
    loadYouTubeIFrameApi();

    /* ====================================================================
     * Manejo de comandos del sender
     * ==================================================================== */

    function handleCommand(cmd, pm) {
      const op = cmd.op;
      log('Op: ' + op);
      switch (op) {
        case 'loadQueue': {
          const items = Array.isArray(cmd.items) ? cmd.items : [];
          const newQueue = items
            .map((it) => ({
              videoId: (it && it.videoId) ? String(it.videoId) : '',
              title: (it && it.title) ? String(it.title) : '',
            }))
            .filter((it) => it.videoId.length > 0);

          // Update inteligente: si el videoId actualmente en reproducción
          // sigue existiendo en la nueva cola, mantén el currentIndex apuntándolo
          // y NO reinicies la reproducción. Si no sigue, carga startIndex.
          let preserveIndex = -1;
          if (
            ytPlayer && ytApiReady && currentIndex >= 0 &&
            currentIndex < queue.length && queue[currentIndex]
          ) {
            const currentVideoId = queue[currentIndex].videoId;
            preserveIndex = newQueue.findIndex((it) => it.videoId === currentVideoId);
          }

          queue = newQueue;

          if (preserveIndex >= 0) {
            currentIndex = preserveIndex;
            log('loadQueue: videoId actual conservado en idx=' + currentIndex +
              ' (no recargamos, seguimos reproduciendo)');
          } else {
            const startIdx = (typeof cmd.startIndex === 'number') ? cmd.startIndex : 0;
            currentIndex = (queue.length > 0)
              ? Math.max(0, Math.min(queue.length - 1, startIdx))
              : -1;
            if (currentIndex >= 0 && queue[currentIndex]) {
              log('loadQueue: arrancando desde idx=' + currentIndex +
                ' (videoId=' + queue[currentIndex].videoId + ')');
            }
          }

          sendStatus({
            op: 'queue',
            size: queue.length,
            index: currentIndex,
            videoId: (currentIndex >= 0 && queue[currentIndex])
              ? queue[currentIndex].videoId : '',
          });

          if (preserveIndex >= 0) {
            // No hacer nada: YouTube IFrame sigue con su videoId actual.
          } else if (currentIndex >= 0 && queue[currentIndex]) {
            loadYouTubePlayer(queue[currentIndex].videoId);
          } else {
            log('loadQueue: queue vacía; nada que reproducir');
          }
          break;
        }
        case 'play':
          if (ytPlayer && typeof ytPlayer.playVideo === 'function') {
            try { ytPlayer.playVideo(); } catch (e) { logError('yt playVideo', e); }
          }
          break;
        case 'pause':
          if (ytPlayer && typeof ytPlayer.pauseVideo === 'function') {
            try { ytPlayer.pauseVideo(); } catch (e) { logError('yt pauseVideo', e); }
          }
          break;
        case 'next':
          if (queue.length === 0) break;
          currentIndex = (currentIndex + 1) % queue.length;
          sendStatus({ op: 'queue', size: queue.length, index: currentIndex, videoId: queue[currentIndex].videoId });
          loadYouTubePlayer(queue[currentIndex].videoId);
          break;
        case 'prev':
          if (queue.length === 0) break;
          currentIndex = (currentIndex - 1 + queue.length) % queue.length;
          sendStatus({ op: 'queue', size: queue.length, index: currentIndex, videoId: queue[currentIndex].videoId });
          loadYouTubePlayer(queue[currentIndex].videoId);
          break;
        case 'stop':
          if (ytPlayer && typeof ytPlayer.stopVideo === 'function') {
            try { ytPlayer.stopVideo(); } catch (_) {}
          }
          queue = [];
          currentIndex = -1;
          sendStatus({ op: 'status', event: 'stopped' });
          break;
        default:
          log('Op desconocida: ' + op);
      }
    }

    function sendStatus(payload) {
      try {
        const str = JSON.stringify(payload);
        // senderId = undefined → broadcast a todos los senders conectados.
        context.sendCustomMessage(NAMESPACE, undefined, str);
      } catch (e) {
        logError('sendCustomMessage failed', e);
      }
    }

    /* ====================================================================
     * YouTube IFrame Player
     * ==================================================================== */

    function loadYouTubePlayer(videoId) {
      if (!ytApiReady) {
        waitForYt(() => loadYouTubePlayer(videoId));
        return;
      }
      log('YT → cargando videoId=' + videoId);
      try {
        if (!ytPlayer) {
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
              onReady: () => {
                log('YT ready: ' + videoId);
                sendStatus({ op: 'yt_ready', videoId: videoId, index: currentIndex });
              },
              onStateChange: onYtStateChange,
              onError: (e) => {
                logError('YT error code: ' + (e && e.data), null);
                sendStatus({ op: 'status', event: 'error', detail: 'yt_' + (e && e.data) });
              },
            },
          });
        } else {
          ytPlayer.loadVideoById(videoId);
        }
      } catch (e) {
        logError('loadYouTubePlayer FAILED for ' + videoId, e);
      }
    }

    function waitForYt(cb) {
      let attempts = 0;
      const interval = setInterval(() => {
        attempts++;
        if (ytApiReady) { clearInterval(interval); cb(); return; }
        if (attempts > 200) { clearInterval(interval); logError('YouTube IFrame API no apareció tras 10s', null); }
      }, 50);
    }

    function onYtStateChange(event) {
      try {
        const state = event.data;
        log('YT state: ' + state);
        switch (state) {
          case window.YT_PLAYING:
            sendStatus({ op: 'status', event: 'playing', index: currentIndex, videoId: queue[currentIndex] && queue[currentIndex].videoId });
            break;
          case window.YT_PAUSED:
            sendStatus({ op: 'status', event: 'paused', index: currentIndex });
            break;
          case window.YT_BUFFERING:
            sendStatus({ op: 'status', event: 'buffering', index: currentIndex });
            break;
          case window.YT_ENDED:
            log('→ YT ENDED: auto-advance');
            // Incluimos videoId en el evento ended para que el sender (VM) pueda
            // quitar el item exacto de la cola sin asumir FIFO — relevante en
            // escenarios con varios peers modificando la cola concurrentemente.
            const endedVideoId = (currentIndex >= 0 && queue[currentIndex])
              ? queue[currentIndex].videoId : '';
            sendStatus({ op: 'status', event: 'ended', index: currentIndex, videoId: endedVideoId });
            // Auto-advance dentro del mismo queue
            if (queue.length > 0) {
              const nextIdx = (currentIndex + 1) % queue.length;
              currentIndex = nextIdx;
              sendStatus({ op: 'queue', size: queue.length, index: currentIndex, videoId: queue[currentIndex].videoId });
              loadYouTubePlayer(queue[currentIndex].videoId);
            }
            break;
        }
      } catch (e) {
        logError('onYtStateChange FAILED', e);
      }
    }

    function loadYouTubeIFrameApi() {
      try {
        const tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        const firstScriptTag = document.getElementsByTagName('script')[0];
        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
        log('YouTube IFrame API script injected');
      } catch (e) {
        logError('Fallo al inyectar YouTube IFrame API', e);
        return;
      }
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
