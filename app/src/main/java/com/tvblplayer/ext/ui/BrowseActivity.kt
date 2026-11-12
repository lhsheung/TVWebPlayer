package com.tvblplayer.ext.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvblplayer.R
import com.tvblplayer.ext.data.CardItem
import com.tvblplayer.ext.data.ExtractedPage
import com.tvblplayer.ext.data.FavEntry
import com.tvblplayer.ext.data.FavStore
import com.tvblplayer.ext.data.MediaProbe
import com.tvblplayer.ext.data.Section
import com.tvblplayer.ext.data.SiteStore
import com.tvblplayer.databinding.ActivityBrowseBinding
import com.tvblplayer.databinding.ItemCardChipBinding
import com.tvblplayer.databinding.ItemCardPosterBinding
import com.tvblplayer.databinding.ItemRowBinding
import com.tvblplayer.ext.util.FocusMotion
import com.tvblplayer.ext.util.ImageLoader

/**
 * 浏览页：隐藏 WebView 加载网页 → 注入 extract.js 解析 → 用原生 TV 界面呈现。
 *
 * 设计要点：
 * 1. WebView 必须全屏真实排版（alpha=0 盖在底层），否则 getBoundingClientRect 全 0，解析会失效。
 * 2. 解析失败/超时/断网要给不同提示，不只说"出错了"。
 * 3. 页面里有视频时，直接进播放页；否则把解析出的卡片当导航继续深入。
 */
class BrowseActivity : BaseTvActivity() {

    companion object {
        const val EXTRA_SITE_ID = "site_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESKTOP = "desktop"
        private const val PARSE_TIMEOUT_MS = 20000L

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private lateinit var b: ActivityBrowseBinding
    private lateinit var store: SiteStore
    private lateinit var favStore: FavStore
    private lateinit var rowsAdapter: RowAdapter

    private var siteId: String = ""
    private var currentUrl: String = ""
    private var siteName: String = ""
    private var desktopUa: Boolean = false

    private var parseScript: String = ""
    private var pageLoaded = false
    private var parseStarted = false
    /** 顶部标题 / 底部提示只在列表顶端可见 */
    private var chromeVisible = true

    private val timeoutRunnable = Runnable {
        if (!parseStarted) showState(
            "解析超时",
            "页面在 ${PARSE_TIMEOUT_MS / 1000} 秒内没能完成解析。\n可能是网络较慢，或站点有脚本防护。",
            retryable = true
        )
    }

    // 用于返回时上一层 URL
    private val history = ArrayDeque<Pair<String, String>>() // url to title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = SiteStore(this)
        favStore = FavStore(this)
        siteId = intent.getStringExtra(EXTRA_SITE_ID) ?: ""
        currentUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        siteName = intent.getStringExtra(EXTRA_TITLE) ?: ""
        desktopUa = intent.getBooleanExtra(EXTRA_DESKTOP, false)

        parseScript = runCatching {
            assets.open("extract.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")

        b.pageTitle.text = siteName
        setupRecycler()
        setupWorker()
        load(currentUrl)
    }

    private fun setupRecycler() {
        rowsAdapter = RowAdapter(
            onClick = { item -> onCardClick(item) },
            onLongClick = { item -> toggleFavorite(item) },
        )
        b.rows.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        b.rows.adapter = rowsAdapter
        b.rows.itemAnimator = null
        b.rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                setChromeVisible(rv.computeVerticalScrollOffset() <= 0)
            }
        })
    }

    /**
     * 标题和底部提示只在最顶端露面，往下浏览就收起来。
     * 之前它们是常驻浮层，滚动时半透明地压在海报上，既挡内容又像乱码。
     */
    private fun setChromeVisible(show: Boolean) {
        if (chromeVisible == show) return
        chromeVisible = show
        listOf(b.header, b.scrollHint).forEach { v ->
            v.animate().cancel()
            v.animate()
                .alpha(if (show) 1f else 0f)
                .translationY(if (show || v !== b.header) 0f else -v.height.toFloat())
                .setDuration(180)
                .withStartAction { if (show) v.visibility = View.VISIBLE }
                .withEndAction { if (!show) v.visibility = View.GONE }
                .start()
        }
    }

    /** 新页面/返回上一页时，回到列表顶端并把标题复位 */
    private fun resetChrome() {
        chromeVisible = true
        listOf(b.header, b.scrollHint).forEach { v ->
            v.animate().cancel()
            v.alpha = 1f
            v.translationY = 0f
            v.visibility = View.VISIBLE
        }
        (b.rows.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
    }

    /** 长按卡片：收藏 / 取消收藏 */
    private fun toggleFavorite(item: CardItem) {
        if (favStore.contains(item.url)) {
            favStore.remove(item.url)
            toast("已取消收藏「${item.title}」")
        } else {
            favStore.add(
                FavEntry(
                    title = item.title,
                    image = item.image,
                    url = item.url,
                    site = b.pageTitle.text?.toString() ?: siteName,
                )
            )
            toast("已收藏「${item.title}」")
        }
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWorker() {
        val w = b.worker
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopUa) DESKTOP_UA else mobileUa()
        }
        w.setBackgroundColor(0x00000000)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
    }

    private fun mobileUa() =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(url: String) {
        if (url.isBlank()) {
            showState("地址为空", "这个站点没有配置网址。", retryable = false)
            return
        }
        pageLoaded = false
        parseStarted = false
        showLoading("正在读取页面…")
        b.worker.stopLoading()
        b.worker.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                super.onPageStarted(view, u, favicon)
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                super.onPageFinished(view, u)
                pageLoaded = true
                // 给 JS 渲染留时间（SPA 常见）
                view?.postDelayed({ runParse() }, 900)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showState(
                        "打不开这个网址",
                        "网络请求失败：${error?.description ?: "未知错误"}\n请检查网址是否正确、设备是否能上网。",
                        retryable = true
                    )
                }
            }
        }
        b.worker.loadUrl(url)
        b.worker.postDelayed(timeoutRunnable, PARSE_TIMEOUT_MS)
    }

    private fun runParse() {
        if (parseStarted || isFinishing) return
        if (parseScript.isBlank()) {
            showState("解析脚本缺失", "APK 内 extract.js 未找到，构建有问题。", retryable = false)
            return
        }
        parseStarted = true
        b.worker.removeCallbacks(timeoutRunnable)

        b.worker.evaluateJavascript(parseScript) { raw ->
            if (isFinishing) return@evaluateJavascript
            // 站点经常把 /play/{id} 跳到 /play/{id}/{ep}，这里跟上真实地址，
            // 否则后面"播放本页"会拿到跳转前的旧地址。
            b.worker.url?.let { real ->
                if (real.startsWith("http")) currentUrl = real
            }
            // evaluateJavascript 返回的是 JSON 字符串的 JSON 表示，需要先解一层
            val json = decodeJsString(raw)
            if (json == null) {
                showState(
                    "解析失败",
                    "页面结构没能识别出内容。\n可能是站点改版，或这个页面确实是空列表。",
                    retryable = true
                )
                return@evaluateJavascript
            }
            val page = runCatching { ExtractedPage.fromJson(json) }.getOrNull()
            if (page == null) {
                showState("解析结果异常", "脚本返回的数据无法解析。", retryable = true)
                return@evaluateJavascript
            }
            render(page)
        }
    }

    /** evaluateJavascript 的返回值是被 JSON 编码过的字符串，解一层 */
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val u = org.json.JSONTokener(raw).nextValue()
            when (u) {
                is String -> u
                is org.json.JSONObject -> u.toString()
                else -> null
            }
        }.getOrNull()
    }

    private fun render(page: ExtractedPage) {
        b.loading.visibility = View.GONE

        if (page.isEmpty && !page.hasVideo) {
            showState(
                "这一页没有可显示的内容",
                "解析到这个页面里没有列表类内容。\n可以按「菜单键」直接打开原始网页看看。",
                retryable = true
            )
            return
        }

        if (page.isEmpty) {
            // 有播放器但没有列表，直接进播放
            openPlayer(currentUrl, page.title)
            return
        }

        b.statePanel.visibility = View.GONE
        b.rows.visibility = View.VISIBLE

        b.pageTitle.text = page.title.ifBlank { siteName }
        // 站点自述整段丢掉：TV 上没处放，也没人读，只会变成压在海报上的一行灰字

        val rows = mutableListOf<Section>()
        if (page.hasVideo || page.sections.any { it.title == "选集" }) {
            // 有选集时优先播第一集，比播"当前页"更符合直觉
            val firstEp = page.sections
                .firstOrNull { it.title == "选集" }
                ?.items?.firstOrNull()
            val target = firstEp?.url ?: currentUrl
            val label = if (firstEp != null) "▶ 播放第 1 集" else "▶ 播放本页视频"
            rows.add(Section("开始播放", "chip", listOf(CardItem(label, null, target))))
        }
        rows.addAll(page.sections)
        // 有下一页时给一个显式入口，否则用户只能看到第一页
        page.next?.let { nextUrl ->
            rows.add(Section("翻页", "chip", listOf(CardItem("下一页 →", null, nextUrl))))
        }
        rowsAdapter.submit(rows, refererBase())
        resetChrome()

        b.rows.post {
            focusFirstCard()
        }
    }

    private fun refererBase(): String = runCatching {
        val u = java.net.URI(b.worker.url ?: currentUrl)
        "${u.scheme}://${u.host}/"
    }.getOrDefault(currentUrl)

    private fun focusFirstCard() {
        for (i in 0 until b.rows.childCount) {
            val row = b.rows.getChildAt(i) ?: continue
            val inner = row.findViewById<RecyclerView>(R.id.inner)
            if (inner != null && inner.childCount > 0) {
                inner.getChildAt(0)?.requestFocus()
                return
            }
        }
    }

    private fun onCardClick(item: CardItem) {
        val targetUrl = item.url
        if (targetUrl.isBlank()) return

        // 如果目标本身就是媒体地址（.m3u8/.mp4/.ts…），交给统一点播入口，
        // 由它识别类型后分给原生播放器或 WebView。
        val media = MediaProbe.classifyByUrl(targetUrl)
        if (media != null && media.kind != MediaProbe.Kind.WEB) {
            MediaLauncher.open(this, targetUrl, item.title)
            return
        }

        // 如果是"播放本页视频"这种标记项，或目标就是当前页，直接进播放
        if (targetUrl == currentUrl) {
            openPlayer(currentUrl, item.title)
            return
        }

        // 同域链接：继续在原生界面里深入
        val sameHost = runCatching {
            val a = java.net.URI(currentUrl).host
            val bHost = java.net.URI(targetUrl).host
            a != null && a == bHost
        }.getOrDefault(false)

        if (sameHost) {
            history.addLast(currentUrl to (b.pageTitle.text?.toString() ?: siteName))
            load(targetUrl)
        } else {
            // 外链：直接交给播放页（可能是外站播放器）
            openPlayer(targetUrl, item.title)
        }
    }

    private fun openPlayer(url: String, title: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_DESKTOP, desktopUa)
        )
    }

    /** 媒体直链走统一点播入口（HLS / TS / MP4 / 网页自动分流） */

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (history.isNotEmpty()) {
                    val (prevUrl, prevTitle) = history.removeLast()
                    b.pageTitle.text = prevTitle
                    load(prevUrl)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                // 菜单键：打开原始网页（逃生通道）
                openPlayer(currentUrl, b.pageTitle.text?.toString() ?: siteName)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showLoading(text: String) {
        b.loading.visibility = View.VISIBLE
        b.loadingText.text = text
        b.statePanel.visibility = View.GONE
        b.rows.visibility = View.GONE
    }

    private fun showState(title: String, detail: String, retryable: Boolean) {
        b.loading.visibility = View.GONE
        b.rows.visibility = View.GONE
        b.statePanel.visibility = View.VISIBLE
        b.stateTitle.text = title
        b.stateDetail.text = detail
        b.stateHint.text =
            if (retryable) "按「确认」重试　按「返回」退出　按「菜单键」打开原始网页"
            else "按「返回」退出"
        b.statePanel.isFocusable = true
        b.statePanel.requestFocus()
        b.statePanel.setOnClickListener {
            if (retryable) {
                history.clear()
                load(currentUrl)
            }
        }
    }

    override fun onDestroy() {
        b.worker.removeCallbacks(timeoutRunnable)
        runCatching {
            b.worker.loadUrl("about:blank")
            b.worker.destroy()
        }
        super.onDestroy()
    }

    // ---------------- 行 / 卡片 RecyclerView ----------------

    private class RowAdapter(
        private val onClick: (CardItem) -> Unit,
        private val onLongClick: (CardItem) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.RowVH>() {

        private var rows: List<Section> = emptyList()
        private var referer: String = ""

        fun submit(list: List<Section>, refererBase: String) {
            rows = list
            referer = refererBase
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val b = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RowVH(b)
        }

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            holder.bind(rows[position], referer, onClick, onLongClick)
        }

        class RowVH(private val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(
                section: Section,
                referer: String,
                onClick: (CardItem) -> Unit,
                onLongClick: (CardItem) -> Unit,
            ) {
                b.rowTitle.text = section.title
                b.rowTitle.visibility = if (section.title.isBlank()) View.GONE else View.VISIBLE

                b.inner.layoutManager =
                    LinearLayoutManager(b.root.context, RecyclerView.HORIZONTAL, false)
                b.inner.itemAnimator = null
                if (section.isPoster) {
                    val adapter = PosterAdapter(referer, onClick, onLongClick)
                    b.inner.adapter = adapter
                    adapter.submit(section.items)
                } else {
                    val adapter = ChipAdapter(onClick, onLongClick)
                    b.inner.adapter = adapter
                    adapter.submit(section.items)
                }
            }
        }
    }

    private class PosterAdapter(
        private val referer: String,
        private val onClick: (CardItem) -> Unit,
        private val onLongClick: (CardItem) -> Unit,
    ) : RecyclerView.Adapter<PosterAdapter.VH>() {

        private var items: List<CardItem> = emptyList()

        fun submit(list: List<CardItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCardPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            FocusMotion.attach(b.cardRoot, focusedScale = 1.08f)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemCardPosterBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: CardItem) {
                b.caption.text = item.title
                if (item.image != null) {
                    b.placeholder.visibility = View.GONE
                    b.poster.visibility = View.VISIBLE
                    ImageLoader.load(b.poster, item.image, referer)
                } else {
                    b.poster.visibility = View.GONE
                    b.placeholder.visibility = View.VISIBLE
                    b.placeholder.text = item.title
                }
                b.cardRoot.setOnClickListener { onClick(item) }
                b.cardRoot.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }

    private class ChipAdapter(
        private val onClick: (CardItem) -> Unit,
        private val onLongClick: (CardItem) -> Unit,
    ) : RecyclerView.Adapter<ChipAdapter.VH>() {

        private var items: List<CardItem> = emptyList()

        fun submit(list: List<CardItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCardChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            FocusMotion.attach(b.root, focusedScale = 1.05f)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemCardChipBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: CardItem) {
                b.root.text = item.title
                b.root.setOnClickListener { onClick(item) }
                b.root.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }
}
