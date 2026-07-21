package com.ggufserver.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Best-effort site-local IPv4 (e.g. 192.168.x.x) that LAN clients can reach. */
    fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
