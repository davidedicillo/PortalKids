package com.davidedicillo.portalroutine.hub

import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddress {
    fun localIpv4Address(): String? {
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") }
    }
}
