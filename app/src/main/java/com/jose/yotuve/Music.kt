package com.jose.yotuve

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Una canción de la lista. [id] = vídeo de YouTube (null hasta encontrarla, p. ej. si viene de Spotify). */
data class Track(
    val id: String?, val title: String, val artist: String, val duration: Long?,
    val thumb: String? = id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
)

/**
 * Modo música: solo sonido, con una lista (cola) que se puede importar de Spotify o YouTube, ampliar y reordenar.
 * Usa el mismo reproductor que los vídeos (así sigue sonando con la pantalla bloqueada y con controles en la
 * notificación). Entre canciones hace fundido: la que acaba baja el volumen y la siguiente entra subiéndolo.
 */
@OptIn(UnstableApi::class)
object Music {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var queue by mutableStateOf<List<Track>>(emptyList())
    var index by mutableStateOf(-1)
    var active by mutableStateOf(false)      // el reproductor está en modo música
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(0)          // 0 no, 1 toda la lista, 2 una canción
    var status by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    val current get() = queue.getOrNull(index)
    /** Lo pone la app: al empezar la música se cierra el vídeo que hubiera. */
    var onStart: (() -> Unit)? = null
    private const val FADE_MS = 3000L

    // ---------- Guardar / cargar la lista
    private fun prefs() = App.instance.getSharedPreferences("musica", Context.MODE_PRIVATE)

    fun load() {
        runCatching {
            val a = JSONArray(prefs().getString("cola", "[]"))
            queue = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Track(o.optString("id").ifBlank { null }, o.optString("t"), o.optString("a"),
                    o.optLong("d").takeIf { it > 0 })
            }
            index = prefs().getInt("i", if (queue.isEmpty()) -1 else 0).coerceIn(-1, queue.size - 1)
            shuffle = prefs().getBoolean("shuffle", false); repeat = prefs().getInt("repeat", 0)
        }
    }

    private fun save() {
        val a = JSONArray()
        queue.forEach { a.put(JSONObject().put("id", it.id ?: "").put("t", it.title).put("a", it.artist).put("d", it.duration ?: 0)) }
        prefs().edit().putString("cola", a.toString()).putInt("i", index)
            .putBoolean("shuffle", shuffle).putInt("repeat", repeat).apply()
    }

    // ---------- Editar la lista
    fun add(t: Track) { queue = queue + t; if (index < 0) index = 0; save() }
    fun addAll(list: List<Track>) { queue = queue + list; if (index < 0 && queue.isNotEmpty()) index = 0; save() }
    fun clear() { stop(); queue = emptyList(); index = -1; save() }
    fun remove(i: Int) {
        if (i !in queue.indices) return
        val wasCurrent = i == index
        queue = queue.toMutableList().also { it.removeAt(i) }
        if (i < index) index-- else if (wasCurrent) { index = index.coerceAtMost(queue.size - 1); if (active) playAt(index) }
        save()
    }
    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        val l = queue.toMutableList(); val t = l.removeAt(from); l.add(to, t); queue = l
        index = when (index) { from -> to; in minOf(from, to)..maxOf(from, to) -> if (from < to) index - 1 else index + 1; else -> index }
        save(); preloadNext()
    }

    // ---------- Reproducción
    private var watcher: Job? = null
    private var listenerAdded = false
    private var fadingOut = false
    private var preloaded: Pair<Int, androidx.media3.exoplayer.source.MediaSource>? = null

    private fun player(): ExoPlayer = PlayerHolder.get(App.instance)

    /** Busca en YouTube la canción si aún no tiene vídeo (p. ej. viene de Spotify). */
    private suspend fun resolve(i: Int): Track? {
        val t = queue.getOrNull(i) ?: return null
        if (t.id != null) return t
        val found = runCatching { Innertube.search("${t.artist} ${t.title} audio") }.getOrDefault(emptyList())
        val best = found.firstOrNull { v -> t.duration == null || v.duration == null || kotlin.math.abs(v.duration - t.duration) <= 20 }
            ?: found.firstOrNull() ?: return null
        val r = t.copy(id = best.id, thumb = best.thumb, duration = t.duration ?: best.duration)
        if (queue.getOrNull(i) == t) { queue = queue.toMutableList().also { it[i] = r }; save() }
        return r
    }

    /** Solo el audio de la canción (menos datos y batería que el vídeo). */
    private suspend fun source(i: Int): androidx.media3.exoplayer.source.MediaSource? {
        val t = resolve(i) ?: return null
        val v = Video(t.id!!, t.title, t.artist, t.duration, null, t.thumb ?: "")
        val st = runCatching { Repo.fastStreams(v) }.getOrNull() ?: return null
        val url = st.audio ?: st.video
        val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist)
            .setArtworkUri(t.thumb?.let { android.net.Uri.parse(it) }).build()
        return PlayerCache.audioSource(App.instance, "music-${t.id}", url, st.ua,
            MediaItem.Builder().setUri(url).setMediaId("music-${t.id}").setMediaMetadata(meta))
    }

    private fun nextIndex(from: Int = index): Int? {
        if (queue.isEmpty()) return null
        if (shuffle && queue.size > 1) return (queue.indices - from).random()
        return if (from + 1 < queue.size) from + 1 else if (repeat == 1) 0 else null
    }

    /** Empieza a sonar la canción [i] (con fundido de entrada). */
    fun playAt(i: Int) {
        if (i !in queue.indices) return
        index = i; save()
        if (!active) onStart?.invoke()
        active = true; loading = true; status = null
        scope.launch {
            val p = player()
            ensureListener(p)
            if (p.isPlaying) fade(p, 0f, 400)
            val src = preloaded?.takeIf { it.first == i }?.second ?: source(i)
            preloaded = null
            loading = false
            if (index != i) return@launch
            if (src == null) { status = "No se encontró \"${queue.getOrNull(i)?.title}\""; next(); return@launch }
            PlayerHolder.loadedId = "music"; PlayerHolder.loadedUrl = null
            Quality.videoEnabled(false)
            p.repeatMode = if (repeat == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.volume = 0f
            p.setMediaSource(src); p.prepare(); p.play()
            fadingOut = false
            fade(p, 1f, 1200)
            startWatcher(); preloadNext()
        }
    }

    /** Prepara la siguiente canción mientras suena la actual: el cambio es inmediato. */
    private fun preloadNext() {
        val n = nextIndex() ?: return
        if (preloaded?.first == n) return
        scope.launch { source(n)?.let { if (nextIndex() == n || shuffle) preloaded = n to it } }
    }

    fun toggle() {
        if (!active) { if (index < 0 && queue.isNotEmpty()) index = 0; playAt(index); return }
        val p = player()
        if (p.isPlaying) scope.launch { fade(p, 0f, 300); p.pause() } else { p.play(); scope.launch { fade(p, 1f, 400) } }
    }

    fun next() { nextIndex()?.let { playAt(it) } ?: run { stop() } }

    fun prev() {
        val p = player()
        if (active && p.currentPosition > 4000) { p.seekTo(0); return }
        val i = if (index > 0) index - 1 else if (repeat == 1) queue.size - 1 else 0
        playAt(i)
    }

    fun seek(ms: Long) { if (active) player().seekTo(ms) }

    fun toggleShuffle() { shuffle = !shuffle; preloaded = null; save(); preloadNext() }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3; save()
        if (active) player().repeatMode = if (repeat == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        preloaded = null; preloadNext()
    }

    /** Sale del modo música (por ejemplo, al abrir un vídeo). */
    fun stop() {
        if (!active) return
        active = false; watcher?.cancel()
        val p = player(); p.stop(); p.clearMediaItems(); p.volume = 1f
        p.repeatMode = Player.REPEAT_MODE_OFF
        Quality.videoEnabled(true)
        if (PlayerHolder.loadedId == "music") PlayerHolder.loadedId = null
    }

    private fun ensureListener(p: ExoPlayer) {
        if (listenerAdded) return
        listenerAdded = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (active && state == Player.STATE_ENDED) next()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (active) { status = "Error con \"${current?.title}\", paso a la siguiente"; next() }
            }
        })
    }

    /** Vigila el final de cada canción para bajar el volumen los últimos segundos (fundido de salida). */
    private fun startWatcher() {
        watcher?.cancel()
        watcher = scope.launch {
            while (active) {
                val p = player()
                val dur = p.duration; val pos = p.currentPosition
                if (repeat != 2 && dur > 0 && p.isPlaying && !fadingOut && dur - pos in 1..FADE_MS) {
                    fadingOut = true; fade(p, 0f, dur - pos)
                }
                delay(250)
            }
        }
    }

    private var fadeJob: Job? = null
    private suspend fun fade(p: ExoPlayer, to: Float, ms: Long) {
        fadeJob?.cancel()
        val job = scope.launch {
            val from = p.volume; val steps = (ms / 40).coerceAtLeast(1)
            for (s in 1..steps) { p.volume = from + (to - from) * s / steps; delay(40) }
            p.volume = to
        }
        fadeJob = job
        if (to == 0f && ms <= 600) job.join()
    }

    // ---------- Importar listas
    /**
     * Importa un enlace: lista/álbum/canción de Spotify (solo se leen los nombres; el sonido sale de YouTube)
     * o lista/vídeo de YouTube. Devuelve cuántas canciones se añadieron.
     */
    suspend fun importLink(link: String): Int {
        val l = link.trim()
        val tracks = when {
            "spotify.com" in l || l.startsWith("spotify:") -> spotify(l)
            "list=" in l -> Repo.playlist(l).map { Track(it.id, it.title, it.channel, it.duration) }
            else -> Repo.idFromLink(l)?.let { id ->
                val (title, author) = oembed(id)
                listOf(Track(id, title, author, null))
            } ?: emptyList()
        }
        if (tracks.isNotEmpty()) addAll(tracks)
        return tracks.size
    }

    /** Título y autor de un vídeo de YouTube (petición ligera). */
    private suspend fun oembed(id: String): Pair<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=$id").openConnection() as HttpURLConnection
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            j.optString("title", "Canción") to j.optString("author_name")
        }.getOrDefault("Canción" to "")
    }

    /** Lee los nombres de las canciones de un enlace de Spotify (página pública de inserción, sin cuenta). */
    private suspend fun spotify(link: String): List<Track> = withContext(Dispatchers.IO) {
        val m = Regex("""(playlist|album|track)[/:]([A-Za-z0-9]+)""").find(link) ?: return@withContext emptyList()
        val (type, id) = m.destructured
        val c = (URL("https://open.spotify.com/embed/$type/$id").openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            connectTimeout = 8000; readTimeout = 10000
        }
        val html = c.inputStream.bufferedReader().use { it.readText() }
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return@withContext emptyList()
        val out = ArrayList<Track>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    o.optJSONArray("trackList")?.let { a ->
                        for (i in 0 until a.length()) a.optJSONObject(i)?.let { t ->
                            val title = t.optString("title")
                            if (title.isNotBlank()) out += Track(null, title, t.optString("subtitle"),
                                t.optLong("duration").takeIf { it > 0 }?.let { it / 1000 })
                        }
                    }
                    o.keys().forEach { walk(o.opt(it)) }
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(JSONObject(json))
        // Una sola canción: la página de "track" trae su nombre en otro sitio
        if (out.isEmpty() && type == "track") Regex(""""name":"([^"]+)"""").find(json)?.groupValues?.get(1)?.let {
            out += Track(null, it, Regex(""""artists":\[\{[^}]*"name":"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "", null)
        }
        out.distinctBy { it.title + it.artist }
    }
}
