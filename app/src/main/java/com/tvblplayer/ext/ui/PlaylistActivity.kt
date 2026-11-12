package com.tvblplayer.ext.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvblplayer.ext.data.Playlist
import com.tvblplayer.ext.data.PlaylistEntry
import com.tvblplayer.ext.data.PlaylistParser
import com.tvblplayer.ext.data.PlaylistStore
import com.tvblplayer.databinding.ActivityPlaylistBinding
import com.tvblplayer.databinding.ItemPlaylistChipBinding
import com.tvblplayer.databinding.ItemPlaylistHeaderBinding
import com.tvblplayer.ext.util.FocusMotion
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 播放列表页。
 *
 * 布局用 4 列网格：40 个频道平铺，比原来一行横排好找得多。
 * 同一份列表用一个满宽标题分隔（SpanSizeLookup 处理）。
 */
class PlaylistActivity : BaseTvActivity() {

    companion object {
        private const val COLUMNS = 4

        /** 直链统一归到这个列表里；同名会被 store 合并，正好当容器用 */
        private const val DIRECT_LIST_NAME = "视频直链"

        /** 视为「视频直链」的扩展名：这类地址整条存下来直接点播，不当播放列表解析 */
        private val MEDIA_EXTS = listOf(
            ".mp4", ".mkv", ".flv", ".webm", ".ts", ".mov", ".avi", ".m4v",
            ".m3u8", ".mp3", ".m4a", ".aac", ".flac", ".wav"
        )
    }

    private lateinit var b: ActivityPlaylistBinding
    private lateinit var store: PlaylistStore
    private lateinit var adapter: GridAdapter

    /**
     * 多选删除模式。
     *
     * 交互（遥控器能用）：长按任意频道进入选择模式 → 确认键勾选 / 取消 →
     * 再按一次长按进入整份列表的选择，或直接按「菜单键」全选。
     * 选择模式下左上角的两个导入按钮临时变成「删除选中」和「取消」，
     * 避免在 TV 上搞一个底部工具条抢焦点。
     */
    private var selecting = false
    private val selected = LinkedHashSet<String>() // "playlistId\nentryUrl"
    private val selectedPlaylists = LinkedHashSet<String>() // playlistId

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = PlaylistStore(this)
        // 直接打开播放列表页时也要有内置列表（扩展页那条路已经播种过，这里是兜底）
        runCatching { store.seedBuiltinIfNeeded(this) }

        adapter = GridAdapter(
            onPlay = { entry -> play(entry) },
            onDeletePlaylist = { pl -> confirmDelete(pl) },
            onToggleSelect = { pl, entry -> toggleEntry(pl, entry) },
            onSelectPlaylist = { pl -> togglePlaylist(pl) },
            isSelecting = { selecting },
            isSelected = { pl, entry -> keyOf(pl, entry) in selected },
            isPlaylistSelected = { pl -> pl.id in selectedPlaylists },
        )

        val glm = GridLayoutManager(this, COLUMNS)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) COLUMNS else 1
        }
        b.plGrid.layoutManager = glm
        b.plGrid.adapter = adapter
        b.plGrid.itemAnimator = null

        FocusMotion.attach(b.btnImportUrl, 1.04f)
        FocusMotion.attach(b.btnImportFile, 1.04f)

        b.btnImportUrl.setOnClickListener {
            if (selecting) commitBatchDelete() else showImportUrlDialog()
        }
        b.btnImportFile.setOnClickListener {
            if (selecting) exitSelectMode() else pickFile.launch(arrayOf("*/*"))
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.all()
        adapter.submit(list)
        b.plEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        updateSelectChrome()
        b.plGrid.post {
            if (list.isEmpty()) {
                b.btnImportUrl.requestFocus()
            } else {
                // 跳过第一个分组标题，直接落在第一个频道上
                b.plGrid.layoutManager?.findViewByPosition(1)?.requestFocus()
            }
        }
    }

    // ---------- 批量删除 ----------

    private fun keyOf(pl: Playlist, entry: PlaylistEntry) = "${pl.id}\n${entry.url}"

    private fun toggleEntry(pl: Playlist, entry: PlaylistEntry) {
        val k = keyOf(pl, entry)
        if (!selected.remove(k)) selected.add(k)
        if (!selecting) selecting = true
        // 勾了具体条目就不该同时整份选中，否则删除语义会重叠
        selectedPlaylists.remove(pl.id)
        updateSelectChrome()
        adapter.notifyDataSetChanged()
    }

    private fun togglePlaylist(pl: Playlist) {
        if (pl.id in selectedPlaylists) {
            selectedPlaylists.remove(pl.id)
        } else {
            selectedPlaylists.add(pl.id)
            // 整份选中时清掉它下面的条目勾选，避免重复计数
            val prefix = "${pl.id}\n"
            selected.removeAll { it.startsWith(prefix) }
        }
        if (!selecting) selecting = true
        updateSelectChrome()
        adapter.notifyDataSetChanged()
    }

    private fun enterSelectMode() {
        selecting = true
        updateSelectChrome()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        selecting = false
        selected.clear()
        selectedPlaylists.clear()
        updateSelectChrome()
        adapter.notifyDataSetChanged()
    }

    private fun selectedCount(): Int {
        val entryCount =
            selected.count { k ->
                val pid = k.substringBefore('\n')
                pid !in selectedPlaylists
            }
        // 整份选中的列表按它的条目数计入，用户看到的数字才对得上
        val byPlaylist =
            store.all().filter { it.id in selectedPlaylists }.sumOf { it.entries.size }
        return entryCount + byPlaylist
    }

    /** 选择模式下把左上角两个按钮改成「删除选中 / 取消」，并给出计数 */
    private fun updateSelectChrome() {
        if (selecting) {
            val n = selectedCount()
            b.btnImportUrl.text = if (n > 0) "删除选中（$n）" else "删除选中"
            b.btnImportUrl.alpha = if (n > 0) 1f else 0.5f
            b.btnImportFile.text = "取消"
            b.plSub.text = "选择模式：确认键勾选 · 长按列表名整份选择 · 返回退出"
        } else {
            b.btnImportUrl.text = "从网址导入"
            b.btnImportUrl.alpha = 1f
            b.btnImportFile.text = "从文件导入"
            b.plSub.text = "支持 M3U8 / M3U / DPL · 长按进入批量选择"
        }
    }

    private fun commitBatchDelete() {
        val entryTargets =
            selected.map { it.substringBefore('\n') to it.substringAfter('\n') }
                .filterNot { (pid, _) -> pid in selectedPlaylists }
        val plTargets = selectedPlaylists.toList()

        if (entryTargets.isEmpty() && plTargets.isEmpty()) {
            toast("还没有选中任何内容")
            return
        }

        val n = selectedCount()
        val what = buildString {
            if (plTargets.isNotEmpty()) {
                append(plTargets.size).append(" 份列表")
                if (entryTargets.isNotEmpty()) append("、")
            }
            if (entryTargets.isNotEmpty()) append(entryTargets.size).append(" 个频道")
        }

        AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage("确定删除 $what（共 $n 个条目）？\n此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                if (plTargets.isNotEmpty()) store.removeMany(plTargets)
                if (entryTargets.isNotEmpty()) store.removeEntries(entryTargets)
                val removed = n
                exitSelectMode()
                refresh()
                toast("已删除 $removed 个条目")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!selecting) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                exitSelectMode()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun play(entry: PlaylistEntry) {
        // 同名条目就是这个频道的多条镜像线路：一起交给播放器，播不通自动换下一条。
        // DPL 里「广东体育」常常有七八条地址，逐条手动点太折腾。
        val all = runCatching { store.all() }.getOrDefault(emptyList())
        val alternates = all
            .flatMap { it.entries }
            .filter { it.title.equals(entry.title, ignoreCase = true) }
            .map { it.url }
            .distinct()

        // 整份频道表也带上：播放器里按上下键可以直接换台，不用退回来重选。
        val owner = all.firstOrNull { pl -> pl.entries.any { it.url == entry.url } }
        val channels = owner?.entries ?: listOf(entry)
        val idx = channels.indexOfFirst { it.url == entry.url }.coerceAtLeast(0)

        MediaLauncher.open(
            this, entry.url, entry.title, entry.logo,
            alternates = alternates,
            channelUrls = channels.map { it.url },
            channelTitles = channels.map { it.title },
            channelIndex = idx,
        )
    }

    private fun confirmDelete(pl: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("删除播放列表")
            .setMessage("确定删除「${pl.name}」？共 ${pl.entries.size} 个条目。")
            .setPositiveButton("删除") { _, _ ->
                store.remove(pl.id)
                refresh()
                toast("已删除「${pl.name}」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 导入 ----------

    private fun importFromUri(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "导入的列表"
        thread {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            runOnUiThread {
                if (text.isNullOrBlank()) {
                    toast("文件读取失败或内容为空")
                    return@runOnUiThread
                }
                applyImport(text, name.ifBlank { "导入的列表" })
            }
        }
    }

    private fun showImportUrlDialog() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val et = EditText(this).apply {
            hint = "播放列表地址，或视频直链（mp4 / m3u8 / ts）"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(TextView(this).apply {
            text = "可以填播放列表地址（m3u / dpl / txt），也可以直接粘视频直链" +
                "（mp4 / mkv / m3u8 / ts 等）。\n" +
                "直链会整条存进「$DIRECT_LIST_NAME」列表，之后从列表里直接点播；" +
                "同一个「$DIRECT_LIST_NAME」列表可以反复追加。\n" +
                "App 只保存地址，不代理、不转存任何内容。"
            setTextColor(0xFF8A8A94.toInt())
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("从网址导入")
            .setView(container)
            .setPositiveButton("导入", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 允许一次粘多条直链（每行一条）；播放列表地址正常只有一条
                val urls = et.text.toString()
                    .split('\n', '\r', ' ', '\t')
                    .map { it.trim() }
                    .filter { it.startsWith("http", true) }
                if (urls.isEmpty()) {
                    toast("请输入 http/https 开头的地址")
                    return@setOnClickListener
                }
                dialog.dismiss()
                when {
                    urls.size > 1 && urls.all { looksLikeMediaUrl(it) } -> importDirectUrls(urls)
                    looksLikeMediaUrl(urls[0]) -> importDirectUrls(listOf(urls[0]))
                    else -> fetchAndImport(urls[0])
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    /**
     * 判断是不是「视频直链」。
     *
     * 以前这里不分类型，任何地址都当播放列表文本去下载解析，用户粘一个
     * mp4 / m3u8 直链只会得到「没解析到可播放的地址」。直链应当整条存下来，
     * 从列表里直接点播，而不是先下载再解析。
     */
    private fun looksLikeMediaUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return MEDIA_EXTS.any { path.endsWith(it) }
    }

    /**
     * 视频直链整条导入：不下载、不解析，原样存成可播记录。
     * 支持一次粘多条（每行一条），统一追加到「视频直链」列表，重复地址跳过。
     */
    private fun importDirectUrls(urls: List<String>) {
        val library = store.all().firstOrNull { it.name == DIRECT_LIST_NAME }
        val seen = library?.entries?.map { it.url }?.toMutableSet() ?: mutableSetOf()
        var n = library?.entries?.size ?: 0
        val added = mutableListOf<PlaylistEntry>()
        for (u in urls) {
            if (!seen.add(u)) continue
            n++
            val file = u.substringBefore('?').substringBefore('#')
                .substringAfterLast('/')
                .ifBlank { "视频直链" }
            val short = if (file.length > 26) file.take(26) + "…" else file
            added += PlaylistEntry(title = "直链 $n · $short", url = u)
        }
        if (added.isEmpty()) {
            toast("这些直链都已经在「$DIRECT_LIST_NAME」里了")
            return
        }
        val pl = if (library == null) {
            Playlist(name = DIRECT_LIST_NAME, entries = added)
        } else {
            library.copy(
                entries = library.entries + added,
                importedAt = System.currentTimeMillis()
            )
        }
        store.add(pl)
        refresh()
        toast("已加入 ${added.size} 条直链，共 ${pl.entries.size} 条")
    }

    private fun fetchAndImport(url: String) {
        toast("正在下载播放列表…")
        thread {
            val text = runCatching { httpGet(url) }.getOrNull()
            runOnUiThread {
                if (text.isNullOrBlank()) {
                    toast("下载失败，请检查地址或网络")
                    return@runOnUiThread
                }
                val name = url.substringBefore('?').substringAfterLast('/')
                    .substringBeforeLast('.').ifBlank { "网络列表" }
                applyImport(text, name)
            }
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun applyImport(text: String, name: String) {
        val pl = PlaylistParser.parse(text, name)
        if (pl.entries.isEmpty()) {
            toast("这个文件里没解析到可播放的地址")
            return
        }
        store.add(pl)
        refresh()
        val bad = pl.entries.count { it.invalid }
        toast(
            buildString {
                append("已导入「${pl.name}」共 ${pl.entries.size} 条")
                if (bad > 0) append("（其中 $bad 条在原列表里标记为失效）")
            }
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- 网格适配器 ----------

    private sealed class Cell {
        data class Header(val playlist: Playlist) : Cell()
        data class Channel(
            val playlist: Playlist,
            val entry: PlaylistEntry,
            val showHost: Boolean,
        ) : Cell()
    }

    private inner class GridAdapter(
        private val onPlay: (PlaylistEntry) -> Unit,
        private val onDeletePlaylist: (Playlist) -> Unit,
        private val onToggleSelect: (Playlist, PlaylistEntry) -> Unit,
        private val onSelectPlaylist: (Playlist) -> Unit,
        private val isSelecting: () -> Boolean,
        private val isSelected: (Playlist, PlaylistEntry) -> Boolean,
        private val isPlaylistSelected: (Playlist) -> Boolean,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var cells: List<Cell> = emptyList()

        fun submit(playlists: List<Playlist>) {
            val out = mutableListOf<Cell>()
            for (pl in playlists) {
                out.add(Cell.Header(pl))
                // 同名频道靠宿主区分
                val nameCount = pl.entries.groupingBy { it.title }.eachCount()
                pl.entries.forEach { e ->
                    out.add(Cell.Channel(pl, e, (nameCount[e.title] ?: 0) > 1))
                }
            }
            cells = out
            notifyDataSetChanged()
        }

        fun isHeader(position: Int): Boolean = cells.getOrNull(position) is Cell.Header

        override fun getItemCount() = cells.size

        override fun getItemViewType(position: Int): Int =
            if (cells[position] is Cell.Header) TYPE_HEADER else TYPE_CHANNEL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(ItemPlaylistHeaderBinding.inflate(inf, parent, false))
            } else {
                val vb = ItemPlaylistChipBinding.inflate(inf, parent, false)
                FocusMotion.attach(vb.root, 1.04f)
                ChannelVH(vb)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val cell = cells[position]) {
                is Cell.Header -> (holder as HeaderVH).bind(cell.playlist)
                is Cell.Channel -> (holder as ChannelVH).bind(cell)
            }
        }

        inner class HeaderVH(private val vb: ItemPlaylistHeaderBinding) :
            RecyclerView.ViewHolder(vb.root) {
            fun bind(pl: Playlist) {
                val wholeSelected = isPlaylistSelected(pl)
                val prefix = if (wholeSelected) "✓ " else ""
                vb.root.text = "$prefix${pl.name}　·　${pl.entries.size} 个条目"
                vb.root.alpha = if (wholeSelected) 1f else 0.86f
                vb.root.setOnLongClickListener {
                    if (isSelecting()) {
                        onSelectPlaylist(pl)
                    } else {
                        onDeletePlaylist(pl)
                    }
                    true
                }
                // 选择模式下点标题也等同于「整份选择」，遥控器上少按几次
                vb.root.setOnClickListener {
                    if (isSelecting()) onSelectPlaylist(pl)
                }
            }
        }

        inner class ChannelVH(private val vb: ItemPlaylistChipBinding) :
            RecyclerView.ViewHolder(vb.root) {
            fun bind(cell: Cell.Channel) {
                val e = cell.entry
                val selecting = isSelecting()
                val checked = isSelected(cell.playlist, e) || isPlaylistSelected(cell.playlist)

                val mark = if (checked) "✓ " else ""
                vb.chTitle.text = "$mark${e.title}"
                vb.chHost.text = if (cell.showHost) PlaylistParser.hostOf(e.url) else ""
                vb.chHost.visibility = if (cell.showHost && vb.chHost.text.isNotEmpty()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                // 原列表标记为失效的，压暗但仍可点
                vb.root.alpha = when {
                    checked -> 1f
                    e.invalid -> 0.45f
                    else -> 1f
                }
                // 勾选靠背景描边体现，不额外加控件——TV 上多一个控件就多一份焦点麻烦
                vb.root.background = if (checked) {
                    androidx.core.content.ContextCompat.getDrawable(vb.root.context, com.tvblplayer.R.drawable.bg_chip_checked)
                } else {
                    androidx.core.content.ContextCompat.getDrawable(vb.root.context, com.tvblplayer.R.drawable.bg_chip)
                }
                vb.root.setOnClickListener {
                    if (selecting || isSelecting()) onToggleSelect(cell.playlist, e) else onPlay(e)
                }
                vb.root.setOnLongClickListener {
                    onToggleSelect(cell.playlist, e)
                    true
                }
            }
        }
    }
}

private const val TYPE_HEADER = 0
private const val TYPE_CHANNEL = 1
