package com.tvblplayer.ext.util

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.tvblplayer.R

/**
 * 焦点动效。
 *
 * 为什么用 SpringAnimation 而不是 ViewPropertyAnimator：
 * 用户快速划过一排卡片时，焦点会连续切换。弹簧从**当前值**继续，
 * 而属性动画会从逻辑值重新开始，表现为"跳一下"。
 *
 * 阻尼选 1.0（临界阻尼，不过冲）——焦点切换不是甩动类手势，
 * 弹一下反而显得轻浮。只有列表滚动才该有惯性回弹。
 */
object FocusMotion {

    private const val FOCUSED_SCALE = 1.06f
    private const val LIFT_DP = 10f

    fun attach(view: View, focusedScale: Float = FOCUSED_SCALE) {
        // 关掉系统默认的焦点高亮方块（API 26+），改由我们自己的描边 + 弹簧负责
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        view.setOnFocusChangeListener { v, hasFocus ->
            val target = if (hasFocus) focusedScale else 1f

            if (reduceMotion(v.context)) {
                // 关了动效就瞬间到位，别硬做过渡
                v.scaleX = target
                v.scaleY = target
                v.translationZ = if (hasFocus) dp(v, LIFT_DP) else 0f
                return@setOnFocusChangeListener
            }

            springFor(v, R.id.tag_focus_spring_x, DynamicAnimation.SCALE_X).animateToFinalPosition(target)
            springFor(v, R.id.tag_focus_spring_y, DynamicAnimation.SCALE_Y).animateToFinalPosition(target)

            v.animate()
                .translationZ(if (hasFocus) dp(v, LIFT_DP) else 0f)
                .setDuration(if (hasFocus) 160 else 220)
                .start()
        }
    }

    private fun springFor(
        view: View,
        tagId: Int,
        property: DynamicAnimation.ViewProperty,
    ): SpringAnimation {
        (view.getTag(tagId) as? SpringAnimation)?.let { return it }
        val anim = SpringAnimation(view, property).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }
        view.setTag(tagId, anim)
        return anim
    }

    /** 系统"移除动画"开关；开了就退化成瞬切，这也是无障碍要求 */
    private fun reduceMotion(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)

    private fun dp(view: View, value: Float) = value * view.resources.displayMetrics.density
}
