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
         * Application ID del Cast Receiver.
         *
         * Valor por defecto: `CC1AD845` = Default Media Receiver (compartido
         * por todas las apps Cast del mundo). Reproduce MP4/HLS pero NO
         * YouTube URLs.
         *
         * Para reproducir YouTube, sustituir por el `APP_ID` propio
         * registrado en Cast Developer Console tras subir el Custom Receiver
         * de `receiver/index.html` a GitHub Pages. Ver `receiver/README.md`
         * sección 3 para el flujo de registro.
         *
         * Mientras el valor siga siendo `CC1AD845` o cualquier placeholder,
         * la app casteará contra el DMR y los videos de YouTube serán
         * rechazados con `idleReason=4 ERROR` (esperado hasta tener el
         * Custom Receiver registrado).
         *
         * Valor público: cualquier capa del sender (CastManager, UI, MediaRouteSelector)
         * lo usa para filtrar con `categoryForCast(APP_ID)`.
         */
        const val APP_ID: String =
            "XXXXXXXX" // PLACEHOLDER — sustituir por el APP_ID real tras registrar en Cast Console.
    }
}
