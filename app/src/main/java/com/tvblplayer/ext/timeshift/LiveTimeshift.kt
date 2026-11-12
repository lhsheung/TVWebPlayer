@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tvblplayer.ext.timeshift

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直播时移时长设置。
 *
 * 注意：这里所有容量都指**安卓设备本机存储**（app 的 cacheDir），
 * 与编译/开发用的电脑磁盘无关。0 分钟表示关闭时移、直连上游。
 */
class TimeshiftPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ext_timeshift", Context.MODE_PRIVATE)

    var minutes: Int
        get() = sp.getInt(KEY, DEFAULT_MINUTES).coerceAtLeast(0)
        set(value) = sp.edit().putInt(KEY, value.coerceAtLeast(0)).apply()

    /** 按 2.5 Mbps 估算的缓存上限；窗口写满后自动丢弃最旧分片，不会无限增长。 */
    fun maxBytes(): Long = minutes * bytesPerMinute()

    companion object {
        const val DEFAULT_MINUTES = 30
        /** NumberPicker 的兜底上限（7 天）；实际可选上限由设备可用空间动态决定。 */
        const val HARD_CAP_MINUTES = 10_080
        private const val KEY = "minutes"
        private const val ESTIMATED_BPS = 2_500_000L

        /** 每分钟预计占用字节数：设置界面与录制引擎共用同一估算口径。 */
        fun bytesPerMinute(): Long = 60L * ESTIMATED_BPS / 8L

        /** 可设分钟上限 = 设备可用空间的 90% 能录多久，不再是人为写死的 240。 */
        fun maxMinutesFor(usableBytes: Long): Int {
            val bySpace = (usableBytes * 9L / 10L) / bytesPerMinute()
            return bySpace.coerceIn(1L, HARD_CAP_MINUTES.toLong()).toInt()
        }

        fun sizeText(bytes: Long): String =
            if (bytes >= 1024L * 1024 * 1024)
                String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            else
                String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
    }
}

/**
 * PotPlayer 式直播时移：边收边把裸 TS 流切成本地分片，同时在本机
 * 127.0.0.1 起一个极简 HTTP 服务，对外提供一个滑动窗口的 HLS 播放列表。
 *
 * 为什么用 HLS 而不是「单个不断变长的文件」：ExoPlayer 对 live HLS
 * 原生支持窗口内任意 seek、暂停、追直播头；单文件方案会在文件变长时
 * 触发 EOF / 长度快照失效，需要反复重载，体验很差。
 *
 * 每个分片都有独立的绝对编号，淘汰老分片只删文件、不改变已有 URL，
 * 因此不会破坏播放器手上的 byte offset / 分片引用。
 */
class LiveTimeshiftEngine(
    context: Context,
    private val prefs: TimeshiftPrefs,
) {
    private data class Seg(val index: Long, val file: File, val bytes: Long, val seconds: Double)

    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "live_ts")
    private val lock = Any()
    private val segments = ArrayList<Seg>()
    private val running = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    @Volatile private var maxBytes: Long = 0L
    @Volatile private var upstream: String = ""
    private var nextIndex = 0L
    private var totalBytes = 0L

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var recorderThread: Thread? = null
    private var pool: java.util.concurrent.ExecutorService? = null

    companion object {
        /** 单个分片约 2 MB ≈ 6 秒 @2.5 Mbps，seek 粒度足够细 */
        private const val SEGMENT_BYTES = 2L * 1024 * 1024
        private const val TS_PACKET = 188
        private const val CONNECT_TIMEOUT = 8000
        private const val READ_TIMEOUT = 15000
    }

    /** 返回本地播放列表地址；返回 null 表示不启用时移，调用方应直连上游。 */
    @Synchronized
    fun start(url: String): String? {
        stop()
        val limit = prefs.maxBytes()
        // HLS 源本身已有分段/窗口，不需要再切一次；只处理连续 TS 流。
        if (limit <= 0L || !url.startsWith("http", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true)
        ) return null

        upstream = url
        maxBytes = limit
        failed.set(false)
        running.set(true)
        dir.mkdirs()
        synchronized(lock) {
            segments.clear()
            nextIndex = 0L
            totalBytes = 0L
        }

        return try {
            server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            pool = Executors.newCachedThreadPool()
            acceptThread = Thread({ acceptLoop() }, "ts-accept").also {
                it.isDaemon = true; it.start()
            }
            recorderThread = Thread({ record() }, "ts-record").also {
                it.isDaemon = true; it.start()
            }
            "http://127.0.0.1:${server!!.localPort}/live.m3u8"
        } catch (e: Exception) {
            running.set(false)
            null
        }
    }

    /** 设置变更后立即收紧/放宽窗口，不重启播放。 */
    fun applyLimit() {
        maxBytes = prefs.maxBytes()
        trim()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { recorderThread?.interrupt() } catch (_: Exception) {}
        try { acceptThread?.interrupt() } catch (_: Exception) {}
        try { pool?.shutdownNow() } catch (_: Exception) {}
        pool = null
        recorderThread = null
        acceptThread = null
        synchronized(lock) {
            segments.forEach { runCatching { it.file.delete() } }
            segments.clear()
            totalBytes = 0L
        }
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    // ---------- 录制：按 188 字节 TS 包边界切段 ----------

    private fun record() {
        var conn: HttpURLConnection? = null
        var out: BufferedOutputStream? = null
        var segFile: File? = null
        var segBytes = 0L
        var segStartMs = 0L
        // 固定缓冲 + 游标，避免每个 TS 包都复制整个数组
        val pending = ByteArray(256 * 1024)
        var pendingLen = 0
        var pos = 0

        fun closeCurrent(now: Long, keep: Boolean) {
            try { out?.flush(); out?.close() } catch (_: Exception) {}
            out = null
            val f = segFile
            if (keep && f != null && segBytes > 0) {
                val seconds = ((now - segStartMs) / 1000.0).coerceIn(0.5, 30.0)
                synchronized(lock) {
                    segments.add(Seg(nextIndex, f, segBytes, seconds))
                    totalBytes += segBytes
                }
                nextIndex++
                trim()
            } else {
                runCatching { f?.delete() }
            }
            segBytes = 0L
            segFile = null
        }

        fun openNext(now: Long) {
            val f = File(dir, "seg_%06d.ts".format(Locale.US, nextIndex))
            segFile = f
            segStartMs = now
            try { out = BufferedOutputStream(java.io.FileOutputStream(f)) }
            catch (e: Exception) { failed.set(true); running.set(false) }
        }

        try {
            conn = (URL(upstream).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TVWebPlayer-Timeshift/1.0")
            }
            val input = conn.inputStream.buffered()
            openNext(System.currentTimeMillis())

            val buf = ByteArray(64 * 1024)
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (pendingLen + n > pending.size) {
                    System.arraycopy(pending, pos, pending, 0, pendingLen - pos)
                    pendingLen -= pos
                    pos = 0
                }
                System.arraycopy(buf, 0, pending, pendingLen, n)
                pendingLen += n

                while (true) {
                    var sync = pos
                    while (sync < pendingLen && pending[sync] != 0x47.toByte()) sync++
                    if (sync > pos) pos = sync
                    if (pendingLen - pos < TS_PACKET) break
                    // 用下一个包边界确认这不是数据里偶然出现的 0x47
                    if (pendingLen - pos >= 2 * TS_PACKET &&
                        pending[pos + TS_PACKET] != 0x47.toByte()
                    ) {
                        pos += 1
                        continue
                    }
                    out?.write(pending, pos, TS_PACKET)
                    pos += TS_PACKET
                    segBytes += TS_PACKET
                    if (segBytes >= SEGMENT_BYTES) {
                        closeCurrent(System.currentTimeMillis(), true)
                        openNext(System.currentTimeMillis())
                    }
                }
                if (pos > 0) {
                    System.arraycopy(pending, pos, pending, 0, pendingLen - pos)
                    pendingLen -= pos
                    pos = 0
                }
            }
        } catch (e: Exception) {
            failed.set(true)
        } finally {
            closeCurrent(System.currentTimeMillis(), !failed.get())
            try { conn?.disconnect() } catch (_: Exception) {}
            running.set(false)
        }
    }

    private fun trim() {
        synchronized(lock) {
            while (totalBytes > maxBytes && segments.size > 1) {
                val old = segments.removeAt(0)
                totalBytes -= old.bytes
                runCatching { old.file.delete() }
            }
        }
    }

    // ---------- 本机 HTTP ----------

    private fun acceptLoop() {
        val s = server ?: return
        while (running.get()) {
            try {
                val client = s.accept()
                pool?.execute { serve(client) }
            } catch (e: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = reader.readLine() ?: return
            // 丢弃请求头
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"

            when {
                path.startsWith("/live.m3u8") -> {
                    if (failed.get()) {
                        write(socket, "503 Service Unavailable", "text/plain", ByteArray(0))
                        return
                    }
                    // 首次起播时录制线程可能还没攒够一个分片，短暂等待，
                    // 避免给 ExoPlayer 一张空列表直接导致起播失败。
                    var waited = 0
                    while (synchronized(lock) { segments.isEmpty() } &&
                        waited < 8000 && running.get() && !failed.get()
                    ) {
                        Thread.sleep(150); waited += 150
                    }
                    if (synchronized(lock) { segments.isEmpty() }) {
                        write(socket, "503 Service Unavailable", "text/plain", ByteArray(0))
                        return
                    }
                    write(
                        socket, "200 OK", "application/vnd.apple.mpegurl",
                        playlist().toByteArray(Charsets.UTF_8)
                    )
                }
                path.startsWith("/seg_") -> {
                    val name = path.substringBefore('?').removePrefix("/")
                    val f = File(dir, name)
                    if (!f.isFile) {
                        write(socket, "404 Not Found", "text/plain", ByteArray(0))
                        return
                    }
                    val body = f.readBytes()
                    write(socket, "200 OK", "video/mp2t", body)
                }
                else -> write(socket, "404 Not Found", "text/plain", ByteArray(0))
            }
        } catch (_: Exception) {
            // 客户端提前断开很正常
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun playlist(): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:10\n")
        synchronized(lock) {
            val first = segments.firstOrNull()?.index ?: 0L
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
            for (s in segments) {
                sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.seconds)).append(",\n")
                sb.append(s.file.name).append('\n')
            }
        }
        // 不加 ENDLIST —— 保持 live，ExoPlayer 会自动刷新并追直播头
        return sb.toString()
    }

    private fun write(socket: Socket, status: String, type: String, body: ByteArray) {
        val os = socket.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Accept-Ranges: none\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        os.write(head)
        if (body.isNotEmpty()) os.write(body)
        os.flush()
    }
}
