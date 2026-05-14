package com.davidedicillo.portalroutine.sync

import android.content.Context
import com.davidedicillo.portalroutine.BuildConfig
import java.util.UUID

class DeviceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("portal-device", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = preferences.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = "portal-${UUID.randomUUID()}"
            preferences.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    var hubUrl: String
        get() = normalize(preferences.getString(KEY_HUB_URL, null) ?: BuildConfig.DEFAULT_HUB_URL)
        set(value) {
            preferences.edit().putString(KEY_HUB_URL, normalize(value)).apply()
        }

    var portalMode: PortalMode
        get() = PortalMode.fromStoredValue(preferences.getString(KEY_PORTAL_MODE, null))
        set(value) {
            preferences.edit().putString(KEY_PORTAL_MODE, value.storedValue).apply()
        }

    private fun normalize(value: String): String {
        val trimmed = value.trim().trimEnd('/').ifBlank { BuildConfig.DEFAULT_HUB_URL.trim().trimEnd('/') }
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_HUB_URL = "hubUrl"
        const val KEY_PORTAL_MODE = "portalMode"
    }
}
