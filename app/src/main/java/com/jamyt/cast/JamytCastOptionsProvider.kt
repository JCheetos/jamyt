package com.jamyt.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Proveedor de opciones invocado por `CastContext.getSharedInstance(context, executor)`
 * cuando está declarado en el manifest via la meta-data `OPTIONS_PROVIDER_CLASS_NAME`.
 *
 * Por qué existe: sin este provider, el SDK v22.x resuelve `getSharedInstance`
 * a opciones por defecto **sin APP_ID válido** (o ignorando `setReceiverApplicationId`
 * programático). Resultado: el sistema establece una sesión de tipo *generic
 * remote playback* en lugar de una `CastSession` real. En esa sesión genérica:
 *  - volume funciona (es operación del transport).
 *  - `queueLoad()` / `load()` no llegan a ningún receiver (no hay app Cast
 *    lanzada en el TV con el APP_ID que el sender pide).
 *  - El `SessionManagerListener<CastSession>` nunca dispara `onSessionStarted`
 *    porque **no existe `CastSession`** para esa sesión — por eso el
 *    `CastDebugPanel` quedaba en `DISCONNECTED` aunque "el TV está enlazado".
 *
 * Con este provider + `CastMediaControlIntent.categoryForCast(APP_ID)` en el
 * `MediaRouteSelector`, la sesión resultante es una `CastSession` ligada al
 * `APP_ID`. Eso desbloquea el ciclo de callbacks `onSessionStarted /
 * onSessionEnded / RemoteMediaClient.Callback`.
 *
 * APP_ID por defecto = `DEFAULT_MEDIA_RECEIVER_APPLICATION_ID` (no requiere
 * registro en Cast Developer Console, pero **no puede reproducir URLs de
 * YouTube firmadas**). Cuando entremos a Plan C, sustituye este `APP_ID`
 * por el del Custom Receiver registrado en la consola.
 */
class JamytCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(APP_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /**
         * Application ID del Cast Receiver (registrado en Cast Developer Console).
         *
         * Origen del valor: tras subir el Custom Receiver de `receiver/index.html`
         * a GitHub Pages y registrarlo en <https://cast.google.com/publish/>, Google
         * devuelve este APP_ID único. Apunta a nuestra URL Pages; el Cast SDK
         * enruta el tráfico al receiver propio, NO al Default Media Receiver.
         *
         * Si por alguna razón necesitas revertir al DMR (p.ej. para diagnosticar),
         * cambia este valor a `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`
         * = `"CC1AD845"`. Los videos de YouTube dejarán de funcionar (DMR los
         * rechaza con `idleReason=4`), pero el resto del pipeline Cast sigue
         * funcionando.
         *
         * Valor público: cualquier capa del sender (CastManager, UI, MediaRouteSelector)
         * lo usa para filtrar con `categoryForCast(APP_ID)`.
         */
        const val APP_ID: String = "70729FA3"
    }
}
