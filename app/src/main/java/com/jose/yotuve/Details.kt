package com.jose.yotuve

import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Comment(
    val author: String, val authorUrl: String?, val avatar: String?, val text: String,
    val likes: Long?, val time: String, val isUploader: Boolean, val pinned: Boolean,
)

data class Details(
    val description: String, val likes: Long?, val date: String?, val channel: String,
    val channelUrl: String?, val followers: Long?, val commentCount: Long?, val comments: List<Comment>,
    val channelId: String? = null, val timestamp: Long? = null, val views: Long? = null,
)

data class ChannelInfo(
    val name: String, val url: String, val avatar: String?, val banner: String?,
    val followers: Long?, val description: String, val videos: List<Video>,
)

private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }
private fun JSONObject.num(k: String) = optLong(k, -1).takeIf { it >= 0 }

internal fun detailsFrom(j: JSONObject, v: Video, comments: List<Comment>): Details {
    val d = j.str("upload_date")
    return Details(
        description = j.str("description") ?: "",
        likes = j.num("like_count"),
        date = d?.takeIf { it.length == 8 }?.let { "${it.substring(6, 8)}/${it.substring(4, 6)}/${it.substring(0, 4)}" },
        channel = j.str("channel") ?: j.str("uploader") ?: v.channel,
        channelUrl = j.str("channel_url") ?: j.str("uploader_url"),
        followers = j.num("channel_follower_count"),
        commentCount = j.num("comment_count"),
        comments = comments,
        channelId = j.str("channel_id"),
        timestamp = Repo.tsOf(j),
        views = j.num("view_count"),
    )
}

private val commentsCache = HashMap<String, List<Comment>>()

/**
 * Comentarios más destacados, de [max] en [max] (20 → 60 → 150…). yt-dlp no puede seguir donde lo
 * dejó, así que cada "cargar más" pide más de golpe; con un tope de tiempo para que nunca se quede colgado.
 */
suspend fun Repo.comments(v: Video, max: Int = 20): List<Comment> = withContext(Dispatchers.IO) {
    synchronized(commentsCache) { commentsCache["${v.id}:$max"] }?.let { return@withContext it }
    val req = YoutubeDLRequest(v.url).apply {
        addOption("-J"); addOption("--skip-download"); addOption("--no-playlist")
        addOption("--write-comments")
    }
    val j = JSONObject(execPublic(req, yt = "max_comments=$max,$max,0,0;comment_sort=top;skip=translated_subs",
        timeoutMs = if (max <= 20) 40_000 else 75_000).out)
    val arr = j.optJSONArray("comments")
    val list = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
        .filter { (it.str("parent") ?: "root") == "root" }
        .map {
            Comment(
                author = it.str("author") ?: "", authorUrl = it.str("author_url"),
                avatar = it.str("author_thumbnail"), text = it.str("text") ?: "",
                likes = it.num("like_count"), time = it.str("_time_text") ?: "",
                isUploader = it.optBoolean("author_is_uploader"), pinned = it.optBoolean("is_pinned"),
            )
        }.sortedByDescending { it.pinned }
    synchronized(commentsCache) { commentsCache["${v.id}:$max"] = list }
    list
}

private val avatarCache = HashMap<String, String?>()

/**
 * Avatar del canal sin lanzar yt-dlp: se lee la etiqueta og:image de la página del canal (muy ligero).
 */
suspend fun Repo.channelAvatar(channelId: String): String? = withContext(Dispatchers.IO) {
    synchronized(avatarCache) { if (avatarCache.containsKey(channelId)) return@withContext avatarCache[channelId] }
    val url = runCatching {
        val c = (java.net.URL("https://www.youtube.com/channel/$channelId").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb")   // evita la página de consentimiento de cookies
        }
        val html = c.inputStream.bufferedReader().use { r -> r.readText() }
        Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""avatar":\{"thumbnails":\[\{"url":"([^"]+)"""").find(html)?.groupValues?.get(1)
    }.getOrNull()
    synchronized(avatarCache) { avatarCache[channelId] = url }
    url
}

/** Página de un canal: avatar, banner, suscriptores, descripción y últimos vídeos. */
suspend fun Repo.channel(url: String, start: Int = 1): ChannelInfo = withContext(Dispatchers.IO) {
    val base = url.trimEnd('/').removeSuffix("/videos").removeSuffix("/featured")
    val req = YoutubeDLRequest("$base/videos").apply {
        addOption("--flat-playlist"); addOption("-J")
        addOption("--playlist-items", "$start-${start + 29}")   // de 30 en 30
        addOption("--extractor-args", "youtubetab:approximate_date")
        addOption("--no-warnings")
    }
    val j = JSONObject(execPublic(req, timeoutMs = 45_000).out)
    val thumbs = j.optJSONArray("thumbnails")
    fun thumb(id: String) = (0 until (thumbs?.length() ?: 0)).map { thumbs!!.getJSONObject(it) }
        .firstOrNull { it.optString("id") == id }?.str("url")
    val ents = j.optJSONArray("entries")
    val name = j.str("channel") ?: j.str("uploader") ?: (j.str("title") ?: "").substringBefore(" - ")
    val videos = (0 until (ents?.length() ?: 0)).mapNotNull { i ->
        val e = ents!!.getJSONObject(i)
        val id = e.str("id")?.takeIf { it.length == 11 } ?: return@mapNotNull null
        Video(id, e.str("title") ?: "", name, e.optDouble("duration").takeIf { !it.isNaN() }?.toLong(),
            e.num("view_count"), "https://i.ytimg.com/vi/$id/hqdefault.jpg", Repo.tsOf(e))
    }
    ChannelInfo(
        name = name, url = base,
        avatar = thumb("avatar_uncropped"), banner = thumb("banner_uncropped"),
        followers = j.num("channel_follower_count"),
        description = j.str("description") ?: "", videos = videos,
    )
}
