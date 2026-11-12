package com.tvblplayer.ext.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tvblplayer.databinding.ActivityLanImportBinding
import com.tvblplayer.ext.lan.LanAddresses
import com.tvblplayer.ext.lan.LanServer

/**
 * 局域网传输页：电视上显示网址 + 二维码，手机扫码就能上传文件或发链接。
 *
 * 服务是「页面级」的：onCreate 起 ServerSocket，onDestroy 关掉。
 * 这样不会在用户退出后还留一个谁都能写的端口，也省一个常驻服务。
 */
class LanImportActivity : BaseTvActivity() {

    private lateinit var b: ActivityLanImportBinding
    private var server: LanServer? = null
    private var port = 0
    private var addresses: List<String> = emptyList()
    private var addrIndex = 0
    private var received = 0
    private var logLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLanImportBinding.inflate(layoutInflater)
        setContentView(b.root)

        addresses = LanAddresses.ipv4Candidates()

        val s = LanServer(
            this,
            onEvent = { line ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) appendLog(line)
                }
            },
            onPlay = { url, title ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        MediaLauncher.open(this@LanImportActivity, url, title)
                        appendLog("  → 已在电视上请求播放：$title")
                    }
                }
            },
            onReceive = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        received++
                        updateStatus()
                    }
                }
            },
        )
        server = s

        port = try {
            s.start(LanServer.DEFAULT_PORT)
        } catch (e: Exception) {
            b.tvStatus.text = "服务启动失败：${e.message}"
            appendLog("服务启动失败：${e.message}")
            0
        }

        renderAddress()
        updateStatus()

        b.btnSwitch.setOnClickListener {
            if (addresses.size < 2) {
                toast("只检测到一个局域网地址")
                return@setOnClickListener
            }
            addrIndex = (addrIndex + 1) % addresses.size
            renderAddress()
            toast("已切换到 ${addresses[addrIndex]}")
        }
        b.btnStop.setOnClickListener { finish() }

        b.btnSwitch.post { b.btnSwitch.requestFocus() }

        if (port > 0) {
            appendLog("服务已启动，端口 $port，等待手机连接…")
        }
    }

    /**
     * 窗口拿到焦点时系统会做一次默认焦点分配，实测会落到日志 ScrollView 上，
     * 遥控器看着像「没焦点」。这里把焦点收回到第一个按钮。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !b.btnSwitch.hasFocus() && !b.btnStop.hasFocus()) {
            b.btnSwitch.requestFocus()
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    // ---------- 地址与二维码 ----------

    private fun currentUrl(): String {
        val ip = addresses.getOrNull(addrIndex) ?: "127.0.0.1"
        return "http://$ip:$port/"
    }

    private fun renderAddress() {
        val url = currentUrl()
        b.tvUrl.text = url

        val others = addresses.filterIndexed { i, _ -> i != addrIndex }
        b.tvHint.text = when {
            addresses.isEmpty() ->
                "没检测到局域网 IP。可用 adb forward 测试，或检查电视的网络连接。"
            others.isEmpty() ->
                "手机相机 / 微信扫一扫，或在浏览器输入上面的地址。"
            else ->
                "扫码或输入地址；「换一个地址」可切到：" + others.joinToString(" / ")
        }

        b.qr.setImageDrawable(
            BitmapDrawable(resources, makeQr(url)).apply {
                // 关闭双线性过滤，二维码放大后仍是硬边方块，手机更好扫
                setFilterBitmap(false)
            }
        )
    }

    /**
     * ZXing 只用来「编码」，不申请相机权限、不引 camera / android-integration 模块。
     * 先按最小尺寸画一张像素级 BitMatrix，再最近邻放大到 800px。
     */
    private fun makeQr(content: String): Bitmap {
        val hints = HashMap<EncodeHintType, Any>().apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 2) // 静区，扫码必需
        }
        val matrix: BitMatrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0, hints
        )
        val w = matrix.width
        val h = matrix.height
        val tiny = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                tiny.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        // 整数倍放大：每个模块正好 N×N 像素，不会出现半个模块的锯齿
        val scale = maxOf(1, QR_PIXELS / maxOf(w, h))
        val scaled = Bitmap.createScaledBitmap(tiny, w * scale, h * scale, false)
        tiny.recycle()
        return scaled
    }

    // ---------- 状态与日志 ----------

    private fun updateStatus() {
        b.tvStatus.text = if (port > 0) {
            "服务运行中 · 端口 $port · 已收到 $received 条"
        } else {
            "服务未启动"
        }
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        while (logLines.size > LOG_LIMIT) logLines.removeAt(0)
        b.tvLog.text = logLines.joinToString("\n")
        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val QR_PIXELS = 800
        private const val LOG_LIMIT = 40
    }
}
