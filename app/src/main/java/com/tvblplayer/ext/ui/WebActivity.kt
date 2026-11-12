package com.tvblplayer.ext.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tvblplayer.BuildConfig
import com.tvblplayer.R
import com.tvblplayer.core.net.BiliClient
import com.tvblplayer.databinding.ActivityWebBinding
import com.tvblplayer.ext.util.FocusMotion
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

/**
 * 网页视图（主角）。
 *
 * 与 BrowseActivity 的区别：这里不解析、不重排，网页原样呈现，
 * 站点的搜索、分类、登录、设置、播放器全部照常工作。
 * App 只做一件事：把遥控器翻译成网页能理解的操作。
 *
 * 空间导航由注入的 tvnav.js 计算几何最近邻，但所有"点击"都用真实
 * MotionEvent 派发（D-004），保证全屏 / 播放 / 表单提交拿得到用户手势。
 */
class WebActivity : BaseTvActivity() {

    companion object {
        const val EXTRA_SITE_ID = "site_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESKTOP = "desktop"
        const val EXTRA_TIDY = "tidy"

        private val ZOOM_STEPS = intArrayOf(100, 125, 150)
        private const val PREFS = "web_view_prefs"

        /** 按住确认键超过这个时间就算长按（电视遥控器的手感阈值） */
        private const val LONG_PRESS_MS = 550L

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 网页自带的、在电视上只会碍事的浮动控件（D-011）。
         * 只按 id 精确命中，绝不做模糊匹配——一旦匹配错会把站点主干道藏起来。
         */
        private val TIDY_IDS = arrayOf("side-toolbar", "roll-to-top")

        /**
         * 移动版站点塞的「App 内打开 / 下载 App」浮层：TV 上点了只会跳应用商店或无效。
         */
        private val TIDY_SELECTORS = arrayOf(
            ".m-open-app", ".fixed-openapp", ".m-nav-openapp",
            ".open-app-btn", "#openapp", ".app-download-bar",
            "[class*=\"openapp\"]"
        )
    }

    private lateinit var b: ActivityWebBinding

    private var siteId = ""
    private var url = ""
    private var title = ""
    private var desktopMode = true
    private var tidyEnabled = true

    private var navScript = ""
    private var documentStartSupported = false

    private var toolbarVisible = false
    private var cursorMode = false
    private var cursorReady = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var focusedInput = false
    private var imeShown = false

    /** 网页播放器全屏时挂 Surface 的容器；退出全屏要原样交还 */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * 我们自己做的「网页全屏」（tvnav.js 把 video 搬进满屏浮层）。
     * 和上面 onShowCustomView 的差别：那条路要求站点自己调 Fullscreen API，
     * 遥控器根本点不到播放器那颗小按钮，所以 TV 上基本走不通。
     */
    private var pageFullscreen = false
    private var errorShown = false

    /** 上次把网页视频交给原生播放器的时间；短时间内不重复跳，避免来回弹 */
    private var lastHandoffAt = 0L

    /** 网络层嗅探到的视频地址：JS 提取失败时用它兜底 */
    private var snifferM3u8: String? = null
    private var snifferTs: String? = null

    /** 当前页面地址（主线程更新），后台探测播放列表时当 Referer 用，避免跨线程读 WebView */
    private var currentPageUrl: String = ""

    /**
     * 最近被请求过的所有 URL。
     *
     * 有些站（粤漫之家）的播放列表地址既不带 `.m3u8` 也不按常规命名，
     * 只能从这里反查：和 .ts 同目录、又不是分片/图片/脚本的那个请求，
     * 基本就是 playlist。
     */
    private val sniffedUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** 长按确认键的手势状态（电视遥控器没有专门的全屏键，MENU 在部分盒子被系统吃掉） */
    private var okLongFired = false
    private val okLongPress = Runnable {
        okLongFired = true
        toggleFullscreen()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val hideStatus = Runnable { b.tvStatus.visibility = View.GONE }
    private val stepPx: Float by lazy { 26f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebBinding.inflate(layoutInflater)
        setContentView(b.root)

        siteId = intent.getStringExtra(EXTRA_SITE_ID).orEmpty()
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        // 默认桌面版：手机版站点（bilibili / 优酷 / 爱奇艺…）在 TV 上只会给出
        // 「看列表可以、点播放就弹请下载 App」的页面，桌面版才有能用的播放器（D-011）。
        // 已存的站点记录里 desktopMode=false 是旧默认值，不再让它压过新默认；
        // 用户显式切换过的版式按域名记住，优先级最高。
        desktopMode = savedDesktopMode() ?: true
        tidyEnabled = intent.getBooleanExtra(EXTRA_TIDY, true)

        setupWebView()
        setupToolbar()
        setupErrorPanel()
        installNavScript()

        b.tvTitle.text = title.ifBlank { url }
        // 传入空 URL 时不要 loadUrl("")——那只会得到一片纯黑，用户完全无法判断出了什么事。
        // 直接用已有的错误面板说清原因，并指向可执行的下一步。
        if (url.isBlank()) {
            showMissingUrl()
            return
        }
        b.web.loadUrl(url)
        showStatus(
            if (documentStartSupported) "方向键移动焦点 · 确认键点击 · 长按确认键全屏"
            else "方向键移动焦点 · 注意：当前 WebView 不支持 iframe 注入"
        )
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val w = b.web
        // debug 包打开 WebView 远程调试：桌面 chrome://inspect 或 CDP 能直接读到
        // 页面里 <video> 的真实编码 / videoWidth / error，排查「有声音没画面」
        // 时不用靠猜。release 包不执行。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopMode) DESKTOP_UA else MOBILE_UA
            textZoom = savedZoom()
        }
        // 页面没渲染前的底色跟应用主题一致，避免每次打开先闪一下纯黑
        w.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        // 电视上的 WebView 视频常见「有声音、没画面」：Chromium 内部用 SurfaceView
        // 渲染视频，部分盒子的窗口合成会把它盖住。整个 WebView 改走硬件层合成后，
        // 视频会走 TextureView 路径，可以绕开这类遮挡；手机 / 模拟器上无副作用。
        runCatching { w.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false

        // T2 会做三处共享状态的完整同步；这里先保证绝不误清会话
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        w.webViewClient = object : WebViewClient() {
            /**
             * 记录播放器真正请求的视频地址。
             *
             * 页面里的 hls.js 会把 `<video>.src` 变成 `blob:`，m3u8 的文件名还常被
             * 混淆，单靠 JS 提取经常拿不到。shouldInterceptRequest 能看到**所有**
             * 请求（XHR / fetch / worker 都算），是最可靠的一处。这里只记录，不拦截。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url?.toString().orEmpty()
                if (u.isNotEmpty()) {
                    when {
                        u.contains(".m3u8", ignoreCase = true) -> {
                            if (snifferM3u8 != u) Log.d("WebHandoff", "sniff m3u8: ${u.take(140)}")
                            snifferM3u8 = u
                        }
                        u.contains(".ts", ignoreCase = true) -> {
                            if (snifferTs == null) Log.d("WebHandoff", "sniff ts: ${u.take(140)}")
                            snifferTs = u
                        }
                    }
                    if (sniffedUrls.size < 120) sniffedUrls.add(u)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                super.onPageStarted(view, u, favicon)
                // 换页时先把上一页的错误面板收掉，否则新页面会被旧提示盖住
                errorShown = false
                b.errorPanel.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                super.onPageFinished(view, u)
                currentPageUrl = u.orEmpty()
                // 特性不支持时，只能在文档完成后补注入（覆盖不到 iframe）
                if (!documentStartSupported && navScript.isNotBlank()) {
                    view?.evaluateJavascript(navScript, null)
                }
                // WebView 登录后把会话同步给原生接口层（D-012）
                syncBiliCookies(u)
                view?.postDelayed({
                    view.evaluateJavascript("window.__tvNav && window.__tvNav.activate()", null)
                    refreshFocusInfo()
                    if (tidyEnabled) applyTidy()
                }, 400)
            }

            /**
             * 加载失败必须显式告诉用户。
             * 之前这里什么都没写：域名解析失败 / 超时 / 证书错误时 WebView 只留一片纯黑，
             * 用户看到的就是「打开其他网址会黑屏」——分不清是网断了还是 App 卡死（D-011）。
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 只处理主文档，图片 / 广告 / 统计脚本失败不该弹整屏错误
                if (request?.isForMainFrame != true) return
                val code = error?.errorCode ?: 0
                val desc = error?.description?.toString().orEmpty()
                showLoadError(code, desc)
            }

            /**
             * 4xx / 5xx 走的是 HTTP 错误而不是 onReceivedError，
             * 页面会是站点自己的错误页或空白，同样需要兜底。
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: 0
                if (code >= 400) showLoadError(-code, "HTTP $code")
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            /**
             * 网页播放器全屏（bilibili / 粤漫之家的 <video> 全屏）时，
             * Chromium 把渲染 Surface 作为一个 View 交过来。之前没有这个回调，
             * 全屏就只剩黑屏——这也是「打开会黑屏」的一半原因。
             */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                hideToolbar()
                hideIme()
                b.videoStage.visibility = View.VISIBLE
                b.videoStage.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                fullscreenView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                fullscreenView = null
                runCatching { fullscreenCallback?.onCustomViewHidden() }
                fullscreenCallback = null
                b.videoStage.removeAllViews()
                b.videoStage.visibility = View.GONE
                b.web.requestFocus()
            }
        }
        // tvnav.js 全屏浮层的状态回调：页面进/出全屏时把布尔状态告诉原生端，
        // 返回键才有机会先退全屏、而不是直接把整个网页视图关掉。
        w.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onFullscreen(active: Boolean) {
                    runOnUiThread {
                        pageFullscreen = active
                        showStatus(if (active) "全屏播放 · 按返回退出" else "已退出全屏")
                    }
                }

                /**
                 * 网页自己播不出画面时（电视上典型是「有声音、画面全黑」），
                 * tvnav.js 会把真实 m3u8 交过来，改用内置原生播放器。
                 * 这条路上解码与渲染都由 ExoPlayer 负责，不依赖 WebView 的
                 * 视频层，能保证电视上一定有画面。
                 */
                @JavascriptInterface
                fun openNativePlayer(url: String?, pageTitle: String?): Boolean {
                    Log.d(
                        "WebHandoff",
                        "call url=${url?.take(90)} snifferM3u8=${snifferM3u8?.take(90)} " +
                            "snifferTs=${snifferTs?.take(90)}"
                    )
                    // 优先级：JS 提取到的地址 → 网络层嗅探到的 m3u8
                    val direct = url.orEmpty().ifBlank { snifferM3u8.orEmpty() }
                    if (direct.isNotBlank()) {
                        runOnUiThread { handoffToNative(direct, pageTitle.orEmpty()) }
                        return true
                    }
                    // 粤漫之家把 playlist 名字也混淆了，嗅探不到 .m3u8；
                    // 但 .ts 分片一定能抓到，用它反推（原生发请求，不受 CORS 限制）
                    val ts = snifferTs.orEmpty()
                    if (ts.isBlank()) return false
                    deriveM3u8FromTs(ts) { found ->
                        handoffToNative(found, pageTitle.orEmpty())
                    }
                    return false   // 探测是异步的，让 JS 继续重试
                }
            },
            "TvHost"
        )
        w.requestFocus()
    }

    // ---------- 加载失败面板 ----------

    /**
     * URL 缺失时的兜底。
     *
     * 只传 siteId、不带 url 启动本页时，过去会直接 loadUrl("") —— WebView 白屏/黑屏，
     * 没有任何可读信息。这里把原因和下一步写到错误面板里，不让用户对着黑屏猜。
     */
    private fun showMissingUrl() {
        errorShown = true
        hideToolbar()
        hideIme()
        b.tvErrTitle.text = "没有拿到要打开的网址"
        b.tvErrDetail.text = "这个页面必须带上站点地址才能打开。\n回扩展页，从「粤漫之家」重新进入。"
        // 桌面版切换对空 URL 没意义
        b.btnErrDesktop.visibility = View.GONE
        b.errorPanel.visibility = View.VISIBLE
        b.errorPanel.post { b.btnErrBack.requestFocus() }
    }

    private fun setupErrorPanel() {
        listOf(b.btnErrRetry, b.btnErrDesktop, b.btnErrBack).forEach { FocusMotion.attach(it, 1.06f) }
        b.btnErrRetry.setOnClickListener {
            b.errorPanel.visibility = View.GONE
            errorShown = false
            b.web.reload()
        }
        b.btnErrDesktop.setOnClickListener { switchToDesktop() }
        b.btnErrBack.setOnClickListener { finish() }
    }

    /**
     * 把 WebView 的错误码翻译成人能看懂的原因。
     * 负数是我们自己造的：-HTTP 状态码。
     */
    private fun showLoadError(code: Int, desc: String) {
        if (errorShown) return
        errorShown = true
        hideToolbar()
        hideIme()

        val reason = when {
            code == -6 -> "网络好像没有连上，检查一下电视的 Wi-Fi。"
            code == -8 -> "连接超时，站点没有响应。"
            code == -2 -> "找不到这个网址的主机名。"
            code == -11 -> "证书不被信任，站点可能已过期。"
            code == -9 -> "站点重定向次数太多。"
            code <= -400 && code > -500 -> "站点返回了 $(-code)（页面不存在或无权限）。"
            code <= -500 -> "站点服务器出错了（$(-code)），稍后再试。"
            else -> "加载中断${if (desc.isNotBlank()) "：$desc" else ""}。"
        }
        b.tvErrTitle.text = "网页没能打开"
        b.tvErrDetail.text = buildString {
            append(reason)
            append("\n")
            append(url)
        }
        // 桌面版按钮在已经是桌面版时没有意义，直接藏掉
        b.btnErrDesktop.visibility = if (desktopMode) View.GONE else View.VISIBLE
        b.errorPanel.visibility = View.VISIBLE
        b.errorPanel.post { b.btnErrRetry.requestFocus() }
    }

    /** 出错时从手机版换到桌面版重试——很多站点换版式就能正常打开 */
    private fun switchToDesktop() {
        if (desktopMode) {
            b.errorPanel.visibility = View.GONE
            errorShown = false
            b.web.reload()
            return
        }
        desktopMode = true
        b.web.settings.userAgentString = DESKTOP_UA
        errorShown = false
        b.errorPanel.visibility = View.GONE
        b.web.reload()
        showStatus("已切换到桌面版")
    }

    // ---------- 清理网页上多余的东西 ----------

    /**
     * 把 WebView 里的 B 站登录态同步给原生接口层。
     *
     * 背景：WebView 用系统的 CookieManager，OkHttp 用自己的 CookieJar，两者完全不互通。
     * 用户在网页视图登录 B 站之后，首页信息流和原生播放器还以为自己是游客，
     * 只能拿到 480P / 试看流。这里在每次页面加载完成时把 bilibili 域名下的 Cookie
     * 灌进 CookieStore，原生接口立刻变成已登录状态（D-012）。
     *
     * 只在 bilibili 相关域名上做，别把其他站点的 Cookie 也搬进来。
     */
    private fun syncBiliCookies(pageUrl: String?) {
        val host = runCatching {
            java.net.URI(pageUrl ?: url).host.orEmpty().lowercase()
        }.getOrDefault("")
        if (host.isBlank()) return
        if (!host.endsWith("bilibili.com") && !host.endsWith("biliapi.net")) return

        runCatching {
            val raw = CookieManager.getInstance().getCookie("https://www.bilibili.com").orEmpty()
            if (raw.isBlank()) return@runCatching
            val pairs = raw.split(';').mapNotNull { part: String ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

            // 未登录时 B 站也会下发一堆匿名 Cookie（buvid3 等）。没有 SESSDATA 就不必同步，
            // 否则会把「游客」当成「已登录」写进去，反而误导界面状态。
            if (!pairs.containsKey("SESSDATA")) return@runCatching

            // blbl 的 CookieStore 用 OkHttp Cookie 模型，按域名分桶。
            // 网页登录态要转成对应的 Cookie 对象再 upsert，原生接口才会认。
            BiliClient.init(applicationContext)
            val now = System.currentTimeMillis()
            val webCookies = pairs.map { (name, value) ->
                okhttp3.Cookie.Builder()
                    .domain("www.bilibili.com")
                    .path("/")
                    .name(name)
                    .value(value)
                    .expiresAt(now + 180L * 24 * 60 * 60 * 1000)
                    .build()
            }
            BiliClient.cookies.upsertAll(webCookies)
        }
    }

    /**
     * 只做「隐藏」，不删 DOM、不改站点逻辑：
     *  - 粤漫之家自带的 #side-toolbar 悬浮侧栏（站点首页 / 切换主题 / 下载 APP /
     *    留言 / 返回顶部）。在 TV 上它挡住右侧一整条内容区，还会被空间导航当成
     *    候选元素抢走焦点——用户说的「右侧控制栏有点多余」就是它（D-011）。
     *  - 移动版站点塞的「App 内打开」浮层，TV 上点了只会跳商店。
     * 这两类都在每次页面加载后重新执行，因为站点会重建 DOM。
     */
    private fun applyTidy() {
        val ids = TIDY_IDS.joinToString(",") { JSONObject.quote(it) }
        val sels = TIDY_SELECTORS.joinToString(",") { JSONObject.quote(it) }
        val js = """
            (function(){
              var q = 0;
              function hide(el){ if(!el) return; el.style.setProperty('display','none','important'); q++; }
              try {
                var ids = [$ids];
                for (var i = 0; i < ids.length; i++) hide(document.getElementById(ids[i]));
                var sels = [$sels];
                for (var j = 0; j < sels.length; j++) {
                  var n = document.querySelectorAll(sels[j]);
                  for (var k = 0; k < n.length; k++) hide(n[k]);
                }
              } catch (e) {}
              return q;
            })()
        """.trimIndent()
        b.web.evaluateJavascript(js) { raw ->
            if ((raw?.toIntOrNull() ?: 0) > 0) {
                // 焦点可能原本停在刚被藏掉的元素上，重新落一次
                b.web.evaluateJavascript("window.__tvNav && window.__tvNav.activate()", null)
            }
        }
    }

    private fun installNavScript() {
        navScript = runCatching {
            assets.open("tvnav.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (navScript.isBlank()) {
            showStatus("导航脚本缺失，遥控器可能不可用")
            return
        }
        // 依赖系统 WebView 版本，必须做特性检测
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(b.web, navScript, setOf("*"))
            }.onFailure { documentStartSupported = false }
        }
    }

    // ---------- 缩放（按站点记住） ----------

    private fun hostKey(): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
    private fun zoomPrefKey() = "zoom_${hostKey()}"
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun savedZoom(): Int = prefs().getInt(zoomPrefKey(), 100)
    /** 没记忆值时返回 null，交给上层决定默认版式 */
    private fun savedDesktopMode(): Boolean? =
        if (prefs().contains(uaPrefKey())) prefs().getBoolean(uaPrefKey(), true) else null

    private fun cycleZoom(delta: Int) {
        val cur = b.web.settings.textZoom
        val idx = ZOOM_STEPS.indexOf(cur).let { if (it < 0) 0 else it }
        val next = ZOOM_STEPS[(idx + delta + ZOOM_STEPS.size) % ZOOM_STEPS.size]
        b.web.settings.textZoom = next
        prefs().edit().putInt(zoomPrefKey(), next).apply()
        b.btnZoom.text = "缩放 $next%"
        showStatus("缩放 $next%（已按站点记住）")
    }

    // ---------- 工具条 ----------

    private fun setupToolbar() {
        b.btnZoom.text = "缩放 ${savedZoom()}%"
        b.btnUa.text = if (desktopMode) "桌面版" else "手机版"
        listOf(b.btnZoom, b.btnUa, b.btnCursor, b.btnRefresh, b.btnFullscreen).forEach {
            FocusMotion.attach(it, 1.06f)
        }
        b.btnZoom.setOnClickListener { cycleZoom(1) }
        b.btnUa.setOnClickListener { toggleUa() }
        b.btnCursor.setOnClickListener { toggleCursorMode() }
        b.btnFullscreen.setOnClickListener { toggleFullscreen() }
        b.btnRefresh.setOnClickListener {
            b.errorPanel.visibility = View.GONE
            errorShown = false
            b.web.reload()
        }
    }

    /**
     * 网页播放器全屏开关。
     *
     * 站点播放器那颗全屏按钮在 TV 上点不到，而 Fullscreen API 又需要用户手势，
     * 所以由 tvnav.js 把 <video> 搬进满屏浮层。这里只负责转达和给出反馈：
     * 找不到视频就说清楚，不要让用户以为按键没反应。
     */
    private fun toggleFullscreen() {
        hideToolbar()
        // 以页面里的真实状态为准：TvHost 回调是异步的，本地标志可能还没更新，
        // 直接按本地标志切会在连按时来回错一拍。
        b.web.evaluateJavascript("window.__tvNav ? window.__tvNav.isFullscreen() : '0'") { state ->
            val isFs = state?.trim('"') == "1"
            val call = if (isFs) "exitFullscreen()" else "fullscreen()"
            b.web.evaluateJavascript("window.__tvNav ? window.__tvNav.$call : '0'") { raw ->
                if (!isFs && raw?.trim('"') != "1") {
                    showStatus("这个页面没有找到可全屏的视频")
                }
            }
        }
    }

    /**
     * 手机版 / 桌面版切换。
     * 站点类型差别很大：资源型站点（粤漫之家）桌面版信息密度高、海报一行更多；
     * 视频型站点（bilibili）桌面版有能用的播放器，手机版只会劝你下载 App。
     * 切换只改 UA 并重载，不动会话（Cookie 在同一个 WebView 里是共享的）。
     */
    private fun toggleUa() {
        desktopMode = !desktopMode
        b.btnUa.text = if (desktopMode) "桌面版" else "手机版"
        b.web.settings.userAgentString = if (desktopMode) DESKTOP_UA else MOBILE_UA
        prefs().edit().putBoolean(uaPrefKey(), desktopMode).apply()
        b.errorPanel.visibility = View.GONE
        errorShown = false
        b.web.reload()
        showStatus(if (desktopMode) "已切换到桌面版" else "已切换到手机版")
    }

    private fun uaPrefKey() = "desktop_${hostKey()}"

    private fun showToolbar() {
        toolbarVisible = true
        b.toolbar.visibility = View.VISIBLE
        b.toolbar.post { b.btnUa.requestFocus() }
    }

    private fun hideToolbar() {
        toolbarVisible = false
        b.toolbar.visibility = View.GONE
        b.web.requestFocus()
    }

    private fun toggleCursorMode() {
        cursorMode = !cursorMode
        if (cursorMode) {
            if (!cursorReady) {
                cursorX = b.web.width / 2f
                cursorY = b.web.height / 2f
                cursorReady = true
            }
            b.cursor.visibility = View.VISIBLE
            b.cursor.moveTo(cursorX, cursorY)
            showStatus("指针模式：方向键移动 · 确认键点击 · 菜单键切回")
        } else {
            b.cursor.visibility = View.GONE
            showStatus("空间导航模式")
        }
    }

    private fun showStatus(text: String) {
        ui.removeCallbacks(hideStatus)
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.text = text
        ui.postDelayed(hideStatus, 3200)
    }

    // ---------- 原生播放器接管 ----------

    /**
     * 用 .ts 分片地址反推播放列表。
     *
     * 粤漫之家这类站点把 m3u8 的文件名一起混淆了（甚至不给扩展名），
     * JS 和 WebView 网络层都拿不到干净的 playlist 地址；但分片一定能抓到。
     * 这里按「同名换扩展名」+「同目录常见名」逐个试，用 #EXTM3U 校验。
     * 请求由原生发起，没有 CORS 限制，比在页面里探测可靠。
     */
    private fun deriveM3u8FromTs(tsUrl: String, onFound: (String) -> Unit) {
        val referer = currentPageUrl
        thread {
            val qm = Regex("\\?.*$").find(tsUrl)?.value.orEmpty()
            val dir = tsUrl.replace(Regex("[^/]*(\\?.*)?$"), "")
            val base = tsUrl.replace(Regex("\\.ts(\\?.*)?$", RegexOption.IGNORE_CASE), "")
            val cands = linkedSetOf<String>()
            // 1) 真抓到过 .m3u8 就直接用
            snifferM3u8?.let { cands.add(it) }
            // 2) 从「所有被请求过的 URL」里反查：和分片同目录、又不是分片 / 图片 /
            //    脚本的，基本就是被打散命名的 playlist（粤漫之家就是这种，名字里
            //    连 m3u8 都不带）
            val sniffed = synchronized(sniffedUrls) { sniffedUrls.toList() }
            for (u in sniffed) {
                if (!u.startsWith(dir)) continue
                if (u.contains(".ts", true) || u.contains(".jpg", true) ||
                    u.contains(".png", true) || u.contains(".webp", true) ||
                    u.contains(".css", true) || u.contains(".js", true) ||
                    u.contains(".json", true)
                ) continue
                cands.add(u)
            }
            // 3) 最后才猜常见文件名
            cands.add("$base.m3u8$qm")
            cands.add("${dir}index.m3u8$qm")
            cands.add("${dir}playlist.m3u8$qm")
            cands.add("${dir}main.m3u8$qm")

            Log.d("WebHandoff", "derive candidates=${cands.size} dir=${dir.take(90)}")
            for (c in cands) {
                val body = httpGetText(c, referer)
                val tag = when {
                    body == null -> "FAIL"
                    body.contains("#EXTM3U") -> "HIT"
                    else -> "miss"
                }
                Log.d("WebHandoff", "probe $tag ${c.take(120)}")
                if (body != null && body.contains("#EXTM3U")) {
                    runOnUiThread { onFound(c) }
                    return@thread
                }
            }
            Log.d("WebHandoff", "derive: no playlist found")
        }
    }

    /** 极简 GET，只用来校验候选播放列表；返回 null 表示失败 */
    private fun httpGetText(url: String, referer: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d("WebHandoff", "http $code ${url.take(110)}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText().take(4096) }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 网页视频在电视上画不出来时，把真实流地址交给内置播放器。
     * 只处理 m3u8（本体是 HLS）；一分钟内不重复跳转，防止用户从播放器
     * 返回网页后又被自动弹回去。
     */
    private fun handoffToNative(mediaUrl: String, pageTitle: String) {
        if (mediaUrl.isBlank()) return   // 还没嗅探到地址，等 JS 的定时重试
        val now = System.currentTimeMillis()
        if (now - lastHandoffAt < 60_000L) {
            Log.d("WebHandoff", "skip: within 60s")
            return
        }
        lastHandoffAt = now
        Log.d("WebHandoff", "handoff -> ${mediaUrl.take(140)}")
        b.web.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');if(v){v.pause();}}catch(e){}})()",
            null
        )
        startActivity(
            Intent(this, NativePlayerActivity::class.java)
                .putExtra(NativePlayerActivity.EXTRA_URL, mediaUrl)
                .putExtra(NativePlayerActivity.EXTRA_TITLE, pageTitle.ifBlank { title })
                // 防盗链：播放器请求分片时要带页面地址做 Referer
                .putExtra(NativePlayerActivity.EXTRA_REFERER, currentPageUrl)
        )
        showStatus("网页播放器不支持，已切到原生播放器")
    }

    // ---------- JS 交互 ----------

    /** evaluateJavascript 返回的是被 JSON 再编码一层的字符串 */
    private fun decode(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                is JSONObject -> v.toString()
                else -> null
            }
        }.getOrNull()
    }

    private fun refreshFocusInfo(withHover: Boolean = false) {
        b.web.evaluateJavascript("window.__tvNav ? window.__tvNav.info() : null") { raw ->
            val o = decode(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }
            focusedInput = o?.optString("type") == "input"
            // 焦点离开输入框就收起输入法；进入时不主动弹，
            // 等用户按确定真正点进去（否则浏览页面会被键盘打断）。
            if (!focusedInput && imeShown) hideIme()
            // 输入框上派发鼠标 hover 会把 Chromium 的触摸编辑态顶掉，输入法就弹不出来。
            // 搜索下拉这类菜单靠 JS 合成 mouseover 已经能展开，不需要这一步。
            if (withHover && !focusedInput) hoverFocused()
        }
    }

    /** 焦点到达时额外派发一次真实 hover，用于纯 CSS :hover 菜单 */
    private fun hoverFocused() {
        b.web.evaluateJavascript("window.__tvNav ? window.__tvNav.center() : null") { raw ->
            val o = decode(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@evaluateJavascript
            hoverAt(
                o.optDouble("fx", 0.5).toFloat() * b.web.width,
                o.optDouble("fy", 0.5).toFloat() * b.web.height
            )
        }
    }

    private fun clickFocused() {
        b.web.evaluateJavascript("window.__tvNav ? window.__tvNav.center() : null") { raw ->
            val o = decode(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o == null) {
                showStatus("页面里还没找到可聚焦的元素，按方向键试试")
                return@evaluateJavascript
            }
            tapAt(
                o.optDouble("fx", 0.5).toFloat() * b.web.width,
                o.optDouble("fy", 0.5).toFloat() * b.web.height
            )
            // 点中的是输入框：Chromium 一般会自己拉起系统输入法；这里再补一次，
            // 覆盖「应用内派发的触摸不建立编辑态」的盒子（MuMu 实测会漏）。
            if (focusedInput) {
                ui.postDelayed({ if (focusedInput && !imeShown) showIme() }, 260)
            }
        }
    }

    // ---------- 真实输入事件 ----------

    private fun pointerArrays(x: Float, y: Float, tool: Int) = Pair(
        arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = tool
        }),
        arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
    )

    private fun tapAt(x: Float, y: Float) {
        // ACTION_DOWN 与 ACTION_UP 必须共用同一个 downTime，否则 Chromium 会把
        // 它们当成两次无关触摸，形不成 tap，也不会建立输入框的编辑态。
        val downTime = SystemClock.uptimeMillis()
        val (props, coords) = pointerArrays(x, y, MotionEvent.TOOL_TYPE_FINGER)
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        // 从根 View 开始分发，而不是直接塞给 WebView：
        // Chromium 要靠完整的 View 树触摸流程才肯建立 Android 编辑态并弹输入法。
        b.web.rootView.dispatchTouchEvent(down)
        down.recycle()
        b.web.postDelayed({
            val t = SystemClock.uptimeMillis()
            val (p2, c2) = pointerArrays(x, y, MotionEvent.TOOL_TYPE_FINGER)
            val up = MotionEvent.obtain(
                downTime, t, MotionEvent.ACTION_UP, 1, p2, c2,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            b.web.rootView.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    private fun hoverAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val (props, coords) = pointerArrays(x, y, MotionEvent.TOOL_TYPE_MOUSE)
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        ev.source = InputDevice.SOURCE_MOUSE
        // dispatchHoverEvent 在 ViewGroup 里是 protected，外部只能走通用手势分发
        runCatching { b.web.dispatchGenericMotionEvent(ev) }
        ev.recycle()
    }

    /** 输入框内方向键属于光标移动，交还给 WebView / 输入法 */
    private fun dispatchToWeb(keyCode: Int) {
        b.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        b.web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun showIme() {
        b.web.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // SHOW_IMPLICIT 在部分 TV / 模拟器上会被直接忽略，输入框就永远打不了字。
        // 先走 SHOW_FORCED；300ms 后若还没进入「接受输入」状态再补一次，
        // 有些盒子第一次调用时窗口 token 尚未准备好。
        @Suppress("DEPRECATION")
        imm.showSoftInput(b.web, InputMethodManager.SHOW_FORCED)
        imeShown = true
        ui.postDelayed({
            if (!imm.isAcceptingText) {
                runCatching {
                    @Suppress("DEPRECATION")
                    imm.toggleSoftInputFromWindow(
                        b.web.windowToken,
                        InputMethodManager.SHOW_FORCED,
                        0
                    )
                }
            }
            // 设备上没有可用输入法 / 系统拒绝弹窗时，别把 imeShown 留在 true：
            // 否则确定键会一直被「输入法打开」分支吞掉，页面就彻底点不动了。
            ui.postDelayed({ imeShown = imm.isAcceptingText }, 400)
        }, 300)
    }

    private fun hideIme(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imeShown && !imm.isAcceptingText) return false
        imeShown = false
        runCatching { imm.hideSoftInputFromWindow(b.web.windowToken, 0) }
        // 收键盘后把焦点还给 WebView：焦点留在输入法/输入框上时，
        // dispatchKeyEvent 后续的方向键没有接收者，焦点框就不会动。
        b.web.requestFocus()
        return true
    }

    // ---------- 按键 ----------

    /**
     * 必须拦在 dispatchKeyEvent：WebView 一旦成为焦点 View，会自己吃掉方向键，
     * Activity.onKeyDown 根本收不到（实测连按 5 次右键一条 move 日志都没有）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 确认键改到「抬起」才点击，并顺带识别长按——长按 = 网页视频全屏。
        // 只在纯浏览状态接管；工具栏 / 错误面板 / 键盘打开时仍交给系统 View 焦点，
        // 否则那几处的按钮会点不动。
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk && fullscreenView == null && !errorShown && !toolbarVisible) {
            // 输入法开着时，确定键先承担「收起键盘」：既给用户一个明确的退出动作，
            // 又不会把确定键整个吞掉。之前这里用 !imeShown 整体排除，
            // 一旦标志位和真实输入法状态不同步，页面就彻底点不动了。
            if (imeShown) {
                if (event.action == KeyEvent.ACTION_UP) hideIme()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                // 用定时器判定长按，不依赖系统重复事件：很多遥控器 / input 注入
                // 只发一对 DOWN/UP，repeatCount 永远是 0。
                if (event.repeatCount == 0 && !okLongFired) {
                    ui.removeCallbacks(okLongPress)
                    ui.postDelayed(okLongPress, LONG_PRESS_MS)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                ui.removeCallbacks(okLongPress)
                if (okLongFired) {
                    okLongFired = false
                    return true
                }
                // 一律真实点击。焦点在输入框时也是先点进去，让 Chromium 自己
                // 进入编辑态并拉起系统输入法（clickFocused 里再兜底补一次）。
                // 以前这里拦成「只弹自绘键盘」，结果链接 / 登录按钮永远点不动。
                if (cursorMode) tapAt(cursorX, cursorY) else clickFocused()
                return true
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // 输入法打开时只截「返回 / 菜单」用来收键盘；方向键、确定键照常走 tvnav。
        // 以前这里把除返回外的所有按键都交给系统，imeShown 一旦和实际状态不同步，
        // 整个页面就会没有任何按键有反应（高亮框不动、播放按钮点不开）。
        if (imeShown && (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_MENU)
        ) {
            if (hideIme()) return true
        }
        // 网页视频全屏：返回键退出全屏，别把整个网页视图关掉
        if (fullscreenView != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_MENU
            ) {
                b.web.evaluateJavascript(
                    "(function(){try{if(document.exitFullscreen)document.exitFullscreen();" +
                        "else if(document.webkitExitFullscreen)document.webkitExitFullscreen();}catch(e){}})()",
                    null
                )
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // 我们自己做的网页全屏（tvnav.js 满屏浮层）：返回键先退全屏
        if (pageFullscreen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_MENU
            ) {
                b.web.evaluateJavascript(
                    "window.__tvNav && window.__tvNav.exitFullscreen()", null
                )
                return true
            }
        }
        // 错误面板：方向键 / 确认键走系统 View 焦点（三个按钮各自处理点击），
        // 返回键在这里直接退出，不能让用户卡在错误页上按不出反应
        if (errorShown) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) return true
            return super.dispatchKeyEvent(event)
        }
        if (toolbarVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BACK) {
                hideToolbar()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (handleTvKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleTvKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { showToolbar(); return true }
            KeyEvent.KEYCODE_BACK -> { handleBack(); return true }
            KeyEvent.KEYCODE_ZOOM_IN -> { cycleZoom(1); return true }
            KeyEvent.KEYCODE_ZOOM_OUT -> { cycleZoom(-1); return true }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (cursorMode) {
                    moveCursor(keyCode)
                    return true
                }
                if (focusedInput) {
                    // 输入框里：左右键交给 WebView 移动光标；上下键仍然走 tvnav，
                    // 否则焦点一进搜索框就再也出不来（播放按钮永远点不到）。
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    ) {
                        dispatchToWeb(keyCode)
                        return true
                    }
                }
                val dir = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> "up"
                    KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                    else -> "right"
                }
                b.web.evaluateJavascript("window.__tvNav && window.__tvNav.move('$dir')", null)
                b.web.postDelayed({ refreshFocusInfo(withHover = true) }, 140)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // 这里留着只是兜底：正常情况下确认键已在 dispatchKeyEvent 顶部
                // 按「抬起点击 / 长按全屏」处理掉了。
                if (cursorMode) tapAt(cursorX, cursorY) else clickFocused()
                return true
            }
        }
        return false
    }

    /** 返回顺序：遥控器/系统键盘 → 弹层 → 网页历史 → 退回站点列表 */
    private fun handleBack(): Boolean {
        if (hideIme()) return true
        b.web.evaluateJavascript("window.__tvNav ? window.__tvNav.dismiss() : '0'") { raw ->
            if (decode(raw) == "1") {
                showStatus("已关闭弹层")
                return@evaluateJavascript
            }
            if (b.web.canGoBack()) {
                b.web.goBack()
                showStatus("返回上一页")
            } else if (!samePage(b.web.url.orEmpty(), url)) {
                // SPA / location.replace 不进 WebView 历史（粤漫之家的搜索页就是），
                // 这时按返回应该回站点首页，而不是直接把整个网页视图关掉。
                b.web.loadUrl(url)
                showStatus("返回站点首页")
            } else {
                finish()
            }
        }
        return true
    }

    /** 忽略末尾斜杠和 hash，避免把首页和首页#锚点当成两个页面 */
    private fun samePage(a: String, b: String): Boolean {
        fun norm(s: String) = s.substringBefore('#').trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    private fun moveCursor(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= stepPx) b.web.scrollBy(0, -(stepPx * 3).toInt())
                else cursorY -= stepPx
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= b.web.height - stepPx) b.web.scrollBy(0, (stepPx * 3).toInt())
                else cursorY += stepPx
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= stepPx
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += stepPx
        }
        cursorX = cursorX.coerceIn(0f, b.web.width.toFloat())
        cursorY = cursorY.coerceIn(0f, b.web.height.toFloat())
        b.cursor.moveTo(cursorX, cursorY)
    }

    override fun onPause() {
        super.onPause()
        b.web.onPause()
        // 让登录会话及时落盘（T2 会做三处统一同步）
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun onResume() {
        super.onResume()
        b.web.onResume()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching {
            b.web.loadUrl("about:blank")
            b.web.destroy()
        }
        super.onDestroy()
    }
}
