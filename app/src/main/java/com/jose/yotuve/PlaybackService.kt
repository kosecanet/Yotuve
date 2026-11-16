package com.jose.yotuve

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Un único reproductor para toda la app: lo usan la pantalla y el servicio de segundo plano. */
object PlayerHolder {
    private var player: ExoPlayer? = null
    var loadedId: String? = null
    var loadedUrl: String? = null   // calidad de vídeo cargada ahora mismo
    fun get(ctx: Context): ExoPlayer = player ?: PlayerCache.newPlayer(ctx.applicationContext).also { player = it }
    fun current(): ExoPlayer? = player
    fun clear() { player = null; loadedId = null }
}

/**
 * Servicio de reproducción: mantiene el audio con la pantalla bloqueada o la app en segundo plano
 * y muestra los controles en la notificación y en la pantalla de bloqueo.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, PlayerHolder.get(this)).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Si cierras la app desde recientes, se para la música. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let { it.stop(); it.clearMediaItems() }
        stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        PlayerHolder.clear()
        super.onDestroy()
    }
}
