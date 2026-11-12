package com.tvblplayer.ext.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * 所有"内容页"的基类。
 *
 * TV 上不该出现状态栏和导航栏——它们既占地方又破坏沉浸感。
 * 统一在这里做沉浸式全屏，子类不用各写一遍。
 */
open class BaseTvActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从对话框/通知返回时系统会把栏放回来，这里再收一次
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
