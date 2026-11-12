package com.tvblplayer.feature.live

interface LiveNavigator {
    fun openAreaDetail(parentAreaId: Int, parentTitle: String, areaId: Int, areaTitle: String): Boolean
}

