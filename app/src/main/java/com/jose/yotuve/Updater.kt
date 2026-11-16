package com.jose.yotuve

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Autoactualización desde las Releases de github.com/kosecanet/Yotuve.
 * Al arrancar comprueba si hay versión nueva; con wifi la descarga sola y luego pide instalar
 * (Android siempre pide confirmar la instalación de una app que no viene de la Play Store).
 */
object Updater {
    private const val API = "https://api.github.com/repos/kosecanet/Yotuve/releases/latest"

    sealed class State {
        data class Available(val version: Int, val url: String, val sizeMb: Int) : State()
        data class Downloading(val version: Int, val progress: Float) : State()
        data class Ready(val version: Int, val file: File) : State()
    }

    var state by mutableStateOf<State?>(null)

    private fun currentVersion(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    suspend fun check(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "yotuve")
                connectTimeout = 8000; readTimeout = 8000
            }
            if (c.responseCode != 200) return@withContext   // repositorio privado o sin releases
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val version = Regex("""\d+""").find(j.optString("tag_name"))?.value?.toIntOrNull() ?: return@withContext
            if (version <= currentVersion(ctx)) return@withContext
            // APK adecuado para este móvil (arm64 normalmente); si no, el universal
            val assets = j.getJSONArray("assets")
            val list = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk") }
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val a = list.firstOrNull { abi.isNotEmpty() && abi in it.optString("name") }
                ?: list.firstOrNull { "universal" in it.optString("name") } ?: list.firstOrNull() ?: return@withContext
            state = State.Available(version, a.getString("browser_download_url"), (a.optLong("size") / 1_048_576).toInt())
            // Con wifi (red sin límite de datos) se descarga ya, sin preguntar
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            if (!cm.isActiveNetworkMetered) download(ctx)
        } catch (_: Exception) { }
    }

    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        val av = state as? State.Available ?: return@withContext
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "yotuve-${av.version}.apk")
            state = State.Downloading(av.version, 0f)
            val c = (URL(av.url).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "yotuve") }
            val total = c.contentLengthLong.coerceAtLeast(1)
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n); done += n
                        state = State.Downloading(av.version, done.toFloat() / total)
                    }
                }
            }
            state = State.Ready(av.version, out)
        } catch (_: Exception) { state = av }
    }

    /** Lanza el instalador de Android (la primera vez pide permitir instalar desde yotuve). */
    fun install(ctx: Context) {
        val r = state as? State.Ready ?: return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", r.file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
