package com.jose.yotuve

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/** ExoPlayer con caché en disco (500 MB LRU): volver a ver un vídeo no gasta datos. */
@OptIn(UnstableApi::class)
object PlayerCache {
    private var cache: SimpleCache? = null

    private fun cache(ctx: Context): SimpleCache = cache ?: SimpleCache(
        File(ctx.cacheDir, "video"),
        LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024),
        StandaloneDatabaseProvider(ctx),
    ).also { cache = it }

    private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36"

    /** [ua]: el mismo User-Agent con el que se pidieron los enlaces (YouTube lo comprueba). */
    private fun factory(ctx: Context, ua: String? = null): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua ?: CHROME_UA)
            .setAllowCrossProtocolRedirects(true)
        return CacheDataSource.Factory()
            .setCache(cache(ctx))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Clave de caché: vídeo + formato (itag) + tamaño (clen). Si YouTube sirve otro fichero del mismo
     * vídeo, no se mezclan trozos de ficheros distintos (eso dejaba la imagen en negro).
     */
    fun cacheKey(videoId: String, url: String, kind: String): String {
        val u = android.net.Uri.parse(url)
        return "$videoId-$kind-${u.getQueryParameter("itag")}-${u.getQueryParameter("clen")}"
    }

    /**
     * Precarga: descarga a la caché los primeros segundos (≈1,5 MB de vídeo y 400 KB de audio)
     * para que al tocar el vídeo empiece casi al instante. Bloqueante: llamar en segundo plano.
     */
    fun precache(ctx: Context, videoId: String, s: Streams, videoUrl: String) {
        fun warm(url: String, kind: String, bytes: Long) = runCatching {
            val spec = androidx.media3.datasource.DataSpec.Builder().setUri(url)
                .setKey(cacheKey(videoId, url, kind)).setPosition(0).setLength(bytes).build()
            androidx.media3.datasource.cache.CacheWriter(factory(ctx, s.ua).createDataSource(), spec, null, null).cache()
        }
        warm(videoUrl, "v", 1_500_000)
        s.audio?.let { warm(it, "a", 400_000) }
    }

    fun newPlayer(ctx: Context): ExoPlayer {
        // Búfer corto para empezar a reproducir antes
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 50_000, 700, 1_500).build()
        val audio = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build()
        return ExoPlayer.Builder(ctx).setLoadControl(lc)
            .setAudioAttributes(audio, true)              // pausa si entra una llamada u otra app
            .setHandleAudioBecomingNoisy(true)            // pausa al quitar los auriculares
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK) // sigue descargando con la pantalla apagada
            .build()
    }

    /** Clave de caché estable por vídeo (las URLs de googlevideo caducan y cambian). */
    fun source(ctx: Context, video: Video, s: Streams, videoUrl: String = s.video): MediaSource {
        val videoId = video.id
        val f = ProgressiveMediaSource.Factory(factory(ctx, s.ua))
        // Título, canal y miniatura para la notificación y la pantalla de bloqueo
        val meta = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(video.title).setArtist(video.channel)
            .setArtworkUri(android.net.Uri.parse(video.thumb)).build()
        fun item(url: String, key: String) = MediaItem.Builder().setUri(url).setCustomCacheKey(key)
            .setMediaId(key).setMediaMetadata(meta).build()
        // La clave incluye el formato (itag) y el tamaño (clen): si YouTube sirve otro fichero del
        // mismo vídeo, no se mezclan trozos de ficheros distintos en la caché (eso dejaba la imagen en negro)
        fun key(url: String, kind: String) = cacheKey(videoId, url, kind)
        val v = f.createMediaSource(item(videoUrl, key(videoUrl, "v")))
        return if (s.audio != null)
            MergingMediaSource(v, f.createMediaSource(item(s.audio, key(s.audio, "a"))))
        else v
    }
}
