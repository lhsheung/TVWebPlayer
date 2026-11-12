package com.tvblplayer.ext.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 站点存储。用 SharedPreferences 存 JSON，够用且无依赖。
 */
class SiteStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sites", Context.MODE_PRIVATE)

    fun all(): List<Site> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Site.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
            .sortedByDescending { it.lastUsed }
    }

    fun save(sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(site: Site) {
        val list = all().toMutableList()
        list.removeAll { it.id == site.id }
        list.add(site)
        save(list)
    }

    fun update(site: Site) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == site.id }
        if (idx >= 0) list[idx] = site else list.add(site)
        save(list)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    fun touch(id: String) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastUsed = System.currentTimeMillis())
            save(list)
        }
    }

    fun find(id: String): Site? = all().firstOrNull { it.id == id }

    /**
     * 首次启动时注入预置站点。只做一次：
     * 用户删掉之后不再自动加回来，否则等于跟用户对着干。
     */
    fun seedDefaultsIfNeeded() {
        if (sp.getBoolean(KEY_SEEDED, false)) return
        val list = all().toMutableList()
        DefaultSites.LIST.forEach { d ->
            if (list.none { it.url == d.url || it.id == d.id }) list.add(d)
        }
        save(list)
        sp.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    companion object {
        private const val KEY = "site_list_json"
        private const val KEY_SEEDED = "seed_defaults_done"
    }
}
