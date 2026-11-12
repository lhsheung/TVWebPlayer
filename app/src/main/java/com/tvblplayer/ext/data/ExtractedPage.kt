package com.tvblplayer.ext.data

import org.json.JSONObject

/** 提取出来的一个条目（卡片） */
data class CardItem(
    val title: String,
    val image: String?,
    val url: String,
)

/** 一个内容区块（对应原生界面的一行） */
data class Section(
    val title: String,
    /** poster = 大图卡片；chip = 小文字块（选集、链接列表） */
    val kind: String,
    val items: List<CardItem>,
) {
    val isPoster: Boolean get() = kind == "poster"
}

/** 一整个页面的提取结果 */
data class ExtractedPage(
    val title: String,
    val poster: String?,
    val intro: String,
    val hasVideo: Boolean,
    /** 「下一页」链接，没有则为 null */
    val next: String?,
    val sections: List<Section>,
) {
    val isEmpty: Boolean get() = sections.isEmpty()

    companion object {
        fun fromJson(raw: String): ExtractedPage {
            val o = JSONObject(raw)
            val secArr = o.optJSONArray("sections")
            val sections = mutableListOf<Section>()
            if (secArr != null) {
                for (i in 0 until secArr.length()) {
                    val s = secArr.getJSONObject(i)
                    val itemArr = s.optJSONArray("items") ?: continue
                    val items = mutableListOf<CardItem>()
                    for (j in 0 until itemArr.length()) {
                        val it = itemArr.getJSONObject(j)
                        val url = it.optString("h")
                        if (url.isBlank()) continue
                        items.add(
                            CardItem(
                                title = it.optString("t").ifBlank { "未命名" },
                                image = it.optString("i").takeIf { v -> v.isNotBlank() },
                                url = url,
                            )
                        )
                    }
                    if (items.isNotEmpty()) {
                        sections.add(
                            Section(
                                title = s.optString("title"),
                                kind = s.optString("kind", "poster"),
                                items = items,
                            )
                        )
                    }
                }
            }
            return ExtractedPage(
                title = o.optString("title").ifBlank { "未命名页面" },
                poster = o.optString("poster").takeIf { it.isNotBlank() },
                intro = o.optString("intro"),
                hasVideo = o.optBoolean("hasVideo", false),
                next = o.optJSONObject("next")?.optString("h")?.takeIf { it.isNotBlank() },
                sections = sections,
            )
        }
    }
}
