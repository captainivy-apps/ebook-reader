package com.captainivy.ebookreader.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** 获取局域网 IPv4 地址，失败返回 null */
    fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
