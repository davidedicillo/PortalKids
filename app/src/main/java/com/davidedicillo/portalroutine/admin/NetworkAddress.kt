package com.davidedicillo.portalroutine.admin

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddress {
    fun localIpv4Address(): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { networkInterface -> networkInterface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address -> !address.isLoopbackAddress && address.hostAddress?.startsWith("169.254.") != true }
            ?.hostAddress
    }
}
