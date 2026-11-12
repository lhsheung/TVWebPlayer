package com.tvblplayer.ext.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvblplayer.core.ui.AppToast
import com.tvblplayer.databinding.FragmentExtHubBinding
import com.tvblplayer.ext.data.DefaultSites
import com.tvblplayer.ext.data.FavStore
import com.tvblplayer.ext.data.PlaylistStore
import com.tvblplayer.ext.data.Site
import com.tvblplayer.ext.data.SiteStore
import com.tvblplayer.ui.RefreshKeyHandler

/**
 * ext 模块入口页（挂在 blbl 侧边栏）。
 *
 * 只做一件事：把原来的独立功能（粤漫之家、播放列表、局域网传片、收藏夹）
 * 暴露成 blbl 主界面里的一屏入口，点击后跳转到 ext 模块自己的 Activity。
 * 不侵入 blbl 的任何现有页面逻辑。
 */
class ExtHubFragment : Fragment(), RefreshKeyHandler {

    private var _binding: FragmentExtHubBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ExtEntryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentExtHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ExtEntryAdapter { entry -> runCatching { entry.action() } }
        binding.recyclerExtEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerExtEntries.adapter = adapter
        adapter.submit(buildEntries(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        // 回到本页时刷新副标题（站点 / 播放列表 / 收藏数量会变）
        _binding?.let { adapter.submit(buildEntries(requireContext())) }
    }

    override fun onDestroyView() {
        binding.recyclerExtEntries.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun buildEntries(context: Context): List<ExtEntry> {
        val store = runCatching { SiteStore(context) }.getOrNull()
        // 预置站点只注入一次；放在这里而不是 Application，是为了不侵入 blbl 的启动流程
        runCatching { store?.seedDefaultsIfNeeded() }
        // 内置播放列表（gdty / tvb）也必须在这里播种。
        // 之前只播种了站点，播种函数写好却没人调用，装完自然一份列表都看不到。
        val playlistStore = runCatching { PlaylistStore(context) }.getOrNull()
        runCatching { playlistStore?.seedBuiltinIfNeeded(context) }
        val sites = runCatching { store?.all().orEmpty() }.getOrDefault(emptyList())
        val playlists = runCatching { playlistStore?.all()?.size ?: 0 }.getOrDefault(0)
        val favs = runCatching { FavStore(context).all().size }.getOrDefault(0)

        return listOf(
            ExtEntry(
                key = "yue",
                title = "粤漫之家",
                subtitle = if (sites.isNotEmpty()) "${sites.size} 个站点 · 网页浏览与播放" else "网页浏览与播放",
                action = { openYueMan(context, sites) },
            ),
            ExtEntry(
                key = "playlist",
                title = "播放列表",
                subtitle = if (playlists > 0) "$playlists 个列表 · 支持批量删除" else "M3U / TXT / JSON 导入，支持批量删除",
                action = { context.startActivity(Intent(context, PlaylistActivity::class.java)) },
            ),
            ExtEntry(
                key = "fav",
                title = "收藏夹",
                subtitle = if (favs > 0) "$favs 条收藏" else "收藏站点页面与影片",
                action = { context.startActivity(Intent(context, FavoritesActivity::class.java)) },
            ),
            ExtEntry(
                key = "lan",
                title = "局域网传片",
                subtitle = "手机扫码推送到电视",
                action = { context.startActivity(Intent(context, LanImportActivity::class.java)) },
            ),
        )
    }

    /**
     * 打开粤漫之家。
     *
     * 直接进 WebActivity（网页视图），不再绕 BrowseActivity——
     * 旧版把它挂在「整理视图」上，而整理视图靠结构启发式解析，
     * 站点一改版就只剩空卡片，用户看到的就是黑屏。
     *
     * 一定要把 url / title / desktop 一起传过去：WebActivity 只认 intent 里的值，
     * 只传 siteId 会让它 loadUrl("")，也就是纯黑（这是本次黑屏的直接原因）。
     */
    private fun openYueMan(context: Context, sites: List<Site>) {
        val store = runCatching { SiteStore(context) }.getOrNull()
        val site = sites.firstOrNull { it.id == DefaultSites.ID_YMV } ?: sites.firstOrNull()
        if (site == null) {
            // 理论上走不到：seedDefaultsIfNeeded 会把粤漫之家补进去
            AppToast.show(context, "站点列表为空，请重试")
            return
        }
        store?.touch(site.id)
        context.startActivity(
            Intent(context, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_SITE_ID, site.id)
                .putExtra(WebActivity.EXTRA_URL, site.url)
                .putExtra(WebActivity.EXTRA_TITLE, site.name)
                .putExtra(WebActivity.EXTRA_DESKTOP, site.desktopMode)
                .putExtra(WebActivity.EXTRA_TIDY, true),
        )
    }

    override fun handleRefreshKey(): Boolean {
        _binding?.let { adapter.submit(buildEntries(requireContext())) }
        return true
    }

    companion object {
        fun newInstance(): ExtHubFragment = ExtHubFragment()
    }
}

data class ExtEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val action: () -> Unit,
)
