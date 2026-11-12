package com.tvblplayer.ext.data

import org.json.JSONObject
import java.util.UUID

/**
 * 一个用户自己添加的站点。App 不内置任何内容，只保存用户配置。
 */
data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    /** 最近访问时间，用于排序 */
    val lastUsed: Long = 0L,
    /** 桌面版 UA，很多站点桌面版布局在 TV 上反而更好用 */
    val desktopMode: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("lastUsed", lastUsed)
        put("desktopMode", desktopMode)
    }

    companion object {
        fun fromJson(o: JSONObject) = Site(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            url = o.optString("url"),
            lastUsed = o.optLong("lastUsed", 0L),
            desktopMode = o.optBoolean("desktopMode", false),
        )

        /** 补全用户只输入域名的情况 */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim()
            return when {
                t.isBlank() -> t
                t.startsWith("http://", true) || t.startsWith("https://", true) -> t
                else -> "https://$t"
            }
        }
    }
}
