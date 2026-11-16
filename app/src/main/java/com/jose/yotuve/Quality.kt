package com.jose.yotuve

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.C

/** Elige la calidad de vídeo para ahorrar batería y datos sin que se note. */
object Quality {
    /**
     * Altura en píxeles que realmente ocupa el vídeo:
     * - ventana flotante: pequeña → 360p
     * - pantalla completa: el lado corto de la pantalla (hasta 1080p)
     * - normal (vertical): ancho de pantalla × 9/16 (p. ej. 1080 px de ancho → ~608 → 720p)
     * Con datos móviles se limita a 480p en vertical y 720p en pantalla completa.
     */
    fun target(ctx: Context, fullscreen: Boolean, pip: Boolean): Int {
        val dm = ctx.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val metered = runCatching { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)
        return when {
            pip -> 360
            fullscreen -> minOf(shortSide, if (metered) 720 else 1080)
            else -> minOf(shortSide * 9 / 16, if (metered) 480 else 720)
        }
    }

    /** Activa/desactiva la pista de vídeo (sin pantalla solo hace falta el audio). */
    fun videoEnabled(on: Boolean) {
        val p = PlayerHolder.current() ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on).build()
    }
}
