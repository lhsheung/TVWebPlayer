package com.tvblplayer.ext.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tvblplayer.ext.data.Site
import com.tvblplayer.databinding.ItemSiteTileBinding
import com.tvblplayer.ext.util.FocusMotion

/**
 * 站点磁贴网格。
 *
 * 参考 Apple TV 的首页：不是一列设置项，而是平铺的大磁贴，
 * 焦点用弹簧放大 + 白色描边，视线一眼能扫完。
 *
 * 顺序：收藏夹 → 播放列表 → 局域网传输 → 用户站点… → 添加站点
 */
class SiteAdapter(
    private var items: List<Site>,
    private val onOpen: (Site) -> Unit,
    private val onLongPress: (Site) -> Unit,
    private val onAdd: () -> Unit,
    private val onFavorites: () -> Unit,
    private val onPlaylists: () -> Unit,
    private val onLan: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_FAV = 0
        private const val TYPE_PLAYLIST = 1
        private const val TYPE_SITE = 2
        private const val TYPE_ADD = 3
        private const val TYPE_LAN = 4
        private const val FIXED_HEAD = 3 // 收藏夹 + 播放列表 + 局域网传输
    }

    fun submit(list: List<Site>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size + FIXED_HEAD + 1

    override fun getItemViewType(position: Int) = when {
        position == 0 -> TYPE_FAV
        position == 1 -> TYPE_PLAYLIST
        position == 2 -> TYPE_LAN
        position <= items.size + FIXED_HEAD - 1 -> TYPE_SITE
        else -> TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val b = ItemSiteTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        FocusMotion.attach(b.tile, focusedScale = 1.06f)
        return when (viewType) {
            TYPE_FAV -> FavHolder(b)
            TYPE_PLAYLIST -> PlaylistHolder(b)
            TYPE_LAN -> LanHolder(b)
            TYPE_ADD -> AddHolder(b)
            else -> SiteHolder(b)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FavHolder -> holder.bind()
            is PlaylistHolder -> holder.bind()
            is LanHolder -> holder.bind()
            is AddHolder -> holder.bind()
            is SiteHolder -> holder.bind(items[position - FIXED_HEAD])
        }
    }

    inner class FavHolder(private val b: ItemSiteTileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            b.initial.text = "★"
            b.name.text = "收藏夹"
            b.url.text = "长按卡片即可收藏"
            b.tile.setOnClickListener { onFavorites() }
            b.tile.setOnLongClickListener { false }
        }
    }

    inner class PlaylistHolder(private val b: ItemSiteTileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            b.initial.text = "▶"
            b.name.text = "播放列表"
            b.url.text = "M3U8 / M3U / DPL"
            b.tile.setOnClickListener { onPlaylists() }
            b.tile.setOnLongClickListener { false }
        }
    }

    /** 局域网传输：手机扫码就能传文件 / 发链接，不用在电视上敲字 */
    inner class LanHolder(private val b: ItemSiteTileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            b.initial.text = "⇅"
            b.name.text = "局域网传输"
            b.url.text = "扫码上传文件 / 发送链接"
            b.tile.setOnClickListener { onLan() }
            b.tile.setOnLongClickListener { false }
        }
    }

    inner class SiteHolder(private val b: ItemSiteTileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(site: Site) {
            b.initial.text = site.name.take(1).uppercase()
            b.name.text = site.name
            b.url.text = site.url
            b.tile.setOnClickListener { onOpen(site) }
            b.tile.setOnLongClickListener {
                onLongPress(site)
                true
            }
        }
    }

    inner class AddHolder(private val b: ItemSiteTileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            b.initial.text = "＋"
            b.name.text = "添加站点"
            b.url.text = "输入任意网址"
            b.tile.alpha = 0.85f
            b.tile.setOnClickListener { onAdd() }
            b.tile.setOnLongClickListener { false }
        }
    }
}
