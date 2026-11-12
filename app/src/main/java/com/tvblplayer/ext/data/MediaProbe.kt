package com.tvblplayer.ext.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * 媒体地址探测。
 *
 * 播放列表里的地址形态很杂：.m3u8、HTTP 承载的 MPEG-TS、无扩展名的跳转、
 * 甚至是网页。同一个播放入口必须先判断"这是什么"，再决定交给哪个播放器。
 *
 * 判定顺序：
 *  1. 协议：udp:// rtp:// 是裸组播，普通 App 播不了（需要运营商网络 + MulticastLock）
 *  2. 扩展名：.m3u8 / .mp4 / .ts 这类直接定
 *  3. 网络嗅探：读前 2KB，看 #EXTM3U 头、Content-Type、TS 同步字节 0x47
 */
object MediaProbe {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    enum class Kind {
        /** HLS 流 */
        HLS,

        /** 渐进式：TS / MP4 / MKV 等，交给解封装器自己嗅探 */
        PROGRESSIVE,

        /** 网页，应该用 WebView 打开 */
        WEB,

        /** 播不了（裸组播等） */
        UNSUPPORTED,
    }

    data class Result(
        val kind: Kind,
        val finalUrl: String,
        /** 建议的 MIME，null 表示让播放器自己嗅探 */
        val mime: String?,
        val detail: String? = null,
    )

    /**
     * 纯函数部分：只根据 URL 形态判断，能定就直接定，定不了返回 null 交给网络嗅探。
     * 单独抽出来是为了可单测。
     */
    fun classifyByUrl(url: String): Result? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme == "udp" || scheme == "rtp") {
            return Result(
                Kind.UNSUPPORTED, url, null,
                "这是裸组播地址（$scheme://），需要运营商内网和组播权限，普通设备播不了。"
            )
        }

        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> Result(Kind.HLS, url, "application/x-mpegURL")
            path.endsWith(".mpd") -> Result(Kind.PROGRESSIVE, url, "application/dash+xml")
            path.endsWith(".mp4") || path.endsWith(".m4v") ->
                Result(Kind.PROGRESSIVE, url, "video/mp4")
            path.endsWith(".webm") -> Result(Kind.PROGRESSIVE, url, "video/webm")
            path.endsWith(".mkv") -> Result(Kind.PROGRESSIVE, url, "video/x-matroska")
            path.endsWith(".ts") -> Result(Kind.PROGRESSIVE, url, "video/mp2t")
            path.endsWith(".html") || path.endsWith(".htm") -> Result(Kind.WEB, url, null)
            else -> null
        }
    }

    /** 完整探测：先看 URL，定不了再发一个短请求嗅探 */
    fun probe(url: String): Result {
        classifyByUrl(url)?.let { return it }
        return sniff(url)
    }

    private fun sniff(url: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                // 只要开头一点，别把整条直播流拖下来
                setRequestProperty("Range", "bytes=0-2047")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return Result(
                    Kind.PROGRESSIVE, conn.url.toString(), null,
                    "服务器返回 $code，仍然尝试播放"
                )
            }

            val ctype = (conn.contentType ?: "").lowercase()
            val finalUrl = conn.url.toString()

            if (ctype.contains("mpegurl")) {
                return Result(Kind.HLS, finalUrl, "application/x-mpegURL")
            }
            if (ctype.contains("text/html")) {
                return Result(Kind.WEB, finalUrl, null, "返回的是网页")
            }

            // 读一点正文
            val buf = ByteArray(2048)
            var n = 0
            conn.inputStream.use { input ->
                var read: Int
                while (n < buf.size) {
                    read = input.read(buf, n, buf.size - n)
                    if (read <= 0) break
                    n += read
                }
            }

            if (n <= 0) {
                return Result(Kind.PROGRESSIVE, finalUrl, null, "服务器没有返回内容")
            }

            val head = String(buf, 0, minOf(n, 64), Charsets.ISO_8859_1).trimStart()
            if (head.startsWith("#EXTM3U")) {
                return Result(Kind.HLS, finalUrl, "application/x-mpegURL")
            }

            // TS 同步字节 0x47，每 188 字节一个
            val looksTs = buf[0] == 0x47.toByte() ||
                (n > 376 && buf[188] == 0x47.toByte() && buf[376] == 0x47.toByte())
            if (looksTs) {
                return Result(Kind.PROGRESSIVE, finalUrl, "video/mp2t")
            }

            if (ctype.contains("mp2t")) {
                return Result(Kind.PROGRESSIVE, finalUrl, "video/mp2t")
            }
            if (ctype.startsWith("video/")) {
                return Result(Kind.PROGRESSIVE, finalUrl, null)
            }

            // 兜底：交给解封装器嗅探
            Result(Kind.PROGRESSIVE, finalUrl, null, null)
        } catch (e: Exception) {
            Result(
                Kind.PROGRESSIVE, url, null,
                "探测失败（${e.javaClass.simpleName}），仍然尝试播放"
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
