package com.tvblplayer

import android.app.Application
import android.os.Build
import com.tvblplayer.core.log.AppLog
import com.tvblplayer.core.log.CrashTracker
import com.tvblplayer.core.emote.ReplyEmotePanelRepository
import com.tvblplayer.core.net.BiliClient
import com.tvblplayer.core.net.WebCookieMaintainer
import com.tvblplayer.ext.data.PlaylistStore
import com.tvblplayer.ext.data.SiteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BlblApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        CrashTracker.install(this)
        AppLog.i(
            "Startup",
            "app=${BuildConfig.VERSION_NAME} api=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL} abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
        )
        AppLog.i("BlblApp", "onCreate")
        BiliClient.init(this)
        appScope.launch {
            runCatching { WebCookieMaintainer.ensureDailyMaintenance() }
                .onFailure { AppLog.w("BlblApp", "daily maintenance failed", it) }
        }
        appScope.launch {
            // 内置播放列表 / 站点在这里播种：装完就该有，不能等用户先打开扩展页。
            // 两个 seed 都是幂等的（一次性标记 + 名字去重），重复调用没有副作用。
            runCatching { PlaylistStore(this@BlblApp).seedBuiltinIfNeeded(this@BlblApp) }
                .onFailure { AppLog.w("BlblApp", "seed builtin playlists failed", it) }
            runCatching { SiteStore(this@BlblApp).seedDefaultsIfNeeded() }
                .onFailure { AppLog.w("BlblApp", "seed default sites failed", it) }
        }
        appScope.launch {
            runCatching { ReplyEmotePanelRepository.warmup(this@BlblApp) }
                .onFailure { AppLog.w("BlblApp", "reply emote warmup failed", it) }
        }
    }

    companion object {
        @JvmStatic
        lateinit var instance: BlblApp
            private set

        fun launchIo(block: suspend CoroutineScope.() -> Unit) {
            instance.appScope.launch(block = block)
        }
    }
}
