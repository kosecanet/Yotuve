package com.jose.yotuve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Búsqueda directa en YouTube (la misma API que usa la web), sin arrancar Python/yt-dlp:
 * mucho más rápida y trae la fecha de subida ("hace 3 días"), que yt-dlp no da en las búsquedas.
 * Si falla, Repo vuelve a usar yt-dlp.
 */
object Innertube {
    private const val VERSION = "2.20251110.00.00"
    private val conts = HashMap<String, String?>()   // página siguiente de cada búsqueda

    private fun lang() = java.util.Locale.getDefault().language.ifBlank { "es" }
    private fun country() = java.util.Locale.getDefault().country.ifBlank { "ES" }

    /** Busca [q]. Con [next] = true devuelve la página siguiente de la última búsqueda de [q]. */
    suspend fun search(q: String, next: Boolean = false): List<Video> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", VERSION).put("hl", lang()).put("gl", country())))
        if (next) {
            val token = synchronized(conts) { conts[q] } ?: return@withContext emptyList()
            body.put("continuation", token)
        } else body.put("query", q)
        val c = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36")
            setRequestProperty("X-YouTube-Client-Name", "1")
            setRequestProperty("X-YouTube-Client-Version", VERSION)
            setRequestProperty("Accept-Language", "${lang()}-${country()},${lang()};q=0.9")
            setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) error("YouTube respondió ${c.responseCode}")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val out = ArrayList<Video>()
        var token: String? = null
        walk(json) { key, obj ->
            when (key) {
                "videoRenderer" -> parseVideo(obj)?.let { out += it }
                "lockupViewModel" -> parseLockup(obj)?.let { out += it }   // formato nuevo de YouTube
                "continuationCommand" -> if (token == null) token = obj.optString("token").ifBlank { null }
            }
        }
        synchronized(conts) { conts[q] = token }
        out.distinctBy { it.id }
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val c = (URL("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 6000; readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36")
            setRequestProperty("X-YouTube-Client-Name", "1")
            setRequestProperty("X-YouTube-Client-Version", VERSION)
            setRequestProperty("Accept-Language", "${lang()}-${country()},${lang()};q=0.9")
            setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) error("YouTube respondió ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    private fun ctx() = JSONObject().put("client", JSONObject()
        .put("clientName", "WEB").put("clientVersion", VERSION).put("hl", lang()).put("gl", country()))

    /**
     * Datos del vídeo (descripción, visitas, fecha, canal, suscriptores, "me gusta", nº de comentarios)
     * directamente de YouTube: 2 peticiones HTTP rápidas, sin Python. Va en paralelo con yt-dlp.
     */
    suspend fun details(v: Video): Details = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", ctx()).put("videoId", v.id)
        val player = post("player", body)
        val next = runCatching { post("next", JSONObject().put("context", ctx()).put("videoId", v.id)) }.getOrNull()
        val vd = player.optJSONObject("videoDetails")
        val mf = player.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val dateIso = mf?.optString("publishDate")?.ifBlank { null } ?: mf?.optString("uploadDate")?.ifBlank { null }
        val ts = dateIso?.let { d ->
            runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(d.take(10))!!.time / 1000
            }.getOrNull()
        }
        val date = dateIso?.take(10)?.split("-")?.takeIf { it.size == 3 }?.let { "${it[2]}/${it[1]}/${it[0]}" }
        var subs: Long? = null; var comments: Long? = null
        if (next != null) walk(next) { k, o ->
            if (k == "subscriberCountText" && subs == null) subs = text(o)?.let(::parseCount)
            if (k == "commentsEntryPointHeaderRenderer" && comments == null) comments = text(o.optJSONObject("commentCount"))?.let(::parseCount)
        }
        val likes = next?.toString()?.let { raw ->
            Regex(""""likeCount":"(\d+)"""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex(""""likeCountIfIndifferentNumber":"(\d+)"""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        }
        val channelId = vd?.optString("channelId")?.ifBlank { null }
        // Recomendados que da YouTube para este vídeo (vienen en la misma respuesta)
        val rel = ArrayList<Video>()
        if (next != null) walk(next) { k, o ->
            when (k) {
                "compactVideoRenderer" -> parseVideo(o)?.let { rel += it }
                "lockupViewModel" -> parseLockup(o)?.let { rel += it }
            }
        }
        Details(
            description = vd?.optString("shortDescription") ?: "",
            likes = likes, date = date,
            channel = vd?.optString("author")?.ifBlank { null } ?: v.channel,
            channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
            followers = subs, commentCount = comments, comments = emptyList(),
            channelId = channelId, timestamp = ts,
            views = vd?.optString("viewCount")?.toLongOrNull(),
            related = rel.filter { it.id != v.id }.distinctBy { it.id },
        )
    }

    // ---------- Enlaces directos con el cliente android_vr (el mismo que usa yt-dlp, sin JavaScript)
    private data class Vr(val version: String, val ua: String, val make: String, val model: String,
                          val sdk: Int, val osVersion: String)

    /**
     * Datos del cliente android_vr. Se leen del propio yt-dlp instalado en la app (así, cuando yt-dlp se
     * actualiza porque YouTube cambia algo, esto también); si no se puede, valores conocidos.
     */
    private val vr: Vr by lazy {
        val def = Vr("1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
            "eureka-user Build/SQ3A.220605.009.A1) gzip", "Oculus", "Quest 3", 32, "12L")
        runCatching {
            val zip = java.util.zip.ZipFile(java.io.File(App.instance.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp"))
            val src = zip.use { z ->
                z.getEntry("yt_dlp/extractor/youtube/_base.py")?.let { e -> z.getInputStream(e).bufferedReader().readText() }
            } ?: return@runCatching def
            val i = src.indexOf("'android_vr': {").takeIf { it >= 0 } ?: return@runCatching def
            val block = src.substring(i, minOf(src.length, i + 1500))
            fun g(k: String) = Regex("""'$k':\s*'([^']+)'""").find(block)?.groupValues?.get(1)
            Vr(g("clientVersion") ?: def.version, g("userAgent") ?: def.ua, g("deviceMake") ?: def.make,
                g("deviceModel") ?: def.model,
                Regex("""'androidSdkVersion':\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: def.sdk,
                g("osVersion") ?: def.osVersion)
        }.getOrDefault(def)
    }

    /**
     * Enlaces para reproducir pedidos directamente a YouTube (una petición HTTP, sin Python).
     * Devuelve null si YouTube no da enlaces directos para ese vídeo (entonces se usa yt-dlp).
     */
    suspend fun streams(v: Video, maxHeight: Int): Streams? = withContext(Dispatchers.IO) {
        val c = vr
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "ANDROID_VR").put("clientVersion", c.version)
            .put("deviceMake", c.make).put("deviceModel", c.model).put("androidSdkVersion", c.sdk)
            .put("userAgent", c.ua).put("osName", "Android").put("osVersion", c.osVersion)
            .put("hl", lang()).put("gl", country())))
            .put("videoId", v.id).put("contentCheckOk", true).put("racyCheckOk", true)
        val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 5000; readTimeout = 7000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", c.ua)
            setRequestProperty("X-YouTube-Client-Name", "28")
            setRequestProperty("X-YouTube-Client-Version", c.version)
            setRequestProperty("Origin", "https://www.youtube.com")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) return@withContext null
        val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        if (j.optJSONObject("playabilityStatus")?.optString("status") != "OK") return@withContext null
        val sd = j.optJSONObject("streamingData") ?: return@withContext null
        fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.filter { it.has("url") }
        val adaptive = list(sd.optJSONArray("adaptiveFormats"))
        val videos = adaptive.filter { "avc1" in it.optString("mimeType") && it.optInt("height") in 144..maxHeight }
            .groupBy { it.optInt("height") }
            .map { (h, l) -> VFormat(h, l.maxBy { it.optLong("bitrate") }.getString("url")) }
            .sortedBy { it.height }
        val audio = adaptive.filter { it.optString("mimeType").startsWith("audio/mp4") }.maxByOrNull { it.optLong("bitrate") }
        if (videos.isNotEmpty() && audio != null)
            return@withContext Streams(videos.last().url, audio.getString("url"), v.title, videos, c.ua,
                dash = runCatching { dashManifest(adaptive, maxHeight) }.getOrNull())
        // Solo calidad básica (vídeo+audio juntos, 360p): sirve para empezar ya; luego se sube de calidad
        val prog = list(sd.optJSONArray("formats")).maxByOrNull { it.optInt("height") } ?: return@withContext null
        Streams(prog.getString("url"), null, v.title, emptyList(), c.ua)
    }

    /**
     * Manifiesto DASH (como hace NewPipe) con todas las calidades H.264 y el audio. Con él, el reproductor
     * arranca en una calidad baja (empieza al instante) y va subiendo sola sin cortes según la conexión
     * y el tamaño de la pantalla. null si a algún formato le faltan los datos necesarios.
     */
    private fun dashManifest(adaptive: List<JSONObject>, maxHeight: Int): String? {
        fun range(o: JSONObject, k: String) = o.optJSONObject(k)?.let { "${it.optString("start")}-${it.optString("end")}" }
        fun codecs(o: JSONObject) = Regex("""codecs="([^"]+)"""").find(o.optString("mimeType"))?.groupValues?.get(1)
        fun esc(u: String) = u.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val ok = adaptive.filter { range(it, "initRange") != null && range(it, "indexRange") != null && codecs(it) != null }
        val vids = ok.filter { "avc1" in it.optString("mimeType") && it.optInt("height") in 144..maxHeight }
            .groupBy { it.optInt("height") }.map { (_, l) -> l.maxBy { it.optLong("bitrate") } }
        var auds = ok.filter { it.optString("mimeType").startsWith("audio/mp4") }
        // Vídeos con varios idiomas de audio: solo el original
        if (auds.any { it.has("audioTrack") }) auds = auds.filter { it.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") == true }
        auds = auds.filter { "drc" !in it.optString("xtags") }
        if (vids.isEmpty() || auds.isEmpty()) return null
        val durMs = (vids + auds).firstNotNullOfOrNull { it.optString("approxDurationMs").toLongOrNull() } ?: return null
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" """)
        sb.append("""profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" """)
        sb.append("""mediaPresentationDuration="PT${durMs / 1000.0}S"><Period>""")
        sb.append("""<AdaptationSet mimeType="video/mp4" subsegmentAlignment="true">""")
        for (f in vids) {
            sb.append("""<Representation id="v${f.optInt("itag")}" codecs="${codecs(f)}" bandwidth="${f.optLong("bitrate")}" """)
            sb.append("""width="${f.optInt("width")}" height="${f.optInt("height")}" frameRate="${f.optInt("fps", 30)}">""")
            sb.append("<BaseURL>${esc(f.getString("url"))}</BaseURL>")
            sb.append("""<SegmentBase indexRange="${range(f, "indexRange")}"><Initialization range="${range(f, "initRange")}"/></SegmentBase></Representation>""")
        }
        sb.append("</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\" subsegmentAlignment=\"true\">")
        for (f in auds) {
            sb.append("""<Representation id="a${f.optInt("itag")}" codecs="${codecs(f)}" bandwidth="${f.optLong("bitrate")}" """)
            sb.append("""audioSamplingRate="${f.optString("audioSampleRate", "44100")}">""")
            sb.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${f.optInt("audioChannels", 2)}"/>""")
            sb.append("<BaseURL>${esc(f.getString("url"))}</BaseURL>")
            sb.append("""<SegmentBase indexRange="${range(f, "indexRange")}"><Initialization range="${range(f, "initRange")}"/></SegmentBase></Representation>""")
        }
        sb.append("</AdaptationSet></Period></MPD>")
        return sb.toString()
    }

    /** Recorre todo el JSON llamando a [f] con cada objeto y su clave. */
    private fun walk(o: Any?, f: (String, JSONObject) -> Unit) {
        when (o) {
            is JSONObject -> o.keys().forEach { k ->
                val v = o.opt(k)
                if (v is JSONObject) f(k, v)
                walk(v, f)
            }
            is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i), f)
        }
    }

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }.ifBlank { null }
    }

    private fun parseVideo(v: JSONObject): Video? {
        val id = v.optString("videoId").takeIf { it.length == 11 } ?: return null
        return Video(
            id = id,
            title = text(v.optJSONObject("title")) ?: "",
            channel = text(v.optJSONObject("ownerText")) ?: text(v.optJSONObject("longBylineText")) ?: "",
            duration = text(v.optJSONObject("lengthText"))?.let(::parseDuration),
            views = text(v.optJSONObject("viewCountText"))?.filter { it.isDigit() }?.toLongOrNull(),
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            timestamp = text(v.optJSONObject("publishedTimeText"))?.let(::parseAgo),
        )
    }

    /** Formato nuevo de YouTube (lockupViewModel): título, canal, visitas, fecha y duración en "partes". */
    private fun parseLockup(o: JSONObject): Video? {
        if (!o.optString("contentType").contains("VIDEO")) return null
        val id = o.optString("contentId").takeIf { it.length == 11 } ?: return null
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content") ?: ""
        val parts = ArrayList<String>()
        walk(meta.optJSONObject("metadata")) { k, obj -> if (k == "text") obj.optString("content").takeIf { it.isNotBlank() }?.let { parts += it } }
        var dur: Long? = null
        walk(o.optJSONObject("contentImage")) { k, obj ->
            if (k == "text" || k == "thumbnailBadgeViewModel") {
                val t = obj.optString("content").ifBlank { obj.optString("text") }
                if (dur == null && Regex("""^\d+(:\d\d)+$""").matches(t)) dur = parseDuration(t)
            }
        }
        val views = parts.firstOrNull { "visualiz" in it.lowercase() || "view" in it.lowercase() }
        val ago = parts.firstOrNull { "hace" in it.lowercase() || "ago" in it.lowercase() }
        val channel = parts.firstOrNull { it != views && it != ago } ?: ""
        return Video(id, title, channel, dur,
            views?.let { v -> parseCount(v) }, "https://i.ytimg.com/vi/$id/hqdefault.jpg", ago?.let(::parseAgo))
    }

    /** "1,2 M visualizaciones" / "26.433 views" → número. */
    private fun parseCount(s: String): Long? {
        val t = s.lowercase()
        val num = Regex("""[\d.,]+""").find(t)?.value ?: return null
        val mult = when {
            "mill" in t || Regex("""\d\s?m(\s|$)""").containsMatchIn(t) -> 1_000_000.0   // "1,2 M", "1.2M"
            " mil" in t || Regex("""\d\s?k""").containsMatchIn(t) -> 1_000.0              // "66 mil", "66K"
            else -> 1.0
        }
        return if (mult > 1) num.replace(",", ".").toDoubleOrNull()?.let { (it * mult).toLong() }
        else num.filter { it.isDigit() }.toLongOrNull()
    }

    /** "1:16:16" → segundos. */
    private fun parseDuration(s: String): Long? =
        s.split(":").mapNotNull { it.trim().toLongOrNull() }.takeIf { it.isNotEmpty() }?.fold(0L) { a, n -> a * 60 + n }

    /** "hace 3 días" / "3 days ago" / "Emitido hace 2 semanas" → fecha aproximada (segundos). */
    fun parseAgo(s: String): Long? {
        val t = s.lowercase()
        val n = Regex("""\d+""").find(t)?.value?.toLongOrNull() ?: 1
        val unit = when {
            "segundo" in t || "second" in t -> 1L
            "minuto" in t || "minute" in t -> 60L
            "hora" in t || "hour" in t -> 3600L
            "día" in t || "dia" in t || "day" in t -> 86400L
            "semana" in t || "week" in t -> 7 * 86400L
            "mes" in t || "month" in t -> 30 * 86400L
            "año" in t || "ano" in t || "year" in t -> 365 * 86400L
            else -> return null
        }
        return System.currentTimeMillis() / 1000 - n * unit
    }
}
