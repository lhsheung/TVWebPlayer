package com.tvblplayer.feature.my

import com.tvblplayer.core.ui.TabContentFocusTarget
import com.tvblplayer.core.ui.TabContentSwitchFocusHost

interface MyTabContentSwitchFocusHost : TabContentSwitchFocusHost {
    fun requestFocusCurrentPageFirstItemFromContentSwitch(): Boolean

    override fun requestFocusCurrentPagePrimaryItemFromContentSwitch(): Boolean = requestFocusCurrentPageFirstItemFromContentSwitch()
}

interface MyTabSwitchFocusTarget : TabContentFocusTarget {
    fun requestFocusFirstItemFromTabSwitch(): Boolean

    override fun requestFocusPrimaryItemFromTab(): Boolean = requestFocusFirstItemFromTabSwitch()

    override fun requestFocusPrimaryItemFromContentSwitch(): Boolean = requestFocusFirstItemFromTabSwitch()
}
