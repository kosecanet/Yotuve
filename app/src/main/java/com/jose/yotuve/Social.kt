package com.jose.yotuve

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val Sub2 = Color(0xFFAAAAAA)
private val CardBg = Color(0xFF272727)

fun fmtCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1f M".format(n / 1e6).replace(".0 ", " ")
    n >= 1_000 -> "%.1f K".format(n / 1e3).replace(".0 ", " ")
    else -> n.toString()
}

@Composable
fun Avatar(url: String?, name: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(0xFF3A3A3A)), contentAlignment = Alignment.Center) {
        Text(name.trimStart('@').take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp)
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Bajo el vídeo: fila del canal, descripción desplegable y tarjeta de comentarios (como YouTube). */
@Composable
fun DetailsSection(vm: MainVM, v: Video) {
    val d = vm.details
    // --- Canal
    Row(Modifier.fillMaxWidth().clickable { vm.openChannel(d?.channelUrl) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(vm.channelAvatar, d?.channel ?: v.channel, 38)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d?.channel ?: v.channel, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            d?.followers?.let { Text("${fmtCount(it)} suscriptores", color = Sub2, fontSize = 12.sp) }
        }
        Text("Ver canal", fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 7.dp),
            color = Color.Black)
    }
    // --- Descripción
    var open by remember(v.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).clickable { open = !open }
        .padding(12.dp).animateContentSize()) {
        val meta = listOfNotNull((d?.views ?: v.views)?.let { "${fmtCount(it)} visualizaciones" },
            d?.date?.let { date -> fmtAgo(d.timestamp)?.let { "$date ($it)" } ?: date },
            d?.likes?.let { "${fmtCount(it)} me gusta" }).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        when {
            d == null && vm.error != null -> Text("No disponible", color = Sub2, fontSize = 13.sp)
            d == null -> Text("Cargando descripción…", color = Sub2, fontSize = 13.sp)
            d.description.isBlank() -> Text("Sin descripción", color = Sub2, fontSize = 13.sp)
            else -> Text(d.description, fontSize = 13.sp, maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis)
        }
        if (d != null && d.description.isNotBlank()) Text(if (open) "Mostrar menos" else "…más", color = Sub2, fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))
    // --- Comentarios (vista previa; al tocar se abre el panel)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
        .clickable { if (d != null) vm.openComments() }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comentarios", fontWeight = FontWeight.Medium)
            d?.commentCount?.let { Text("  ${fmtCount(it)}", color = Sub2, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.UnfoldMore, null, tint = Sub2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        val first = d?.comments?.firstOrNull()
        when {
            d == null && vm.error != null -> Text("No disponible", color = Sub2, fontSize = 13.sp)
            d == null -> Text("Cargando comentarios…", color = Sub2, fontSize = 13.sp)
            first == null && d.commentCount == 0L -> Text("No hay comentarios o están desactivados", color = Sub2, fontSize = 13.sp)
            first == null -> Text("Toca para ver los comentarios", color = Sub2, fontSize = 13.sp)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                first!!
                Avatar(first.avatar, first.author, 22)
                Spacer(Modifier.width(8.dp))
                Text(first.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Panel desplegable de comentarios (se arrastra hacia arriba para verlo entero). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(vm: MainVM) {
    val d = vm.details ?: return
    if (!vm.showComments) return
    if (d.comments.isEmpty()) {
        ModalBottomSheet(onDismissRequest = { vm.showComments = false }, containerColor = Color(0xFF212121)) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                val err = vm.commentsError
                when {
                    vm.commentsLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Red)
                        Text("Cargando comentarios…", color = Sub2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Text(err, color = Sub2, fontSize = 13.sp)
                        TextButton(onClick = { vm.loadComments(20) }) { Text("Reintentar") }
                    }
                    else -> Text("No hay comentarios", color = Sub2)
                }
            }
        }
        return
    }
    ModalBottomSheet(onDismissRequest = { vm.showComments = false }, containerColor = Color(0xFF212121)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Comentarios", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            d.commentCount?.let { Text("  ${fmtCount(it)}", color = Sub2) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.showComments = false }) { Icon(Icons.Default.Close, "Cerrar") }
        }
        HorizontalDivider(color = Color(0xFF333333))
        LazyColumn(Modifier.fillMaxHeight(0.9f)) {
            items(d.comments.size) { i -> val c = d.comments[i]
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Box(Modifier.clickable { vm.showComments = false; vm.openChannel(c.authorUrl) }) { Avatar(c.avatar, c.author, 34) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        if (c.pinned) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PushPin, null, tint = Sub2, modifier = Modifier.size(12.dp))
                            Text(" Fijado", color = Sub2, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.author, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = if (c.isUploader) Color.Black else Sub2,
                                modifier = (if (c.isUploader) Modifier.clip(RoundedCornerShape(10.dp)).background(Sub2)
                                    .padding(horizontal = 6.dp) else Modifier)
                                    .clickable { vm.showComments = false; vm.openChannel(c.authorUrl) })
                            if (c.time.isNotEmpty()) Text("  · ${c.time}", fontSize = 12.sp, color = Sub2)
                        }
                        Text(c.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ThumbUpOffAlt, null, tint = Sub2, modifier = Modifier.size(14.dp))
                            c.likes?.takeIf { it > 0 }?.let { Text("  ${fmtCount(it)}", fontSize = 12.sp, color = Sub2) }
                        }
                    }
                }
            }
            // Final de la lista: cargar más / cargando / error
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    val err = vm.commentsError
                    when {
                        vm.commentsLoading -> CircularProgressIndicator(color = Color.Red)
                        err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(err, color = Sub2, fontSize = 12.sp)
                            TextButton(onClick = { vm.moreComments() }) { Text("Reintentar") }
                        }
                        vm.canMoreComments -> TextButton(onClick = { vm.moreComments() }) { Text("Cargar más comentarios") }
                    }
                }
            }
        }
    }
}

/** Perfil del canal: banner, avatar, suscriptores, descripción y sus vídeos. */
@Composable
fun ChannelScreen(vm: MainVM) {
    val c = vm.channel
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (!vm.back()) vm.closeChannel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
        Text(c?.name ?: "Canal", fontWeight = FontWeight.Medium, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (c == null) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            vm.channelError?.let { Text(it, color = Color(0xFFFF6E6E)) } ?: CircularProgressIndicator(color = Color.Red)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            c.banner?.let {
                AsyncImage(it, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(6.2f).padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)))
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.avatar, c.name, 72)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(c.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(listOfNotNull(c.followers?.let { "${fmtCount(it)} suscriptores" }, "${c.videos.size}+ vídeos")
                        .joinToString(" · "), color = Sub2, fontSize = 13.sp)
                }
            }
            if (c.description.isNotBlank()) {
                var open by remember { mutableStateOf(false) }
                Text(c.description, color = Sub2, fontSize = 13.sp, maxLines = if (open) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { open = !open }.padding(horizontal = 12.dp).animateContentSize())
            }
            Text("Vídeos", fontWeight = FontWeight.Medium, modifier = Modifier.padding(12.dp))
        }
        items(c.videos, key = { it.id }) { v -> VideoCard(v) { vm.play(v) } }
        item {   // al llegar al final: 30 vídeos más
            LaunchedEffect(c.videos.size) { vm.channelMore() }
            if (vm.channelLoadingMore) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Red)
            }
        }
    }
}
