package com.tvblplayer.ext.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.tvblplayer.databinding.ExtActivityPlayerBinding

/**
 * 播放页：用站点自己的网页播放器播放。
 *
 * 为什么这么做：站点把播放地址保护在设备指纹 + 额度 + 登录之后，
 * 直接解析 m3u8 需要绕过这些——那超出本项目范围。
 * 让页面在真实 WebView 里自己跑，指纹和额度都按站点规则走，不构成绕过。
 *
 * 遥控器操作：D-pad 移动虚拟指针，确认键在指针位置派发真实触摸事件。
 * 用真实 MotionEvent 而不是 JS 点击，才能通过浏览器的用户手势校验（自动播放等）。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ExtActivityPlayerBinding
    private var desktopUa = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val stepPx: Float by lazy { STEP_DP * resources.displayMetrics.density }
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorReady = false

    private var lastKeyTime = 0L
    private var repeatAccel = 1

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideChrome = Runnable { fadeChrome(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ExtActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        desktopUa = intent.getBooleanExtra(EXTRA_DESKTOP, false)

        b.title.text = title
        b.hint.text = "正在加载播放页…"
        // 起播先亮一下标题，随后自动淡出，把画面让出来
        fadeChrome(true)
        setupWebView(url)
    }

    /** 顶部 chrome 的显隐。不常驻，避免一直压着画面。 */
    private fun fadeChrome(show: Boolean) {
        ui.removeCallbacks(hideChrome)
        b.topBar.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(if (show) 160 else 320)
            .withStartAction { if (show) b.topBar.visibility = View.VISIBLE }
            .withEndAction { if (!show) b.topBar.visibility = View.GONE }
            .start()
        if (show) ui.postDelayed(hideChrome, 4000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        val w = b.web
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopUa) DESKTOP_UA else mobileUa()
        }
        w.setBackgroundColor(0xFF000000.toInt())

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                b.hint.text = "正在加载播放页…"
                b.hint.visibility = View.VISIBLE
                b.errorPanel.visibility = View.GONE
                // 不再重复 fadeChrome(true)：网页内重定向/多次导航会不停重置计时，
                // 标题条就变成"一直挂在上面"。首次那次由 onCreate 负责。
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                // 加载完把提示收掉，别让"正在加载播放页…"长期停在标题下面
                b.hint.visibility = View.GONE
                view?.evaluateJavascript(AUTOPLAY_JS, null)
                view?.postDelayed({ view.evaluateJavascript(AUTOPLAY_JS, null) }, 1500)
                view?.postDelayed({ view.evaluateJavascript(AUTOPLAY_JS, null) }, 3500)
            }

            // 播放页加载失败也是纯黑，必须给用户一句能读懂的话（D-011）
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                showPlayError(error?.errorCode ?: 0, error?.description?.toString().orEmpty())
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                b.root.visibility = View.INVISIBLE
                val container = FrameLayout(this@PlayerActivity)
                container.setBackgroundColor(0xFF000000.toInt())
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addContentView(
                    container,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                hideSystemBars()
            }

            override fun onHideCustomView() {
                fullscreenView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                fullscreenView = null
                try {
                    fullscreenCallback?.onCustomViewHidden()
                } catch (e: Exception) {
                    // ignore
                }
                fullscreenCallback = null
                b.root.visibility = View.VISIBLE
            }
        }

        b.web.loadUrl(url)
        b.web.postDelayed({ initCursor() }, 300)

        listOf(b.btnErrRetry, b.btnErrDesktop, b.btnErrBack).forEach {
            com.tvblplayer.ext.util.FocusMotion.attach(it, 1.06f)
        }
        b.btnErrRetry.setOnClickListener {
            b.errorPanel.visibility = View.GONE
            b.hint.text = "正在加载播放页…"
            b.web.reload()
        }
        b.btnErrDesktop.setOnClickListener {
            desktopUa = true
            b.web.settings.userAgentString = DESKTOP_UA
            b.errorPanel.visibility = View.GONE
            b.web.reload()
        }
        b.btnErrBack.setOnClickListener { finish() }
        b.btnErrDesktop.visibility = if (desktopUa) View.GONE else View.VISIBLE
    }

    /**
     * 播放页加载失败必须显式告知。
     * 之前这里什么都不做：域名不通 / 站点拒绝 / 证书错误时整屏纯黑，
     * 用户看到的就是「打开其他网址会黑屏」（D-011）。
     */
    private fun showPlayError(code: Int, desc: String) {
        b.hint.visibility = View.GONE
        fadeChrome(false)
        val reason = when {
            code == -6 -> "网络好像没有连上，检查一下电视的 Wi-Fi。"
            code == -8 -> "连接超时，站点没有响应。"
            code == -2 -> "找不到这个网址的主机名。"
            code == -11 -> "证书不被信任，站点可能已过期。"
            else -> "加载中断${if (desc.isNotBlank()) "：$desc" else ""}。"
        }
        b.tvErrDetail.text = reason
        b.btnErrDesktop.visibility = if (desktopUa) View.GONE else View.VISIBLE
        b.errorPanel.visibility = View.VISIBLE
        b.errorPanel.post { b.btnErrRetry.requestFocus() }
    }

    private fun initCursor() {
        if (b.web.width == 0 || b.web.height == 0) {
            b.web.postDelayed({ initCursor() }, 200)
            return
        }
        cursorX = b.web.width / 2f
        cursorY = b.web.height / 2f
        b.cursor.moveTo(cursorX, cursorY)
        cursorReady = true
        b.cursor.visibility = View.VISIBLE
    }

    private fun mobileUa() =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    // ---------- 遥控器 → 指针 → 真实触摸 ----------

    private fun moveCursor(dx: Float, dy: Float) {
        if (!cursorReady) return
        cursorX = (cursorX + dx).coerceIn(0f, b.web.width.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, b.web.height.toFloat())
        b.cursor.moveTo(cursorX, cursorY)
    }

    /** 指针在上下边缘时，把页面滚动几行 */
    private fun scrollPageIfAtEdge(dir: Int): Boolean {
        if (!cursorReady) return false
        val atTop = cursorY <= stepPx
        val atBottom = cursorY >= b.web.height - stepPx
        if (dir < 0 && atTop) return scrollPage(-3)
        if (dir > 0 && atBottom) return scrollPage(3)
        return false
    }

    private fun scrollPage(lines: Int): Boolean {
        val before = b.web.scrollY
        b.web.scrollBy(0, (stepPx * lines).toInt())
        return b.web.scrollY != before
    }

    /** 在指针位置向 WebView 派发一次真实点击 */
    private fun tapAtCursor() {
        if (!cursorReady) return
        val now = SystemClock.uptimeMillis()
        val w = b.web
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = cursorX
                y = cursorY
                pressure = 1f
                size = 1f
            }
        )
        val down = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        w.dispatchTouchEvent(down)
        down.recycle()
        w.postDelayed({
            val t = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_UP, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0
            )
            w.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 错误面板显示时，方向键 / 确认键交给系统 View 焦点（三个按钮自己处理点击）
        if (b.errorPanel.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
            return super.onKeyDown(keyCode, event)
        }
        // 任何按键都把标题条唤回来，然后重新计时
        fadeChrome(true)

        val now = SystemClock.uptimeMillis()
        repeatAccel = if (now - lastKeyTime > 420) 1 else (repeatAccel + 1).coerceAtMost(4)
        lastKeyTime = now
        val step = stepPx * repeatAccel

        when (keyCode) {
            // 指针顶到上下边缘后继续按同一方向，就滚动页面。
            // 否则长页面里的内容（比如播放器在下方）永远够不着。
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!scrollPageIfAtEdge(-1)) moveCursor(0f, -step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!scrollPageIfAtEdge(1)) moveCursor(0f, step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(step, 0f); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { scrollPage(-3); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { scrollPage(3); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                tapAtCursor(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                b.web.evaluateJavascript(TOGGLE_PLAY_JS, null); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (fullscreenView != null) {
                    b.web.evaluateJavascript(EXIT_FULLSCREEN_JS, null)
                    return true
                }
                if (b.web.canGoBack()) {
                    b.web.goBack(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        b.web.onPause()
    }

    override fun onResume() {
        super.onResume()
        b.web.onResume()
    }

    override fun onDestroy() {
        runCatching {
            b.web.loadUrl("about:blank")
            b.web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESKTOP = "desktop"
        private const val STEP_DP = 26f

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 尽力自动播放：只做"替用户点一下播放"，
         * 站点自己的播放器与权限校验照常执行。
         */
        private const val AUTOPLAY_JS = """
            (function(){
              try{
                var v = document.querySelector('video');
                if (v && v.paused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); }
                var btns = document.querySelectorAll('[class*=play],[id*=play],.art-icon-play');
                for (var i=0;i<btns.length;i++){
                  var s = getComputedStyle(btns[i]);
                  if (s.display==='none'||s.visibility==='hidden') continue;
                  var r = btns[i].getBoundingClientRect();
                  if (r.width>0 && r.height>0) { btns[i].click(); break; }
                }
              }catch(e){}
            })();
        """

        private const val TOGGLE_PLAY_JS = """
            (function(){
              try{
                var v=document.querySelector('video');
                if(!v) return;
                if(v.paused) v.play(); else v.pause();
              }catch(e){}
            })();
        """

        private const val EXIT_FULLSCREEN_JS = """
            (function(){ try{ if(document.exitFullscreen) document.exitFullscreen();
              else if(document.webkitExitFullscreen) document.webkitExitFullscreen(); }catch(e){} })();
        """
    }
}
