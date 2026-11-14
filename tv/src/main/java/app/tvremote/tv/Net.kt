package app.tvremote.tv

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** First private IPv4 address on a non-loopback interface, or null. */
    fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
