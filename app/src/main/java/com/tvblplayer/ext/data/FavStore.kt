package com.tvblplayer.ext.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一条收藏记录 */
data class FavEntry(
    val title: String,
    val image: String?,
    val url: String,
    val site: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("image", image ?: "")
        put("url", url)
        put("site", site)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = FavEntry(
            title = o.optString("title"),
            image = o.optString("image").takeIf { it.isNotBlank() },
            url = o.optString("url"),
            site = o.optString("site"),
            addedAt = o.optLong("addedAt", 0L),
        )
    }
}

/**
 * 收藏存储。按 URL 去重，最新收藏排在前面。
 */
class FavStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("favs", Context.MODE_PRIVATE)

    fun all(): List<FavEntry> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { FavEntry.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.addedAt }
    }

    private fun save(list: List<FavEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(entry: FavEntry) {
        val list = all().filterNot { it.url == entry.url }.toMutableList()
        list.add(0, entry)
        save(list)
    }

    fun remove(url: String) {
        save(all().filterNot { it.url == url })
    }

    fun contains(url: String): Boolean = all().any { it.url == url }

    companion object {
        private const val KEY = "fav_list_json"
    }
}
