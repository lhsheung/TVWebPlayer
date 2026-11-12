package com.tvblplayer.ext.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 极简图片加载器。
 *
 * 为什么不用 Glide：任意站点的封面图经常需要带 Referer / UA 才给，
 * 自己实现可以直接控制请求头，也能统一做磁盘缓存，省一个依赖。
 */
object ImageLoader {

    private val executor = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())

    private val memCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private var diskDir: File? = null

    fun init(cacheDir: File) {
        diskDir = File(cacheDir, "img").apply { mkdirs() }
    }

    private fun key(url: String) = try {
        val d = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        d.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        url.hashCode().toString()
    }

    fun load(iv: ImageView, url: String?, referer: String? = null) {
        if (url.isNullOrBlank()) {
            iv.setImageDrawable(null)
            return
        }
        iv.tag = url
        val k = key(url)

        memCache.get(k)?.let {
            iv.setImageBitmap(it)
            return
        }
        iv.setImageDrawable(null)

        executor.execute {
            val bmp = readDisk(k) ?: download(url, referer)?.also { writeDisk(k, it) }
            if (bmp != null) {
                memCache.put(k, bmp)
                main.post {
                    if (iv.tag == url) iv.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun readDisk(k: String): Bitmap? {
        val dir = diskDir ?: return null
        val f = File(dir, k)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    private fun writeDisk(k: String, bmp: Bitmap) {
        val dir = diskDir ?: return
        runCatching {
            File(dir, k).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
    }

    private fun download(url: String, referer: String?): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                referer?.let { setRequestProperty("Referer", it) }
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
