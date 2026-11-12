package com.tvblplayer.ext.lan

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 列出手机上可能用来访问电视的 IPv4 地址。
 *
 * 不用 WifiManager：盒子上可能是网线，而且 WifiManager 在部分 TV 上返回 0。
 * 直接枚举网卡最可靠，也不需要运行时权限。
 */
object LanAddresses {

    fun ipv4Candidates(): List<String> {
        val found = LinkedHashSet<String>()
        runCatching {
            val list = NetworkInterface.getNetworkInterfaces()
            while (list.hasMoreElements()) {
                val ni = list.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip == "0.0.0.0" || ip == "127.0.0.1") continue
                    found.add(ip)
                }
            }
        }
        // 家用网段优先；模拟器 NAT 地址排最后（宿主机扫了也连不上）
        return found.sortedWith(compareBy({ rank(it) }, { it }))
    }

    private fun rank(ip: String): Int = when {
        ip.startsWith("192.168.") -> 0
        ip.startsWith("10.") && ip != "10.0.2.15" -> 1
        ip.startsWith("172.") -> 2
        ip == "10.0.2.15" -> 9
        else -> 5
    }
}
