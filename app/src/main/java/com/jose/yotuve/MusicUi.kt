package com.jose.yotuve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MRed = Color(0xFFF2383A)
private val MSub = Color(0xFFAAAAAA)
private val MCard = Color(0xFF1E1E1E)

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Pantalla del modo música: canción actual con controles tipo Spotify y la lista debajo. */
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Track?>(null) }        // canción que se está arrastrando
    var dragOffset by remember { mutableStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            Text("Modo música", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Add, "Añadir canciones") }
            IconButton(onClick = { importOpen = true }) { Icon(Icons.Default.Link, "Importar lista") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Vaciar lista") }, onClick = { Music.clear(); menu = false })
                }
            }
        }
        Music.status?.let { Text(it, color = MSub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }

        LazyColumn(Modifier.fillMaxSize()) {
            item { NowPlaying() }
            item { VibeBar() }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lista · ${Music.queue.size} canciones", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (Music.queue.size > 1) Text("Arrastra ≡ para mover · desliza para quitar", color = MSub, fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp))
                }
            }
            if (Music.queue.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("La lista está vacía", color = MSub)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { searchOpen = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("Buscar canciones", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { importOpen = true }, modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B6B6B)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("Importar lista de Spotify o YouTube")
                    }
                }
            }
            itemsIndexed(Music.queue, key = { _, t -> System.identityHashCode(t) }) { i, t ->
                TrackRow(i, t, edit, dragging = drag == t, dragOffset = if (drag == t) dragOffset else 0f,
                    onDragStart = { drag = t; dragOffset = 0f },
                    onDrag = { dy ->
                        // Arrastrar: al pasar media fila, la canción cambia de sitio
                        dragOffset += dy
                        val from = Music.queue.indexOf(t)
                        if (dragOffset > rowPx / 2 && from < Music.queue.size - 1) { Music.move(from, from + 1); dragOffset -= rowPx }
                        else if (dragOffset < -rowPx / 2 && from > 0) { Music.move(from, from - 1); dragOffset += rowPx }
                    },
                    onDragEnd = { drag = null; dragOffset = 0f })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (importOpen) ImportDialog(onDismiss = { importOpen = false }) { link ->
        scope.launch {
            Music.loading = true
            val n = runCatching { Music.importLink(link) }.getOrDefault(0)
            Music.loading = false
            Music.status = if (n > 0) "Añadidas $n canciones" else "No se encontraron canciones en ese enlace"
        }
    }
    if (searchOpen) SearchAddDialog(onDismiss = { searchOpen = false })
}

/** Carátula, título, barra de progreso y controles (aleatorio, anterior, play/pausa, siguiente, repetir). */
@Composable
private fun NowPlaying() {
    val t = Music.current
    val player = remember { PlayerHolder.get(App.instance) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (Music.active) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0); playing = player.isPlaying }
            else { pos = 0; dur = (Music.current?.duration ?: 0) * 1000; playing = false }
            delay(400)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.78f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MCard),
            contentAlignment = Alignment.Center) {
            if (t?.thumb != null) AsyncImage(t.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.MusicNote, null, tint = MSub, modifier = Modifier.size(72.dp))
            if (Music.loading) CircularProgressIndicator(color = MRed)
        }
        Spacer(Modifier.height(16.dp))
        Text(t?.title ?: "Nada sonando", fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t?.artist ?: "", color = MSub, fontSize = 14.sp, maxLines = 1)
                if (t?.auto == true) Text("Añadida sola · relacionada con lo que te gusta", color = MSub, fontSize = 11.sp)
            }
            // Me gusta / No me gusta (solo si hay canción)
            val r = Music.ratingOf(t)
            if (t != null) IconButton(onClick = { Music.dislike() }, enabled = t.id != null) {
                Icon(if (r == -1) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt, "No me gusta",
                    tint = if (r == -1) MRed else Color.White)
            }
            if (t != null) IconButton(onClick = { Music.like() }, enabled = t.id != null) {
                Icon(if (r == 1) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, "Me gusta",
                    tint = if (r == 1) MRed else Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Slider(value = dragging ?: frac, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { Music.seek((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF4D4D4D)))
        Row(Modifier.fillMaxWidth()) {
            Text(mmss(((dragging ?: frac) * dur).toLong()), color = MSub, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(mmss(dur), color = MSub, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Music.toggleShuffle() }) {
                Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (Music.shuffle) MRed else Color.White)
            }
            IconButton(onClick = { Music.prev() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, "Anterior", modifier = Modifier.size(38.dp))
            }
            Box(Modifier.size(68.dp).clip(CircleShape).background(Color.White).clickable { Music.toggle() },
                contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pausa" else "Reproducir",
                    tint = Color.Black, modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { Music.next() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, "Siguiente", modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = { Music.cycleRepeat() }) {
                Icon(if (Music.repeat == 2) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repetir",
                    tint = if (Music.repeat > 0) MRed else Color.White)
            }
        }
    }
}

/** Barra de "ambiente": una frase que guía las canciones auto (p. ej. "años 80", "alegre", "para correr"). */
@Composable
private fun VibeBar() {
    var text by remember { mutableStateOf(Music.vibe) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun apply() { Music.updateVibe(text); focus.clearFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = if (Music.vibe.isNotBlank()) MRed else MSub) },
            placeholder = { Text("Ambiente: años 80, alegre, para correr…", color = MSub) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = { text = ""; Music.updateVibe(""); focus.clearFocus() }) {
                    Icon(Icons.Default.Close, "Quitar")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF4D4D4D),
                cursorColor = Color.White),
        )
        Text(if (Music.vibe.isBlank()) "Escribe un ambiente y las canciones que se añaden solas lo seguirán"
            else "Las canciones auto siguen el ambiente «${Music.vibe}»", color = MSub, fontSize = 11.sp,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp))
    }
}

/**
 * Fila de la lista: nombre, artista y duración.
 * - Mantener pulsada y arrastrar (o arrastrar desde ≡) para moverla.
 * - Deslizar hacia un lado para quitarla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackRow(i: Int, t: Track, edit: Boolean, dragging: Boolean, dragOffset: Float,
                     onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val isCurrent = i == Music.index
    val idx by rememberUpdatedState(i)
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled) { Music.remove(Music.queue.indexOf(t).takeIf { it >= 0 } ?: idx); true } else false
    })
    val dragMod = Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
            onDragEnd = { onDragEnd() }, onDragCancel = { onDragEnd() })
    }
    SwipeToDismissBox(state = swipe, enableDismissFromStartToEnd = !dragging, enableDismissFromEndToStart = !dragging,
        modifier = Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = dragOffset },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Color(0xFF8B1A1A)).padding(horizontal = 24.dp),
                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp)); Text("Quitar", color = Color.White)
                }
            }
        }) {
        Row(Modifier.fillMaxWidth().height(60.dp)
            .background(if (dragging) Color(0xFF2A2A2A) else Color(0xFF111111))
            .then(dragMod)
            .clickable(enabled = !edit && !dragging) { Music.playAt(idx) }.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isCurrent && Music.active) Icon(Icons.Default.GraphicEq, null, tint = MRed, modifier = Modifier.size(18.dp))
                else Text("${i + 1}", color = MSub, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = if (isCurrent) MRed else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(t.artist.ifBlank { null }, t.duration?.let { fmtDur(it) },
                    if (t.auto) "auto" else null).joinToString(" · "),
                    color = MSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (Music.ratingOf(t)) {
                1 -> Icon(Icons.Default.ThumbUp, "Me gusta", tint = MRed, modifier = Modifier.size(16.dp))
                -1 -> Icon(Icons.Default.ThumbDown, "No me gusta", tint = MSub, modifier = Modifier.size(16.dp))
            }
            // Asa para arrastrar sin tener que mantener pulsado
            Box(Modifier.size(44.dp).pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                    onDragEnd = { onDragEnd() }, onDragCancel = { onDragEnd() })
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, "Mover", tint = MSub)
            }
        }
    }
}

/** Pegar un enlace de Spotify (lista, álbum o canción) o de YouTube (lista o vídeo). */
@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Importar lista") },
        text = {
            Column {
                Text("Pega un enlace de Spotify (lista, álbum o canción) o de YouTube (lista o vídeo). " +
                    "De Spotify solo se leen los nombres; el sonido sale de YouTube.", color = MSub, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true,
                    placeholder = { Text("https://open.spotify.com/playlist/…") })
            }
        },
        confirmButton = { TextButton(onClick = { if (link.isNotBlank()) { onImport(link); onDismiss() } }) { Text("Añadir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

/** Buscar canciones y añadirlas a la lista con "+". */
@Composable
private fun SearchAddDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Video>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(setOf<String>()) }
    fun go() { if (q.isBlank()) return; busy = true
        scope.launch {
            res = runCatching { Innertube.musicSearch(q).map { it.video } }.getOrDefault(emptyList())
                .ifEmpty { runCatching { Innertube.search(q) }.getOrDefault(emptyList()).filter { (it.duration ?: 0) in 30..1200 } }
            busy = false
        } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Añadir canciones") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Artista o canción") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Default.Search, "Buscar") } })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = MRed)
                LazyColumn {
                    items(res, key = { it.id }) { v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(v.channel, v.duration?.let { fmtDur(it) }).joinToString(" · "), color = MSub, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                Music.add(Track(v.id, v.title, v.channel, v.duration)); added = added + v.id
                            }) {
                                Icon(if (v.id in added) Icons.Default.Check else Icons.Default.Add, "Añadir",
                                    tint = if (v.id in added) MRed else Color.White)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Hecho") } })
}

/** Barra pequeña abajo cuando suena música y estás en otra pantalla. */
@Composable
fun MusicMiniBar(onOpen: () -> Unit) {
    val t = Music.current ?: return
    val player = remember { PlayerHolder.get(App.instance) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { playing = player.isPlaying; delay(400) } }
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2A2A2A)).clickable(onClick = onOpen).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MCard)) {
                t.thumb?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = MSub, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { Music.toggle() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pausa") }
            IconButton(onClick = { Music.next() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
        }
    }
}

@Suppress("unused") private val keepIcon = Icons.AutoMirrored.Filled.QueueMusic
