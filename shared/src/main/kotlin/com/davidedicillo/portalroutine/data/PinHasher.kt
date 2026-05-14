package com.davidedicillo.portalroutine.data

import java.security.MessageDigest

object PinHasher {
    private const val PREFIX = "sha256:v1:"

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("PortalKids:$pin".toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun verify(pin: String, hash: String?): Boolean {
        if (hash.isNullOrBlank()) return false
        return MessageDigest.isEqual(hash(pin).toByteArray(Charsets.UTF_8), hash.toByteArray(Charsets.UTF_8))
    }
}
