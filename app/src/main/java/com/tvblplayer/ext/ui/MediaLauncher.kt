package com.tvblplayer.ext.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.tvblplayer.ext.data.MediaProbe
import kotlin.concurrent.thread

/**
 * 统一点播入口。
 *
 * 播放列表里的地址什么形态都有，这里负责「先认，再分发」：
 *   HLS / 渐进式 → 原生播放器 ExoPlayer
 *   网页         → WebView 播放器
 *   裸组播       → 直接告诉用户播不了
 *
 * 有扩展名的地址零延迟直接分发；没扩展名的才发一个短请求嗅探。
 */
object MediaLauncher {

    fun open(
        activity: Activity,
        url: String,
        title: String,
        logo: String? = null,
        alternates: List<String> = emptyList(),
        channelUrls: List<String> = emptyList(),
        channelTitles: List<String> = emptyList(),
        channelIndex: Int = 0,
    ) {
        if (url.isBlank()) {
            toast(activity, "地址为空")
            return
        }

        val fast = MediaProbe.classifyByUrl(url)
        when {
            fast == null -> {
                // 形态不明，需要嗅探
                toast(activity, "正在识别地址类型…")
                thread {
                    val r = MediaProbe.probe(url)
                    if (activity.isFinishing || activity.isDestroyed) return@thread
                    activity.runOnUiThread { dispatch(activity, r, title, logo, alternates, channelUrls, channelTitles, channelIndex) }
                }
            }
            fast.kind == MediaProbe.Kind.UNSUPPORTED -> {
                toast(activity, fast.detail ?: "这个地址播不了")
            }
            else -> dispatch(activity, fast, title, logo, alternates, channelUrls, channelTitles, channelIndex)
        }
    }

    private fun dispatch(
        activity: Activity,
        r: MediaProbe.Result,
        title: String,
        logo: String?,
        alternates: List<String>,
        channelUrls: List<String>,
        channelTitles: List<String>,
        channelIndex: Int,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        when (r.kind) {
            MediaProbe.Kind.HLS, MediaProbe.Kind.PROGRESSIVE -> {
                activity.startActivity(
                    Intent(activity, NativePlayerActivity::class.java)
                        .putExtra(NativePlayerActivity.EXTRA_URL, r.finalUrl)
                        .putExtra(NativePlayerActivity.EXTRA_TITLE, title)
                        .putExtra(NativePlayerActivity.EXTRA_MIME, r.mime)
                        // 备用线路一起交过去：这条播不通就自动换下一条
                        .putStringArrayListExtra(
                            NativePlayerActivity.EXTRA_URLS,
                            ArrayList(alternates)
                        )
                        // 整份频道表：播放器里上下键换台用
                        .putStringArrayListExtra(
                            NativePlayerActivity.EXTRA_CH_URLS,
                            ArrayList(channelUrls)
                        )
                        .putStringArrayListExtra(
                            NativePlayerActivity.EXTRA_CH_TITLES,
                            ArrayList(channelTitles)
                        )
                        .putExtra(NativePlayerActivity.EXTRA_CH_INDEX, channelIndex)
                )
            }
            MediaProbe.Kind.WEB -> {
                activity.startActivity(
                    Intent(activity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URL, r.finalUrl)
                        .putExtra(PlayerActivity.EXTRA_TITLE, title)
                        // 网页播放器也走桌面版：bilibili / 优酷这类站点在手机版 UA 下
                        // 只给「打开 App」的空壳，播放器根本不挂载（D-011）。
                        .putExtra(PlayerActivity.EXTRA_DESKTOP, true)
                )
            }
            MediaProbe.Kind.UNSUPPORTED -> {
                toast(activity, r.detail ?: "这个地址播不了")
            }
        }
    }

    private fun toast(activity: Activity, s: String) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()
    }
}
