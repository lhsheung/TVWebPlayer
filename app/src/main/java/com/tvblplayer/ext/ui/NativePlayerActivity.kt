package com.tvblplayer.ext.ui

import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tvblplayer.ext.timeshift.LiveTimeshiftEngine
import com.tvblplayer.ext.timeshift.TimeshiftPrefs
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import com.tvblplayer.ext.data.PlaylistParser
import com.tvblplayer.databinding.ActivityNativePlayerBinding

/**
 * 原生播放器（ExoPlayer）。
 *
 * 交互设计参考 Apple TV：**画面优先**。
 * 控制层只占上下两条窄渐变带，3 秒无操作自动淡出，中间区域永不遮挡。
 * 任何按键都会把控制层唤回来。
 */
class NativePlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "np_url"
        const val EXTRA_TITLE = "np_title"
        const val EXTRA_LOGO = "np_logo"
        const val EXTRA_MIME = "np_mime"

        /** 网页视频的 Referer（防盗链校验用）；从网页播放器接管时必须带上 */
        const val EXTRA_REFERER = "np_referer"

        /** 同一频道的备用线路（含当前这条，按优先级排好）。一条播不通自动换下一条。 */
        const val EXTRA_URLS = "np_urls"

        /** 整份频道的顺序表：上下键换台就靠它 */
        const val EXTRA_CH_URLS = "np_ch_urls"
        const val EXTRA_CH_TITLES = "np_ch_titles"
        const val EXTRA_CH_INDEX = "np_ch_index"

        private const val HIDE_DELAY_MS = 3000L
        private const val SEEK_STEP_MS = 10_000L
    }

    private lateinit var b: ActivityNativePlayerBinding
    private var player: ExoPlayer? = null
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentMime: String? = null
    private var retriedWithoutMime = false
    private var mediaReferer: String = ""

    /** 直播时移：缓存写在安卓设备自己的 cacheDir，分钟数由用户按菜单键设置 */
    private lateinit var timeshiftPrefs: TimeshiftPrefs
    private var timeshift: LiveTimeshiftEngine? = null
    private var timeshiftPlayUrl: String? = null
    private var timeshiftFallback = false
    private var timeshiftDialog: AlertDialog? = null

    /** 同一频道的备用线路（已去重、当前这条排第一）与当前用到第几条 */
    private var lines: List<String> = emptyList()
    private var lineIndex = 0

    /** 整份频道表（换台用） */
    private var chUrls: List<String> = emptyList()
    private var chTitles: List<String> = emptyList()
    private var chIndex = 0

    private val hideBannerTask = Runnable { b.chBanner.visibility = View.GONE }
    private val bannerTick = object : Runnable {
        override fun run() {
            if (b.chBanner.visibility != View.VISIBLE) return
            refreshBanner()
            ui.postDelayed(this, 1000)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var controlsVisible = false
    /** 只有用户主动暂停才让控制层常驻；缓冲 / 起播不算 */
    private var pausedByUser = false

    private val hideControlsTask = Runnable { setControlsVisible(false) }

    private val progressTask = object : Runnable {
        override fun run() {
            updateProgress()
            if (controlsVisible) ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNativePlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        timeshiftPrefs = TimeshiftPrefs(this)
        timeshift = LiveTimeshiftEngine(this, timeshiftPrefs)

        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { PlaylistParser.titleFromUrl(currentUrl) }
        currentMime = intent.getStringExtra(EXTRA_MIME)?.takeIf { it.isNotBlank() }
        mediaReferer = intent.getStringExtra(EXTRA_REFERER).orEmpty()

        // 备用线路：把自己放在第一位，其余按原顺序跟上，重复地址丢掉
        val passed = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        lines = (listOf(currentUrl) + passed).filter { it.isNotBlank() }.distinct()
        lineIndex = 0
        loadChannelTable()

        b.npErrorPanel.setOnClickListener {
            retriedWithoutMime = false
            start(currentUrl, currentMime)
        }

        if (currentUrl.isBlank()) {
            showError("地址为空", "没有可播放的地址。")
            return
        }
        start(currentUrl, currentMime)
        // 起播先亮一下控制层，让用户知道标题和进度，然后自动隐去
        setControlsVisible(true)
        // 起播先把频道信息亮一下（像电视开机显示台标），5 秒后自动收起
        if (chUrls.size > 1) showBanner()
    }

    /** 播放页主题是 AppCompat（非 MaterialComponents），AppToast 的 MaterialCardView 会崩，
     *  这里统一用系统 Toast。 */
    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- 播放 ----------

    private fun start(url: String, mime: String?) {
        pausedByUser = false
        b.npErrorPanel.visibility = View.GONE
        b.npLoading.visibility = View.VISIBLE

        releasePlayer()

        // 从网页播放器接管的地址常常带防盗链：不带 Referer 会直接 403。
        // 统一装一个带 Referer 的 HTTP 数据源；普通直连（Referer 为空）不受影响。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        if (mediaReferer.isNotBlank()) {
            httpFactory.setDefaultRequestProperties(mapOf("Referer" to mediaReferer))
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .build()
        player = p
        b.playerView.player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) b.npLoading.visibility = View.GONE
                updateProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.npState.text = if (isPlaying) "" else "已暂停"
                // 恢复播放后重新计时；缓冲/起播阶段不靠这个回调，scheduleHide 自己会排
                if (isPlaying) {
                    pausedByUser = false
                    if (controlsVisible) scheduleHide()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                b.npLoading.visibility = View.GONE
                // 本地时移播不起来（源不是裸 TS / 端口异常）：关掉时移，
                // 用原始线路直连重试一次，保证「至少能看」不受影响。
                if (timeshiftPlayUrl != null && !timeshiftFallback) {
                    timeshiftFallback = true
                    timeshift?.stop()
                    toast("本地时移不可用，已切回直连播放")
                    start(url, null)
                    return
                }
                // 服务器谎报 Content-Type 很常见，去掉 MIME 让解封装器自己嗅探
                if (mime != null && !retriedWithoutMime) {
                    retriedWithoutMime = true
                    start(url, null)
                    return
                }
                // 这条线路彻底不通：自动换同一频道的下一条备用地址。
                // DPL 里一个频道常常挂着七八个镜像，用户不该自己一条条点。
                val next = lineIndex + 1
                if (next < lines.size) {
                    lineIndex = next
                    retriedWithoutMime = false
                    timeshiftFallback = false
                    toast("线路 ${lineIndex} 播放失败，自动切换下一条（${next + 1}/${lines.size}）")
                    start(lines[next], null)
                    return
                }
                showError(
                    "播放失败",
                    buildString {
                        append("这个频道的 ${lines.size} 条线路都试过了，都没能播放。\n")
                        append("原因：").append(error.errorCodeName)
                        error.message?.let { append("\n").append(it.take(200)) }
                    }
                )
            }
        })

        // 直播时移：把上游切成设备本地的滑动窗口 HLS，播放器读的是已录下的内容，
        // 于是暂停 / 续播 / 往回拖都不再依赖源是否声明 DVR 窗口。
        timeshift?.stop()
        timeshiftPlayUrl = null
        val local = if (!timeshiftFallback && timeshiftPrefs.minutes > 0) timeshift?.start(url) else null
        val playUrl: String
        val playMime: String?
        if (local != null) {
            timeshiftPlayUrl = local
            playUrl = local
            playMime = MimeTypes.APPLICATION_M3U8
        } else {
            playUrl = url
            playMime = mime
        }

        val item = MediaItem.Builder()
            .setUri(playUrl)
            .apply { if (playMime != null) setMimeType(playMime) }
            .build()

        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    // ---------- 换台 ----------

    /** 读整份频道表；没传就退化成单频道（只播当前这条） */
    private fun loadChannelTable() {
        val urls = intent.getStringArrayListExtra(EXTRA_CH_URLS).orEmpty().filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            chUrls = listOf(currentUrl)
            chTitles = listOf(currentTitle)
            chIndex = 0
            return
        }
        val titles = intent.getStringArrayListExtra(EXTRA_CH_TITLES).orEmpty()
        chUrls = urls
        chTitles = if (titles.size == urls.size) titles else urls.map { "" }
        val wanted = intent.getIntExtra(EXTRA_CH_INDEX, urls.indexOf(currentUrl))
        chIndex = if (wanted in urls.indices) wanted else 0
        currentUrl = chUrls[chIndex]
        currentTitle = chTitles[chIndex].ifBlank { PlaylistParser.titleFromUrl(currentUrl) }
    }

    /**
     * 上下键换台。方向键在这里必须自己吃掉：PlayerView 默认会把它们
     * 当成控件焦点移动，用户按上下只会看到进度条跳一下。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 时移设置对话框打开时，把上下键留给 NumberPicker，不要拿去换台
        if (timeshiftDialog?.isShowing == true) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && chUrls.size > 1) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { switchChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { switchChannel(1); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && b.chBanner.visibility == View.VISIBLE &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_UP && event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            b.chBanner.visibility = View.GONE
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchChannel(delta: Int) {
        if (chUrls.isEmpty()) return
        val n = chUrls.size
        chIndex = ((chIndex + delta) % n + n) % n
        currentUrl = chUrls[chIndex]
        currentTitle = chTitles.getOrNull(chIndex).orEmpty()
            .ifBlank { PlaylistParser.titleFromUrl(currentUrl) }
        // 换台后备用线路只剩当前这条，避免把上一个频道的镜像带过来
        lines = listOf(currentUrl)
        lineIndex = 0
        retriedWithoutMime = false
        timeshiftFallback = false
        start(currentUrl, null)
        showBanner()
    }

    // ---------- 换台信息层 ----------

    private fun showBanner() {
        // 两层底部信息互斥，否则进度条和文字会叠成一团
        setControlsVisible(false)
        b.chBanner.visibility = View.VISIBLE
        refreshBanner()
        ui.removeCallbacks(bannerTick)
        ui.post(bannerTick)
        ui.removeCallbacks(hideBannerTask)
        ui.postDelayed(hideBannerTask, 5000)
    }

    private fun refreshBanner() {
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val ss = cal.get(Calendar.SECOND)

        b.chNum.text = String.format(Locale.US, "%02d", chIndex + 1)
        b.chName.text = currentTitle.ifBlank { "频道 ${chIndex + 1}" }

        // 有可 seek 窗口就按真实缓存显示：左边是已经播过去多久，右边是「可回看」上限。
        // 没有窗口（裸 TS 之类）才退回按整点估的写法。
        val win = seekableWindow()
        if (win != null) {
            val span = (win.second - win.first).coerceAtLeast(1)
            val behind = ((player?.currentPosition ?: win.first) - win.first).coerceIn(0, span)
            b.chProgress.progress = ((behind * 100) / span).toInt().coerceIn(0, 100)
            b.chStart.text = "-" + clockText(behind)
            b.chEnd.text = "可回看 " + clockText(span)
            b.chProgram.text = if (timeshiftPlayUrl != null)
                "时移中 · 可回看 " + clockText(span) else "直播中 · 已缓存 " + clockText(span)
        } else {
            b.chProgress.progress = ((mm * 60 + ss) * 100) / 3600
            b.chStart.text = String.format(Locale.US, "%02d:00", hh)
            b.chEnd.text = String.format(Locale.US, "%02d:00", (hh + 1) % 24)
            }
    }

    /**
     * 直播的时间跳移。
     *
     * 能不能回看完全取决于「已经缓存在播放器里、且源声明可 seek」的那段窗口：
     * HLS 直播的 playlist 会给出 DVR 窗口，此时 [seekableTimeRanges] 非空，
     * 往回拖就是在已缓冲内容里走；裸 TS / 不含分段信息的源没有这个窗口，
     * 只能老老实实播当前点。所以这里先判断窗口，没有再明确告诉用户，
     * 而不是让他按了没反应。
     */
    private fun seekableWindow(): Pair<Long, Long>? {
        val p = player ?: return null
        // 本地时移是 live HLS：window 可 seek，但 duration 恒为 TIME_UNSET，
        // 不能拿它当窗口。窗口由我们自己的分片决定，直接按设定分钟数算。
        if (timeshiftPlayUrl != null) {
            val span = timeshiftPrefs.minutes * 60_000L
            return 0L to span
        }
        if (!p.isCurrentMediaItemSeekable) return null
        val d = p.duration
        return if (d > 0 && d != C.TIME_UNSET) 0L to d else null
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val win = seekableWindow()
        if (win == null) {
            Log.d("TShift", "seek blocked: tsUrl=${timeshiftPlayUrl != null} " +
                "seekable=${p.isCurrentMediaItemSeekable} dur=${p.duration} pos=${p.currentPosition}")
            toast("当前没有可回看的缓存，按菜单键开启直播时移")
            return
        }
        // live 窗口内直接按当前播放位置跳转，不再依赖 duration
        val target = (p.currentPosition + deltaMs).coerceIn(win.first, win.second)
        Log.d("TShift", "seek ${p.currentPosition} -> $target (win=${win.first}..${win.second})")
        p.seekTo(target)
        showBanner()
    }

    /** 窗口内已缓冲的时长，用于信息层显示「已播 / 可回看」 */
    private fun bufferedBehindMs(): Long {
        val p = player ?: return 0
        val win = seekableWindow() ?: return 0
        return (p.currentPosition - win.first).coerceAtLeast(0)
    }

    /** 分辨率 · 帧率，取自解码器实际输出，不是播放列表里写的 */
    private fun formatText(): String {
        val f = player?.videoFormat ?: return "分辨率未知"
        if (f.width <= 0 || f.height <= 0) return "分辨率未知"
        val res = "${f.width}×${f.height}"
        return if (f.frameRate > 0f) String.format(Locale.US, "%s · %.2ffps", res, f.frameRate) else res
    }

    private fun clockText(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun releasePlayer() {
        b.playerView.player = null
        player?.release()
        player = null
    }

    // ---------- 控制层 ----------

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) {
            if (visible) scheduleHide()
            return
        }
        controlsVisible = visible
        ui.removeCallbacks(hideControlsTask)
        if (visible && b.chBanner.visibility == View.VISIBLE) {
            ui.removeCallbacks(bannerTick)
            ui.removeCallbacks(hideBannerTask)
            b.chBanner.visibility = View.GONE
        }

        val target = if (visible) 1f else 0f
        val from = b.controls.alpha
        b.controls.animate()
            .alpha(target)
            .setDuration(if (visible) 160 else 260)
            .withStartAction { if (visible) b.controls.visibility = View.VISIBLE }
            .withEndAction { if (!visible) b.controls.visibility = View.GONE }
            .start()

        if (visible) {
            b.controls.alpha = from
            updateProgress()
            ui.removeCallbacks(progressTask)
            ui.post(progressTask)
            scheduleHide()
        } else {
            ui.removeCallbacks(progressTask)
        }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControlsTask)
        // 不能拿 isPlaying 当条件：起播和缓冲时它同样是 false，
        // 一旦跳过就再也没人补排，顶部标题和底部提示会永久挂在画面上。
        // 只有用户主动暂停才保留控制层（否则找不到播放键）。
        if (pausedByUser) return
        ui.postDelayed(hideControlsTask, HIDE_DELAY_MS)
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        // 直播流的 duration 是未定义值（负数），不能当长度算
        val isLive = duration <= 0

        b.npTimeCurrent.text = if (isLive) "" else formatTime(p.currentPosition)
        b.npTimeTotal.text = if (isLive) "直播" else formatTime(duration)

        val fraction = if (isLive || duration <= 0) 0f
        else (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f)

        val trackWidth = b.npScrubber.width
        b.npProgress.layoutParams = (b.npProgress.layoutParams as android.widget.FrameLayout.LayoutParams)
            .also { it.width = (trackWidth * fraction).toInt() }
        b.npProgress.requestLayout()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun showError(title: String, detail: String) {
        setControlsVisible(false)
        b.npErrorPanel.visibility = View.VISIBLE
        b.npErrorTitle.text = title
        b.npErrorDetail.text = detail
        b.npErrorPanel.requestFocus()
    }

    // ---------- 按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player

        // 错误面板显示时只处理重试和返回
        if (b.npErrorPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    retriedWithoutMime = false
                    start(currentUrl, currentMime)
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val wasVisible = controlsVisible
                p?.let {
                    if (it.isPlaying) {
                        it.pause()
                        pausedByUser = true
                    } else {
                        it.play()
                        pausedByUser = false
                    }
                }
                if (wasVisible) scheduleHide() else setControlsVisible(true)
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                setControlsVisible(true)
                seekBy(-SEEK_STEP_MS)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                setControlsVisible(true)
                seekBy(SEEK_STEP_MS)
                return true
            }

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showTimeshiftDialog()
                return true
            }

            // 上下键不做事，但按了就亮控制层
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                setControlsVisible(true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 菜单键：自己定直播时移时长（任意整数分钟）。
     * 上限不写死，按设备可用空间动态算出；「预计占用」「设备可用空间」
     * 都读安卓设备本机存储，与编译/开发用的电脑磁盘无关。
     * 0 分钟 = 关闭时移、直连上游。
     */
    private fun showTimeshiftDialog() {
        val free = cacheDir.usableSpace
        val maxM = maxOf(TimeshiftPrefs.maxMinutesFor(free), timeshiftPrefs.minutes)
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = maxM
            value = timeshiftPrefs.minutes.coerceAtMost(maxM)
            wrapSelectorWheel = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // 上限可能到几千分钟，长按加速滚动，免得按几百次遥控器
            setOnLongPressUpdateInterval(30L)
        }
        val info = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(8, 16, 8, 8)
        }
        fun refresh() {
            val m = picker.value
            val est = m * TimeshiftPrefs.bytesPerMinute()
            info.text = buildString {
                append("缓存写在设备内部存储，窗口写满后自动丢弃最旧内容。\n")
                append("预计占用：").append(if (m == 0) "0（关闭时移）" else TimeshiftPrefs.sizeText(est)).append("\n")
                append("设备可用空间：").append(TimeshiftPrefs.sizeText(free)).append("\n")
                append("本设备最多可设 ").append(maxM).append(" 分钟（按可用空间自动算，留 10% 余量）。\n")
                append("按确认保存，换台后生效；0 分钟 = 直连不缓存。")
            }
        }
        picker.setOnValueChangedListener { _, _, _ -> refresh() }
        refresh()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 24, 32, 8)
            addView(picker)
            addView(info, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        timeshiftDialog = AlertDialog.Builder(this)
            .setTitle("直播时移时长（分钟）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                timeshiftPrefs.minutes = picker.value
                timeshift?.applyLimit()
                toast(
                    if (picker.value == 0) "时移已关闭，换台后生效"
                    else "时移 ${picker.value} 分钟，换台后生效"
                )
            }
            .setNegativeButton("取消", null)
            .show()
        timeshiftDialog?.setOnDismissListener { timeshiftDialog = null }
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
        player?.pause()
        setControlsVisible(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        timeshift?.stop()
        releasePlayer()
        super.onDestroy()
    }
}
