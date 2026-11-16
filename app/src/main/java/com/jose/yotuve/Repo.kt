package com.jose.yotuve

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val duration: Long?,      // segundos
    val views: Long?,
    val thumb: String,
    val timestamp: Long? = null,   // fecha de subida (segundos Unix; aproximada en listas)
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
}

/** "hace 3 días", "hace 2 meses"… */
fun fmtAgo(ts: Long?): String? {
    if (ts == null || ts <= 0) return null
    val s = System.currentTimeMillis() / 1000 - ts
    return when {
        s < 3600 -> "hace ${maxOf(1, s / 60)} min"
        s < 86400 -> "hace ${s / 3600} h"
        s < 7 * 86400 -> (s / 86400).let { "hace $it ${if (it == 1L) "día" else "días"}" }
        s < 30 * 86400 -> (s / (7 * 86400)).let { "hace $it ${if (it == 1L) "semana" else "semanas"}" }
        s < 365 * 86400 -> (s / (30 * 86400)).let { "hace $it ${if (it == 1L) "mes" else "meses"}" }
        else -> (s / (365 * 86400)).let { "hace $it ${if (it == 1L) "año" else "años"}" }
    }
}

/** Streams separados de vídeo y audio (YouTube ya no da >360p progresivo). */
/** Un formato de vídeo disponible (solo imagen) con su altura en píxeles. */
data class VFormat(val height: Int, val url: String)

/**
 * Streams separados de vídeo y audio (YouTube ya no da >360p progresivo).
 * [videos] son todas las calidades H.264 disponibles, para cambiar de calidad sin volver a llamar a yt-dlp.
 */
data class Streams(val video: String, val audio: String?, val title: String, val videos: List<VFormat> = emptyList()) {
    /** El formato más pequeño que cubra [target] píxeles de alto (o el mayor si ninguno llega). */
    fun videoFor(target: Int): String {
        if (videos.isEmpty() || audio == null) return video
        return (videos.filter { it.height >= target }.minByOrNull { it.height } ?: videos.maxBy { it.height }).url
    }
}

object Repo {

    private suspend fun waitReady() {
        while (!App.ready) {
            App.initError?.let { error("yt-dlp no pudo iniciarse:\n$it") }
            delay(100)
        }
    }

    /**
     * Ejecuta yt-dlp.
     * - [yt]: argumentos extra para YouTube; se juntan con el idioma en UN solo --extractor-args
     *   (si se pasan varios para "youtube", el último pisa a los anteriores).
     * - [timeoutMs]: si YouTube no responde en ese tiempo se mata el proceso (nada se queda cargando infinito).
     * - Si el fichero de yt-dlp está dañado, lo reinstala y reintenta.
     * - Si YouTube dice que el vídeo "no está disponible", reintenta con otros clientes de YouTube.
     */
    private suspend fun exec(req: YoutubeDLRequest, id: String? = null, cb: ((Float, Long, String) -> Unit)? = null,
                             retried: Boolean = false, yt: String = "", timeoutMs: Long = 60_000,
                             altClient: Boolean = false): com.yausername.youtubedl_android.YoutubeDLResponse {
        req.addOption("--cache-dir", File(App.instance.cacheDir, "yt-dlp").absolutePath)
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "15")
        val lang = java.util.Locale.getDefault().language.ifBlank { "es" }
        val extra = listOf("lang=$lang", yt, if (altClient) "player_client=web_embedded,tv,web_safari,mweb" else "")
            .filter { it.isNotBlank() }.joinToString(";")
        req.addOption("--extractor-args", "youtube:$extra")
        // Solo en el reintento: intérprete de JavaScript (QuickJS) para los clientes web de YouTube,
        // necesarios para vídeos "para niños" o con restricciones. En el resto no se usa (más rápido).
        if (altClient) qjsPath()?.let {
            req.addOption("--js-runtimes", "quickjs:$it")
            req.addOption("--remote-components", "ejs:github")
        }
        req.addOption("--add-header", "Accept-Language:$lang-${java.util.Locale.getDefault().country.ifBlank { "ES" }},$lang;q=0.9")
        val pid = id ?: java.util.UUID.randomUUID().toString()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = if (timeoutMs > 0) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            delay(timeoutMs); timedOut.set(true)
            runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
        } else null
        App.gate.acquire()
        val r = try {
            Result.success(YoutubeDL.getInstance().execute(req, pid, cb))
        } catch (e: Exception) {
            val m = e.message ?: ""
            if ("ZipImportError" in m || "bad local file header" in m || "zipimport" in m) {
                // Fichero de yt-dlp dañado: reinstalar el incluido en la app y reintentar
                val ctx = App.instance
                File(ctx.noBackupFilesDir, "youtubedl-android/yt-dlp").deleteRecursively()
                File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles()
                    ?.filter { it.name.contains("youtubedl", true) }?.forEach { it.delete() }
                YoutubeDL.getInstance().init(ctx)
                App.setStatus("yt-dlp reparado")
                runCatching { YoutubeDL.getInstance().execute(req, pid, cb) }
            } else Result.failure(e)
        } finally { App.gate.release(); watchdog?.cancel() }
        r.exceptionOrNull()?.let { e ->
            if (timedOut.get()) throw Exception("YouTube tarda demasiado en responder. Inténtalo de nuevo.")
            val m = e.message ?: ""
            // "Video unavailable / This content isn't available": probar con otros clientes de YouTube
            if (!altClient && id == null && unavailable(m))
                return exec(req, id, cb, retried, yt, timeoutMs, altClient = true)
            // Error de YouTube (cambió algo): actualizar yt-dlp y reintentar una vez
            if (!retried && id == null && ("[youtube]" in m || "HTTP Error 403" in m) && App.forceUpdateIfStale())
                return exec(req, id, cb, retried = true, yt = yt, timeoutMs = timeoutMs, altClient = altClient)
            throw Exception(friendly(m))
        }
        return r.getOrThrow()
    }

    /** Ruta del intérprete QuickJS incluido en la app (si está compilado para este móvil). */
    private fun qjsPath(): String? =
        File(App.instance.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.exists() }?.absolutePath

    private fun unavailable(m: String) = "navailable" in m || "isn't available" in m || "not available" in m ||
        "no está disponible" in m || "no disponible" in m

    /** Mensaje de error comprensible a partir del de yt-dlp. */
    private fun friendly(m: String): String {
        val line = m.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: m.take(200)
        return when {
            "Sign in to confirm your age" in m || "age" in line.lowercase() && "confirm" in line.lowercase() ->
                "Este vídeo tiene restricción de edad y YouTube pide iniciar sesión."
            "Private video" in m -> "Es un vídeo privado."
            "members-only" in m || "Join this channel" in m -> "Vídeo solo para miembros del canal."
            "not available in your country" in m -> "Este vídeo no está disponible en tu país."
            "try again later" in m || "rate" in m.lowercase() && "limit" in m.lowercase() ->
                "YouTube está limitando las peticiones. Espera un poco y vuelve a intentarlo."
            unavailable(m) -> (if (qjsPath() == null) "Falta el intérprete JavaScript (QuickJS) en esta versión; " else "") +
                "YouTube no deja reproducir este vídeo. Detalle: $line"
            else -> line
        }
    }

    suspend fun execPublic(req: YoutubeDLRequest, yt: String = "", timeoutMs: Long = 60_000) =
        exec(req, yt = yt, timeoutMs = timeoutMs)

    suspend fun execPublic(req: YoutubeDLRequest) = exec(req)

    /** Sugerencias del buscador: el mismo endpoint que usa YouTube. */
    suspend fun suggestions(q: String): List<String> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        try {
            val u = "https://suggestqueries-clients6.youtube.com/complete/search?client=firefox&ds=yt&hl=es&gl=ES&q=" +
                URLEncoder.encode(q, "UTF-8")
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(txt).getJSONArray(1)
            List(arr.length()) { arr.getString(it) }.take(10)
        } catch (e: Exception) { emptyList() }
    }

    private fun parse(e: JSONObject): Video? {
        val id = e.optString("id").takeIf { it.length == 11 } ?: return null // solo vídeos, no canales/listas
        if (e.optString("ie_key") == "YoutubeTab") return null
        return Video(
            id = id,
            title = e.optString("title"),
            channel = e.optString("channel", e.optString("uploader", "")).let { if (it == "null") "" else it },
            duration = e.optDouble("duration").takeIf { !it.isNaN() }?.toLong(),
            views = e.optLong("view_count", -1).takeIf { it >= 0 },
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            timestamp = tsOf(e),
        )
    }

    /** Fecha de subida en segundos (timestamp, o upload_date AAAAMMDD). */
    fun tsOf(e: JSONObject): Long? {
        e.optLong("timestamp", 0).takeIf { it > 0 }?.let { return it }
        e.optLong("release_timestamp", 0).takeIf { it > 0 }?.let { return it }
        val d = e.optString("upload_date").takeIf { it.length == 8 } ?: return null
        return runCatching {
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(d)!!.time / 1000
        }.getOrNull()
    }

    /** Una sola ejecución de yt-dlp para varias fuentes (búsquedas y/o mixes); devuelve vídeos por fuente. */
    private suspend fun fetch(urls: List<String>, perUrl: Int, start: Int = 1): List<List<Video>> = withContext(Dispatchers.IO) {
        // Búsquedas ("ytsearchN:texto"): directas a YouTube, sin Python y con fecha de subida
        val searchRx = Regex("""^ytsearch(\d*):(.*)$""")
        if (start == 1 && urls.any { searchRx.matches(it) }) {
            val results = arrayOfNulls<List<Video>>(urls.size)
            urls.forEachIndexed { i, u ->
                searchRx.find(u)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: perUrl
                    results[i] = runCatching { Innertube.search(m.groupValues[2]).take(minOf(n, perUrl)) }.getOrNull()
                }
            }
            // Lo que no sea búsqueda (o haya fallado) va por yt-dlp en una sola ejecución
            val rest = urls.indices.filter { results[it] == null }
            if (rest.isNotEmpty()) {
                val r = fetchYtDlp(rest.map { urls[it] }, perUrl, start)
                rest.forEachIndexed { k, i -> results[i] = r.getOrNull(k) ?: emptyList() }
            }
            return@withContext results.map { it ?: emptyList() }
        }
        fetchYtDlp(urls, perUrl, start)
    }

    private suspend fun fetchYtDlp(urls: List<String>, perUrl: Int, start: Int = 1): List<List<Video>> = withContext(Dispatchers.IO) {
        waitReady()
        fun req(u: List<String>) = YoutubeDLRequest(u).apply {
            addOption("--flat-playlist"); addOption("-j")
            addOption("--playlist-items", "$start-${start + perUrl - 1}")   // paginado
            addOption("--extractor-args", "youtubetab:approximate_date")      // fecha de subida aproximada
            addOption("--no-warnings")
        }
        fun lines(out: String) = out.lines().filter { it.startsWith("{") }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        try {
            // Todas a la vez (más rápido: Python arranca una vez). Agrupamos por la lista de origen.
            val all = lines(exec(req(urls)).out)
            if (urls.size == 1) return@withContext listOf(all.mapNotNull(::parse))
            val groups = all.groupBy { it.optString("playlist_webpage_url", it.optString("playlist", "")) }.values
            groups.map { g -> g.mapNotNull(::parse) }
        } catch (e: Exception) {
            if (urls.size == 1) throw e
            // Si una fuente falla (p. ej. un vídeo sin mix), probamos una a una ignorando las que fallen
            urls.map { u -> runCatching { lines(exec(req(listOf(u))).out).mapNotNull(::parse) }.getOrDefault(emptyList()) }
        }
    }

    /** Búsqueda rápida con yt-dlp (--flat-playlist: no resuelve cada vídeo). */
    suspend fun search(q: String, count: Int = 20, start: Int = 1): List<Video> {
        runCatching { Innertube.search(q, next = start > 1) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return fetchYtDlp(listOf("ytsearch${start + count - 1}:$q"), count, start).flatten().distinctBy { it.id }
    }

    /** URL del "Mix" automático de YouTube para un vídeo: son las recomendaciones de YouTube. */
    private fun mix(id: String) = "https://www.youtube.com/watch?v=$id&list=RD$id"

    /** Palabras clave del título para buscar vídeos del mismo tema en otros canales. */
    private fun keywords(v: Video): String {
        val stop = setOf("para", "como", "este", "esta", "pero", "with", "from", "that", "this", "the", "and",
            "your", "sobre", "desde", "todo", "todos", "video", "vídeo", "oficial", "official", "parte", "capitulo", "capítulo")
        val chan = v.channel.lowercase().split(" ").toSet()
        return v.title.replace(Regex("""#\S+|\[.*?]|\(.*?\)|[|:\-–—"'¡!¿?.,]"""), " ")
            .split(Regex("""\s+""")).map { it.trim() }
            .filter { it.length > 3 && !it.any(Char::isDigit) && it.lowercase() !in stop && it.lowercase() !in chan }
            .take(5).joinToString(" ")
    }

    /**
     * Variedad: como mucho [max] vídeos por canal y repartidos (no seguidos).
     * Si queda poco, se completa con los que sobraban.
     */
    private fun diversify(list: List<Video>, max: Int, min: Int = 12): List<Video> {
        val count = HashMap<String, Int>(); val kept = ArrayList<Video>(); val extra = ArrayList<Video>()
        for (v in list) {
            val k = v.channel.lowercase().ifBlank { v.id }
            val n = count.getOrDefault(k, 0)
            if (n < max) { kept += v; count[k] = n + 1 } else extra += v
        }
        // Evitar dos del mismo canal seguidos
        val out = ArrayList<Video>(); val pool = kept.toMutableList()
        while (pool.isNotEmpty()) {
            val last = out.lastOrNull()?.channel
            val i = pool.indexOfFirst { it.channel != last || it.channel.isBlank() }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(i)
        }
        return if (out.size >= min) out else out + extra.take(min - out.size)
    }

    /**
     * Prioriza lo más nuevo: cada vídeo baja un puesto por cada ~5 días de antigüedad
     * (sin fecha conocida cuenta como de hace 2 meses). Así lo reciente sube arriba sin perder relevancia.
     */
    private fun rankFresh(list: List<Video>): List<Video> {
        val now = System.currentTimeMillis() / 1000
        return list.withIndex().sortedBy { (i, v) ->
            val days = v.timestamp?.let { ((now - it) / 86400.0).coerceAtLeast(0.0) } ?: 60.0
            i + days / 5.0
        }.map { it.value }
    }

    /** Relacionados: Mix de YouTube + mismo tema en otros canales; máx. 3 del mismo canal. */
    suspend fun related(v: Video): List<Video> {
        val kw = keywords(v)
        val urls = buildList {
            add(mix(v.id))
            if (kw.isNotBlank()) add("ytsearch12:$kw")
            if (v.channel.isNotBlank()) add("ytsearch4:${v.channel}")
        }
        return diversify(rankFresh(interleave(fetch(urls, 20)).filter { it.id != v.id }), max = 3)
    }

    /**
     * Inicio personalizado: Mixes de 3 vídeos al azar de los últimos que has visto, temas de lo que ves
     * en otros canales, un canal favorito y algo de lo más popular; máx. 2 por canal y sin repetir lo visto.
     */
    suspend fun home(history: List<Video>): List<Video> {
        if (history.isEmpty()) return diversify(search("tendencias", 15), max = 2).take(10)
        val recent = history.take(10).shuffled().take(3)
        val topics = history.take(8).shuffled().take(2).map(::keywords).filter { it.isNotBlank() }
        val channel = history.map { it.channel }.filter { it.isNotBlank() }.distinct().take(3).shuffled().firstOrNull()
        val urls = recent.map { mix(it.id) } + topics.map { "ytsearch8:$it" } +
            listOfNotNull(channel?.let { "ytsearch5:$it" }) + "ytsearch8:vídeos populares hoy"
        val seen = history.map { it.id }.toSet()
        val feed = diversify(rankFresh(interleave(fetch(urls, 8)).filter { it.id !in seen }), max = 2, min = 10).take(10)
        return feed.ifEmpty { search("tendencias", 10) }
    }

    /** 10 Shorts relacionados con lo que ves (temas y canales del historial), de hasta 3 minutos. */
    suspend fun shorts(history: List<Video>): List<Video> {
        val topics = history.take(8).shuffled().take(2).map(::keywords).filter { it.isNotBlank() }
        val channel = history.map { it.channel }.filter { it.isNotBlank() }.distinct().take(3).shuffled().firstOrNull()
        val urls = topics.map { "ytsearch8:$it #shorts" } + listOfNotNull(channel?.let { "ytsearch6:$it #shorts" })
        var list = if (urls.isEmpty()) emptyList()
            else diversify(rankFresh(interleave(fetch(urls, 8)).filter { (it.duration ?: 999) <= 180 }), max = 2, min = 10)
        if (list.size < 5) list = list + search("#shorts", 15).filter { (it.duration ?: 999) <= 180 }
        return list.distinctBy { it.id }.take(10)
    }

    /** Intercala las listas (1º de cada una, 2º de cada una…) como el inicio de YouTube. */
    private fun interleave(lists: List<List<Video>>): List<Video> {
        val out = LinkedHashMap<String, Video>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
        return out.values.toList()
    }

    private val infoCache = HashMap<String, Pair<Long, Pair<Streams, Details>>>()

    /**
     * Una sola ejecución de yt-dlp da los enlaces para reproducir y la descripción/canal.
     * Se guarda en memoria 3 h (los enlaces de YouTube caducan a las ~6 h): reabrir un vídeo es instantáneo.
     */
    /**
     * Enlaces para reproducir + datos del vídeo, lo más rápido posible:
     * - En paralelo, los datos (descripción, fecha, canal…) se piden directamente a YouTube por HTTP (sin Python).
     * - Los enlaces se sacan con yt-dlp en modo RÁPIDO: un solo cliente (android_vr) y sin descargar la página
     *   web ni manifiestos. Si falla, se hace la extracción completa normal (y, si hace falta, otros clientes).
     * Se guarda en memoria 3 h (los enlaces de YouTube caducan a las ~6 h): reabrir un vídeo es instantáneo.
     */
    suspend fun info(v: Video, maxHeight: Int = 1080): Pair<Streams, Details> = withContext(Dispatchers.IO) {
        synchronized(infoCache) { infoCache[v.id] }?.let { (t, r) ->
            if (System.currentTimeMillis() - t < 3 * 3600_000L) return@withContext r
        }
        waitReady()
        val det = async { runCatching { Innertube.details(v) }.getOrNull() }
        val ext = try { extract(v, maxHeight, fast = true) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null } ?: extract(v, maxHeight, fast = false)
        val r = ext.first to merge(det.await(), ext.second)
        synchronized(infoCache) { infoCache[v.id] = System.currentTimeMillis() to r }
        r
    }

    /**
     * Extracción completa (todos los clientes de YouTube): se usa en segundo plano cuando el modo rápido
     * solo dio la calidad básica (360p), para subir de calidad sin cortar.
     */
    suspend fun upgrade(v: Video, maxHeight: Int = 1080): Streams? = withContext(Dispatchers.IO) {
        val st = runCatching { extract(v, maxHeight, fast = false).first }.getOrNull() ?: return@withContext null
        if (st.audio == null) return@withContext null
        synchronized(infoCache) { infoCache[v.id]?.let { (t, r) -> infoCache[v.id] = t to (st to r.second) } }
        st
    }

    /** Une los datos de la web (más completos) con los de yt-dlp (respaldo). */
    private fun merge(web: Details?, yt: Details): Details {
        if (web == null) return yt
        return web.copy(
            description = web.description.ifBlank { yt.description },
            likes = web.likes ?: yt.likes, date = web.date ?: yt.date,
            channelUrl = web.channelUrl ?: yt.channelUrl, followers = web.followers ?: yt.followers,
            commentCount = web.commentCount ?: yt.commentCount, channelId = web.channelId ?: yt.channelId,
            timestamp = web.timestamp ?: yt.timestamp, views = web.views ?: yt.views,
            channel = web.channel.ifBlank { yt.channel },
        )
    }

    private suspend fun extract(v: Video, maxHeight: Int, fast: Boolean): Pair<Streams, Details> {
        val req = YoutubeDLRequest(v.url).apply {
            addOption("-J")
            // H.264 (avc1) primero: todos los móviles lo decodifican por hardware. AV1/VP9 en muchos
            // móviles se decodifican por software y gastan muchísima más batería.
            addOption("-f", "bv*[height<=$maxHeight][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$maxHeight][ext=mp4]+ba[ext=m4a]/b[height<=$maxHeight]/b")
            addOption("--no-playlist")
        }
        val yt = if (fast) "player_client=android_vr;player_skip=webpage,configs;skip=translated_subs,hls,dash"
            else "skip=translated_subs"
        val j = JSONObject(exec(req, yt = yt, timeoutMs = if (fast) 25_000 else 45_000).out)
        val rf = j.optJSONArray("requested_formats")
        // Todas las calidades H.264 de solo vídeo (144p…1080p) para adaptar la calidad al tamaño
        val all = j.optJSONArray("formats")
        val videos = (0 until (all?.length() ?: 0)).map { all!!.getJSONObject(it) }
            .filter { it.optString("vcodec").startsWith("avc1") && it.optString("acodec") == "none" &&
                it.optString("protocol").startsWith("http") && it.optInt("height") in 144..1080 && it.has("url") }
            .groupBy { it.optInt("height") }
            .map { (h, l) -> VFormat(h, l.maxBy { it.optDouble("tbr", 0.0) }.getString("url")) }
            .sortedBy { it.height }
        val st = if (rf != null && rf.length() > 0)
            Streams(rf.getJSONObject(0).getString("url"), rf.optJSONObject(1)?.optString("url"), v.title, videos)
        else Streams(j.getString("url"), null, v.title)
        return st to detailsFrom(j, v, emptyList())
    }

    /** Olvida los enlaces guardados de un vídeo (caducados o que han dado error). */
    fun invalidate(id: String) { synchronized(infoCache) { infoCache.remove(id) } }

    suspend fun streams(v: Video, maxHeight: Int = 720): Streams = info(v, maxHeight).first

    /**
     * URL para la tele: un único stream con vídeo y audio juntos (Chromecast no puede unir
     * pistas separadas). Prioriza HLS hasta 1080p; si no, el mp4 de 360p.
     */
    suspend fun castUrl(v: Video): String = withContext(Dispatchers.IO) {
        waitReady()
        val req = YoutubeDLRequest(v.url).apply {
            addOption("-f", "b[protocol^=m3u8][height<=1080]/18/b[acodec!=none][vcodec!=none]")
            addOption("-g"); addOption("--no-playlist"); addOption("--no-warnings")
        }
        exec(req).out.lines().first { it.startsWith("http") }
    }

    /**
     * Descarga con yt-dlp + ffmpeg (mezcla vídeo+audio en mp4).
     * Devuelve el fichero local (carpeta privada de la app, compartible con FileProvider)
     * y además lo copia a Descargas/MiTube para que se vea en la galería/archivos.
     */
    suspend fun download(ctx: Context, v: Video, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        waitReady()
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "").apply { mkdirs() }
        existing(ctx, v)?.let { return@withContext it }
        val req = YoutubeDLRequest(v.url).apply {
            addOption("-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/bv*[height<=1080]+ba/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--restrict-filenames")
            addOption("-o", "${dir.absolutePath}/%(title).80s [${v.id}].%(ext)s")
        }
        exec(req, v.id, cb = { p: Float, _: Long, _: String -> onProgress(p / 100f) })
        val f = existing(ctx, v) ?: error("No se encontró el fichero descargado")
        publishToDownloads(ctx, f)
        f
    }

    /** Mata el proceso de yt-dlp de esa descarga y borra los ficheros a medias. */
    fun cancelDownload(ctx: Context, id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?.listFiles { f -> f.name.contains("[$id]") && f.extension != "mp4" }
            ?.forEach { it.delete() }
        // Por si ffmpeg ya había creado el mp4 incompleto
        ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?.listFiles { f -> f.name.contains("[$id]") }?.forEach { it.delete() }
    }

    fun existing(ctx: Context, v: Video): File? =
        ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?.listFiles { f -> f.name.endsWith("[${v.id}].mp4") }
            ?.firstOrNull()

    private fun publishToDownloads(ctx: Context, f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/yotuve")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "yotuve")
                d.mkdirs(); f.copyTo(File(d, f.name), overwrite = true)
            }
        } catch (_: Exception) { }
    }

    /** Extrae el id de un enlace compartido (youtu.be, watch?v=, shorts/). */
    fun idFromLink(text: String): String? =
        Regex("""(?:v=|youtu\.be/|shorts/|embed/)([\w-]{11})""").find(text)?.groupValues?.get(1)
}
