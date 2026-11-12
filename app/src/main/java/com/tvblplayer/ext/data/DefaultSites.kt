package com.tvblplayer.ext.data

/**
 * 预置站点。
 *
 * 注意：这里只是「默认书签」——一个网址，不包含任何内容、接口或播放地址。
 * App 仍然只做三件事：加载用户指定的网页、解析结构、把遥控器操作翻译成网页操作。
 * 用户可以在站点列表里长按删除，删掉后不会自动恢复。
 */
object DefaultSites {

    /** 用固定 id，重复注入不会产生重复项 */
    const val ID_YMV = "builtin-ymvid"

    val LIST: List<Site> = listOf(
        Site(
            id = ID_YMV,
            name = "粤漫之家",
            url = "https://www.ymvid.com",
            desktopMode = false,
        ),
    )
}
