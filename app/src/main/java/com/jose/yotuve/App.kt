package com.jose.yotuve

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Guarda cualquier cierre inesperado para mostrarlo en el siguiente arranque
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile(this).writeText(android.util.Log.getStackTraceString(e)) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        scope.launch { kotlinx.coroutines.delay(3000); Updater.check(this@App) }   // ¿hay versión nueva de yotuve?
        // Deja abierta la conexión con YouTube (DNS + cifrado): el primer vídeo arranca antes
        scope.launch {
            runCatching {
                (java.net.URL("https://www.youtube.com/generate_204").openConnection() as java.net.HttpURLConnection)
                    .apply { connectTimeout = 4000; readTimeout = 4000 }.inputStream.close()
            }
        }
        scope.launch {
            try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                ready = true          // se puede usar ya; la actualización no bloquea el arranque
            } catch (e: Throwable) {
                Log.e("yotuve", "init yt-dlp", e)
                initError = Log.getStackTraceString(e)
                setStatus("Error iniciando yt-dlp"); return@launch
            }
            // Comprobar actualización de yt-dlp como mucho cada 12 h, y no nada más abrir
            val prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong("ytdlp_check", 0) > 12 * 3600_000L) {
                kotlinx.coroutines.delay(20_000)
                updateNow()
            }
        }
    }

    /** Actualiza yt-dlp esperando a que no haya ninguna ejecución en marcha. */
    suspend fun updateNow() {
        exclusive { autoUpdate() }
        getSharedPreferences("ajustes", MODE_PRIVATE).edit().putLong("ytdlp_check", System.currentTimeMillis()).apply()
        if (status != null) { kotlinx.coroutines.delay(4000); setStatus(null) }
    }

    /** Actualiza yt-dlp en segundo plano en cada arranque (estable; si falla, nightly). */
    private fun autoUpdate() {
            setStatus("Buscando actualizaciones…")
            val msg = try {
                when (YoutubeDL.getInstance().updateYoutubeDL(this@App, YoutubeDL.UpdateChannel.STABLE)) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp actualizado a ${YoutubeDL.getInstance().versionName(this@App)}"
                    else -> null // ya estaba al día: no molestamos
                }
            } catch (e: Throwable) {
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(this@App, YoutubeDL.UpdateChannel.NIGHTLY); null
                } catch (_: Throwable) { Log.w("yotuve", "update", e); null }
            }
            setStatus(msg)
    }

    companion object {
        lateinit var instance: App
        @Volatile var ready = false
        /** Mensaje breve que la barra superior muestra mientras se actualiza. */
        var status by mutableStateOf<String?>(null)
        @Volatile var initError: String? = null
        /**
         * Varias ejecuciones de yt-dlp pueden ir a la vez (cada una toma un permiso);
         * la actualización los toma todos, así nunca se reemplaza el fichero mientras se usa.
         */
        private const val PERMITS = 16
        val gate = kotlinx.coroutines.sync.Semaphore(PERMITS)
        suspend fun <T> exclusive(block: () -> T): T {
            repeat(PERMITS) { gate.acquire() }
            try { return block() } finally { repeat(PERMITS) { gate.release() } }
        }
        @Volatile private var lastForced = 0L
        /** Si YouTube ha cambiado y yt-dlp falla, fuerza una actualización (como mucho cada 10 min). */
        suspend fun forceUpdateIfStale(): Boolean {
            if (System.currentTimeMillis() - lastForced < 600_000) return false
            lastForced = System.currentTimeMillis()
            instance.updateNow(); return true
        }
        fun crashFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "ultimo_error.txt")
        /** Los cambios de estado de Compose siempre en el hilo principal. */
        fun setStatus(s: String?) = android.os.Handler(android.os.Looper.getMainLooper()).post { status = s }
    }
}
