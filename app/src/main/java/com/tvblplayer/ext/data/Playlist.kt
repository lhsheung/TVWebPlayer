package com.tvblplayer.ext.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 播放列表里的一个频道/节目 */
data class PlaylistEntry(
    val title: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    /** PotPlayer 会把它标记为失效；保留但默认靠后 */
    val invalid: Boolean = false,
)

/** 一份导入的播放列表 */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val entries: List<PlaylistEntry>,
    val importedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("importedAt", importedAt)
        put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("title", e.title)
                    put("url", e.url)
                    put("logo", e.logo ?: "")
                    put("group", e.group ?: "")
                    put("invalid", e.invalid)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = e.optString("url")
                if (url.isBlank()) return@mapNotNull null
                PlaylistEntry(
                    title = e.optString("title"),
                    url = url,
                    logo = e.optString("logo").takeIf { it.isNotBlank() },
                    group = e.optString("group").takeIf { it.isNotBlank() },
                    invalid = e.optBoolean("invalid", false),
                )
            }
            return Playlist(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "未命名列表" },
                entries = entries,
                importedAt = o.optLong("importedAt", 0L),
            )
        }
    }
}
