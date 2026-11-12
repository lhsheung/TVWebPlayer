package com.tvblplayer.feature.my

import android.content.res.Resources
import com.tvblplayer.core.net.BiliClient
import com.tvblplayer.core.ui.GridSpanPolicy

fun spanCountForWidth(resources: Resources): Int {
    val prefs = BiliClient.prefs
    val dm = resources.displayMetrics
    val widthDp = dm.widthPixels / dm.density
    return GridSpanPolicy.fixedSpanCountForWidthDp(
        widthDp = widthDp,
        overrideSpanCount = prefs.gridSpanCount,
    )
}
