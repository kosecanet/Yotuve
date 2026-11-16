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
