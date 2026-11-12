package com.tvblplayer.ext.data

/**
 * 播放列表解析器（纯函数，无 Android 依赖，方便单测）。
 *
 * 支持三种格式：
 *  1. DAUMPLAYLIST（PotPlayer 的 .dpl）
 *  2. M3U / M3U8 扩展格式（#EXTM3U + #EXTINF）
 *  3. 纯 URL 列表（每行一个地址）
 */
object PlaylistParser {

    private val DPL_LINE = Regex("""^(\d+)\*(file|title|thumbnail|author|group|invalid|played)\*(.*)$""")
    private val ATTR = Regex("""([a-zA-Z0-9-]+)="([^"]*)"""")

    fun parse(text: String, name: String): Playlist {
        val body = text.removePrefix("\uFEFF")
        val entries = when {
            body.trimStart().startsWith("DAUMPLAYLIST", ignoreCase = true) -> parseDpl(body)
            body.contains("#EXTINF") || body.contains("#EXTM3U") -> parseM3u(body)
            else -> parsePlain(body)
        }
        return Playlist(name = name, entries = entries)
    }

    // ---------- DAUMPLAYLIST ----------

    private fun parseDpl(text: String): List<PlaylistEntry> {
        // 每个条目由若干 "N*key*value" 行组成，按 N 聚合
        val map = LinkedHashMap<Int, MutableMap<String, String>>()
        for (raw in text.lineSequence()) {
            val m = DPL_LINE.matchEntire(raw.trim()) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val key = m.groupValues[2]
            val value = m.groupValues[3].trim()
            map.getOrPut(idx) { mutableMapOf() }[key] = value
        }
        val list = map.entries.sortedBy { it.key }.mapNotNull { (_, o) ->
            val url = o["file"]?.trim().orEmpty()
            if (url.isBlank()) return@mapNotNull null
            // PotPlayer 会把「2026-06-07 07:21:12」这种日期当成分隔行写进 file 字段，
            // 不过滤的话会在频道列表里多出一个永远播不了的假频道。
            if (!looksPlayable(url)) return@mapNotNull null
            PlaylistEntry(
                title = o["title"]?.trim().orEmpty().ifBlank { titleFromUrl(url) },
                url = url,
                logo = o["thumbnail"]?.trim()?.takeIf { it.isNotBlank() },
                group = (o["author"] ?: o["group"])?.trim()?.takeIf { it.isNotBlank() },
                invalid = o["invalid"] == "1",
            )
        }
        return dedupeAndSort(list)
    }

    /** 带 scheme 的地址，或本地/UNC 绝对路径，才认为是可以播的条目 */
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
    private val WIN_PATH = Regex("""^[A-Za-z]:[\\/]""")

    private fun looksPlayable(url: String): Boolean =
        SCHEME.containsMatchIn(url) ||
            url.startsWith("/") ||
            url.startsWith("\\\\") ||
            WIN_PATH.containsMatchIn(url)

    // ---------- M3U / M3U8 ----------

    private fun parseM3u(text: String): List<PlaylistEntry> {
        val list = mutableListOf<PlaylistEntry>()
        var pendingTitle: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // #EXTINF:-1 tvg-logo="..." group-title="...",频道名
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pendingLogo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    pendingGroup = (attrs["group-title"] ?: attrs["group"])?.takeIf { it.isNotBlank() }
                    pendingTitle = line.substringAfterLast(',', "").trim().takeIf { it.isNotBlank() }
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
                }

                line.startsWith("#") -> {
                    // 其他指令忽略（#EXTM3U、#EXTVLCOPT 等）
                }

                else -> {
                    val url = normalizeUrl(line) ?: continue
                    list.add(
                        PlaylistEntry(
                            title = pendingTitle ?: titleFromUrl(url),
                            url = url,
                            logo = pendingLogo,
                            group = pendingGroup,
                        )
                    )
                    pendingTitle = null
                    pendingLogo = null
                    // group 可以延续到后续条目，这里保留
                }
            }
        }
        return dedupeAndSort(list)
    }

    // ---------- 纯 URL 列表 ----------

    private fun parsePlain(text: String): List<PlaylistEntry> {
        val list = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val url = normalizeUrl(line) ?: return@mapNotNull null
                PlaylistEntry(title = titleFromUrl(url), url = url)
            }
            .toList()
        return dedupeAndSort(list)
    }

    // ---------- 工具 ----------

    private fun normalizeUrl(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("#")) return null
        return when {
            t.startsWith("http://", true) || t.startsWith("https://", true) ||
                t.startsWith("rtmp://", true) || t.startsWith("rtsp://", true) ||
                t.startsWith("udp://", true) || t.startsWith("rtp://", true) -> t
            else -> null
        }
    }

    /** 从 URL 里取个能看的名字 */
    fun titleFromUrl(url: String): String {
        val noQuery = url.substringBefore('?').trimEnd('/')
        val last = noQuery.substringAfterLast('/')
        return last.ifBlank { url }
    }

    /** 取主机名，用于区分同名频道（同一频道往往有十几个不同来源） */
    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: ""

    /** 按 URL 去重；有效条目排前面，其余保持原顺序 */
    private fun dedupeAndSort(list: List<PlaylistEntry>): List<PlaylistEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<PlaylistEntry>(list.size)
        for (e in list) {
            if (!seen.add(e.url)) continue
            out.add(e)
        }
        return out.sortedBy { if (it.invalid) 1 else 0 }
    }

    /** 根据地址猜测媒体类型，供播放器用 */
    fun guessMime(url: String): String? {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".m3u8") -> "application/x-mpegURL"
            u.endsWith(".mpd") -> "application/dash+xml"
            u.endsWith(".mp4") || u.endsWith(".m4v") -> "video/mp4"
            u.endsWith(".webm") -> "video/webm"
            u.endsWith(".mkv") -> "video/x-matroska"
            u.endsWith(".ts") -> "video/mp2t"
            else -> null
        }
    }
}
