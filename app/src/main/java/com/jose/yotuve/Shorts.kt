package com.jose.yotuve

import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val Red = Color(0xFFFF0000)
private val Grey = Color(0xFFAAAAAA)

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Red, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

/** Fila horizontal con los vídeos que dejaste a medias. */
@Composable
fun ContinueRow(vm: MainVM) {
    val list = vm.continueList
    if (list.isEmpty()) return
    SectionTitle(Icons.Default.History, "Seguir viendo")
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { "c" + it.id }) { v ->
            Column(Modifier.width(200.dp).clickable { vm.play(v) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                    AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color(0xFF222222)))
                    Positions.progress(v.id)?.let { prog ->
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x88FFFFFF))) {
                            Box(Modifier.fillMaxWidth(prog).fillMaxHeight().background(Red))
                        }
                    }
                }
                Text(v.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (v.channel.isNotBlank()) Text(v.channel, fontSize = 11.sp, color = Grey, maxLines = 1)
            }
        }
    }
}

/** Estante de Shorts en el inicio (tarjetas verticales). */
@Composable
fun ShortsShelf(vm: MainVM) {
    val list = vm.shorts
    if (list.isEmpty()) return
    SectionTitle(Icons.Default.Bolt, "Shorts")
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 24.dp)) {
        itemsIndexed(list, key = { _, v -> "s" + v.id }) { i, v ->
            Box(Modifier.width(140.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).clickable { vm.openShorts(i) }) {
                AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color(0xFF222222)))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(8.dp)) {
                    Text(v.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
                    v.views?.let { Text("${fmtCount(it)} visualizaciones", fontSize = 10.sp, color = Color(0xFFDDDDDD)) }
                }
            }
        }
    }
}

/** Reproductor de Shorts: vertical, pantalla completa, desliza arriba/abajo, en bucle. */
@OptIn(UnstableApi::class, ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(vm: MainVM) {
    val ctx = LocalContext.current
    val list = vm.shorts
    if (list.isEmpty()) { vm.closeShorts(); return }
    val pager = rememberPagerState(initialPage = vm.shortsStart.coerceIn(0, list.size - 1)) { list.size }
    val player = remember { PlayerHolder.get(ctx) }
    val scope = rememberCoroutineScope()
    var playingId by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    val window = (ctx as? android.app.Activity)?.window

    DisposableEffect(Unit) {
        player.repeatMode = Player.REPEAT_MODE_ONE
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Al asentarse en una página: cargar ese Short y precargar el siguiente
    LaunchedEffect(pager.settledPage) {
        val idx = pager.settledPage
        val v = list.getOrNull(idx) ?: return@LaunchedEffect
        playingId = null; paused = false
        player.stop()
        val st = runCatching { Repo.fastStreams(v) }.getOrNull() ?: return@LaunchedEffect
        if (pager.settledPage != idx) return@LaunchedEffect
        player.setMediaSource(PlayerCache.source(ctx, v, st)); player.prepare(); player.playWhenReady = true
        PlayerHolder.loadedId = v.id; PlayerHolder.loadedUrl = st.video
        playingId = v.id
    }

    VerticalPager(state = pager, modifier = Modifier.fillMaxSize().background(Color.Black)) { page ->
        val v = list[page]
        Box(Modifier.fillMaxSize().clickable {
            // Tocar = pausa / reanudar
            if (player.isPlaying) { player.pause(); paused = true } else { player.play(); paused = false }
        }) {
            AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (playingId == v.id && page == pager.settledPage) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            this.player = player; useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (page == pager.settledPage) {
                CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.Center))
            }
            if (paused && page == pager.settledPage)
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(72.dp))

            // Info abajo a la izquierda
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.8f).navigationBarsPadding().padding(16.dp)) {
                if (v.channel.isNotBlank()) Text("@${v.channel}", fontWeight = FontWeight.Bold, color = Color.White)
                Text(v.title, color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            // Botones a la derecha
            Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ShortButton(Icons.Default.Download, "Descargar") { vm.download(ctx, share = false, video = v) }
                ShortButton(Icons.Default.Share, "Compartir") { vm.download(ctx, share = true, video = v) }
                ShortButton(Icons.Default.Link, "Enlace") {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://youtube.com/shorts/${v.id}"), "Compartir enlace"))
                }
            }
            // Atrás
            IconButton(onClick = { vm.closeShorts() }, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ShortButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0x55000000)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White)
        }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
