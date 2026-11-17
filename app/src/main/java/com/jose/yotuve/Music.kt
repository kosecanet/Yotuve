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
import kotlinx.coroutines.async
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
    val auto: Boolean = false,   // añadida sola al terminar la lista (relacionada con lo que te gusta)
)

/** Valoración guardada de una canción: 1 me gusta, -1 no me gusta. */
data class Rating(val id: String, val r: Int, val title: String, val artist: String)

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
    /** Frase de referencia para las canciones auto (p. ej. "años 80", "alegre"). */
    var vibe by mutableStateOf("")

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
                    o.optLong("d").takeIf { it > 0 }, auto = o.optBoolean("auto"))
            }
            index = prefs().getInt("i", if (queue.isEmpty()) -1 else 0).coerceIn(-1, queue.size - 1)
            shuffle = prefs().getBoolean("shuffle", false); repeat = prefs().getInt("repeat", 0)
            vibe = prefs().getString("ambiente", "") ?: ""
            val r = JSONArray(prefs().getString("valoraciones", "[]"))
            ratings = (0 until r.length()).map { r.getJSONObject(it) }
                .associate { it.getString("id") to Rating(it.getString("id"), it.getInt("r"), it.optString("t"), it.optString("a")) }
        }
    }

    private fun save() {
        val a = JSONArray()
        queue.forEach { a.put(JSONObject().put("id", it.id ?: "").put("t", it.title).put("a", it.artist)
            .put("d", it.duration ?: 0).put("auto", it.auto)) }
        val r = JSONArray()
        ratings.values.forEach { r.put(JSONObject().put("id", it.id).put("r", it.r).put("t", it.title).put("a", it.artist)) }
        prefs().edit().putString("valoraciones", r.toString()).apply()
        prefs().edit().putString("cola", a.toString()).putInt("i", index).putString("ambiente", vibe)
            .putBoolean("shuffle", shuffle).putInt("repeat", repeat).apply()
    }

    // ---------- Me gusta / No me gusta (registro guardado en el móvil)
    var ratings by mutableStateOf<Map<String, Rating>>(emptyMap())

    fun ratingOf(t: Track?) = t?.id?.let { ratings[it]?.r } ?: 0

    /** Me gusta (o lo quita si ya estaba). */
    fun like() = rate(1)

    /** No me gusta: se salta ya y no volverá a sonar ni a añadirse sola. */
    fun dislike() { rate(-1); if (ratingOf(current) == -1 && active) next() }

    private fun rate(r: Int) {
        val t = current ?: return
        val id = t.id ?: return
        val m = ratings.toMutableMap()
        if (m[id]?.r == r) m.remove(id) else m[id] = Rating(id, r, t.title, t.artist)
        ratings = m; save()
    }

    /** Cambia la frase de ambiente; las canciones auto que aún no han sonado se rehacen con ella. */
    fun updateVibe(v: String) {
        vibe = v.trim()
        val keep = queue.withIndex().filter { (i, t) -> !(t.auto && i > index) }.map { it.value }
        if (keep.size != queue.size) { queue = keep; preloaded = null }
        save()
        if (active) ensureNext()
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
        val found = runCatching { Innertube.musicSearch("${t.artist} ${t.title}").map { it.video } }.getOrDefault(emptyList())
            .ifEmpty { runCatching { Innertube.search("${t.artist} ${t.title} audio") }.getOrDefault(emptyList()) }
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
        val ok = queue.indices.filter { ratingOf(queue[it]) != -1 }
        if (shuffle && ok.size > 1) return (ok - from).randomOrNull()
        ok.firstOrNull { it > from }?.let { return it }
        return if (repeat == 1) ok.firstOrNull() else null
    }

    // ---------- Al acabarse la lista: canciones relacionadas, una tras otra, sin repetir
    private var autoBusy = false

    /**
     * Si la canción actual es la última, añade ya una relacionada (así la siguiente está precargada).
     * Se elige a partir de la canción actual y de algunas que te gustan; nunca repite ni añade las que no te gustan,
     * y da prioridad a artistas que te gustan.
     */
    private fun ensureNext() {
        if (repeat != 0 || shuffle || nextIndex() != null || autoBusy) return
        autoBusy = true
        scope.launch {
            try { pickRelated()?.let { add(it); preloadNext() } } finally { autoBusy = false }
        }
    }

    /**
     * Ambiente: entiende décadas ("años 80", "80s", "ochenta", "1985") y estados de ánimo/géneros en español
     * (con sus equivalentes en inglés, que es como suelen estar las etiquetas de YouTube).
     */
    object Vibe {
        data class Parsed(val words: Set<String>, val years: IntRange?, val queries: List<String>)
        private val SYN = mapOf(
            "alegre" to listOf("happy", "upbeat", "feel good", "alegre", "feliz"), "feliz" to listOf("happy", "feel good"),
            "triste" to listOf("sad", "melancholic", "triste"), "tranquilo" to listOf("chill", "relax", "calm", "acoustic"),
            "relajado" to listOf("chill", "relax", "calm"), "relax" to listOf("chill", "relax", "calm"),
            "fiesta" to listOf("party", "dance", "fiesta"), "bailar" to listOf("dance", "party", "baile"),
            "correr" to listOf("workout", "running", "energy", "upbeat"), "gimnasio" to listOf("workout", "gym", "energy"),
            "romantico" to listOf("love", "romantic", "romántico"), "romántico" to listOf("love", "romantic"),
            "rock" to listOf("rock"), "pop" to listOf("pop"), "metal" to listOf("metal"), "reggaeton" to listOf("reggaeton"),
            "electronica" to listOf("electronic", "edm", "house"), "electrónica" to listOf("electronic", "edm", "house"),
            "clasica" to listOf("classical"), "clásica" to listOf("classical"), "jazz" to listOf("jazz"),
            "español" to listOf("español", "spanish"), "espanol" to listOf("español", "spanish"),
            "infantil" to listOf("kids", "children", "infantil"), "navidad" to listOf("christmas", "navidad"))
        private val DEC = mapOf("sesenta" to 1960, "setenta" to 1970, "ochenta" to 1980, "noventa" to 1990)

        fun parse(text: String): Parsed {
            val t = text.lowercase().trim()
            if (t.isBlank()) return Parsed(emptySet(), null, emptyList())
            var start: Int? = null
            Regex("""\b(19\d\d|20[0-2]\d)\b""").find(t)?.let { val y = it.value.toInt(); start = y - y % 10 }
            Regex("""(?:años|anos|decada|década)?\s*'?(\d0)\s*'?s?\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (start == null && it in listOf(50, 60, 70, 80, 90)) start = 1900 + it
                else if (start == null && it in listOf(0, 10, 20)) start = 2000 + it
            }
            DEC.entries.firstOrNull { it.key in t }?.let { start = start ?: it.value }
            if ("dos mil" in t || "2000s" in t) start = start ?: 2000
            val words = t.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 && it != "años" && it != "anos" }
            val expanded = words.flatMap { w -> SYN[w] ?: listOf(w) }.toSet()
            val years = start?.let { it..(it + 9) }
            val queries = buildList {
                add(text)
                years?.let { add("${it.first % 100}s hits"); add("éxitos años ${it.first % 100}") }
                words.firstOrNull { it in SYN }?.let { w -> SYN[w]?.firstOrNull()?.let { add("$it music") } }
            }.distinct()
            return Parsed(expanded, years, queries)
        }

        /** Puntos por encajar: año, etiquetas/género, descripción y, lo último, el título. */
        fun match(p: Parsed, m: Innertube.Meta?, v: Video): Double {
            var sc = 0.0
            if (p.years != null) {
                val y = m?.year
                sc += when { y == null -> 0.0; y in p.years -> 4.0; else -> -2.5 }
            }
            if (p.words.isNotEmpty()) {
                val tags = (m?.tags ?: emptyList()).joinToString(" ").lowercase() + " " + (m?.category ?: "").lowercase()
                val desc = (m?.description ?: "").lowercase()
                val title = (v.title + " " + v.channel).lowercase()
                for (w in p.words) {
                    if (w in tags) sc += 2.0
                    if (w in desc) sc += 1.5
                    if (w in title) sc += 0.5
                }
            }
            return sc
        }
    }

    /** Versiones que no queremos en la radio: directos, conciertos, karaoke, versiones aceleradas… */
    private val BAD = Regex("""\b(live|en vivo|en directo|directo|concierto|concert|tour|festival|session|sesi[oó]n|""" +
        """karaoke|cover|reacci[oó]n|reaction|tutorial|8d|slowed|reverb|sped ?up|nightcore|instrumental|""" +
        """full album|álbum completo|album completo|mix \d{4}|1 hora|1 hour|loop)\b""", RegexOption.IGNORE_CASE)

    /**
     * Elige la siguiente canción "como un DJ":
     * - parecida en estilo: sale de las recomendaciones de YouTube para la canción actual y para las que te gustan
     * - variando: penaliza el mismo artista que las últimas canciones (no tiene por qué ser del mismo autor)
     * - popular: puntúa por visualizaciones (lo que más gusta suele ser lo más visto)
     * - bien grabada: fuera directos, conciertos, karaoke, versiones aceleradas…; premia audio oficial y
     *   los canales «- Topic» de YouTube (grabaciones de estudio)
     * - nunca repite ni añade nada que no te guste
     */
    private suspend fun pickRelated(): Track? {
        val liked = ratings.values.filter { it.r == 1 }
        val disliked = ratings.values.filter { it.r == -1 }
        val likedArtists = liked.map { artistKey(it.artist) }.toSet()
        val badArtists = disliked.map { artistKey(it.artist) }.toSet()
        val used = queue.mapNotNull { it.id }.toSet() + disliked.map { it.id }
        val recentArtists = queue.take(index + 1).takeLast(4).map { artistKey(it.artist) }
        // Semillas: la canción actual (si es una canción de verdad) y un par de las que te gustan
        val seeds = (listOfNotNull(current?.id) + liked.shuffled().take(2).map { it.id }).distinct()
        val radio = kotlinx.coroutines.coroutineScope {
            seeds.mapIndexed { si, id -> async {
                runCatching { Innertube.musicRadio(id) }.getOrDefault(emptyList()).mapIndexed { i, s -> Triple(s, si, i) }
            } }.flatMap { it.await() }
        }
        // Ambiente: canciones que encajan con la frase (sola, por década y junto al artista actual)
        val v = Vibe.parse(vibe)
        val vibeSongs = if (vibe.isBlank()) emptyList() else kotlinx.coroutines.coroutineScope {
            (v.queries + listOfNotNull(current?.artist?.takeIf { it.isNotBlank() }?.let { "$it $vibe" },
                liked.randomOrNull()?.artist?.let { "$it $vibe" }))
                .map { q -> async { runCatching { Innertube.musicSearch(q) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }.mapIndexed { i, s -> Triple(s, 9, i) }   // 9 = viene del ambiente
        }
        // Solo canciones: estudio (ATV) o videoclip oficial (OMV); nada subido por usuarios, podcasts ni directos
        val cands = (radio + vibeSongs).filter { (s, _, _) ->
            val x = s.video
            s.type in setOf("ATV", "OMV") && x.id !in used && (x.duration == null || x.duration in 90..480) &&
                !BAD.containsMatchIn(x.title)
        }.distinctBy { it.first.video.id }
        if (cands.isEmpty()) return pickRelatedYoutube(used, recentArtists, likedArtists, badArtists)
        fun score(c: Triple<Innertube.Song, Int, Int>): Double {
            val (s, si, i) = c
            val a = artistKey(s.video.channel)
            var sc = if (s.type == "ATV") 2.0 else 1.0                       // grabación de estudio primero
            if (vibe.isNotBlank() && si == 9) sc += 1.5 - i * 0.03           // sale de buscar el ambiente
            if (a in likedArtists) sc += 1.0
            if (a in badArtists) sc -= 6.0
            sc -= recentArtists.count { it == a } * 3.5                       // variar de artista, como un DJ
            if (si != 9) sc -= i * 0.12 + si * 0.4                            // la radio ya viene por parecido
            return sc + Math.random() * 1.2
        }
        // Con ambiente: de las 15 mejores, se miran etiquetas, género, descripción y año (antes que el título)
        if (vibe.isNotBlank()) {
            val top = cands.sortedByDescending(::score).take(15)
            val metas = kotlinx.coroutines.coroutineScope { top.map { c -> async { Innertube.meta(c.first.video.id) } }.map { it.await() } }
            val best = top.zip(metas).maxBy { (c, m) -> score(c) + Vibe.match(v, m, c.first.video) }.first.first.video
            return Track(best.id, cleanTitle(best.title), best.channel, best.duration, auto = true)
        }
        val best = cands.maxBy(::score).first.video
        return Track(best.id, cleanTitle(best.title), best.channel, best.duration, auto = true)
    }

    /** Respaldo si YouTube Music no responde: recomendaciones normales de YouTube, filtradas. */
    private suspend fun pickRelatedYoutube(used: Set<String>, recent: List<String>, liked: Set<String>, bad: Set<String>): Track? {
        val id = current?.id ?: return null
        val c = runCatching { Innertube.related(id) }.getOrDefault(emptyList())
            .filter { it.id !in used && (it.duration == null || it.duration in 90..480) && !BAD.containsMatchIn(it.title) &&
                (it.views ?: 0) >= 100_000 }
        val best = c.maxByOrNull { v ->
            val a = artistKey(v.channel)
            kotlin.math.ln((v.views ?: 1L).toDouble()) * 0.5 + (if (v.channel.endsWith("- Topic")) 2.0 else 0.0) +
                (if (a in liked) 1.0 else 0.0) - (if (a in bad) 6.0 else 0.0) - recent.count { it == a } * 3.5
        } ?: return null
        return Track(best.id, cleanTitle(best.title), best.channel.removeSuffix(" - Topic"), best.duration, auto = true)
    }

    /** Clave del artista (sin "- Topic", "VEVO", mayúsculas…) para comparar. */
    private fun artistKey(a: String) = a.lowercase().removeSuffix(" - topic").replace("vevo", "").trim()

    /** Quita del título lo que sobra: "(Official Video)", "[HD]"… */
    private fun cleanTitle(t: String) = t.replace(Regex("""\s*[\(\[][^\)\]]*(official|oficial|video|audio|lyric|letra|hd|4k)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE), "").trim().ifBlank { t }


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
            startWatcher(); ensureNext(); preloadNext()
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

    fun next() {
        nextIndex()?.let { playAt(it); return }
        // Fin de la lista: buscar una relacionada y seguir
        if (repeat == 0 && !shuffle) scope.launch {
            loading = true
            val t = pickRelated(); loading = false
            if (t != null) { add(t); playAt(queue.lastIndex) } else stop()
        } else stop()
    }

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
