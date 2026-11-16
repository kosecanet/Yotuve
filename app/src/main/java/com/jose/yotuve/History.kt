package com.jose.yotuve

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Historial local de vídeos vistos (los últimos 50), base de las recomendaciones del inicio. */
object History {
    private fun prefs() = App.instance.getSharedPreferences("historial", Context.MODE_PRIVATE)

    fun list(): List<Video> = runCatching {
        val arr = JSONArray(prefs().getString("vistos", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Video(o.getString("id"), o.optString("t"), o.optString("c"), null, null,
                "https://i.ytimg.com/vi/${o.getString("id")}/hqdefault.jpg")
        }
    }.getOrDefault(emptyList())

    fun add(v: Video) {
        val all = (listOf(v) + list().filter { it.id != v.id }).take(50)
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.title).put("c", it.channel)) }
        prefs().edit().putString("vistos", arr.toString()).apply()
    }
}

/** Punto de reproducción de cada vídeo, para continuar donde lo dejaste. */
object Positions {
    private fun prefs() = App.instance.getSharedPreferences("posiciones", Context.MODE_PRIVATE)

    /** Posición guardada en ms (0 si no hay o si ya lo terminaste). */
    fun get(id: String): Long = prefs().getLong("p_$id", 0L)

    fun save(id: String, pos: Long, dur: Long) {
        if (pos < 10_000) return                          // menos de 10 s: no merece la pena
        if (dur > 0 && pos > dur - 20_000) { clear(id); return } // casi terminado: se da por visto
        prefs().edit().putLong("p_$id", pos).putLong("d_$id", dur).apply()
    }

    fun clear(id: String) { prefs().edit().remove("p_$id").remove("d_$id").apply() }

    /** Progreso 0..1 para la barra roja de la miniatura, o null. */
    fun progress(id: String): Float? {
        val p = get(id); val d = prefs().getLong("d_$id", 0L)
        return if (p > 0 && d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else null
    }
}

/** Último inicio cargado: se muestra al instante al abrir la app mientras se refresca. */
object FeedCache {
    private fun prefs() = App.instance.getSharedPreferences("inicio", Context.MODE_PRIVATE)
    fun save(list: List<Video>) {
        val arr = JSONArray()
        list.take(40).forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.title).put("c", it.channel)
                .put("d", it.duration ?: -1).put("v", it.views ?: -1).put("ts", it.timestamp ?: 0))
        }
        prefs().edit().putString("lista", arr.toString()).apply()
    }
    fun load(): List<Video> = runCatching {
        val arr = JSONArray(prefs().getString("lista", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i); val id = o.getString("id")
            Video(id, o.optString("t"), o.optString("c"), o.optLong("d").takeIf { it >= 0 },
                o.optLong("v").takeIf { it >= 0 }, "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                o.optLong("ts").takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())
}
