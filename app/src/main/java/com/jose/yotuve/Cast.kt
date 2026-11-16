package com.jose.yotuve

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Configuración de Google Cast: receptor multimedia estándar de Chromecast / Google TV. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

@OptIn(UnstableApi::class)
object CastHelper {
    var player: CastPlayer? = null
        private set

    /** Prepara Cast (si el móvil tiene Google Play Services). Llamar en el hilo principal. */
    fun init(ctx: Context, listener: SessionAvailabilityListener) {
        if (player != null) return
        player = runCatching { CastPlayer(CastContext.getSharedInstance(ctx.applicationContext)) }
            .getOrNull()?.also { it.setSessionAvailabilityListener(listener) }
    }

    val isCasting get() = player?.isCastSessionAvailable == true

    /** Diálogo para elegir la tele o el Chromecast de la red. */
    fun chooseDevice(ctx: Context): Boolean = runCatching {
        val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        MediaRouteChooserDialog(themed).apply {
            routeSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
        }.show()
    }.isSuccess

    fun disconnect(ctx: Context) {
        runCatching { CastContext.getSharedInstance(ctx.applicationContext).sessionManager.endCurrentSession(true) }
    }

    /** Envía un vídeo a la tele empezando en [posMs]. */
    fun load(v: Video, url: String, posMs: Long) {
        val p = player ?: return
        val mime = if (".m3u8" in url || "/manifest/hls" in url) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4
        val item = MediaItem.Builder().setUri(url).setMimeType(mime).setMediaId(v.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setArtist(v.channel)
                .setArtworkUri(android.net.Uri.parse(v.thumb)).build())
            .build()
        p.setMediaItem(item, posMs)
        p.prepare()
        p.playWhenReady = true
    }

    /** Alternativa sin Chromecast: duplicar la pantalla del móvil (Miracast / Smart View). */
    fun openScreenMirroring(ctx: Context) {
        val intents = listOf(Intent(Settings.ACTION_CAST_SETTINGS), Intent("android.settings.WIFI_DISPLAY_SETTINGS"))
        for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
