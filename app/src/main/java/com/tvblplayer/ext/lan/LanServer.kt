package com.tvblplayer.ext.lan

import android.content.Context
import com.tvblplayer.ext.data.PlaylistParser
import com.tvblplayer.ext.data.PlaylistStore
import com.tvblplayer.ext.data.Site
import com.tvblplayer.ext.data.SiteStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 局域网导入通道：一个只在「局域网传输」页打开时存活的极小 HTTP 服务。
 *
 * 为什么手写 ServerSocket：项目一直用 --offline 编译，nanohttpd / ktor 都要联网拉依赖；
 * 这里只需要 5 个路由，用 JDK 自带的 socket 就能写完，零新增运行时依赖。
 * 二维码只用到 ZXing 的编码部分（core，纯 Java），同样不联网、不需要相机权限。
 *
 * 路由：
 *   GET  /                → assets/lan_upload.html（手机端上传页）
 *   GET  /api/history     → 最近收到什么（手机页轮询）
 *   POST /api/upload      → multipart 文件，落 PlaylistStore / SiteStore
 *   POST /api/link        → 网址文本，按 mode 分发到站点 / 播放列表 / 立即播放
 *
 * 线程：accept 一条线程，每个请求丢进 cachedThreadPool。回调 [onEvent] / [onPlay]
 * 都在后台线程触发，调用方自己切主线程。
 *
 * 安全边界：只监听局域网、不鉴权、不写外部存储、不代理播放地址。
 * 页面退出即 stop() 关端口（见 LanImportActivity.onDestroy）。
 */
class LanServer(
    context: Context,
    private val onEvent: (String) -> Unit,
    private val onPlay: (url: String, title: String) -> Unit,
    /** 真正收到一批数据时回调，用于页面上的「已收到 N 条」计数 */
    private val onReceive: () -> Unit = {},
) {

    companion object {
        const val DEFAULT_PORT = 8099
        private const val MAX_BODY = 20 * 1024 * 1024       // 单请求上限，防内存被打爆
        private const val MAX_PLAYLIST = 8 * 1024 * 1024     // 单份播放列表上限
        private const val HISTORY_LIMIT = 30
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val DOMAIN = Regex(
            """^[a-zA-Z0-9][a-zA-Z0-9.\-]*\.[a-zA-Z]{2,}(?::\d+)?(?:/.*)?$"""
        )
    }

    private val app = context.applicationContext
    private val playlistStore = PlaylistStore(app)
    private val siteStore = SiteStore(app)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val history = Collections.synchronizedList(ArrayList<HistoryItem>())

    @Volatile
    private var running = false

    /** 实际监听端口（8099 被占用时会顺延，全被占用时取系统随机端口） */
    var port: Int = 0
        private set

    // ---------- 生命周期 ----------

    fun start(preferred: Int = DEFAULT_PORT): Int {
        val ss: ServerSocket = run {
            for (p in preferred until preferred + 12) {
                try {
                    return@run ServerSocket(p, 32)
                } catch (e: Exception) {
                    // 端口被占，试下一个
                }
            }
            // 都不行就让系统给一个空闲端口，页面上会显示真实端口
            ServerSocket(0, 32)
        }
        serverSocket = ss
        port = ss.localPort
        running = true

        acceptThread = thread(name = "lan-http-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = ss.accept()
                    pool.execute { handle(client) }
                } catch (e: SocketException) {
                    if (running) record("端口异常：${e.message}")
                    break
                } catch (e: Exception) {
                    if (running) record("连接异常：${e.message}")
                }
            }
        }
        return port
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { pool.shutdownNow() }
        acceptThread?.interrupt()
        acceptThread = null
    }

    // ---------- HTTP 骨架 ----------

    private fun handle(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = 20_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()

                val requestLine = readAsciiLine(input)
                    ?: return@use
                val pieces = requestLine.split(' ')
                if (pieces.size < 2) {
                    writeResponse(out, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
                    return@use
                }
                val method = pieces[0].uppercase(Locale.ROOT)
                val path = pieces[1].substringBefore('?')

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] =
                            line.substring(i + 1).trim()
                    }
                }

                // curl -F / 部分浏览器上传大文件会先等 100 Continue
                if (headers["expect"]?.contains("100-continue", true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    out.flush()
                }

                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len < 0 || len > MAX_BODY) {
                    writeResponse(out, 413, "Payload Too Large", "text/plain; charset=utf-8", "文件太大".toByteArray(StandardCharsets.UTF_8))
                    return@use
                }
                val body = if (len > 0) readExactly(input, len) else ByteArray(0)

                route(method, path, headers, body, out)
            }
        } catch (e: Exception) {
            // 客户端提前断开 / 读超时，不必打断服务
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
        out: OutputStream,
    ) {
        when {
            method == "OPTIONS" -> writeResponse(
                out, 204, "No Content", "text/plain", ByteArray(0),
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\n"
            )

            method == "GET" && (path == "/" || path == "/index.html") -> {
                val html = runCatching {
                    app.assets.open("lan_upload.html").use { it.readBytes() }
                }.getOrElse {
                    "上传页资源缺失".toByteArray(StandardCharsets.UTF_8)
                }
                writeResponse(out, 200, "OK", "text/html; charset=utf-8", html)
            }

            method == "GET" && path == "/api/history" -> writeResponse(
                out, 200, "OK", "application/json; charset=utf-8",
                historyJson().toString().toByteArray(StandardCharsets.UTF_8)
            )

            method == "GET" && path == "/favicon.ico" -> writeResponse(
                out, 204, "No Content", "image/x-icon", ByteArray(0)
            )

            method == "POST" && path == "/api/upload" -> json(out, handleUpload(headers, body))

            method == "POST" && path == "/api/link" -> json(out, handleLink(body))

            else -> writeResponse(
                out, 404, "Not Found", "text/plain; charset=utf-8",
                "404 Not Found".toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    private fun json(out: OutputStream, r: Result) = writeResponse(
        out, 200, "OK", "application/json; charset=utf-8",
        JSONObject()
            .put("ok", r.ok)
            .put("message", r.message)
            .put("detail", r.detail)
            .toString().toByteArray(StandardCharsets.UTF_8)
    )

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        reason: String,
        type: String,
        body: ByteArray,
        extra: String = "",
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append(extra)
            append("\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream(128)
        var c = input.read()
        if (c == -1) return null
        while (c != -1) {
            if (c == 10) break
            if (c != 13) buf.write(c)
            if (buf.size() > 8192) return null
            c = input.read()
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readExactly(input: BufferedInputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = input.read(out, done, n - done)
            if (r < 0) throw java.io.EOFException("body truncated")
            done += r
        }
        return out
    }

    // ---------- 上传文件 ----------

    private fun handleUpload(headers: Map<String, String>, body: ByteArray): Result {
        val ct = headers["content-type"].orEmpty()
        val boundary = Regex("""boundary=(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE)
            .find(ct)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        if (boundary.isNullOrBlank()) {
            return Result(false, "上传格式不对", "请求缺少 multipart boundary")
        }

        val parts = parseMultipart(body, boundary)
        val mode = parts.firstOrNull { it.name == "mode" && it.filename == null }
            ?.let { decodeText(it.content).trim() }
            .orEmpty().ifBlank { "playlist" }
        val files = parts.filter { it.name == "file" && it.filename != null }
        if (files.isEmpty()) return Result(false, "没有收到文件", "表单里没有 file 字段")

        val lines = ArrayList<String>()
        var success = 0
        for (f in files) {
            if (f.content.isEmpty()) {
                lines.add("${f.filename}：空文件")
                continue
            }
            val text = decodeText(f.content)
            val base = f.filename!!
                .substringAfterLast('/').substringAfterLast('\\')
            val name = base.substringBeforeLast('.').ifBlank { base }.ifBlank { "导入的列表" }
            val r = when (mode) {
                "sites" -> importSites(listOf(text))
                else -> importPlaylist(text, name)
            }
            if (r.ok) success++
            lines.add("${f.filename}：${r.message}${if (r.detail.isNotBlank()) "（${r.detail}）" else ""}")
        }

        record("收到 ${files.size} 个文件（$mode），成功 $success 个", counted = true)
        return Result(
            success > 0,
            "已处理 ${files.size} 个文件，成功 $success 个",
            lines.joinToString("；")
        )
    }

    /**
     * 手写 multipart 解析。boundary 由浏览器随机生成，不会出现在文件内容里。
     * 结构：--B\r\n<part headers>\r\n\r\n<content>\r\n--B\r\n...\r\n--B--\r\n
     */
    private fun parseMultipart(body: ByteArray, boundary: String): List<Part> {
        val marker = ("--" + boundary).toByteArray(StandardCharsets.US_ASCII)
        val nextMarker = ("\r\n--" + boundary).toByteArray(StandardCharsets.US_ASCII)
        val headerEndMark = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val out = ArrayList<Part>()

        var cursor = indexOf(body, marker, 0)
        while (cursor >= 0) {
            var p = cursor + marker.size
            // 结尾的 --B--，到此为止
            if (p + 1 < body.size && body[p] == MINUS && body[p + 1] == MINUS) break
            if (p + 1 < body.size && body[p] == CR && body[p + 1] == LF) p += 2

            val end = indexOf(body, nextMarker, p)
            if (end < 0) break
            val partBytes = body.copyOfRange(p, end)
            val headerEnd = indexOf(partBytes, headerEndMark, 0)
            if (headerEnd >= 0) {
                val head = String(partBytes, 0, headerEnd, StandardCharsets.UTF_8)
                val cd = head.lineSequence()
                    .firstOrNull { it.startsWith("content-disposition:", true) }
                    .orEmpty()
                // \b 很关键：filename="a.m3u" 里也含 "name=\""，不加边界会取错字段
                val name = Regex("\\bname=\"([^\"]*)\"").find(cd)?.groupValues?.get(1).orEmpty()
                val filename = Regex("\\bfilename=\"([^\"]*)\"").find(cd)?.groupValues?.get(1)
                val content = partBytes.copyOfRange(headerEnd + 4, partBytes.size)
                out.add(Part(name, filename, content))
            }
            cursor = end + 2 // end 指向 \r\n，+2 就是下一个 --B
        }
        return out
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        val last = hay.size - needle.size
        var i = maxOf(from, 0)
        while (i <= last) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    // ---------- 发送链接 ----------

    private fun handleLink(body: ByteArray): Result {
        val obj = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }.getOrNull()
            ?: return Result(false, "请求不是有效 JSON")
        val text = obj.optString("text")
        val mode = obj.optString("mode", "auto").ifBlank { "auto" }
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return Result(false, "没有有效的网址")

        val r = when (mode) {
            "site" -> importSites(lines)
            "playlist" -> importPlaylistLinks(lines)
            "play" -> playLinks(lines)
            else -> autoImport(lines)
        }
        record("收到 ${lines.size} 条链接（$mode）：${r.message}", counted = true)
        return Result(r.ok, r.message, r.detail)
    }

    /** 站点清单 / 站点链接：一行一个网址 */
    private fun importSites(chunks: List<String>): Result {
        val lines = chunks.flatMap { it.lineSequence().toList() }
        val existing = siteStore.all().map { it.url }.toHashSet()
        var added = 0
        var skipped = 0
        for (raw in lines) {
            val url = normalizeSiteUrl(raw)
            if (url == null) { skipped++; continue }
            if (url in existing) { skipped++; continue }
            existing.add(url)
            siteStore.add(Site(name = hostOf(url).ifBlank { url }, url = url))
            added++
        }
        return Result(
            added > 0,
            "新增 $added 个站点" + if (skipped > 0) "，跳过 $skipped 个（重复或不像网址）" else "",
            if (added > 0) "去首页「站点列表」查看" else "没有可添加的新网址"
        )
    }

    /** 播放列表链接：电视自己去下载文本再解析，手机只负责发一个 URL */
    private fun importPlaylistLinks(lines: List<String>): Result {
        val msgs = ArrayList<String>()
        var ok = 0
        for (url in lines) {
            val text = fetchText(url, MAX_PLAYLIST)
            if (text.isNullOrBlank()) {
                msgs.add("${short(url)}：下载失败")
                continue
            }
            val r = importPlaylist(text, nameFromUrl(url))
            msgs.add("${short(url)}：${r.message}")
            if (r.ok) ok++
        }
        return Result(ok > 0, "导入 $ok 份播放列表", msgs.joinToString("；"))
    }

    private fun playLinks(lines: List<String>): Result {
        val first = lines.first()
        val title = PlaylistParser.titleFromUrl(first)
        onPlay(first, title)
        val rest = lines.size - 1
        return Result(
            true,
            "已在电视上请求播放" + if (rest > 0) "第 1 个地址（其余 $rest 个已忽略）" else "",
            first
        )
    }

    /** 自动识别：按扩展名分流，.m3u8 再嗅一下是频道清单还是 HLS 媒体清单 */
    private fun autoImport(lines: List<String>): Result {
        val msgs = ArrayList<String>()
        var sites = 0
        var lists = 0
        var handled = 0
        val toPlay = ArrayList<String>()

        for (raw in lines) {
            val maybeUrl = normalizeSiteUrl(raw)
            if (maybeUrl == null) {
                msgs.add("跳过：${short(raw)}（不像网址）")
                continue
            }
            val url = maybeUrl
            when (classify(url)) {
                Kind.SITE -> {
                    val r = importSites(listOf(url))
                    if (r.ok) { sites++; handled++ }
                    msgs.add("站点 ${hostOf(url)}：${r.message}")
                }

                Kind.PLAYLIST -> {
                    val text = fetchText(url, MAX_PLAYLIST)
                    if (text.isNullOrBlank()) {
                        msgs.add("${short(url)}：下载失败")
                    } else {
                        val r = importPlaylist(text, nameFromUrl(url))
                        if (r.ok) { lists++; handled++ }
                        msgs.add("列表 ${short(url)}：${r.message}")
                    }
                }

                Kind.PLAY -> {
                    toPlay.add(url)
                    handled++
                    msgs.add("播放 ${short(url)}")
                }

                Kind.M3U8 -> {
                    val head = fetchText(url, 8192)
                    // #EXTM3U + #EXTINF = 频道清单；#EXT-X- 开头的是 HLS 媒体清单
                    if (head != null && head.contains("#EXTINF", true) && !head.contains("#EXT-X-", true)) {
                        val text = fetchText(url, MAX_PLAYLIST) ?: head
                        val r = importPlaylist(text, nameFromUrl(url))
                        if (r.ok) { lists++; handled++ }
                        msgs.add("列表 ${short(url)}：${r.message}")
                    } else {
                        toPlay.add(url)
                        handled++
                        msgs.add("播放 ${short(url)}")
                    }
                }
            }
        }

        // 一次只起一个播放器，避免活动栈叠一堆
        if (toPlay.isNotEmpty()) {
            val url = toPlay.first()
            onPlay(url, PlaylistParser.titleFromUrl(url))
            if (toPlay.size > 1) msgs.add("只播放第 1 个视频地址，其余 ${toPlay.size - 1} 个已忽略")
        }
        return Result(
            handled > 0,
            "站点 +$sites · 列表 +$lists · 播放 ${if (toPlay.isEmpty()) 0 else 1}",
            msgs.joinToString("；")
        )
    }

    private fun importPlaylist(text: String, name: String): Result {
        val pl = PlaylistParser.parse(text, name)
        if (pl.entries.isEmpty()) return Result(false, "没解析到可播放的地址")
        playlistStore.add(pl)
        return Result(true, "「${pl.name}」${pl.entries.size} 条")
    }

    // ---------- 工具 ----------

    private enum class Kind { SITE, PLAYLIST, PLAY, M3U8 }

    private fun classify(url: String): Kind {
        val p = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            p.endsWith(".m3u8") -> Kind.M3U8
            p.endsWith(".m3u") || p.endsWith(".dpl") ||
                p.endsWith(".txt") || p.endsWith(".json") -> Kind.PLAYLIST
            p.endsWith(".mp4") || p.endsWith(".mkv") || p.endsWith(".ts") ||
                p.endsWith(".mpd") || p.endsWith(".webm") ||
                p.endsWith(".flv") || p.endsWith(".avi") -> Kind.PLAY
            else -> Kind.SITE
        }
    }

    private fun normalizeSiteUrl(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        if (!t.contains("://")) {
            if (!DOMAIN.matches(t)) return null
            t = "https://$t"
        }
        if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) return null
        return t
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            .removePrefix("www.")

    private fun nameFromUrl(url: String): String = url
        .substringBefore('?').substringAfterLast('/')
        .substringBeforeLast('.').ifBlank { "网络列表" }

    private fun short(s: String) = if (s.length <= 46) s else s.take(43) + "…"

    /** UTF-8 失败时退回 GB18030：PotPlayer 导出的 DPL / 老 M3U 常是 Windows ANSI */
    private fun decodeText(bytes: ByteArray): String {
        var arr = bytes
        if (arr.size >= 3 && arr[0] == 0xEF.toByte() && arr[1] == 0xBB.toByte() && arr[2] == 0xBF.toByte()) {
            arr = arr.copyOfRange(3, arr.size)
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(arr)).toString()
        } catch (e: CharacterCodingException) {
            runCatching { String(arr, Charset.forName("GB18030")) }
                .getOrDefault(String(arr, StandardCharsets.UTF_8))
        }
    }

    private fun fetchText(url: String, maxBytes: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            if (conn.responseCode !in 200..299) return null
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val tmp = ByteArray(8192)
                while (buf.size() < maxBytes) {
                    val n = input.read(tmp, 0, minOf(tmp.size, maxBytes - buf.size()))
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
            }
            decodeText(buf.toByteArray())
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---------- 记录 ----------

    private data class Part(val name: String, val filename: String?, val content: ByteArray)
    private data class Result(val ok: Boolean, val message: String, val detail: String = "")
    private data class HistoryItem(val time: String, val text: String)

    private fun historyJson(): JSONObject {
        val arr = JSONArray()
        val snapshot = synchronized(history) { history.toList() }
        snapshot.forEach {
            arr.put(JSONObject().put("time", it.time).put("text", it.text))
        }
        return JSONObject().put("items", arr)
    }

    private fun record(text: String, counted: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(history) {
            history.add(0, HistoryItem(time, text))
            while (history.size > HISTORY_LIMIT) history.removeAt(history.size - 1)
        }
        runCatching { onEvent("$time  $text") }
        if (counted) runCatching { onReceive() }
    }

    private val MINUS: Byte = '-'.code.toByte()
    private val CR: Byte = 13.toByte()
    private val LF: Byte = 10.toByte()
}
