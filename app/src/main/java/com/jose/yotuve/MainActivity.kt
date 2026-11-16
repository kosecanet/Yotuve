package com.jose.yotuve

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val YtRed = Color(0xFFFF0000)
private val Bg = Color(0xFF0F0F0F)
private val Sub = Color(0xFFAAAAAA)

// ---------------------------------------------------------------- ViewModel
class MainVM : ViewModel() {
    var query by mutableStateOf("")
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var results by mutableStateOf<List<Video>>(emptyList())
    var related by mutableStateOf<List<Video>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
    var current by mutableStateOf<Video?>(null)
    var streams by mutableStateOf<Streams?>(null)
    var dlProgress by mutableStateOf<Float?>(null)
    private var sugJob: Job? = null
    private var playJob: Job? = null

    var fullscreen by mutableStateOf(false)
    /** Vídeo minimizado (abajo a la derecha) mientras buscas o navegas. */
    var minimized by mutableStateOf(false)
    fun expand() { minimized = false; searching = false; channelOpen = false }
    private var searchFromVideo = false

    /** Abrir el buscador: si estabas viendo un vídeo, sigue sonando en la miniventana. */
    fun openSearch() {
        searchFromVideo = current != null && !minimized
        if (current != null) { minimized = true; fullscreen = false }
        searching = true
    }

    /** Cerrar el buscador sin buscar: si venías de un vídeo, vuelve a él. */
    fun cancelSearch() {
        searching = false
        if (searchFromVideo) minimized = false
        searchFromVideo = false
    }
    /** Bloqueo para niños en pantalla completa: ignora toques y el botón atrás. */
    var kidLock by mutableStateOf(false)

    // Detalles, comentarios y canal
    var details by mutableStateOf<Details?>(null)
    var showComments by mutableStateOf(false)
    var channelOpen by mutableStateOf(false)
    var channel by mutableStateOf<ChannelInfo?>(null)
    var channelError by mutableStateOf<String?>(null)
    var channelAvatar by mutableStateOf<String?>(null)
    private val channelCache = HashMap<String, ChannelInfo>()
    // Para volver al vídeo en el mismo punto tras ver un canal
    var resumeId: String? = null
    var resumePos = 0L

    // ---------- Historial de navegación: inicio → búsqueda → vídeo → relacionado → canal → vídeo… siempre se puede volver
    private data class Snap(val video: Video?, val query: String, val lastQuery: String?, val results: List<Video>,
                            val channelOpen: Boolean, val channel: ChannelInfo?, val shortsOpen: Boolean,
                            val minimized: Boolean)
    private val backStack = ArrayDeque<Snap>()
    private var restoring = false
    var backDepth by mutableStateOf(0)

    /** Guarda dónde estás antes de ir a otro sitio. */
    private fun push() {
        if (restoring) return
        backStack.addLast(Snap(current, query, lastQuery, results, channelOpen, channel, shortsOpen, minimized))
        while (backStack.size > 60) backStack.removeFirst()
        backDepth = backStack.size
    }

    /** Vuelve al sitio anterior. false si no hay a dónde volver. */
    fun back(): Boolean {
        val s = backStack.removeLastOrNull() ?: return false
        backDepth = backStack.size
        restoring = true
        try {
            if (shortsOpen && !s.shortsOpen) closeShorts()
            query = s.query; lastQuery = s.lastQuery; results = s.results; error = null
            channel = s.channel; channelOpen = s.channelOpen; minimized = s.minimized
            val sv = s.video
            if (sv == null) { if (current != null) closeVideo() }
            else if (sv.id != current?.id) play(sv)   // sigue donde lo dejaste
        } finally { restoring = false }
        return true
    }

    fun openChannel(url: String?) {
        if (url == null) return
        push()
        if (current != null) { minimized = true; fullscreen = false }
        channelOpen = true; channelError = null; channelHasMore = true
        channelCache[url]?.let { channel = it; return }
        channel = null
        viewModelScope.launch {
            try { channel = Repo.channel(url).also { channelCache[url] = it; if (details?.channelUrl == url) channelAvatar = it.avatar } }
            catch (e: Throwable) { channelError = "No se pudo cargar el canal" }
        }
    }

    fun closeChannel() { channelOpen = false }

    // Canal paginado: de 30 en 30 al llegar al final
    var channelLoadingMore by mutableStateOf(false)
    var channelHasMore = true
    fun channelMore() {
        val c = channel ?: return
        if (channelLoadingMore || !channelHasMore || c.videos.size < 30) return
        channelLoadingMore = true
        viewModelScope.launch {
            try {
                val more = Repo.channel(c.url, c.videos.size + 1).videos
                channelHasMore = more.size >= 30
                if (channel?.url == c.url) channel = c.copy(videos = (c.videos + more).distinctBy { it.id })
            } catch (_: Exception) { channelHasMore = false }
            channelLoadingMore = false
        }
    }

    var commentsLoading by mutableStateOf(false)

    /** Abre el panel de comentarios y los carga si aún no están. */
    var commentsMax = 20
    var commentsError by mutableStateOf<String?>(null)
    val canMoreComments get() = (details?.comments?.size ?: 0) >= commentsMax && commentsMax < 300

    /** Abre el panel de comentarios y carga los 20 primeros si aún no están. */
    fun openComments() {
        showComments = true
        if (details?.comments?.isNotEmpty() == true || commentsLoading) return
        loadComments(20)
    }

    fun moreComments() = loadComments(if (commentsMax < 60) 60 else if (commentsMax < 150) 150 else 300)

    fun loadComments(max: Int) {
        val v = current ?: return
        if (commentsLoading) return
        commentsLoading = true; commentsError = null
        viewModelScope.launch {
            try {
                val cs = Repo.comments(v, max)
                if (current?.id == v.id) { details = details?.copy(comments = cs); commentsMax = max }
            } catch (e: Exception) {
                if (current?.id == v.id) commentsError = e.message ?: "No se pudieron cargar los comentarios"
            }
            commentsLoading = false
        }
    }

    /** Guarda por dónde va el vídeo actual (o lo borra si ha terminado). */
    fun saveProgress() {
        if (shortsOpen) return   // los Shorts no se guardan en "Seguir viendo"
        val p = PlayerHolder.current() ?: return
        val id = PlayerHolder.loadedId ?: return
        if (casting) { CastHelper.player?.let { Positions.save(id, it.currentPosition, it.duration) }; return }
        if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) Positions.clear(id)
        else if (p.isPlaying || p.currentPosition > 0) Positions.save(id, p.currentPosition, p.duration)
    }

    /** Inicio: recomendaciones a partir de lo último que has visto. */
    var continueList by mutableStateOf<List<Video>>(emptyList())
    var shorts by mutableStateOf<List<Video>>(emptyList())
    var shortsOpen by mutableStateOf(false)
    var shortsStart = 0

    private fun unmetered() = runCatching {
        !App.instance.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    /**
     * Precarga en segundo plano (solo con wifi): pide los enlaces a yt-dlp (quedan 3 h en memoria)
     * y, si [media], guarda en caché los primeros segundos para que empiece al instante.
     */
    private suspend fun prefetch(list: List<Video>, media: Boolean) {
        if (!unmetered()) return
        for (v in list) {
            if (media && current != null) break   // si ya estás viendo un vídeo, no le quitamos velocidad
            val st = runCatching { Repo.info(v).first }.getOrNull() ?: continue
            if (media && Positions.get(v.id) == 0L) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PlayerCache.precache(App.instance, v.id, st, st.videoFor(Quality.target(App.instance, false, false)))
            }
        }
    }

    fun openShorts(index: Int) {
        push()
        saveProgress(); closeVideo()
        shortsStart = index; shortsOpen = true
    }

    fun closeShorts() {
        shortsOpen = false
        PlayerHolder.current()?.let { it.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF; it.stop(); it.clearMediaItems() }
        PlayerHolder.loadedId = null
    }

    fun home() {
        backStack.clear(); backDepth = 0; lastQuery = null
        if (shortsOpen) closeShorts()
        if (current != null) { minimized = true; fullscreen = false }
        channelOpen = false; searching = false; suggestions = emptyList(); query = ""
        // Se quitan los resultados de búsqueda al momento y se enseña el último inicio guardado
        results = FeedCache.load()
        // Seguir viendo: vídeos del historial que dejaste a medias
        continueList = History.list().filter { Positions.get(it.id) > 0 }.take(12)
        viewModelScope.launch {
            loading = true; error = null
            // Se enseña al instante el último inicio guardado y se refresca por detrás
            if (results.isNotEmpty()) loading = false
            try { results = Repo.home(History.list()).also { FeedCache.save(it) } }
            catch (e: Throwable) { if (results.isEmpty()) error = e.message }
            loading = false
            runCatching { Repo.shorts(History.list()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { shorts = it }
        }
    }

    fun onQueryChange(q: String) {
        query = q
        sugJob?.cancel()
        sugJob = viewModelScope.launch { delay(200); suggestions = Repo.suggestions(q) }
    }

    var lastQuery: String? = null     // búsqueda actual (null = inicio)
    var loadingMore by mutableStateOf(false)
    private var canLoadMore = true

    /** Al llegar al final de los resultados: 20 más. */
    fun loadMore() {
        val q = lastQuery ?: return
        if (loadingMore || loading || !canLoadMore || results.isEmpty()) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val more = Repo.search(q, 20, results.size + 1)
                canLoadMore = more.isNotEmpty()
                if (lastQuery == q) results = (results + more).distinctBy { it.id }
            } catch (_: Exception) { canLoadMore = false }
            loadingMore = false
        }
    }

    fun search(q: String, fromUser: Boolean = true) {
        if (q.isBlank()) return
        push()
        if (fromUser) query = q
        lastQuery = q; canLoadMore = true; channelOpen = false
        if (current != null) { minimized = true; fullscreen = false }   // sigue sonando en la miniventana
        searching = false; suggestions = emptyList(); searchFromVideo = false
        viewModelScope.launch {
            loading = true; error = null
            try { results = Repo.search(q) } catch (e: Throwable) { error = e.message }
            loading = false
            prefetch(results.take(3), media = true)   // precarga: solo los primeros resultados de la búsqueda
        }
    }

    var pip by mutableStateOf(false)
    var casting by mutableStateOf(false)
    var castStatus by mutableStateOf<String?>(null)

    /** La tele se ha conectado: pasamos el vídeo actual allí desde el mismo punto. */
    fun startCast() {
        casting = true
        current?.let { castLoad(it, PlayerHolder.current()?.currentPosition ?: 0L) }
    }

    /** La tele se ha desconectado: seguimos en el móvil donde iba. */
    fun stopCast() {
        val pos = CastHelper.player?.currentPosition ?: 0L
        casting = false; castStatus = null
        PlayerHolder.current()?.let { if (PlayerHolder.loadedId == current?.id) { it.seekTo(pos); it.play() } }
    }

    private fun castLoad(v: Video, pos: Long) {
        PlayerHolder.current()?.pause()
        castStatus = "Enviando a la TV…"
        viewModelScope.launch {
            try { CastHelper.load(v, Repo.castUrl(v), pos); castStatus = "Reproduciendo en la TV" }
            catch (e: Exception) { castStatus = "No se pudo enviar a la TV" }
        }
    }
    var dlId by mutableStateOf<String?>(null)   // vídeo que se está descargando

    /** Cierra el vídeo y para el sonido (como al salir del reproductor en YouTube). */
    fun closeVideo() {
        minimized = false
        kidLock = false; cancelCountdown(); ended = false
        saveProgress()
        current = null; fullscreen = false; showComments = false
        if (casting) CastHelper.player?.stop()
        PlayerHolder.current()?.let { it.stop(); it.clearMediaItems() }
        PlayerHolder.loadedId = null
    }

    fun play(v: Video) {
        if (current?.id == v.id && playJob?.isActive == true) return   // ya se está cargando
        if (current?.id != v.id) push()
        channelOpen = false
        commentsMax = 20; commentsError = null
        History.add(v)
        cancelCountdown(); ended = false; nextVideo = null
        saveProgress()
        minimized = false
        // Si sonaba otro vídeo, baja su volumen poco a poco mientras carga el nuevo (fundido)
        if (PlayerHolder.loadedId != v.id) PlayerHolder.current()?.let { p ->
            if (p.isPlaying) { crossfade = true; fadeVolume(p, 0.15f, 600) } else p.stop()
        }
        current = v; streams = null; error = null; details = null
        dlProgress = if (dlId == v.id) dlProgress else null
        details = null; showComments = false; channelAvatar = null; related = emptyList()
        if (casting) castLoad(v, Positions.get(v.id))
        playJob?.cancel()
        playJob = viewModelScope.launch {
            // 1º lo urgente: enlaces + descripción en una sola llamada → el vídeo empieza cuanto antes
            try {
                val (st, d) = Repo.info(v)
                if (current?.id != v.id) return@launch
                streams = st; details = d
                if (PlayerHolder.loadedId != v.id) loadPlayer(v, st, null)
                if (st.audio == null) launch {   // modo rápido solo dio 360p: mejor calidad sin cortar
                    Repo.upgrade(v)?.let { up -> if (current?.id == v.id) streams = up }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { if (current?.id == v.id) error = "No se pudo reproducir: ${e.message}"; return@launch }
            // 2º relacionados, 3º comentarios, 4º avatar del canal: de uno en uno para no frenar el vídeo
            val rel = runCatching { Repo.related(v) }.getOrDefault(emptyList())
            if (current?.id != v.id) return@launch
            related = rel
            // Comentarios y canal ya NO se piden automáticamente (cada uno es un proceso de yt-dlp
            // que gasta batería): solo al abrir los comentarios o el canal.
            details?.channelUrl?.let { url -> channelAvatar = channelCache[url]?.avatar }
            if (channelAvatar == null) details?.channelId?.let { cid ->
                val a = Repo.channelAvatar(cid)
                if (current?.id == v.id) channelAvatar = a
            }
        }
    }

    private var retriedId: String? = null

    // ---------- Reproducción (en el VM: funciona aunque la pantalla esté apagada o estés en un canal)
    private var listenerAttached = false

    private fun attachListener(p: androidx.media3.exoplayer.ExoPlayer) {
        if (listenerAttached) return
        listenerAttached = true
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (shortsOpen) return
                if (state == androidx.media3.common.Player.STATE_ENDED) onEnded()
                else if (ended && state != androidx.media3.common.Player.STATE_IDLE) { ended = false; cancelCountdown() }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                if (!shortsOpen) recoverPlayback(p.currentPosition)
            }
        })
    }

    /** Carga un vídeo en el reproductor, en [start] o donde lo dejaste. */
    private fun loadPlayer(v: Video, st: Streams, start: Long?) {
        val ctx = App.instance
        val p = PlayerHolder.get(ctx)
        attachListener(p)
        val url = st.videoFor(Quality.target(ctx, fullscreen, pip))
        val pos = start ?: Positions.get(v.id)
        val fadeIn = crossfade; crossfade = false
        if (fadeIn) {
            // Fundido: termina de bajar el anterior y el nuevo entra subiendo el volumen
            viewModelScope.launch {
                fadeVolume(p, 0f, 250); kotlinx.coroutines.delay(260)
                p.setMediaSource(PlayerCache.source(ctx, v, st, url), pos); p.prepare()
                p.playWhenReady = !casting
                fadeVolume(p, 1f, 900)
            }
        } else {
            p.volume = 1f
            p.setMediaSource(PlayerCache.source(ctx, v, st, url), pos); p.prepare()
            p.playWhenReady = !casting   // si está en la TV, en el móvil queda en pausa
        }
        PlayerHolder.loadedId = v.id; PlayerHolder.loadedUrl = url
        if (start == null && pos > 0)
            Toast.makeText(ctx, "Continuando desde ${fmtDur(pos / 1000)}", Toast.LENGTH_SHORT).show()
    }

    private var crossfade = false
    private var fadeJob: Job? = null

    /** Lleva el volumen del reproductor a [to] en [ms] milisegundos. */
    private fun fadeVolume(p: androidx.media3.exoplayer.ExoPlayer, to: Float, ms: Long) {
        fadeJob?.cancel()
        fadeJob = viewModelScope.launch {
            val from = p.volume; val steps = (ms / 30).coerceAtLeast(1)
            for (i in 1..steps) { p.volume = from + (to - from) * i / steps; kotlinx.coroutines.delay(30) }
            p.volume = to
        }
    }

    /** Precarga al tocar un vídeo (antes de soltar el dedo): se gana medio segundo. */
    fun warm(v: Video) {
        if (current?.id == v.id) return
        viewModelScope.launch { runCatching { Repo.info(v) } }
    }

    // ---------- Recuperación: enlace caducado (pasan ~6 h), caché dañada, corte de red…
    private var retryId: String? = null
    private var retries = 0
    private var lastRetry = 0L

    /** Pide enlaces nuevos a YouTube y sigue en el mismo punto. Hasta 3 intentos por vídeo. */
    fun recoverPlayback(pos: Long) {
        val v = current ?: return
        val now = System.currentTimeMillis()
        if (retryId != v.id || now - lastRetry > 600_000) { retryId = v.id; retries = 0 }
        if (retries >= 3) { error = "No se pudo reproducir este vídeo. Vuelve a abrirlo para reintentar"; return }
        retries++; lastRetry = now
        Repo.invalidate(v.id)
        PlayerHolder.loadedId = null
        viewModelScope.launch {
            try {
                val st = Repo.info(v).first
                if (current?.id != v.id) return@launch
                error = null; streams = st
                loadPlayer(v, st, pos)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = "No se pudo reproducir: ${e.message}" }
        }
    }

    // ---------- Al terminar: siguiente vídeo con cuenta atrás de 10 s
    var ended by mutableStateOf(false)
    var countdown by mutableStateOf<Int?>(null)
    var nextVideo by mutableStateOf<Video?>(null)
    private var countdownJob: Job? = null

    private fun onEnded() {
        val v = current ?: return
        ended = true
        Positions.clear(v.id)
        val next = related.firstOrNull { it.id != v.id && Positions.get(it.id) == 0L }
            ?: related.firstOrNull { it.id != v.id } ?: return
        nextVideo = next
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 1) { countdown = i; kotlinx.coroutines.delay(1000) }
            countdown = null
            play(next)
        }
    }

    fun cancelCountdown() { countdownJob?.cancel(); countdownJob = null; countdown = null }

    fun playNextNow() { val n = nextVideo ?: return; cancelCountdown(); play(n) }

    /** Volver a ver desde el principio. */
    fun replay() {
        cancelCountdown(); ended = false
        PlayerHolder.current()?.let { it.seekTo(0); it.play() }
    }

    private var cancelled = false

    fun download(ctx: Context, share: Boolean, video: Video? = current) {
        val v = video ?: return
        if (dlId != null) {
            Toast.makeText(ctx, "Espera a que termine la descarga actual o cancélala", Toast.LENGTH_SHORT).show(); return
        }
        dlId = v.id; cancelled = false
        viewModelScope.launch {
            dlProgress = 0f
            try {
                val f = Repo.download(ctx, v) { p -> if (current?.id == v.id) dlProgress = p.coerceIn(0f, 0.99f) }
                if (current?.id == v.id) dlProgress = 1f
                if (share) shareFile(ctx, f, v.title)
                else Toast.makeText(ctx, "Guardado en Descargas/yotuve", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (current?.id == v.id) dlProgress = null
                if (cancelled) Toast.makeText(ctx, "Descarga cancelada", Toast.LENGTH_SHORT).show()
                else Toast.makeText(ctx, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { dlId = null }
        }
    }

    /** Para la descarga en curso y borra el fichero a medias. */
    fun cancelDownload(ctx: Context) {
        val id = dlId ?: return
        cancelled = true
        Repo.cancelDownload(ctx, id)
    }

    // Al final de la clase: así todas las propiedades ya están creadas antes de usarlas
    init {
        home()
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (PlayerHolder.current()?.isPlaying == true || casting) saveProgress()
            }
        }
    }
}

private fun shareFile(ctx: Context, f: File, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Compartir vídeo"))
}

// ---------------------------------------------------------------- Activity
class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = App.crashFile(this)
        val lastCrash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        if (lastCrash == null) handleShare(intent)
        CastHelper.init(this, object : androidx.media3.cast.SessionAvailabilityListener {
            override fun onCastSessionAvailable() = vm.startCast()
            override fun onCastSessionUnavailable() = vm.stopCast()
        })
        if (CastHelper.isCasting) vm.casting = true
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = YtRed)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    var crash by remember { mutableStateOf(lastCrash) }
                    if (crash != null) CrashScreen(crash!!) { crash = null } else AppScreen(vm)
                }
            }
        }
    }

    private var controller: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    override fun onStart() {
        super.onStart()
        Quality.videoEnabled(true)
        // Arranca/conecta el servicio de reproducción (sonido con el móvil bloqueado)
        val token = androidx.media3.session.SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controller = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onStop() {
        vm.saveProgress()
        // Con la pantalla apagada o la app en segundo plano no se descarga ni decodifica el vídeo: solo audio
        if (!isInPictureInPictureMode) Quality.videoEnabled(false)
        controller?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    /** Ventana flotante (picture-in-picture) con el vídeo. */
    fun enterPip() {
        if (vm.current == null) return
        val params = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
        runCatching { enterPictureInPictureMode(params) }
    }

    /** Al pulsar Inicio con un vídeo sonando, pasa a ventana flotante automáticamente. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.current != null && PlayerHolder.current()?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        vm.pip = isInPip
        // Si cierras la ventana flotante con la X, se para (como YouTube)
        if (!isInPip && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // Se cerró la ventana flotante con la X: parar, quitar la notificación y cerrar la app
            vm.closeVideo()
            stopService(Intent(this, PlaybackService::class.java))
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(i: Intent?) {
        val text = i?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = Repo.idFromLink(text) ?: return
        vm.play(Video(id, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Vídeo compartido", "", null, null,
            "https://i.ytimg.com/vi/$id/hqdefault.jpg"))
    }
}

// ---------------------------------------------------------------- UI
@Composable
fun CrashScreen(text: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("yotuve se cerró la última vez", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Haz una captura de esto y mándasela a Claude.", color = Sub, fontSize = 13.sp)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(Icons.Default.ContentCopy, "Copiar") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
            }
            Pill(Icons.Default.Close, "Continuar", onClose)
        }
        LazyColumn { item { Text(text, fontSize = 11.sp, color = Color(0xFFFF8A80)) } }
    }
}

@Composable
fun AppScreen(vm: MainVM) {
    BackHandler(vm.current != null || vm.searching || vm.channelOpen || vm.shortsOpen || vm.kidLock ||
        vm.fullscreen || vm.backDepth > 0) {
        when {
            vm.kidLock -> {}                          // bloqueado: atrás no hace nada
            vm.fullscreen -> vm.fullscreen = false
            vm.searching -> vm.cancelSearch()
            vm.back() -> {}                           // vuelve al sitio anterior del historial
            vm.shortsOpen -> vm.closeShorts()
            vm.channelOpen -> vm.closeChannel()
            vm.current != null && vm.minimized -> vm.expand()
            vm.current != null -> vm.closeVideo()
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(if (vm.fullscreen || vm.pip) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding()) {
        when {
            vm.pip && vm.current != null -> PlayerScreen(vm, vm.current!!)
            vm.shortsOpen -> ShortsScreen(vm)
            vm.searching -> SearchScreen(vm)
            vm.channelOpen -> ChannelScreen(vm)
            vm.current != null && !vm.minimized -> {
                if (!vm.fullscreen) TopBar(onSearch = { vm.openSearch() }, onHome = { vm.home() })
                PlayerScreen(vm, vm.current!!)
            }
            else -> {
                TopBar(onSearch = { vm.openSearch() }, onHome = { vm.home() })
                Feed(vm, vm.results, footer = {
                    if (vm.lastQuery == null) {
                        // Al final del inicio: Seguir viendo y Shorts
                        ContinueRow(vm)
                        ShortsShelf(vm)
                    } else {
                        // Búsqueda: al llegar al final se cargan 20 más
                        LaunchedEffect(vm.results.size) { vm.loadMore() }
                        if (vm.loadingMore) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = YtRed)
                        }
                    }
                })
            }
        }
    }
    // Vídeo minimizado: abajo a la derecha, por encima del teclado
    if (vm.current != null && vm.minimized && !vm.shortsOpen && !vm.pip) MiniPlayer(vm)
    }
}

/** Miniventana con el vídeo: tocar = volver al vídeo; X = cerrarlo. */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(vm: MainVM) {
    val ctx = LocalContext.current
    val player = remember { PlayerHolder.get(ctx) }
    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).width(176.dp).aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable { vm.expand() }) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = false } },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (vm.streams == null) AsyncImage(vm.current?.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color(0x99000000))
                .clickable { vm.closeVideo() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, "Cerrar", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun TopBar(onSearch: () -> Unit, onHome: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(onClick = onHome), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFFF2383A)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text("yotuve", fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-1).sp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Buscar") }
    }
    UpdateBanner()
    App.status?.let {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sync, null, tint = Sub, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(it, color = Sub, fontSize = 12.sp)
        }
    }
}

/** Aviso de nueva versión de yotuve bajo la barra superior. */
@Composable
fun UpdateBanner() {
    val st = Updater.state ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (text, action) = when (st) {
        is Updater.State.Available -> "Nueva versión ${st.version} (${st.sizeMb} MB)" to "Descargar"
        is Updater.State.Downloading -> "Descargando versión ${st.version}… ${(st.progress * 100).toInt()} %" to null
        is Updater.State.Ready -> "Versión ${st.version} lista" to "Instalar"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF1E3A5F)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) Text(action, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF8AB4F8),
            modifier = Modifier.clickable {
                when (st) {
                    is Updater.State.Available -> scope.launch { Updater.download(ctx.applicationContext) }
                    is Updater.State.Ready -> Updater.install(ctx)
                    else -> {}
                }
            }.padding(4.dp))
    }
}

@Composable
fun SearchScreen(vm: MainVM) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.cancelSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
        TextField(
            value = vm.query, onValueChange = vm::onQueryChange,
            placeholder = { Text("Buscar en YouTube") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF272727), unfocusedContainerColor = Color(0xFF272727),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Default.Close, null) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(vm.query) }),
            modifier = Modifier.weight(1f).focusRequester(fr),
        )
    }
    LazyColumn {
        items(vm.suggestions) { s ->
            Row(Modifier.fillMaxWidth().clickable { vm.search(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = Sub)
                Spacer(Modifier.width(16.dp))
                Text(s, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.NorthWest, "Completar", tint = Sub,
                    modifier = Modifier.clickable { vm.onQueryChange("$s ") })
            }
        }
    }
}

@Composable
fun Feed(vm: MainVM, list: List<Video>, header: (@Composable () -> Unit)? = null,
         footer: (@Composable () -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize()) {
        header?.let { item { it() } }
        if (vm.loading && vm.current == null) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = YtRed) }
        }
        vm.error?.let { if (vm.current == null) item { Text(it, color = YtRed, modifier = Modifier.padding(16.dp)) } }
        items(list, key = { it.id + list.indexOf(it) }) { v -> VideoCard(v, onPress = { vm.warm(v) }) { vm.play(v) } }
        footer?.let { item { it() } }
    }
}

@Composable
fun VideoCard(v: Video, onPress: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()
        .pointerInput(v.id) { detectTapGestures(onPress = { onPress?.invoke() }, onTap = { onClick() }) }
        .padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color(0xFF222222)))
            Positions.progress(v.id)?.let { prog ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x88FFFFFF))) {
                    Box(Modifier.fillMaxWidth(prog).fillMaxHeight().background(YtRed))
                }
            }
            v.duration?.let {
                Text(fmtDur(it), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3A3A3A)), contentAlignment = Alignment.Center) {
                Text(v.channel.take(1).uppercase(), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                Text(listOfNotNull(v.channel.ifBlank { null }, v.views?.let { fmtViews(it) }, fmtAgo(v.timestamp))
                    .joinToString(" · "), color = Sub, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainVM, v: Video) {
    val ctx = LocalContext.current
    val player = remember { PlayerHolder.get(ctx) }
    // Pantalla siempre encendida mientras el vídeo se reproduce (se apaga sola en pausa o al salir)
    val window = (ctx as? android.app.Activity)?.window
    DisposableEffect(player) {
        fun apply(playing: Boolean) {
            if (playing) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val l = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = apply(isPlaying)
        }
        player.addListener(l); apply(player.isPlaying)
        onDispose { player.removeListener(l); apply(false) }
    }
    // Calidad según el tamaño real del vídeo en pantalla (y menos con datos móviles)
    val target = Quality.target(ctx, vm.fullscreen, vm.pip)
    LaunchedEffect(vm.streams, target) {
        val st = vm.streams ?: return@LaunchedEffect
        val url = st.videoFor(target)
        if (PlayerHolder.loadedId == v.id && PlayerHolder.loadedUrl != url) {
            // Cambio de calidad (pantalla completa / flotante) en el mismo punto
            val pos = player.currentPosition
            player.setMediaSource(PlayerCache.source(ctx, v, st, url), pos); player.prepare()
            PlayerHolder.loadedUrl = url
        }
    }
    CommentsSheet(vm)

    var ctrlVisible by remember { mutableStateOf(false) }   // controles del reproductor visibles
    var lockShown by remember { mutableStateOf(false) }     // candado visible estando bloqueado
    var holding by remember { mutableStateOf(false) }       // manteniendo pulsado el candado
    var lockTick by remember { mutableStateOf(0) }
    // Bloqueado: el candado se oculta solo a los 3 s de tocar (salvo si lo estás manteniendo)
    LaunchedEffect(lockTick, holding) {
        if (vm.kidLock && lockShown && !holding) { kotlinx.coroutines.delay(3000); lockShown = false }
    }
    val compact = vm.fullscreen || vm.pip
    FullscreenEffect(vm.fullscreen && !vm.pip)
    val boxMod = if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    Box(boxMod.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    // Muestra el botón de pantalla completa en los controles
                    setFullscreenButtonClickListener { full -> vm.fullscreen = full }
                    // El candado aparece y desaparece a la vez que los controles
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                        ctrlVisible = vis == android.view.View.VISIBLE
                    })
                }
            },
            update = {
                // Mientras se envía a la TV, los controles manejan la TV
                val target = if (vm.casting) CastHelper.player ?: player else player
                if (it.player !== target) it.player = target
                it.setFullscreenButtonState(vm.fullscreen)
                it.useController = !vm.pip && !vm.kidLock   // en flotante o bloqueado: solo el vídeo
                if (vm.kidLock) it.hideController()
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (vm.streams == null && !vm.casting)   // mientras carga: la miniatura en vez de negro
            AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (vm.casting) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Cast, null, tint = Color.White, modifier = Modifier.size(40.dp))
            Text(vm.castStatus ?: "Conectado a la TV", color = Color.White, fontSize = 14.sp)
        } else if (vm.streams == null && vm.error == null) CircularProgressIndicator(color = YtRed)
        vm.error?.let { Text(it, color = Color.White, modifier = Modifier.padding(16.dp)) }
        if (!vm.pip && vm.ended) EndOverlay(vm)
        if (vm.fullscreen && !vm.pip) {
            if (vm.kidLock) TouchBlocker(onTap = { lockShown = true; lockTick++ })
            val show = holding || if (vm.kidLock) lockShown else ctrlVisible
            if (show) HoldToLock(vm.kidLock, Modifier.align(Alignment.TopEnd).padding(16.dp), onHold = { holding = it; lockTick++ }) {
                vm.kidLock = !vm.kidLock
                if (vm.kidLock) { lockShown = true; lockTick++ }   // tras bloquear se ve un momento y se oculta
            }
        }
    }
    if (compact) return

    var castChoice by remember { mutableStateOf(false) }
    if (castChoice) AlertDialog(
        onDismissRequest = { castChoice = false },
        title = { Text("Enviar a la TV") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable {
                    castChoice = false
                    if (CastHelper.player == null || !CastHelper.chooseDevice(ctx)) {
                        Toast.makeText(ctx, "Chromecast no disponible en este móvil; usa duplicar pantalla", Toast.LENGTH_LONG).show()
                    }
                }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cast, null); Spacer(Modifier.width(14.dp))
                    Column { Text("Chromecast / Google TV"); Text("El vídeo se reproduce en la tele; el móvil hace de mando", color = Sub, fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().clickable { castChoice = false; CastHelper.openScreenMirroring(ctx) }
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ScreenShare, null); Spacer(Modifier.width(14.dp))
                    Column { Text("Duplicar pantalla"); Text("Smart View, Miracast… (teles Samsung, LG, etc.)", color = Sub, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { castChoice = false }) { Text("Cancelar") } },
    )

    Feed(vm, vm.related, header = {
        Column(Modifier.padding(12.dp)) {
            Text(v.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            DetailsSection(vm, v)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (vm.casting) Icons.Default.CastConnected else Icons.Default.Cast,
                    if (vm.casting) "Desconectar TV" else "Enviar a TV") {
                    if (vm.casting) CastHelper.disconnect(ctx) else castChoice = true
                }
                Pill(Icons.Default.PictureInPictureAlt, "Flotante") { (ctx as? MainActivity)?.enterPip() }
                Pill(Icons.Default.Download, if (vm.dlProgress == 1f) "Descargado" else "Descargar") { vm.download(ctx, share = false) }
                Pill(Icons.Default.Share, "Compartir") { vm.download(ctx, share = true) }
                Pill(Icons.Default.Link, "Enlace") {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, v.url), "Compartir enlace"))
                }
            }
            vm.dlProgress?.takeIf { it < 1f }?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LinearProgressIndicator(progress = { it }, color = YtRed, modifier = Modifier.fillMaxWidth())
                        Text("Descargando… ${(it * 100).toInt()} %", color = Sub, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill(Icons.Default.Close, "Cancelar") { vm.cancelDownload(ctx) }
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = Color(0xFF272727))
        }
    })
}

/** Al terminar: "Siguiente en N" con el próximo vídeo, o "Volver a ver". */
@Composable
fun EndOverlay(vm: MainVM) {
    val next = vm.nextVideo
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            if (vm.countdown != null && next != null) {
                Text("Siguiente en ${vm.countdown}", color = Sub, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 420.dp)) {
                    AsyncImage(next.thumb, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(next.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (next.channel.isNotBlank()) Text(next.channel, color = Sub, fontSize = 12.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(Icons.Default.Close, "Cancelar") { vm.cancelCountdown() }
                    Pill(Icons.Default.SkipNext, "Reproducir ya") { vm.playNextNow() }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(Icons.Default.Replay, "Volver a ver") { vm.replay() }
                    if (next != null) Pill(Icons.Default.SkipNext, "Siguiente") { vm.playNextNow() }
                }
            }
        }
    }
}

/** Capa invisible que se traga todos los toques (pantalla bloqueada). */
@Composable
fun TouchBlocker(onTap: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) onTap()   // tocar: enseña el candado
                e.changes.forEach { it.consume() }
            }
        }
    })
}

/**
 * Botón de candado: hay que mantenerlo pulsado 5 segundos para bloquear o desbloquear.
 * Un anillo rojo se va llenando mientras se mantiene; si se suelta antes, no pasa nada.
 */
@Composable
fun HoldToLock(locked: Boolean, modifier: Modifier, onHold: (Boolean) -> Unit = {}, onToggle: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.End) {   // fijo a la derecha: el texto no lo desplaza
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color(if (locked) 0x44000000 else 0x66000000))
            .pointerInput(locked) {
                detectTapGestures(onPress = {
                    onHold(true)
                    val job = scope.launch {
                        val start = System.currentTimeMillis()
                        while (true) {
                            progress = ((System.currentTimeMillis() - start) / 5000f).coerceAtMost(1f)
                            if (progress >= 1f) {
                                onToggle()
                                Toast.makeText(ctx, if (locked) "Pantalla desbloqueada" else "Pantalla bloqueada", Toast.LENGTH_SHORT).show()
                                break
                            }
                            kotlinx.coroutines.delay(16)
                        }
                    }
                    tryAwaitRelease(); job.cancel(); progress = 0f; onHold(false)
                })
            }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress }, color = YtRed, strokeWidth = 3.dp,
                trackColor = Color.Transparent, modifier = Modifier.fillMaxSize())
            Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, if (locked) "Desbloquear" else "Bloquear",
                tint = Color.White.copy(alpha = if (locked) 0.6f else 1f))
        }
        if (progress > 0f) Text(if (locked) "Mantén para desbloquear" else "Mantén 5 s para bloquear",
            color = Color.White, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp).background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Pantalla completa: horizontal y sin barras del sistema; al salir, todo vuelve a la normalidad. */
@Composable
fun FullscreenEffect(full: Boolean) {
    val act = LocalContext.current as? android.app.Activity ?: return
    DisposableEffect(full) {
        val ctl = androidx.core.view.WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (full) {
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF272727)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

fun fmtDur(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
private fun fmtViews(n: Long) = when {
    n >= 1_000_000 -> "%.1f M visualizaciones".format(n / 1e6)
    n >= 1_000 -> "%d K visualizaciones".format(n / 1000)
    else -> "$n visualizaciones"
}
