package com.tvblplayer.ext.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 已导入的播放列表。多份共存，按导入时间倒序。
 */
class PlaylistStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    fun all(): List<Playlist> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Playlist.fromJson(it) }
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.importedAt }
    }

    fun save(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(playlist: Playlist) {
        // 同名列表直接替换，避免反复导入产生重复
        val list = all().filterNot { it.name == playlist.name }.toMutableList()
        list.add(0, playlist)
        save(list)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    /**
     * 批量删除：一次删掉多份列表。
     *
     * 只做一次落盘而不是循环调 [remove]：循环会让 N 次读写磁盘、
     * 也容易在中间状态崩溃时留下半删的数据。
     */
    fun removeMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val drop = ids.toSet()
        save(all().filterNot { it.id in drop })
    }

    /**
     * 批量删除条目。从多份列表里按 (playlistId, 条目 url) 精确移除，
     * 删空的列表整份丢掉——留着空壳列表没有意义，只会在首页占位。
     */
    fun removeEntries(targets: Collection<Pair<String, String>>) {
        if (targets.isEmpty()) return
        val byPlaylist = targets.groupBy({ it.first }, { it.second })
        val updated =
            all().mapNotNull { pl ->
                val urls = byPlaylist[pl.id] ?: return@mapNotNull pl
                val drop = urls.toSet()
                val left = pl.entries.filterNot { it.url in drop }
                if (left.isEmpty()) null else pl.copy(entries = left)
            }
        save(updated)
    }

    fun totalEntries(): Int = all().sumOf { it.entries.size }

    /**
     * 首次启动导入 APK 内置的示例播放列表。
     *
     * 只是一份「地址清单」，不含内容。用户删掉后不再自动加回来。
     *
     * v2：内置列表从 1 份（广东体育）增加到 2 份（+ TVB）。
     * 老用户升上来时 v1 标记已经是 true，不能再按「首次启动」处理——
     * 那样会把用户早就删掉的广东体育又塞回去。所以升级路径只补新加的那份，
     * 并且按名字去重，避免同名列表出现两份。
     */
    fun seedBuiltinIfNeeded(context: Context) {
        if (sp.getBoolean(KEY_SEEDED_V2, false)) return

        // v1 播过种：广东体育按用户意愿不再补，只补 v2 新增的
        val alreadySeeded = sp.getBoolean(KEY_SEEDED_V1, false)
        val seeds = if (alreadySeeded) BUILTINS.filterNot { it.name == BUILTIN_NAME } else BUILTINS

        sp.edit().putBoolean(KEY_SEEDED_V2, true).apply()

        val existing = all().map { it.name }.toSet()
        for (seed in seeds) {
            if (seed.name in existing) continue
            val text = runCatching {
                context.assets.open(seed.asset).bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) continue
            val pl = PlaylistParser.parse(text, seed.name)
            if (pl.entries.isNotEmpty()) add(pl)
        }
    }

    /** APK 内置列表：资源路径 + 展示名 */
    private data class Builtin(val asset: String, val name: String)

    companion object {
        private const val KEY = "playlist_list_json"
        private const val KEY_SEEDED_V1 = "seed_builtin_playlist_done"
        private const val KEY_SEEDED_V2 = "seed_builtin_playlist_v2"
        const val BUILTIN_NAME = "广东体育"

        private val BUILTINS = listOf(
            Builtin("builtin/gdty.dpl", BUILTIN_NAME),
            Builtin("builtin/tvb.dpl", "TVB")
        )
    }
}
